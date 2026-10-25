package com.example.guardian_iot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future

object SyncScheduler {
    private const val JOB_ID = 712045
    private val immediateExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var immediateSyncInFlight = false

    fun enqueue(context: Context, immediate: Boolean = true) {
        val app = context.applicationContext
        val sessions = EncryptedSessionStore(app)
        val backend = SupabaseBackend(sessions)
        if (!backend.isConfigured || sessions.read() == null) return
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler != null) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(app, SupabaseSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(job)
        }
        if (immediate && !immediateSyncInFlight) {
            immediateSyncInFlight = true
            immediateExecutor.execute {
                try {
                    SupabaseSyncRunner(app).run()
                } catch (t: Throwable) {
                    android.util.Log.w("SyncScheduler", "Immediate sync failed: ${t.message}")
                } finally {
                    immediateSyncInFlight = false
                }
            }
        }
    }
}

class SupabaseSyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var active: Future<*>? = null

    override fun onStartJob(parameters: JobParameters): Boolean {
        val backend = SupabaseBackend(EncryptedSessionStore(applicationContext))
        if (!backend.isConfigured || EncryptedSessionStore(applicationContext).read() == null) return false
        active = executor.submit {
            val retry = runCatching { SupabaseSyncRunner(applicationContext).run() }.getOrElse {
                getSharedPreferences("tzinda_native", MODE_PRIVATE).edit().putString(
                    "sync_status",
                    "No se pudo sincronizar; los datos locales siguen guardados.",
                ).apply()
                true
            }
            jobFinished(parameters, retry)
        }
        return true
    }

    override fun onStopJob(parameters: JobParameters): Boolean {
        active?.cancel(true)
        active = null
        return true
    }

    override fun onDestroy() {
        active?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}

private class SupabaseSyncRunner(context: Context) {
    private val app = context.applicationContext
    private val store = AppStore(app)
    private val database = store.telemetry
    private val sessions = EncryptedSessionStore(app)
    private val backend = SupabaseBackend(sessions)

