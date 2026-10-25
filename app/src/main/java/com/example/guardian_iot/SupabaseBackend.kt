package com.example.guardian_iot

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class BackendFailure(val category: Category, val status: Int = 0, cause: Throwable? = null) :
    IOException(messageFor(category), cause) {
    enum class Category {
        NETWORK, INVALID_CREDENTIALS, EMAIL_UNCONFIRMED, EMAIL_CONFIRMATION_REQUIRED,
        EMAIL_RATE_LIMIT, AUTH_RATE_LIMIT, EMAIL_DELIVERY, VALIDATION, TEMPORARY, SESSION,
        CONFIGURATION, MISSING_MIGRATION, CAREGIVER_INVITE_INVALID_OR_EXPIRED,
        CAREGIVER_INVITE_SELF, UNKNOWN
    }

    companion object {
        private fun messageFor(category: Category): String = when (category) {
            Category.NETWORK -> "Sin conexión. Los datos locales se conservaron y se sincronizarán después."
            Category.INVALID_CREDENTIALS -> "Correo o contraseña incorrectos."
            Category.EMAIL_UNCONFIRMED -> "Confirma tu correo antes de iniciar sesión."
            Category.EMAIL_CONFIRMATION_REQUIRED -> "Supabase todavía exige confirmar el correo. Desactiva “Confirm email” en Authentication → Providers → Email para usar registro normal."
            Category.EMAIL_RATE_LIMIT -> "Supabase sigue enviando confirmaciones por correo y alcanzó su límite. Desactiva “Confirm email” en Authentication → Providers → Email."
            Category.AUTH_RATE_LIMIT -> "Se hicieron demasiados intentos. Espera un momento antes de volver a probar."
            Category.EMAIL_DELIVERY -> "No se pudo enviar el correo de verificación. Revisa la configuración SEND_EMAIL_HOOK_SECRET y TWILIO_* en Supabase."
            Category.VALIDATION -> "Revisa los datos e inténtalo de nuevo."
            Category.TEMPORARY -> "Supabase no está disponible ahora. Inténtalo más tarde."
            Category.SESSION -> "Tu sesión terminó. Inicia sesión otra vez."
            Category.CONFIGURATION -> "Falta configurar la URL o la clave pública de Supabase."
            Category.MISSING_MIGRATION -> "Supabase todavía no tiene activada la vinculación de cámaras. Aplica la migración 20261022003000_camera_pairing_and_three_roles.sql al proyecto configurado en la app."
            Category.CAREGIVER_INVITE_INVALID_OR_EXPIRED -> "El código venció, ya se usó o no coincide. Pide a la persona protegida que genere uno nuevo y vuelve a intentarlo."
            Category.CAREGIVER_INVITE_SELF -> "No puedes vincular tu cuenta contigo misma. La persona cuidadora debe usar otra cuenta."
            Category.UNKNOWN -> "No se pudo completar la operación. Inténtalo de nuevo."
        }
    }
}

data class PendingFallEvent(
    val id: String,
    val incidentId: String?,
    val deviceId: String,
    val occurredAtMillis: Long,
    val source: String,
    val classification: String,
    val confirmationStatus: String,
    val summary: String?,
)

