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
import android.util.Log

/**
 * Receives linked camera fall inserts for the wearer while background monitoring is active.
 *
 * Each call to [start] creates a fresh CoroutineScope so the instance can be
 * restarted after [stop] without needing to create a new object.
 */
class CameraFallRealtime(
    private val backend: SupabaseBackend,
    private val sessions: EncryptedSessionStore,
    private val onFallEvent: (JSONObject) -> Unit,
) {
    @Volatile private var activeScope: CoroutineScope? = null
    @Volatile private var listeningOwnerId: String? = null
    private var channel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var client: io.github.jan.supabase.SupabaseClient? = null

    fun start(ownerId: String) {
        if (ownerId.isBlank() || !backend.isConfigured) return
        if (listeningOwnerId == ownerId) return
        if (listeningOwnerId != null) {
            stop()
        }
        listeningOwnerId = ownerId
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeScope = scope
        scope.launch {
            try {
                val session = sessions.read() ?: return@launch
                val supabase = createSupabaseClient(backend.realtimeUrl(), backend.realtimeKey()) {
                    httpEngine = CIO.create()
                    accessToken = { backend.currentSession()?.accessToken ?: sessions.read()?.accessToken }
                    install(Realtime)
                }
                client = supabase
                supabase.realtime.setAuth(session.accessToken)

                val activeChannel = supabase.realtime.channel("wearer-camera-falls-$ownerId")
                channel = activeChannel
                val changes = activeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "fall_events"
                }
                changes.onEach { action ->
                    val rowData = when (action) {
                        is PostgresAction.Insert -> action.record
                        else -> null
                    } ?: return@onEach
                    val row = runCatching { JSONObject(rowData.toString()) }.getOrNull() ?: return@onEach
                    if (row.optString("user_id") == ownerId &&
                        row.optString("source") == "camera_ai" &&
                        row.optString("classification") == "possible_fall" &&
                        row.optString("confirmation_status") == "pending") {
                        onFallEvent(row)
                    }
                }.launchIn(scope)
                activeChannel.subscribe(blockUntilSubscribed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("CameraFallRealtime", "Realtime subscription failed: ${e.javaClass.simpleName}")
                // The foreground service polls the authorized REST endpoint as a recovery path.
            }
        }
    }

    fun stop() {
        listeningOwnerId = null
        val oldChannel = channel
        val oldClient = client
        val oldScope = activeScope
        channel = null; client = null; activeScope = null
        oldScope?.launch {
            runCatching { oldChannel?.unsubscribe() }
            runCatching { oldClient?.realtime?.disconnect() }
            runCatching { oldClient?.close() }
        }?.invokeOnCompletion { oldScope.cancel() }
    }
}