    fun run(): Boolean {
        val session = backend.currentSession() ?: return false
        store.prepareContactsForUser(session.userId)
        var hasError = false

        try {
            val existing = backend.userDevices(session.userId)
            val byLogicalId = mutableMapOf<String, String>()
            val allExistingIds = mutableSetOf<String>()
            for (i in 0 until existing.length()) {
                val dev = existing.optJSONObject(i) ?: continue
                val devId = dev.optString("id")
                val logId = dev.optString("logical_id")
                val devType = dev.optString("device_type")
                val linkStatus = dev.optString("link_status")
                val devName = dev.optString("device_name").takeUnless { it.isBlank() } ?: "Cámara"
                if (devId.isNotBlank()) allExistingIds.add(devId)
                if (logId.isNotBlank() && devId.isNotBlank()) {
                    byLogicalId[logId] = devId
                }
                if (devType == "camera" && linkStatus == "linked" && logId != "camera-phone-local" && devId.isNotBlank()) {
                    store.upsertRegisteredCamera(devId, devName, enqueueSync = false)
                }
            }

            val existingPhoneId = byLogicalId["phone-primary"]
                ?: (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }
                    .firstOrNull { it.optString("device_type") == "phone" }?.optString("id")
            val phoneFallbackId = existingPhoneId
                ?: if (allExistingIds.contains(store.localDeviceId())) java.util.UUID.randomUUID().toString() else store.localDeviceId()
            val phoneLogical = if (existingPhoneId != null) {
                (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == existingPhoneId }?.optString("logical_id")?.takeIf { it.isNotBlank() } ?: "phone-primary"
            } else "phone-primary"
            val phoneId = backend.registerOrUpdateDevice(
                existingPhoneId, phoneFallbackId, phoneLogical, "Teléfono TzindaApp", "phone", "not_applicable"
            )

            val existingFallId = byLogicalId["fall-sensor-phone"]
            val fallFallback = existingFallId
                ?: if (allExistingIds.contains(store.fallSensorDeviceId())) java.util.UUID.randomUUID().toString() else store.fallSensorDeviceId()
            val fallId = backend.registerOrUpdateDevice(
                existingFallId, fallFallback, "fall-sensor-phone", "Sensor de caídas del teléfono", "fall_sensor", "unknown"
            )

            val existingWearId = byLogicalId["wearable-sensors"]
            val wearFallback = existingWearId
                ?: if (allExistingIds.contains(store.wearableDeviceId())) java.util.UUID.randomUUID().toString() else store.wearableDeviceId()
            val wearId = backend.registerOrUpdateDevice(
                existingWearId, wearFallback, "wearable-sensors", "Pulsera y sensores biométricos", "wearable", "unknown"
            )

            val existingArdId = byLogicalId["arduino-uno-q"]
            val ardFallback = existingArdId
                ?: if (allExistingIds.contains(store.arduinoDeviceId())) java.util.UUID.randomUUID().toString() else store.arduinoDeviceId()
            val ardId = backend.registerOrUpdateDevice(
                existingArdId, ardFallback, "arduino-uno-q", "Arduino UNO Q · puente de datos", "arduino", "unknown"
            )

            val existingCamId = byLogicalId["camera-phone-local"]
            val camFallback = existingCamId
                ?: if (allExistingIds.contains(store.cameraDeviceId())) java.util.UUID.randomUUID().toString() else store.cameraDeviceId()
            val camId = backend.registerOrUpdateDevice(
                existingCamId, camFallback, "camera-phone-local", "Cámara IA de este teléfono", "camera", "not_applicable"
            )

            store.registeredCameras().forEach { camera ->
                if (!allExistingIds.contains(camera.id)) {
                    val existingReg = byLogicalId["camera-${camera.id}"]
                    val regFallback = existingReg ?: camera.id
                    backend.registerOrUpdateDevice(
                        existingReg, regFallback, "camera-${camera.id}", camera.name, "camera", "unlinked"
                    )
                }
            }

            store.prefs.edit()
                .putString("local_device_id", phoneId)
                .putString("fall_sensor_device_id", fallId)
                .putString("wearable_device_id", wearId)
                .putString("arduino_device_id", ardId)
                .putString("local_camera_device_id", camId)
                .putBoolean("device_sync_configured", true)
                .apply()
        } catch (error: Exception) {
            android.util.Log.w("SyncRunner", "Device registration warning: ${error.message}", error)
            hasError = true
            store.prefs.edit().putBoolean("device_sync_configured", true).apply()
        }

        try { syncProfile() } catch (_: BackendFailure) { hasError = true }
        if (store.role() != AppStore.ROLE_CAMERA) {
            val caregiver = store.role() == AppStore.ROLE_CAREGIVER
            val medicationOwner = if (caregiver) store.prefs.getString("caregiver_owner_id", "").orEmpty() else session.userId
            if (medicationOwner.isNotBlank()) {
                try { MedicationRepository(app, backend, store).synchronize(medicationOwner, readOnly = caregiver) }
                catch (f: BackendFailure) { if (f.status != 404) hasError = true }
                catch (_: Exception) {}
            }
            if (store.role() == AppStore.ROLE_WEARER) {
                try {
                    val caregivers = backend.activeCaregiversForOwner()
                    store.prefs.edit().putBoolean("wearer_has_linked_caregiver", caregivers.length() > 0).apply()
                } catch (_: Exception) {}
            }
        }
        if (store.prefs.getBoolean("device_sync_configured", false)) {
            try { syncContacts() } catch (_: BackendFailure) { hasError = true }
            syncEvents { hasError = true }
            syncNotificationAttempts { hasError = true }
            syncMeasurements { hasError = true }
            syncPhoneMotion(session.userId) { hasError = true }
        }

        val summary = database.syncStateSummary()
        val failures = if (hasError) store.prefs.getInt("sync_global_failures", 0) + 1 else 0
        store.prefs.edit().putInt("sync_global_failures", failures).apply()
        val retry = (summary.pending > 0 || hasError) && failures < MAX_GLOBAL_RETRIES
        if (!retry) {
            if (!hasError && summary.pending == 0 && summary.failed == 0) store.prefs.edit()
                    .putLong("last_sync_success_at", System.currentTimeMillis())
                    .putString("sync_status", "Sincronizado")
                    .apply()
            else store.prefs.edit().putString("sync_status", "Se agotaron los reintentos; revisa la conexión y pulsa Sincronizar ahora.").apply()
        } else if (summary.failed > 0) {
            store.prefs.edit().putString("sync_status", "Hay datos locales que requieren reintento manual.").apply()
        } else {
            store.prefs.edit().putString("sync_status", "Sincronización pendiente; se reintentará con conexión.").apply()
        }
        return retry
    }

