package com.example.guardian_iot

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.telecom.TelecomManager
import android.telephony.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class GuardianMonitorService : Service(), SensorEventListener {
    private lateinit var store: AppStore
    private lateinit var sensors: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val detector = FallDetector()
    private val gate = IncidentGate()
    private var deadline = 0L
    private var expected = 0
    private val received = mutableSetOf<String>()
    private var failed = false
    private var queued = false
    private var smsFinished = false
    private var callsFinished = false
    // In-process test seams; no intent or exported component can override these.
    internal var smsTransportOverride: SmsTransport? = null
    internal var callTransportOverride: ((TrustedContact) -> Unit)? = null
    private var targets = emptyList<TrustedContact>()
    private var calls = emptyList<TrustedContact>()
    private var callIndex = 0
    private var observedCall = false
    private var callStartedAt = 0L
    private var phoneListener: PhoneStateListener? = null
    private var listenedPhone: TelephonyManager? = null
    private var locationListener: LocationListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var incidentWakeLock: PowerManager.WakeLock? = null
    private var locationForeground = false
    private var lastStoredMotionAt = 0L
    private var lastMotionSyncQueuedAt = 0L
    private val telemetryExecutor = Executors.newSingleThreadExecutor()
    private var arduinoRequestInFlight = false
    private var unoQBleClient: UnoQBleClient? = null
    private var cameraFallRealtime: CameraFallRealtime? = null
    @Volatile private var cameraFallPollInFlight = false
    private var cameraFallUserId = ""
    private val locationUploadInFlight = AtomicBoolean(false)
    @Volatile private var remoteDeviceRegistered = false
    private var lastLocationUploadQueuedAt = 0L

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        instance = this
        channels()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_CANCEL -> { cancelCountdown(); return if (monitoring) START_STICKY else START_NOT_STICKY }
            ACTION_BRACELET_DISCONNECT -> {
                store.prefs.edit().putBoolean("bracelet_connection_enabled", false).apply()
                handler.removeCallbacks(arduinoPoll)
                stopUnoQBleClient()
                updateArduinoStatus("Pulsera desconectada desde la app.", recordEvent = false)
                if (!store.prefs.getBoolean("monitor_enabled", false)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_BRACELET_CONNECT, ACTION_BRACELET_RECONNECT -> {
                store.prefs.edit().putBoolean("bracelet_connection_enabled", true).apply()
                handler.removeCallbacks(arduinoPoll)
                stopUnoQBleClient()
            }
        }
        if (intent?.action == ACTION_START) store.prefs.edit()
            .putBoolean("monitor_enabled", true)
            .putBoolean("bracelet_connection_enabled", true)
            .commit()
        try {
            val locationWanted = store.prefs.getBoolean("include_location", false) && hasLocation()
            val fromVisibleUser = intent?.getBooleanExtra("user_visible", false) == true
            locationForeground = locationWanted && (locationForeground || fromVisibleUser)
            val braceletBleActive = store.prefs.getBoolean("bracelet_connection_enabled", false) &&
                store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE
            val bleForeground = (intent?.action in setOf(ACTION_START, ACTION_BRACELET_CONNECT, ACTION_BRACELET_RECONNECT) || monitoring || braceletBleActive) &&
                (Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT))
            val type = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
                (if (locationForeground && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
                (if (bleForeground && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            if (Build.VERSION.SDK_INT >= 29) startForeground(MONITOR_ID, monitorNotification(), type)
            else startForeground(MONITOR_ID, monitorNotification())
        } catch (e: Exception) {
            store.event("No se pudo iniciar el servicio: ${e.javaClass.simpleName}. Revisa permisos y abre la app.")
            store.prefs.edit().putBoolean("monitor_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (store.prefs.getBoolean("monitor_enabled", false) && !monitoring) startSensors()
        configureCameraFallMonitoring()
        if (!locationForeground) {
            locationListener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }
            locationListener = null
            clearRemoteLocation()
        }
        if (locationForeground && locationListener == null) startLocation()
        configureArduinoConnection()
        when (intent?.action) {
            ACTION_TEST -> beginCountdown(source = "test")
            ACTION_ALERT_NOW -> {
                val shouldDispatch = when (gate.state) {
                    IncidentGate.State.COUNTDOWN -> { handler.removeCallbacks(tick); gate.expire() }
                    IncidentGate.State.IDLE -> gate.begin(UUID.randomUUID().toString(), immediate = true)
                    IncidentGate.State.DISPATCHING -> false
                }
                if (shouldDispatch) dispatch()
            }
            else -> {
                // Resume only a countdown interrupted before dispatch. Never resend an already queued incident.
                if (intent == null && store.prefs.getString("pending_id", "").orEmpty().isNotEmpty()) {
                    val left = store.prefs.getLong("pending_deadline", 0) - System.currentTimeMillis()
                    val token = store.prefs.getString("pending_id", "").orEmpty()
                    if (store.prefs.getString("dispatch_id", "") == token) {
                        clearPending(); store.event("La alerta ya se solicitó; no se repetirá el envío.")
                    } else if (left > 0) beginCountdown(left.coerceAtMost(20_000), token)
                    else if (gate.begin(token, immediate = true)) dispatch()
                }
            }
        }
        return if (store.prefs.getBoolean("monitor_enabled", false) ||
            store.prefs.getBoolean("bracelet_connection_enabled", false)) START_STICKY else START_NOT_STICKY
    }
    private fun startSensors() {
        if (!has(Manifest.permission.SEND_SMS) || !has(Manifest.permission.CALL_PHONE) || store.recipients().isEmpty()) {
            store.prefs.edit().putBoolean("monitor_enabled", false).apply()
            store.event("Monitoreo no iniciado: configura contactos y permite SMS y llamadas.")
            if (gate.state == IncidentGate.State.IDLE) stopSelf()
            return
        }
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null || !sensors.registerListener(this, sensor, 20_000, 0)) {
            store.prefs.edit().putBoolean("monitor_enabled", false).apply()
            store.event("Este dispositivo no tiene acelerómetro disponible. Puedes enviar una alerta manual.")
            stopSelf()
            return
        }
        monitoring = true
        lastStoredMotionAt = 0L
        if (!sensor.isWakeUpSensor) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:fall-monitor")
            handler.post(renewWakeLock)
        }
        store.event("Monitoreo activo · SMS automático después de 20 segundos sin cancelar")
        updateNotification()
    }
    private val arduinoPoll = object : Runnable {
        override fun run() {
            if (!shouldPollArduinoWifi()) return
            if (arduinoRequestInFlight) return
            arduinoRequestInFlight = true
            val endpoint = "${store.arduinoBaseUrl().trimEnd('/')}/api/v1/sensor"
            telemetryExecutor.execute {
                var status = "UNO Q: no se pudo consultar el servicio."
                val samples = mutableListOf<JSONObject>()
                try {
                    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2_500
                        readTimeout = 2_500
                        setRequestProperty("Accept", "application/json")
                    }
                    val code = connection.responseCode
                    val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (code in 200..299) {
                        val root = JSONObject(response)
                        val readings = root.optJSONArray("readings") ?: root.optJSONArray("data")
                        if (readings != null) {
                            for (index in 0 until readings.length()) readings.optJSONObject(index)?.let(samples::add)
                        } else {
                            samples += root.optJSONObject("reading") ?: root.optJSONObject("data") ?: root
                        }
                        status = if (samples.isEmpty()) "UNO Q: conectado, esperando una lectura." else "UNO Q: conexión activa; ${samples.size} lectura(s) recibida(s)."
                    } else {
                        val detail = runCatching { JSONObject(response).optString("message").takeIf(String::isNotBlank) }.getOrNull()
                        status = "UNO Q: HTTP $code${detail?.let { " · $it" }.orEmpty()}"
                    }
                } catch (error: Exception) {
                    status = "UNO Q: sin conexión · ${error.javaClass.simpleName}"
                }
                val finalStatus = status
                samples.forEach { sample -> runCatching { persistArduinoSample(sample) } }
                handler.post {
                    arduinoRequestInFlight = false
                    if (!monitoring) return@post
                    updateArduinoStatus(finalStatus)
                    handler.postDelayed(this, ARDUINO_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun startUnoQBleClient() {
        if (unoQBleClient != null) return
        val permissionsAvailable = if (Build.VERSION.SDK_INT >= 31) {
            has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
        } else has(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!permissionsAvailable) {
            updateArduinoStatus("Pulsera Bluetooth: permite dispositivos cercanos para encontrarla.", recordEvent = false)
            return
        }
        unoQBleClient = UnoQBleClient(
            this,
            onReading = { sample -> handler.post { if (shouldReadBracelet()) persistArduinoSample(sample) } },
            onStatus = { status -> handler.post { if (shouldReadBracelet()) updateArduinoStatus("Bluetooth: $status", recordEvent = false) } },
        ).also { it.start() }
    }

    private fun shouldReadBracelet(): Boolean = store.prefs.getBoolean("bracelet_connection_enabled", monitoring)

    private fun shouldPollArduinoWifi(): Boolean = shouldReadBracelet() &&
        store.braceletConnectionMode() == AppStore.BRACELET_MODE_WIFI

    private fun configureArduinoConnection() {
        if (!shouldReadBracelet()) {
            handler.removeCallbacks(arduinoPoll)
            stopUnoQBleClient()
            return
        }
        when (store.braceletConnectionMode()) {
            AppStore.BRACELET_MODE_WIFI -> {
                stopUnoQBleClient()
                handler.removeCallbacks(arduinoPoll)
                handler.post(arduinoPoll)
            }
            else -> {
                handler.removeCallbacks(arduinoPoll)
                startUnoQBleClient()
            }
        }
    }

    private fun stopUnoQBleClient() {
        unoQBleClient?.stop()
        unoQBleClient = null
    }

    private fun persistArduinoSample(sample: JSONObject) {
        val changed = store.telemetry.recordArduinoSample(sample)
        val count = sample.optLong("caidas", -1L)
        var newFallEvents = 0
        if (count >= 0L) {
            val previous = store.prefs.getLong("arduino_fall_count", -1L)
            if (previous >= 0L && count > previous) {
                newFallEvents = (count - previous).coerceAtMost(MAX_QUEUED_ARDUINO_FALLS).toInt()
                val capturedAt = sample.optLong("captured_at_ms", System.currentTimeMillis())
                handler.post {
                    val startsAlert = monitoring && gate.state == IncidentGate.State.IDLE &&
                        beginCountdown(source = "arduino", occurredAtMillis = capturedAt)
                    val eventsToQueue = newFallEvents - if (startsAlert) 1 else 0
                    repeat(eventsToQueue.coerceAtLeast(0)) { index ->
                        store.telemetry.queueFallEvent(LocalFallEvent(
                            id = UUID.randomUUID().toString(), incidentId = null, deviceId = store.arduinoDeviceId(),
                            occurredAtMillis = capturedAt + index, source = "arduino", classification = "possible_fall",
                            confirmationStatus = "pending", summary = "La pulsera detectó un golpe compatible con una caída.",
                        ))
                    }
                    SyncScheduler.enqueue(applicationContext)
                }
            }
            // The counter resets after a board restart, so always store its latest value as baseline.
            store.prefs.edit().putLong("arduino_fall_count", count).apply()
        }
        if (changed) SyncScheduler.enqueue(applicationContext)
    }

    private fun updateArduinoStatus(status: String, recordEvent: Boolean = true) {
        if (store.prefs.getString("arduino_status", "").orEmpty() == status) return
        store.prefs.edit().putString("arduino_status", status).apply()
        if (recordEvent) store.event(status)
    }
    private val renewWakeLock = object : Runnable {
        override fun run() {
            if (!monitoring) return
            wakeLock?.let { if (it.isHeld) it.release(); it.acquire(10 * 60 * 1000L) }
            handler.postDelayed(this, 8 * 60 * 1000L)
        }
    }
    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val now = System.currentTimeMillis()
        // Persist a lightweight 1 Hz trace for local testing; detection still uses every sensor callback.
        if (now - lastStoredMotionAt >= 1_000L) {
            runCatching { store.telemetry.recordMotion(v[0], v[1], v[2], now) }
            lastStoredMotionAt = now
        }
        if (now - lastMotionSyncQueuedAt >= PHONE_MOTION_SYNC_INTERVAL_MS) {
            lastMotionSyncQueuedAt = now
            SyncScheduler.enqueue(applicationContext)
        }
        val magnitude = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
        acceptSensorSample(magnitude, event.timestamp / 1_000_000, event.sensor.type == Sensor.TYPE_ACCELEROMETER)
    }
    internal fun acceptSensorSample(magnitude: Double, timestamp: Long, includesGravity: Boolean = true) {
        if (gate.state == IncidentGate.State.IDLE && detector.sample(magnitude, timestamp, includesGravity)) beginCountdown()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun beginCountdown(
        duration: Long = 20_000,
        token: String = UUID.randomUUID().toString(),
        source: String = "phone",
        occurredAtMillis: Long = System.currentTimeMillis(),
        queueFallEvent: Boolean = true,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            store.prefs.edit().putBoolean("monitor_enabled", false).apply()
            store.event("Monitoreo en pausa: permite notificaciones para ver y cancelar las alertas.")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return false
        }
        if (!gate.begin(token)) return false
        holdIncidentWakeLock()
        deadline = SystemClock.elapsedRealtime() + duration
        store.prefs.edit().putString("pending_id", gate.id).putLong("pending_deadline", System.currentTimeMillis() + duration).commit()
        if (source in setOf("phone", "arduino", "camera")) {
            store.prefs.edit().putString("last_fall_incident_id", gate.id)
                .putLong("last_fall_incident_at_ms", occurredAtMillis).commit()
        }
        val detail = when (source) {
            "arduino" -> "la pulsera detectó un golpe"
            "camera" -> "la cámara vinculada detectó una posible caída"
            else -> "se detectó un movimiento compatible con una caída"
        }
        store.event("Posible caída · $detail. Tienes 20 segundos para pulsar Estoy bien", gate.id.orEmpty(), queueFallEvent)
        handler.post(tick)
        return true
    }

    private fun configureCameraFallMonitoring() {
        val shouldListen = monitoring && store.prefs.getBoolean("monitor_enabled", false) &&
            store.role() == AppStore.ROLE_WEARER
        if (!shouldListen) {
            stopCameraFallMonitoring()
            return
        }
        val sessions = EncryptedSessionStore(this)
        val session = sessions.read() ?: run { stopCameraFallMonitoring(); return }
        val backend = SupabaseBackend(sessions)
        if (!backend.isConfigured) { stopCameraFallMonitoring(); return }
        if (cameraFallUserId != session.userId) {
            cameraFallRealtime?.stop()
            cameraFallUserId = session.userId
            cameraFallRealtime = CameraFallRealtime(backend, sessions) { event ->
                handler.post { handleCameraFallEvent(event) }
            }.also { it.start(session.userId) }
        }
        if (!store.prefs.contains(KEY_CAMERA_FALL_CURSOR)) {
            store.prefs.edit().putLong(KEY_CAMERA_FALL_CURSOR, System.currentTimeMillis()).commit()
        }
        handler.removeCallbacks(cameraFallPoll)
        handler.post(cameraFallPoll)
    }

    private val cameraFallPoll = object : Runnable {
        override fun run() {
            if (!monitoring || !store.prefs.getBoolean("monitor_enabled", false) ||
                store.role() != AppStore.ROLE_WEARER) return
            if (!cameraFallPollInFlight) {
                cameraFallPollInFlight = true
                telemetryExecutor.execute {
                    try {
                        val sessions = EncryptedSessionStore(applicationContext)
                        val backend = SupabaseBackend(sessions)
                        val session = backend.currentSession() ?: return@execute
                        if (!backend.isConfigured || session.userId != cameraFallUserId) return@execute
                        val cursor = store.prefs.getLong(KEY_CAMERA_FALL_CURSOR, System.currentTimeMillis())
                        val events = backend.recentCameraFallEvents(session.userId, cursor)
                        handler.post {
                            if (!monitoring || session.userId != cameraFallUserId) return@post
                            val newestCreatedAt = (0 until events.length()).mapNotNull { index ->
                                events.optJSONObject(index)?.let { parseEventTime(it.optString("created_at")) }
                            }.maxOrNull()
                            for (index in 0 until events.length()) {
                                events.optJSONObject(index)?.let(::handleCameraFallEvent)
                            }
                            if (newestCreatedAt != null && newestCreatedAt > cursor) {
                                store.prefs.edit().putLong(KEY_CAMERA_FALL_CURSOR, newestCreatedAt).commit()
                            }
                        }
                    } catch (_: Exception) {
                        // Realtime remains active; the next poll retries any events missed during a connection loss.
                    } finally {
                        cameraFallPollInFlight = false
                    }
                }
            }
            handler.postDelayed(this, CAMERA_FALL_POLL_INTERVAL_MS)
        }
    }

    private fun handleCameraFallEvent(row: JSONObject) {
        if (!monitoring || store.role() != AppStore.ROLE_WEARER ||
            row.optString("user_id") != cameraFallUserId ||
            row.optString("source") != "camera_ai" ||
            row.optString("classification") != "possible_fall" ||
            row.optString("confirmation_status") != "pending") return
        val eventId = row.optString("id")
        if (runCatching { UUID.fromString(eventId) }.isFailure) return
        val eventDeviceId = row.optString("device_id")
        if (runCatching { UUID.fromString(eventDeviceId) }.isFailure) return
        if (!rememberCameraFall(eventId)) return
        val occurredAt = parseEventTime(row.optString("occurred_at")).takeIf { it > 0L } ?: System.currentTimeMillis()
        val createdAt = parseEventTime(row.optString("created_at"))
        if (createdAt > store.prefs.getLong(KEY_CAMERA_FALL_CURSOR, 0L)) {
            store.prefs.edit().putLong(KEY_CAMERA_FALL_CURSOR, createdAt).commit()
        }

        val activeIncident = gate.id?.takeIf { gate.state != IncidentGate.State.IDLE }
        val previousIncident = store.prefs.getString("last_fall_incident_id", null)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        val previousAt = store.prefs.getLong("last_fall_incident_at_ms", 0L)
        val correlatedIncident = activeIncident ?: previousIncident?.takeIf {
            previousAt > 0L && kotlin.math.abs(occurredAt - previousAt) <= CAMERA_FALL_DEDUPE_WINDOW_MS
        }

        if (correlatedIncident != null) {
            queueCameraFallAlias(row, eventId, eventDeviceId, occurredAt, correlatedIncident)
            return
        }

        val started = beginCountdown(
            token = eventId,
            source = "camera",
            occurredAtMillis = occurredAt,
            queueFallEvent = false,
        )
        val incident = gate.id?.takeIf { gate.state != IncidentGate.State.IDLE } ?: eventId
        queueCameraFallAlias(row, eventId, eventDeviceId, occurredAt, incident)
        if (!started && gate.state == IncidentGate.State.IDLE) {
            // No countdown could be started (for example, missing notification permission); keep the camera event for history.
            SyncScheduler.enqueue(applicationContext)
        }
    }

    private fun queueCameraFallAlias(row: JSONObject, eventId: String, deviceId: String, occurredAt: Long, incidentId: String) {
        store.telemetry.queueFallEvent(LocalFallEvent(
            id = eventId,
            incidentId = incidentId,
            deviceId = deviceId,
            occurredAtMillis = occurredAt,
            source = "camera_ai",
            classification = "possible_fall",
            confirmationStatus = "pending",
            summary = row.optString("summary").takeIf { it.isNotBlank() }
                ?: "La cámara vinculada detectó una posible caída.",
        ))
        SyncScheduler.enqueue(applicationContext)
    }

    private fun rememberCameraFall(eventId: String): Boolean {
        val old = runCatching { org.json.JSONArray(store.prefs.getString(KEY_CAMERA_FALL_IDS, "[]")) }
            .getOrDefault(org.json.JSONArray())
        if ((0 until old.length()).any { old.optString(it) == eventId }) return false
        val updated = org.json.JSONArray()
        for (index in maxOf(0, old.length() - MAX_REMEMBERED_CAMERA_FALLS + 1) until old.length()) {
            updated.put(old.optString(index))
        }
        updated.put(eventId)
        store.prefs.edit().putString(KEY_CAMERA_FALL_IDS, updated.toString()).commit()
        return true
    }

    private fun parseEventTime(value: String): Long = runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.getOrDefault(0L)

    private val tick = object : Runnable {
        override fun run() {
            if (gate.state != IncidentGate.State.COUNTDOWN) return
            val left = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
            if (left <= 0) {
                if (gate.expire()) dispatch()
            } else {
                val cancel = PendingIntent.getService(this@GuardianMonitorService, 2,
                    Intent(this@GuardianMonitorService, GuardianMonitorService::class.java).setAction(ACTION_CANCEL), PI_FLAGS)
                val builder = NotificationCompat.Builder(this@GuardianMonitorService, ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("¿Estás bien? · $left s")
                    .setContentText("Pulsa Estoy bien para cancelar el SMS y la llamada.")
                    .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(true)
                    .setContentIntent(openApp()).addAction(0, "Estoy bien", cancel)
                if (Build.VERSION.SDK_INT < 26) builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).setVibrate(longArrayOf(0, 700, 250, 700))
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()) builder.setFullScreenIntent(openApp(), true)
                manager.notify(ALERT_ID, builder.build())
                handler.postDelayed(this, 1_000)
            }
        }
    }
    private fun clearPending() {
        store.prefs.edit().remove("pending_id").remove("pending_deadline").commit()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_ID)
    }
    private fun cancelCountdown() {
        val incident = gate.id.orEmpty()
        if (!gate.cancel()) { if (!monitoring && gate.state == IncidentGate.State.IDLE) stopSelf(); return }
        handler.removeCallbacks(tick)
        clearPending()
        store.event("Alerta cancelada · confirmaste que estás bien", incident)
        finishIncident()
    }
    private fun dispatch() {
        if (gate.state != IncidentGate.State.DISPATCHING) return
        val incident = gate.id ?: return
        holdIncidentWakeLock()
        store.prefs.edit().putString("dispatch_id", incident).remove("pending_id").remove("pending_deadline").commit()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_ID)
        targets = store.recipients()
        expected = 0; received.clear(); failed = false; queued = false; smsFinished = false; callsFinished = false
        if (targets.isEmpty()) {
            store.event("No se envió la alerta: no hay contactos válidos.", incident)
            finishIncident(); return
        }
        // Read the latest values immediately before composing the outgoing SMS so the
        // caregiver receives the most recent pulse/SpO₂ collected on this phone.
        val latestVitals = store.telemetry.latestArduinoVitals()
        val pulse = latestVitals.optDouble("bpm", Double.NaN)
            .takeIf { it.isFinite() && it > 0.0 && it < 300.0 }
        val oxygen = latestVitals.optDouble("spo2", Double.NaN)
            .takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }
        fun readingTime(key: String): String {
            val capturedAt = latestVitals.optLong(key)
            return if (capturedAt > 0L) " (${android.text.format.DateFormat.format("dd/MM HH:mm", capturedAt)})" else ""
        }
        val pulseText = pulse?.let { "${it.toInt()} BPM${readingTime("heart_rate_at")}" } ?: "sin lectura"
        val oxygenText = oxygen?.let { "${it.toInt()}%${readingTime("spo2_at")}" } ?: "sin lectura"
        val vitalSummary = "Últimas lecturas de pulsera: pulso $pulseText; SpO₂ $oxygenText."
        val location = store.locationText()
        val summary = DirectSmsDispatcher.dispatch(targets, { c ->
            val greeting = c.name.takeIf { it.isNotBlank() }?.let { "Hola $it" } ?: "Hola"
            "$greeting, TzindaApp solicita ayuda: posible caída o alerta manual. Verifica cómo estoy. $vitalSummary $location"
        }, SmsTransport { contact, body ->
            try {
                smsTransportOverride?.let { transport ->
                    expected++
                    try { transport.queue(contact, body) } catch (e: Exception) { expected--; throw e }
                    store.event("SMS solicitado para ${contact.name.ifBlank { contact.phone }}", incident)
                    return@SmsTransport
                }
                check(has(Manifest.permission.SEND_SMS)) { "falta permiso de SMS" }
                val sms = smsManager()
                val parts = sms.divideMessage(body)
                val sent = ArrayList<PendingIntent>()
                val delivered = ArrayList<PendingIntent>()
                parts.indices.forEach { part ->
                    fun callback(action: String): PendingIntent {
                        val intent = Intent(this, SmsStatusReceiver::class.java).setAction(action).apply {
                            data = Uri.parse("tzinda://sms/$incident/${Uri.encode(contact.phone)}/$part/$action")
                            putExtra("incident", incident); putExtra("phone", contact.phone)
                            putExtra("name", contact.name); putExtra("part", part); putExtra("parts", parts.size)
                        }
                        return PendingIntent.getBroadcast(this, part, intent, PI_FLAGS)
                    }
                    sent.add(callback(ACTION_SMS_SENT)); delivered.add(callback(ACTION_SMS_DELIVERED))
                }
                expected += parts.size
                try {
                    if (parts.size == 1) sms.sendTextMessage(contact.phone, null, body, sent[0], delivered[0])
                    else sms.sendMultipartTextMessage(contact.phone, null, parts, sent, delivered)
                } catch (e: Exception) { expected -= parts.size; throw e }
                store.event("SMS solicitado para ${contact.name.ifBlank { contact.phone }}", incident)
            } catch (e: Exception) {
                failed = true
                store.event("No se pudo solicitar SMS a ${contact.name.ifBlank { contact.phone }}: ${e.message ?: e.javaClass.simpleName}", incident)
                throw e
            }
        })
        queued = true
        if (summary.failures > 0) failed = true
        store.event("Alerta: ${summary.requested} SMS solicitados · ${summary.failures} errores", incident)
        statusNotification("SMS solicitados: ${summary.requested}. Consulta Actividad para ver el resultado.")
        if (expected > received.size) handler.postDelayed(smsTimeout, 30_000)
        completeSmsIfReady()
        // Both actions start at countdown expiry. SMS callbacks cannot postpone the call.
        startCalls()
    }
    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager {
        check(packageManager.hasSystemFeature(if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY)) { "el dispositivo no admite SMS" }
        val subscriptions = if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
            (getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).activeSubscriptionInfoList.orEmpty() else emptyList()
        val saved = store.prefs.getInt("sms_subscription", -1)
        val default = SubscriptionManager.getDefaultSmsSubscriptionId()
        val id = subscriptions.firstOrNull { it.subscriptionId == saved }?.subscriptionId
            ?: default.takeIf { it in 0 until Int.MAX_VALUE }
            ?: subscriptions.minByOrNull { it.simSlotIndex }?.subscriptionId
            ?: throw IllegalStateException("configura una SIM predeterminada para SMS o permite leer el estado del teléfono")
        // A subscription-bound manager never launches an SMS application/WhatsApp/SIM chooser.
        return if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java).createForSubscriptionId(id)
            else SmsManager.getSmsManagerForSubscriptionId(id)
    }
    internal fun smsResult(incident: String, key: String, success: Boolean) {
        if (gate.id != incident || gate.state != IncidentGate.State.DISPATCHING || smsFinished) return
        if (!received.add(key)) return
        if (!success) failed = true
        completeSmsIfReady()
    }
    private fun completeSmsIfReady() {
        if (!queued || received.size < expected || smsFinished) return
        smsFinished = true
        handler.removeCallbacks(smsTimeout)
        completeIncidentIfReady()
    }
    private val smsTimeout = Runnable {
        if (gate.state == IncidentGate.State.DISPATCHING && !smsFinished) {
            smsFinished = true; failed = true
            store.event("Android no confirmó todos los SMS dentro de 30 segundos. Revisa Actividad.", gate.id.orEmpty())
            completeIncidentIfReady()
        }
    }
    private fun completeIncidentIfReady() {
        if (smsFinished && callsFinished) finishIncident()
    }
    private fun finishCalls() {
        callsFinished = true
        handler.removeCallbacks(checkCall)
        completeIncidentIfReady()
    }
    private fun startCalls() {
        if (!has(Manifest.permission.CALL_PHONE) && callTransportOverride == null) {
            store.event("Llamada no solicitada: falta el permiso de teléfono.", gate.id.orEmpty())
            finishCalls(); return
        }
        calls = targets.take(if (has(Manifest.permission.READ_PHONE_STATE)) 2 else 1)
        callIndex = 0
        startPhoneListener()
        nextCall()
    }
    @Suppress("DEPRECATION")
    private fun startPhoneListener() {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return
        try {
            listenedPhone = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            phoneListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (state != TelephonyManager.CALL_STATE_IDLE) observedCall = true
                    else if (observedCall) { observedCall = false; handler.removeCallbacks(checkCall); handler.postDelayed(checkCall, 2_000) }
                }
            }
            listenedPhone?.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: Exception) { phoneListener = null }
    }
    private fun nextCall() {
        if (gate.state != IncidentGate.State.DISPATCHING) return
        if (callsFinished) return
        if ((checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED && callTransportOverride == null) || callIndex >= calls.size) { finishCalls(); return }
        if (callTransportOverride == null && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            runCatching { (getSystemService(TELECOM_SERVICE) as TelecomManager).isInCall }.getOrDefault(true)) {
            handler.removeCallbacks(checkCall); handler.postDelayed(checkCall, 5_000); return
        }
        val contact = calls[callIndex++]
        try {
            callTransportOverride?.let { transport ->
                transport(contact)
                observedCall = false; callStartedAt = SystemClock.elapsedRealtime()
                store.event("Llamada solicitada a ${contact.name.ifBlank { contact.phone }}", gate.id.orEmpty())
                handler.postDelayed(checkCall, 5_000)
                return
            }
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            val extras = Bundle()
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val accounts = telecom.callCapablePhoneAccounts
                val saved = store.prefs.getInt("sms_subscription", -1)
                val chosen = accounts.firstOrNull { account ->
                    if (Build.VERSION.SDK_INT >= 30) (getSystemService(TELEPHONY_SERVICE) as TelephonyManager)
                        .createForPhoneAccountHandle(account)?.subscriptionId == saved else account.id == saved.toString()
                } ?: telecom.getDefaultOutgoingPhoneAccount("tel") ?: accounts.firstOrNull()
                chosen?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            }
            telecom.placeCall(Uri.fromParts("tel", contact.phone, null), extras)
            observedCall = false; callStartedAt = SystemClock.elapsedRealtime()
            store.event("Llamada solicitada a ${contact.name.ifBlank { contact.phone }}", gate.id.orEmpty())
            handler.postDelayed(checkCall, 5_000)
        } catch (e: Exception) {
            store.event("No se pudo llamar a ${contact.name.ifBlank { contact.phone }}: ${e.javaClass.simpleName}", gate.id.orEmpty())
            handler.postDelayed({ nextCall() }, 2_000)
        }
    }
    private val checkCall = object : Runnable {
        override fun run() {
            if (gate.state != IncidentGate.State.DISPATCHING || callsFinished) return
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { finishCalls(); return }
            val inCall = runCatching { (getSystemService(TELECOM_SERVICE) as TelecomManager).isInCall }.getOrDefault(true)
            if (inCall) { observedCall = true; handler.postDelayed(this, 5_000) }
            else if (observedCall || SystemClock.elapsedRealtime() - callStartedAt >= 15_000) { observedCall = false; nextCall() }
            else handler.postDelayed(this, 5_000)
        }
    }
    @Suppress("DEPRECATION")
    private fun finishIncident() {
        handler.removeCallbacks(checkCall); handler.removeCallbacks(smsTimeout)
        phoneListener?.let { runCatching { listenedPhone?.listen(it, PhoneStateListener.LISTEN_NONE) } }
        phoneListener = null
        gate.finish()
        releaseIncidentWakeLock()
        clearPending()
        store.prefs.edit().remove("dispatch_id").apply()
        updateNotification()
        if (!monitoring) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }?.let { saveLocation(it) }
            val provider = when {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> return
            }
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) { saveLocation(location) }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            // Location is refreshed periodically only while the user has enabled monitoring and consented.
            // Android may still throttle updates based on the provider, battery policy, and device state.
            manager.requestLocationUpdates(provider, 5_000, 5f, locationListener!!, Looper.getMainLooper())
        } catch (_: Exception) { store.event("Ubicación no disponible; las alertas por SMS siguen activas.") }
    }
    private fun saveLocation(location: Location) {
        store.prefs.edit().putString("latitude", location.latitude.toString()).putString("longitude", location.longitude.toString())
            .putLong("location_time", location.time).apply()
        runCatching { store.telemetry.recordLocation(location.latitude, location.longitude, location.time) }
        if (!locationForeground || !monitoring || !store.prefs.getBoolean("include_location", false) || !hasLocation()) return
        val now = System.currentTimeMillis()
        if (now - lastLocationUploadQueuedAt < 8_000L || !locationUploadInFlight.compareAndSet(false, true)) return
        lastLocationUploadQueuedAt = now
        runCatching {
            telemetryExecutor.execute {
                try {
                    val backend = SupabaseBackend(EncryptedSessionStore(this))
                    if (!backend.isConfigured || backend.currentSession() == null) return@execute
                    val deviceId = store.localDeviceId()
                    if (!remoteDeviceRegistered) {
                        backend.upsertDevice(deviceId, deviceId, "Teléfono TzindaApp", "phone", "not_applicable")
                        remoteDeviceRegistered = true
                    }
                    backend.upsertCaregiverLocation(deviceId, location.latitude, location.longitude,
                        if (location.hasAccuracy()) location.accuracy else null, location.time)
                } catch (_: Exception) {
                    // Location collection remains local when the account or network is unavailable.
                } finally {
                    locationUploadInFlight.set(false)
                }
            }
        }.onFailure { locationUploadInFlight.set(false) }
    }
    private fun clearRemoteLocation() {
        val deviceId = store.localDeviceId()
        runCatching {
            telemetryExecutor.execute {
                runCatching {
                    val backend = SupabaseBackend(EncryptedSessionStore(this))
                    if (backend.isConfigured && backend.currentSession() != null) backend.deleteCaregiverLocation(deviceId)
                }
                remoteDeviceRegistered = false
            }
        }
    }
    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun hasLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PI_FLAGS)
    private fun monitorNotification() = NotificationCompat.Builder(this, MONITOR_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("TzindaApp · ${when { monitoring -> "monitoreo activo"; gate.state != IncidentGate.State.IDLE -> "alerta en proceso"; else -> "servicio activo" }}")
        .setContentText(store.prefs.getString("status", "Preparando monitoreo"))
        .setContentIntent(openApp()).setOnlyAlertOnce(true).setOngoing(true).build()
    private fun updateNotification() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(MONITOR_ID, monitorNotification()) }
    private fun statusNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(SMS_ID,
            NotificationCompat.Builder(this, STATUS_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TzindaApp · estado de SMS").setContentText(text).setContentIntent(openApp()).setAutoCancel(true).build())
    }
    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL, "Monitoreo", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Resultados de SMS", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Posible caída", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        })
    }
    // Keep the countdown and SMS callbacks moving when the screen switches off.
    // Every acquisition has a timeout; all completion/stop paths release it.
    private fun holdIncidentWakeLock() {
        if (incidentWakeLock == null) incidentWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:alert-countdown")
        incidentWakeLock?.let { if (it.isHeld) it.release(); it.acquire(120_000) }
        handler.removeCallbacks(renewIncidentWakeLock)
        handler.postDelayed(renewIncidentWakeLock, 60_000)
    }
    private val renewIncidentWakeLock = Runnable {
        if (gate.state != IncidentGate.State.IDLE) holdIncidentWakeLock() else releaseIncidentWakeLock()
    }
    private fun releaseIncidentWakeLock() {
        handler.removeCallbacks(renewIncidentWakeLock)
        incidentWakeLock?.let { if (it.isHeld) it.release() }
    }
    private fun stopAll() {
        stopCameraFallMonitoring()
        handler.removeCallbacks(arduinoPoll)
        stopUnoQBleClient()
        monitoring = false
        store.prefs.edit().putBoolean("monitor_enabled", false).putBoolean("bracelet_connection_enabled", false).commit()
        locationListener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }
        locationListener = null
        clearRemoteLocation()
        clearPending(); gate.finish(); releaseIncidentWakeLock()
        store.event("Monitoreo detenido")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        monitoring = false
        stopCameraFallMonitoring()
        stopUnoQBleClient()
        handler.removeCallbacksAndMessages(null)
        telemetryExecutor.shutdown()
        sensors.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        releaseIncidentWakeLock()
        locationListener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }
        @Suppress("DEPRECATION")
        phoneListener?.let { runCatching { listenedPhone?.listen(it, PhoneStateListener.LISTEN_NONE) } }
        instance = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile var monitoring = false
            private set
        @Volatile internal var instance: GuardianMonitorService? = null
        const val ACTION_START = "com.example.guardian_iot.START"
        const val ACTION_STOP = "com.example.guardian_iot.STOP"
        const val ACTION_CANCEL = "com.example.guardian_iot.CANCEL_ALERT"
        const val ACTION_UPDATE = "com.example.guardian_iot.UPDATE"
        const val ACTION_TEST = "com.example.guardian_iot.TEST"
        const val ACTION_ALERT_NOW = "com.example.guardian_iot.ALERT_NOW"
        const val ACTION_BRACELET_CONNECT = "com.example.guardian_iot.BRACELET_CONNECT"
        const val ACTION_BRACELET_DISCONNECT = "com.example.guardian_iot.BRACELET_DISCONNECT"
        const val ACTION_BRACELET_RECONNECT = "com.example.guardian_iot.BRACELET_RECONNECT"
        const val ACTION_SMS_SENT = "com.example.guardian_iot.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.example.guardian_iot.SMS_DELIVERED"
        const val STATUS_CHANNEL = "native_sms_status"
        private const val MONITOR_CHANNEL = "native_monitor"
        private const val ALERT_CHANNEL = "native_fall_alert"
        private const val MONITOR_ID = 2001
        private const val ALERT_ID = 2002
        private const val SMS_ID = 2003
        private const val ARDUINO_POLL_INTERVAL_MS = 5_000L
        private const val CAMERA_FALL_POLL_INTERVAL_MS = 5_000L
        private const val PHONE_MOTION_SYNC_INTERVAL_MS = 30_000L
        private const val CAMERA_FALL_DEDUPE_WINDOW_MS = 45_000L
        private const val MAX_REMEMBERED_CAMERA_FALLS = 256
        private const val KEY_CAMERA_FALL_CURSOR = "camera_fall_created_at_cursor_ms"
        private const val KEY_CAMERA_FALL_IDS = "handled_camera_fall_event_ids"
        private const val MAX_QUEUED_ARDUINO_FALLS = 10L
        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private fun stopCameraFallMonitoring() {
        handler.removeCallbacks(cameraFallPoll)
        cameraFallRealtime?.stop()
        cameraFallRealtime = null
        cameraFallUserId = ""
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sent = intent.action == GuardianMonitorService.ACTION_SMS_SENT
        if (!sent && intent.action != GuardianMonitorService.ACTION_SMS_DELIVERED) return
        val incident = intent.getStringExtra("incident") ?: return
        val phone = intent.getStringExtra("phone").orEmpty()
        val name = intent.getStringExtra("name").orEmpty().ifBlank { phone }
        val part = intent.getIntExtra("part", 0)
        val key = "$incident|$phone|$part|${intent.action}"
        val store = AppStore(context)
        if (!store.acceptCallback(key)) return
        val success = resultCode == Activity.RESULT_OK
        val detail = when (resultCode) {
            Activity.RESULT_OK -> if (sent) "SMS enviado a la red" else {
                val pdu = intent.getByteArrayExtra("pdu")
                val status = pdu?.let { runCatching { SmsMessage.createFromPdu(it, intent.getStringExtra("format") ?: "3gpp")?.status }.getOrNull() }
                if (status == 0) "SMS entregado al destinatario" else "Reporte de entrega recibido${status?.let { " (estado $it)" }.orEmpty()}"
            }
            SmsManager.RESULT_ERROR_NO_SERVICE -> "SMS no enviado: sin servicio móvil"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "SMS no enviado: radio/SIM apagada"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS no enviado: límite de envío excedido"
            else -> "${if (sent) "SMS rechazado" else "Entrega no confirmada"} (código $resultCode)"
        }
        store.event("$detail · $name · parte ${part + 1}/${intent.getIntExtra("parts", 1)}", incident)
        if (sent) GuardianMonitorService.instance?.smsResult(incident, key, success)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2003,
            NotificationCompat.Builder(context, GuardianMonitorService.STATUS_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TzindaApp · resultado de SMS").setContentText("$detail · $name").setOnlyAlertOnce(true).setAutoCancel(true).build())
    }
}

class GuardianBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = AppStore(context)
        if (!store.prefs.getBoolean("monitor_enabled", false)) return
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) || store.recipients().isEmpty()) {
            store.prefs.edit().putBoolean("monitor_enabled", false).apply()
            store.event("Abre TzindaApp y configura los permisos para reactivar el monitoreo.")
            return
        }
        try {
            val start = Intent(context, GuardianMonitorService::class.java).setAction(GuardianMonitorService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start) else context.startService(start)
        } catch (_: Exception) {
            store.event("Android no permitió reiniciar el monitoreo. Abre TzindaApp para activarlo.")
        }
    }
}
