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

/** Receives RLS-filtered location changes over Supabase Realtime while the caregiver screen is open. */
class CaregiverLocationRealtime(
    private val backend: SupabaseBackend,
    private val sessions: EncryptedSessionStore,
    private val onLocation: (JSONObject?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var listeningDeviceId: String? = null
    private var channel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var client: io.github.jan.supabase.SupabaseClient? = null

    fun start(deviceId: String) {
        if (deviceId.isBlank() || listeningDeviceId == deviceId || !backend.isConfigured) return
        listeningDeviceId = deviceId
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
                val activeChannel = supabase.realtime.channel("caregiver-location-$deviceId")
                channel = activeChannel
                val changes = activeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "caregiver_locations"
                }
                changes.onEach { action ->
                    val record = when (action) {
                        is PostgresAction.Insert -> action.record
                        is PostgresAction.Update -> action.record
                        is PostgresAction.Select -> action.record
                        is PostgresAction.Delete -> action.oldRecord
                    }
                    val row = runCatching { JSONObject(record.toString()) }.getOrNull()
                    if (row?.optString("device_id") == deviceId) {
                        onLocation(if (action is PostgresAction.Delete) null else row)
                    }
                }.launchIn(scope)
                activeChannel.subscribe(blockUntilSubscribed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The REST snapshot remains available when Realtime cannot connect.
            }
        }
    }

    fun stop() {
        listeningDeviceId = null
        val oldChannel = channel
        val oldClient = client
        scope.launch {
            runCatching { oldChannel?.unsubscribe() }
            runCatching { oldClient?.realtime?.disconnect() }
            runCatching { oldClient?.close() }
        }.invokeOnCompletion { scope.cancel() }
    }
}