class SupabaseBackend(
    private val sessions: EncryptedSessionStore,
    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/'),
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    private val redirectUrl: String = BuildConfig.SUPABASE_REDIRECT_URL,
) {
    internal fun realtimeUrl(): String = baseUrl
    internal fun realtimeKey(): String = publishableKey
    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") && publishableKey.startsWith("sb_publishable_")

    fun signUp(email: String, password: String, fullName: String): AuthSession? {
        val body = JSONObject().put("email", email.trim()).put("password", password)
            .put("data", JSONObject().put("full_name", fullName.trim()))
        val response = request("POST", "/auth/v1/signup", body, needsSession = false)
        return parseAndSaveSession(response)
    }

    fun signIn(email: String, password: String): AuthSession {
        val response = request(
            "POST",
            "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password),
            needsSession = false,
        )
        return parseAndSaveSession(response) ?: throw BackendFailure(BackendFailure.Category.UNKNOWN)
    }

    fun requestPasswordRecovery(email: String) {
        request(
            "POST",
            "/auth/v1/recover?redirect_to=" + encode(redirectUrl),
            JSONObject().put("email", email.trim()),
            needsSession = false,
        )
    }

    fun acceptPasswordRecovery(uri: Uri): AuthSession {
        val values = parseQuery(uri.encodedFragment.orEmpty())
        val access = values["access_token"].orEmpty()
        val refresh = values["refresh_token"].orEmpty()
        if (access.isBlank() || refresh.isBlank()) throw BackendFailure(BackendFailure.Category.SESSION)
        val expiresAt = epochSeconds(values["expires_at"], values["expires_in"])
        val interim = AuthSession(access, refresh, expiresAt, "", "")
        sessions.write(interim)
        val user = request("GET", "/auth/v1/user", null, needsSession = true)
        return interim.copy(userId = user.optString("id"), email = user.optString("email")).also(sessions::write)
    }

    fun updatePassword(newPassword: String) {
        request("PUT", "/auth/v1/user", JSONObject().put("password", newPassword), needsSession = true)
    }

    fun signOut() {
        try { request("POST", "/auth/v1/logout", null, needsSession = true) }
        finally { sessions.clear() }
    }

    fun deleteAccount() {
        request("POST", "/functions/v1/delete-account", JSONObject(), needsSession = true)
        sessions.clear()
    }

    fun currentSession(): AuthSession? {
        val current = sessions.read() ?: return null
        if (current.expiresAtSeconds > System.currentTimeMillis() / 1000 + TOKEN_REFRESH_MARGIN_SECONDS) return current
        return try {
            refresh(current)
        } catch (failure: BackendFailure) {
            if (failure.category == BackendFailure.Category.NETWORK && current.expiresAtSeconds > System.currentTimeMillis() / 1000) current
            else throw failure
        }
    }

    fun fetchProfile(): JSONObject? {
        val session = requireSession()
        return request(
            "GET",
            "/rest/v1/profiles?select=full_name,role,preferences,updated_at&user_id=eq." + encode(session.userId) + "&limit=1",
            null,
            true,
        ).optJSONArray("data")?.optJSONObject(0)
    }

    fun fetchTrustedContacts(): JSONArray {
        val session = requireSession()
        return request(
            "GET",
            "/rest/v1/trusted_contacts?select=id,name,phone,preferences,updated_at&user_id=eq." + encode(session.userId) + "&order=updated_at.asc&limit=100",
            null,
            true,
        ).optJSONArray("data") ?: JSONArray()
    }

    fun updateProfile(fullName: String, role: String?, preferences: JSONObject) {
        val session = requireSession()
        val body = JSONObject().put("full_name", fullName.take(120)).put("preferences", preferences)
        body.put("role", role?.takeIf { it in setOf(AppStore.ROLE_WEARER, AppStore.ROLE_CAREGIVER, AppStore.ROLE_CAMERA) } ?: JSONObject.NULL)
        request(
            "PATCH",
            "/rest/v1/profiles?user_id=eq." + encode(session.userId),
            body,
            needsSession = true,
            prefer = "return=minimal",
        )
    }

    fun upsertDevice(deviceId: String, logicalId: String, name: String, deviceType: String, linkStatus: String) {
        request(
            "POST",
            "/rest/v1/devices?on_conflict=id",
            JSONObject().put("id", deviceId).put("logical_id", logicalId).put("name", name.take(120))
                .put("device_type", deviceType).put("link_status", linkStatus).put("last_sync_at", isoTime(System.currentTimeMillis())),
            needsSession = true,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    fun upsertContact(id: String, name: String, phone: String, preferences: JSONObject) {
        request(
            "POST",
            "/rest/v1/trusted_contacts?on_conflict=id",
            JSONObject().put("id", id).put("name", name.take(120)).put("phone", phone)
                .put("preferences", preferences),
            needsSession = true,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    fun deleteContact(id: String) {
        request("DELETE", "/rest/v1/trusted_contacts?id=eq." + encode(id), null, needsSession = true)
    }

    fun medicationRecords(ownerUserId: String): JSONArray = request(
        "GET",
        "/rest/v1/medications?select=id,owner_user_id,name,prescribed_dose,presentation,frequency,schedule_times,start_date,end_date,instructions,is_active,share_with_caregiver,is_deleted,created_at,updated_at" +
            "&owner_user_id=eq." + encode(ownerUserId) + "&order=updated_at.desc&limit=500",
        null,
        true,
    ).optJSONArray("data") ?: JSONArray()

    fun upsertMedication(record: Medication) {
        val body = JSONObject()
            .put("id", record.id)
            .put("owner_user_id", record.ownerUserId)
            .put("name", record.name)
            .put("prescribed_dose", record.prescribedDose)
            .put("presentation", record.presentation)
            .put("frequency", record.frequency)
            .put("schedule_times", JSONArray(record.scheduleTimes))
            .put("start_date", record.startDate)
            .put("end_date", record.endDate ?: JSONObject.NULL)
            .put("instructions", record.instructions)
            .put("is_active", record.isActive)
            .put("share_with_caregiver", record.shareWithCaregiver)
            .put("is_deleted", record.isDeleted)
            .put("created_at", isoTime(record.createdAtMillis))
            .put("updated_at", isoTime(record.updatedAtMillis))
        request(
            "POST", "/rest/v1/medications?on_conflict=id", body, true,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    fun medicationIntakeRecords(ownerUserId: String): JSONArray = request(
        "GET",
        "/rest/v1/medication_intakes?select=id,medication_id,owner_user_id,schedule_instance_id,scheduled_at,action_at,status,snoozed_until,snooze_count,created_at,updated_at" +
            "&owner_user_id=eq." + encode(ownerUserId) + "&order=scheduled_at.desc&limit=1000",
        null,
        true,
    ).optJSONArray("data") ?: JSONArray()

    fun upsertMedicationIntake(record: MedicationIntake) {
        val body = JSONObject()
            .put("id", record.id)
            .put("medication_id", record.medicationId)
            .put("owner_user_id", record.ownerUserId)
            .put("schedule_instance_id", record.scheduleInstanceId)
            .put("scheduled_at", isoTime(record.scheduledAtMillis))
            .put("action_at", record.actionAtMillis?.let(::isoTime) ?: JSONObject.NULL)
            .put("status", record.status)
            .put("snoozed_until", record.snoozedUntilMillis?.let(::isoTime) ?: JSONObject.NULL)
            .put("snooze_count", record.snoozeCount)
            .put("created_at", isoTime(record.createdAtMillis))
            .put("updated_at", isoTime(record.updatedAtMillis))
        request(
            "POST", "/rest/v1/medication_intakes?on_conflict=id", body, true,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    fun insertFallEvent(event: PendingFallEvent) {
        val body = JSONObject()
            .put("id", event.id)
            .put("device_id", event.deviceId)
            .put("occurred_at", isoTime(event.occurredAtMillis))
            .put("source", event.source)
            .put("classification", event.classification)
            .put("confirmation_status", event.confirmationStatus)
        event.summary?.let { body.put("summary", it.take(240)) }
        request(
            "POST",
            "/rest/v1/fall_events?on_conflict=id",
            body,
            needsSession = true,
            prefer = "resolution=ignore-duplicates,return=minimal",
        )
    }

    fun upsertEmergencyAlert(id: String, deviceId: String, fallEventId: String, status: String) {
        request(
            "POST",
            "/rest/v1/emergency_alerts?on_conflict=id",
            JSONObject().put("id", id).put("device_id", deviceId).put("fall_event_id", fallEventId).put("status", status),
            needsSession = true,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    fun insertNotificationAttempt(
        id: String,
        deviceId: String,
        alertId: String,
        channel: String,
        status: String,
        attemptedAtMillis: Long,
        confirmedAtMillis: Long?,
        errorCode: String?,
    ) {
        val body = JSONObject()
            .put("id", id)
            .put("device_id", deviceId)
            .put("alert_id", alertId)
            .put("channel", channel)
            .put("status", status)
            .put("attempted_at", isoTime(attemptedAtMillis))
        confirmedAtMillis?.let { body.put("confirmed_at", isoTime(it)) }
        errorCode?.let { body.put("error_code", it.take(80)) }
        request(
            "POST",
            "/rest/v1/notification_attempts?on_conflict=id",
            body,
            needsSession = true,
            prefer = "resolution=ignore-duplicates,return=minimal",
        )
    }

    fun insertMeasurement(
        id: String,
        deviceId: String?,
        type: String,
        value: Double,
        unit: String,
        capturedAtMillis: Long,
        source: String,
        isValid: Boolean?,
    ) {
        val body = JSONObject().put("id", id).put("measurement_type", type).put("value", value)
            .put("unit", unit).put("captured_at", isoTime(capturedAtMillis)).put("source", source)
        deviceId?.let { body.put("device_id", it) }
        isValid?.let { body.put("is_valid", it) }
        request("POST", "/rest/v1/measurements?on_conflict=id", body, true, prefer = "resolution=ignore-duplicates,return=minimal")
    }

    fun createCaregiverInvite(deviceId: String): Pair<String, String> {
        val result = request("POST", "/rest/v1/rpc/create_caregiver_invite", JSONObject().put("p_device_id", deviceId), true)
        val row = result.optJSONArray("data")?.optJSONObject(0) ?: result.optJSONObject("data") ?: result
        return row.optString("code") to row.optString("expires_at")
    }

    fun createCaregiverAccountInvite(): Pair<String, String> {
        val result = request("POST", "/rest/v1/rpc/create_caregiver_account_invite", JSONObject(), true)
        val row = result.optJSONArray("data")?.optJSONObject(0) ?: result.optJSONObject("data") ?: result
        return row.optString("code") to row.optString("expires_at")
    }

    fun createCameraDeviceInvite(deviceName: String): Pair<String, String> {
        val result = request("POST", "/rest/v1/rpc/create_camera_device_invite",
            JSONObject().put("p_device_name", deviceName.trim().take(120)), true)
        val row = result.optJSONArray("data")?.optJSONObject(0) ?: result.optJSONObject("data") ?: result
        return row.optString("code") to row.optString("expires_at")
    }

    fun acceptCameraDeviceInvite(code: String): JSONObject {
        val result = request("POST", "/rest/v1/rpc/accept_camera_device_invite",
            JSONObject().put("p_code", code.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }), true)
        return result.optJSONArray("data")?.optJSONObject(0) ?: result.optJSONObject("data") ?: result
    }

    fun unlinkPairedCamera() {
        request("POST", "/rest/v1/rpc/unlink_paired_camera", JSONObject(), true)
    }

    fun insertPairedCameraFallEvent(event: PendingFallEvent) {
        request("POST", "/rest/v1/rpc/submit_paired_camera_fall_event", JSONObject()
            .put("p_event_id", event.id)
            .put("p_occurred_at", isoTime(event.occurredAtMillis))
            .put("p_summary", event.summary ?: JSONObject.NULL), true)
    }

    fun acceptCaregiverInvite(code: String): JSONObject {
        val result = request("POST", "/rest/v1/rpc/accept_caregiver_invite",
            JSONObject().put("p_code", code.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }), true)
        return result.optJSONArray("data")?.optJSONObject(0)
            ?: result.optJSONObject("data")
            ?: result
    }

    fun upsertCaregiverLocation(deviceId: String, latitude: Double, longitude: Double, accuracyMeters: Float?, capturedAt: Long) {
        val body = JSONObject().put("device_id", deviceId).put("latitude", latitude).put("longitude", longitude)
            .put("captured_at", isoTime(capturedAt))
        accuracyMeters?.takeIf { it.isFinite() && it >= 0 }?.let { body.put("accuracy_m", it.toDouble()) }
        request("POST", "/rest/v1/caregiver_locations?on_conflict=device_id", body, true,
            prefer = "resolution=merge-duplicates,return=minimal")
    }

    fun sharedLocation(ownerId: String, deviceId: String): JSONObject? = request(
        "GET",
        "/rest/v1/caregiver_locations?select=device_id,latitude,longitude,accuracy_m,captured_at&owner_user_id=eq." +
            encode(ownerId) + "&device_id=eq." + encode(deviceId) + "&limit=1",
        null,
        true,
    ).optJSONArray("data")?.optJSONObject(0)

    fun deleteCaregiverLocation(deviceId: String) {
        request("DELETE", "/rest/v1/caregiver_locations?device_id=eq." + encode(deviceId), null, true)
    }

    fun unlinkCaregiver(linkId: String) {
        request("POST", "/rest/v1/rpc/unlink_caregiver", JSONObject().put("p_link_id", linkId), true)
    }

    fun linkedPeople(): JSONArray {
        val session = requireSession()
        return request(
            "GET",
            "/rest/v1/caregiver_links?select=id,owner_user_id,device_id,created_at,status&caregiver_user_id=eq." +
                encode(session.userId) + "&status=eq.active&order=created_at.desc",
            null,
            true,
        ).optJSONArray("data") ?: JSONArray()
    }

    fun sharedEvents(ownerId: String, deviceId: String? = null): JSONArray = request(
        "GET",
        "/rest/v1/fall_events?select=id,device_id,occurred_at,source,classification,confirmation_status,summary&user_id=eq." +
            encode(ownerId) + (deviceId?.takeIf { it.isNotBlank() }?.let { "&device_id=eq." + encode(it) } ?: "") + "&order=occurred_at.desc&limit=100",
        null,
        true,
    ).optJSONArray("data") ?: JSONArray()

    fun recentCameraFallEvents(ownerId: String, createdAfterMillis: Long): JSONArray = request(
        "GET",
        "/rest/v1/fall_events?select=id,user_id,device_id,occurred_at,created_at,source,classification,confirmation_status,summary" +
            "&user_id=eq." + encode(ownerId) + "&source=eq.camera_ai&classification=eq.possible_fall" +
            "&confirmation_status=eq.pending&created_at=gte." + encode(isoTime(createdAfterMillis)) +
            "&order=created_at.asc&limit=100",
        null,
        true,
    ).optJSONArray("data") ?: JSONArray()

    fun sharedMeasurements(ownerId: String, deviceId: String? = null): JSONArray = request(
        "GET",
        "/rest/v1/measurements?select=id,device_id,measurement_type,value,unit,captured_at,source,is_valid&user_id=eq." +
            encode(ownerId) + (deviceId?.takeIf { it.isNotBlank() }?.let { "&device_id=eq." + encode(it) } ?: "") + "&order=captured_at.desc&limit=100",
        null,
        true,
    ).optJSONArray("data") ?: JSONArray()

    fun upsertPhoneMotionStatus(
        deviceId: String,
        sampleCount: Int,
        averageDynamicG: Double,
        peakDynamicG: Double,
        capturedAtMillis: Long,
    ) {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("sample_count", sampleCount)
            .put("average_dynamic_g", averageDynamicG)
            .put("peak_dynamic_g", peakDynamicG)
            .put("captured_at", isoTime(capturedAtMillis))
        request(
            "POST",
            "/rest/v1/phone_motion_status?on_conflict=device_id",
            body,
            true,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    fun sharedPhoneMotion(ownerId: String, deviceId: String? = null): JSONObject? = request(
        "GET",
        "/rest/v1/phone_motion_status?select=device_id,sample_count,average_dynamic_g,peak_dynamic_g,captured_at&user_id=eq." +
            encode(ownerId) + (deviceId?.takeIf { it.isNotBlank() }?.let { "&device_id=eq." + encode(it) } ?: "") +
            "&order=captured_at.desc&limit=1",
        null,
        true,
    ).optJSONArray("data")?.optJSONObject(0)

    fun linkedDevices(ownerId: String, deviceId: String): JSONObject? = request(
        "GET",
        "/rest/v1/devices?select=id,name,device_type,link_status,last_connected_at,last_sync_at&id=eq." +
            encode(deviceId) + "&user_id=eq." + encode(ownerId) + "&limit=1",
        null,
        true,
    ).optJSONArray("data")?.optJSONObject(0)

    fun linkedDevices(ownerId: String): JSONArray = request(
        "GET",
        "/rest/v1/devices?select=id,name,device_type,link_status,last_connected_at,last_sync_at&user_id=eq." +
            encode(ownerId) + "&order=device_type.asc,name.asc&limit=100",
        null,
        true,
    ).optJSONArray("data") ?: JSONArray()

    fun sharedProfile(ownerId: String): JSONObject? = request(
        "GET",
        "/rest/v1/profiles?select=full_name,role,updated_at&user_id=eq." + encode(ownerId) + "&limit=1",
        null,
        true,
    ).optJSONArray("data")?.optJSONObject(0)

    private fun parseAndSaveSession(response: JSONObject): AuthSession? {
        if (!response.has("access_token")) return null
        val user = response.optJSONObject("user") ?: JSONObject()
        val session = AuthSession(
            accessToken = response.optString("access_token"),
            refreshToken = response.optString("refresh_token"),
            expiresAtSeconds = epochSeconds(response.optString("expires_at"), response.optString("expires_in")),
            userId = user.optString("id"),
            email = user.optString("email"),
        )
        if (session.accessToken.isNotBlank() && session.refreshToken.isNotBlank()) sessions.write(session)
        return session.takeIf { it.accessToken.isNotBlank() && it.refreshToken.isNotBlank() }
    }

    private fun requireSession(): AuthSession =
        currentSession() ?: throw BackendFailure(BackendFailure.Category.SESSION)

    private fun refresh(session: AuthSession): AuthSession {
        val response = try {
            request(
                "POST",
                "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", session.refreshToken),
                needsSession = false,
            )
        } catch (failure: BackendFailure) {
            if (failure.category == BackendFailure.Category.INVALID_CREDENTIALS ||
                failure.category == BackendFailure.Category.VALIDATION ||
                failure.category == BackendFailure.Category.SESSION) {
                sessions.clear()
                throw BackendFailure(BackendFailure.Category.SESSION, failure.status, failure)
            }
            throw failure
        }
        return parseAndSaveSession(response) ?: throw BackendFailure(BackendFailure.Category.SESSION)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        needsSession: Boolean,
        prefer: String? = null,
    ): JSONObject {
        if (!isConfigured) throw BackendFailure(BackendFailure.Category.CONFIGURATION)
        val session = if (needsSession) requireSession() else null
        val connection = try {
            URL(baseUrl + path).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw BackendFailure(BackendFailure.Category.CONFIGURATION, cause = error)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("apikey", publishableKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            session?.let { connection.setRequestProperty("Authorization", "Bearer " + it.accessToken) }
            prefer?.let { connection.setRequestProperty("Prefer", it) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val errorJson = runCatching { JSONObject(text) }.getOrNull()
                val code = (errorJson?.optString("error_code")?.takeIf { it.isNotBlank() }
                    ?: errorJson?.optString("code").orEmpty()).lowercase(Locale.ROOT)
                val message = (errorJson?.optString("msg")?.takeIf { it.isNotBlank() }
                    ?: errorJson?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: errorJson?.optString("details")?.takeIf { it.isNotBlank() }
                    ?: errorJson?.optString("hint").orEmpty()).lowercase(Locale.ROOT)
                val sendsEmail = path == "/auth/v1/recover"
                val category = when {
                    path == "/rest/v1/rpc/accept_caregiver_invite" && message.contains("cannot_link_self") ->
                        BackendFailure.Category.CAREGIVER_INVITE_SELF
                    path == "/rest/v1/rpc/accept_caregiver_invite" && message.contains("invite_invalid_or_expired") ->
                        BackendFailure.Category.CAREGIVER_INVITE_INVALID_OR_EXPIRED
                    path == "/rest/v1/rpc/create_camera_device_invite" &&
                        (code == "pgrst202" || status == 404 && (message.contains("schema cache") || message.contains("function"))) -> BackendFailure.Category.MISSING_MIGRATION
                    code == "email_not_confirmed" || message.contains("email not confirmed") -> BackendFailure.Category.EMAIL_UNCONFIRMED
                    code in setOf("invalid_credentials", "invalid_grant") || message.contains("invalid login credentials") -> BackendFailure.Category.INVALID_CREDENTIALS
                    status == 429 && path == "/auth/v1/signup" -> BackendFailure.Category.EMAIL_CONFIRMATION_REQUIRED
                    status == 429 && sendsEmail -> BackendFailure.Category.EMAIL_RATE_LIMIT
                    status == 429 -> BackendFailure.Category.AUTH_RATE_LIMIT
                    status >= 500 && path == "/auth/v1/signup" -> BackendFailure.Category.EMAIL_CONFIRMATION_REQUIRED
                    status >= 500 && sendsEmail -> BackendFailure.Category.EMAIL_DELIVERY
                    status == 400 || status == 422 -> BackendFailure.Category.VALIDATION
                    status == 401 || status == 403 -> BackendFailure.Category.SESSION
                    status >= 500 -> BackendFailure.Category.TEMPORARY
                    else -> BackendFailure.Category.UNKNOWN
                }
                throw BackendFailure(category, status)
            }
            return if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }
                .getOrElse { runCatching { JSONObject().put("data", JSONArray(text)) }.getOrDefault(JSONObject()) }
        } catch (failure: BackendFailure) {
            throw failure
        } catch (error: Exception) {
            throw BackendFailure(BackendFailure.Category.NETWORK, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun isoTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun epochSeconds(expiresAt: String?, expiresIn: String?): Long {
        val absolute = expiresAt?.toLongOrNull() ?: 0L
        return if (absolute > 0L) absolute else System.currentTimeMillis() / 1000 + (expiresIn?.toLongOrNull() ?: 3600L)
    }

    private fun parseQuery(value: String): Map<String, String> = value.split('&').mapNotNull {
        val pair = it.split('=', limit = 2)
        if (pair.size == 2) runCatching {
            java.net.URLDecoder.decode(pair[0], Charsets.UTF_8.name()) to
                java.net.URLDecoder.decode(pair[1], Charsets.UTF_8.name())
        }.getOrNull() else null
    }.toMap()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val TOKEN_REFRESH_MARGIN_SECONDS = 90L
    }
}