    private fun syncProfile() {
        val local = store.profile()
        val localName = local.text("fullName").trim()
        val remote = backend.fetchProfile()
        val remoteTime = remote?.optString("updated_at")?.toEpochMillis() ?: 0L
        val localTime = store.profileUpdatedAt()
        val hasUnsyncedLocalEdit = localTime > store.lastProfileSyncAt()
        val hasUnsyncedLocalRole = store.roleUpdatedAt() > store.lastRoleSyncAt()

        if (localName.isBlank()) {
            if (remote != null) {
                store.applyRemoteProfile(remote.optString("full_name"), remote.optString("role"), remoteTime)
                if (hasUnsyncedLocalRole && store.role().isNotBlank()) {
                    backend.updateProfile(remote.optString("full_name"), store.role(), JSONObject())
                    store.markProfileSynced()
                }
            }
            return
        }

        if (remote != null && remoteTime > localTime && !hasUnsyncedLocalEdit) {
            val mergedRole = if (hasUnsyncedLocalRole) store.role() else remote.optString("role")
            store.applyRemoteProfile(remote.optString("full_name"), mergedRole, remoteTime)
            if (hasUnsyncedLocalRole) {
                backend.updateProfile(remote.optString("full_name"), mergedRole, JSONObject())
                store.markProfileSynced()
            }
            return
        }

        val preferences = JSONObject()
            .put("privacy_notice_version", store.prefs.getString("privacy_notice_version", ""))
            .put("privacy_consent_at", store.prefs.getLong("privacy_consent_at", 0L))
        backend.updateProfile(localName, store.role().takeIf { it.isNotBlank() }, preferences)
        store.markProfileSynced()
    }

