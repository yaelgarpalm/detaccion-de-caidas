package com.example.guardian_iot

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Streams RLS-authorized measurements and fall events for one linked wearer account. */
class CaregiverHealthRealtime(
    private val backend: SupabaseBackend,
    private val sessions: EncryptedSessionStore,
    private val onMeasurement: (JSONObject) -> Unit,
    private val onFallEvent: (JSONObject) -> Unit,
    private val onPhoneMotion: (JSONObject) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var listeningKey: String? = null
    private var measurementChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var fallChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var phoneMotionChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var client: io.github.jan.supabase.SupabaseClient? = null

    fun start(ownerId: String, deviceId: String?) {
        if (ownerId.isBlank() || listeningKey != null || !backend.isConfigured) return
        val key = "$ownerId:${deviceId.orEmpty()}"
        listeningKey = key
        scope.launch {
            try {
                val session = sessions.read() ?: return@launch
                val supabase = createSupabaseClient(backend.realtimeUrl(), backend.realtimeKey()) {
                    httpEngine = CIO.create()
                    accessToken = { sessions.read()?.accessToken }
                    install(Realtime)
                }
                client = supabase
                supabase.realtime.setAuth(session.accessToken)

                val measurements = supabase.realtime.channel("caregiver-measurements-$ownerId")
                measurementChannel = measurements
                val measurementChanges = measurements.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "measurements"
                }
                measurementChanges.onEach { action ->
                    val record = action.recordOrNull() ?: return@onEach
                    val row = runCatching { JSONObject(record.toString()) }.getOrNull() ?: return@onEach
                    if (row.optString("user_id") == ownerId &&
                        (deviceId.isNullOrBlank() || row.optString("device_id") == deviceId)) {
                        onMeasurement(row)
                    }
                }.launchIn(scope)

                val falls = supabase.realtime.channel("caregiver-falls-$ownerId")
                fallChannel = falls
                val fallChanges = falls.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "fall_events"
                }
                fallChanges.onEach { action ->
                    val record = action.recordOrNull() ?: return@onEach
                    val row = runCatching { JSONObject(record.toString()) }.getOrNull() ?: return@onEach
                    if (row.optString("user_id") == ownerId &&
                        (deviceId.isNullOrBlank() || row.optString("device_id") == deviceId)) {
                        onFallEvent(row)
                    }
                }.launchIn(scope)

                measurements.subscribe(blockUntilSubscribed = true)
                falls.subscribe(blockUntilSubscribed = true)

                val phoneMotion = supabase.realtime.channel("caregiver-phone-motion-$ownerId")
                phoneMotionChannel = phoneMotion
                val motionChanges = phoneMotion.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "phone_motion_status"
                }
                motionChanges.onEach { action ->
                    val record = action.recordOrNull() ?: return@onEach
                    val row = runCatching { JSONObject(record.toString()) }.getOrNull() ?: return@onEach
                    if (row.optString("user_id") == ownerId &&
                        (deviceId.isNullOrBlank() || row.optString("device_id") == deviceId)) {
                        onPhoneMotion(row)
                    }
                }.launchIn(scope)
                phoneMotion.subscribe(blockUntilSubscribed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The initial REST snapshot and the next refresh remain available as fallback.
            }
        }
    }

    fun stop() {
        listeningKey = null
        val oldMeasurements = measurementChannel
        val oldFalls = fallChannel
        val oldPhoneMotion = phoneMotionChannel
        val oldClient = client
        scope.launch {
            runCatching { oldMeasurements?.unsubscribe() }
            runCatching { oldFalls?.unsubscribe() }
            runCatching { oldPhoneMotion?.unsubscribe() }
            runCatching { oldClient?.realtime?.disconnect() }
            runCatching { oldClient?.close() }
        }.invokeOnCompletion { scope.cancel() }
    }

    private fun PostgresAction.recordOrNull(): Any? = when (this) {
        is PostgresAction.Insert -> record
        is PostgresAction.Update -> record
        is PostgresAction.Select -> record
        is PostgresAction.Delete -> null
    }
}