    private fun syncContacts() {
        database.pendingContacts().forEach { contact ->
            try {
                if (contact.deleted) backend.deleteContact(contact.id)
                else backend.upsertContact(contact.id, contact.name, contact.phone, JSONObject(contact.preferencesJson))
                database.markContactSynced(contact.id, contact.deleted)
            } catch (_: BackendFailure) {
                database.markContactFailure(contact.id)
                throw BackendFailure(BackendFailure.Category.TEMPORARY)
            }
        }

        if (database.pendingContacts().isNotEmpty()) return
        val remote = backend.fetchTrustedContacts()
        val contacts = buildList {
            for (i in 0 until remote.length()) {
                val row = remote.optJSONObject(i) ?: continue
                val phone = PhoneNumbers.normalize(row.optString("phone")) ?: continue
                val id = row.optString("id")
                if (runCatching { java.util.UUID.fromString(id) }.isFailure) continue
                add(
                    LocalContactRecord(
                        id = id,
                        name = row.optString("name").take(120),
                        phone = phone,
                        preferencesJson = row.optJSONObject("preferences")?.toString() ?: "{}",
                        updatedAtMillis = row.optString("updated_at").toEpochMillis().takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
        store.applyRemoteContacts(contacts)
    }

    private inline fun syncEvents(onError: () -> Unit) {
        val ardId = store.arduinoDeviceId()
        val phoneId = store.localDeviceId()
        database.pendingFallEvents().forEach { event ->
            try {
                val targetDeviceId = if (event.source == "arduino") ardId else phoneId
                val pending = PendingFallEvent(
                    event.id, event.incidentId, targetDeviceId, event.occurredAtMillis, event.source,
                    event.classification, event.confirmationStatus, event.summary,
                )
                val cameraAccountEvent = store.role() == AppStore.ROLE_CAMERA && event.source == "camera_ai"
                if (cameraAccountEvent) backend.insertPairedCameraFallEvent(pending) else backend.insertFallEvent(pending)
                val incident = event.incidentId
                val cameraIncidentAlias = store.role() == AppStore.ROLE_WEARER && event.source == "camera_ai"
                if (incident != null && !cameraAccountEvent && !cameraIncidentAlias) backend.upsertEmergencyAlert(
                    incident, event.deviceId, database.firstFallEventId(incident) ?: event.id,
                    database.latestAlertState(incident),
                )
                database.markFallEventSynced(event.id)
            } catch (_: BackendFailure) {
                database.markFallEventFailure(event.id)
                onError()
            }
        }
    }

    private inline fun syncNotificationAttempts(onError: () -> Unit) {
        database.pendingNotificationAttempts().forEach { attempt ->
            try {
                val eventId = database.firstFallEventId(attempt.incidentId)
                    ?: throw BackendFailure(BackendFailure.Category.VALIDATION)
                val alertStatus = if (attempt.status in setOf("requested", "accepted_by_os", "sent_to_network", "delivered"))
                    "actions_requested" else "not_confirmed"
                backend.upsertEmergencyAlert(attempt.incidentId, attempt.deviceId, eventId, alertStatus)
                backend.insertNotificationAttempt(
                    attempt.id, attempt.deviceId, attempt.incidentId, attempt.channel, attempt.status,
                    attempt.attemptedAtMillis, attempt.confirmedAtMillis, attempt.errorCode,
                )
                database.markAttemptSynced(attempt.id)
            } catch (_: BackendFailure) {
                database.markAttemptFailure(attempt.id)
                onError()
            }
        }
    }

    private inline fun syncMeasurements(onError: () -> Unit) {
        val ardId = store.arduinoDeviceId()
        val phoneId = store.localDeviceId()
        database.pendingMeasurements().forEach { reading ->
            try {
                val targetDeviceId = if (reading.source == LocalTelemetryDatabase.SOURCE_ARDUINO) ardId else phoneId
                backend.insertMeasurement(
                    reading.id, targetDeviceId, reading.type, reading.value, reading.unit,
                    reading.capturedAtMillis, reading.source, reading.isValid,
                )
                database.markMeasurementSynced(reading.id)
            } catch (_: BackendFailure) {
                database.markMeasurementFailure(reading.id)
                onError()
            }
        }
    }

    private inline fun syncPhoneMotion(userId: String, onError: () -> Unit) {
        val firstSyncWindowStart = System.currentTimeMillis() - FIRST_PHONE_MOTION_WINDOW_MS
        val lastUploadedAt = store.prefs.getLong("phone_motion_last_uploaded_at_$userId", firstSyncWindowStart)
        val summary = database.phoneMotionSummary(lastUploadedAt) ?: return
        try {
            backend.upsertPhoneMotionStatus(
                store.localDeviceId(),
                summary.sampleCount,
                summary.averageDynamicG,
                summary.peakDynamicG,
                summary.capturedAtMillis,
            )
            store.prefs.edit().putLong("phone_motion_last_uploaded_at_$userId", summary.capturedAtMillis).apply()
        } catch (_: BackendFailure) {
            onError()
        }
    }

    private fun String.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)

    companion object {
        private const val MAX_GLOBAL_RETRIES = 8
        private const val FIRST_PHONE_MOTION_WINDOW_MS = 60_000L
    }
}
