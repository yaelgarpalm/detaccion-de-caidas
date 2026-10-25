package com.example.guardian_iot

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.*
import android.location.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.example.deteccioncaidas.CameraFallController
import com.example.deteccioncaidas.PoseOverlayView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var store: AppStore
    private lateinit var sessions: EncryptedSessionStore
    private lateinit var backend: SupabaseBackend
    private lateinit var medicationRepository: MedicationRepository
    private lateinit var frame: FrameLayout
    private lateinit var root: View
    private var tab = 0
    private var authMode: AuthMode? = null
    private var authStatus: TextView? = null
    private var authEmail: EditText? = null
    private var authPassword: EditText? = null
    private var authName: EditText? = null
    private var authConfirmation: EditText? = null
    private var authPrivacy: CheckBox? = null
    private var caregiverRefreshInFlight = false
    private var caregiverRefreshAt = 0L
    private var caregiverLocationRealtime: CaregiverLocationRealtime? = null
    private var caregiverLocationRealtimeDevice = ""
    private var caregiverHealthRealtime: CaregiverHealthRealtime? = null
    private var caregiverHealthRealtimeKey = ""
    private var locationFreshnessRenderAt = 0L
    private var profileReturnTab = 0
    private var cameraReturnTab = 0
    private var deviceReturnTab = 0
    private var medicationReturnTab = 0
    private var activityFilter = 0
    private var activitySection = 0
    private var patientPulseValue: TextView? = null
    private var patientSpo2Value: TextView? = null
    private var patientVitalsStatus: TextView? = null
    private var caregiverSharedLocationView: TextView? = null
    private var caregiverLocationCoordinatesView: TextView? = null
    private var caregiverLocationFreshnessView: TextView? = null
    private var caregiverSharedStatusView: TextView? = null
    private var caregiverMapButton: View? = null
    private var homePulseraStatus: TextView? = null
    private val braceletStatusViews = mutableListOf<TextView>()
    private var pendingBraceletAction: String? = null
    private var patientVitalsRefreshAt = 0L
    private var cameraRegistryRenderedRevision = 0L
    private var editingContacts = false
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var appBarSubtitle: TextView? = null
    private var appBarStatus: TextView? = null
    private var appBarProfile: TextView? = null
    private var homeStatus: TextView? = null
    private var homeHeadline: TextView? = null
    private var monitorButton: Button? = null
    private var monitorToggle: Switch? = null
    private var updatingMonitorToggle = false
    private var emergencyProgressCard: LinearLayout? = null
    private var emergencyProgressStatus: TextView? = null
    private var smsPermissionStatus: TextView? = null
    private var smsPermissionButton: Button? = null
    private var smsRequestedInSession = false
    private var smsRequestInFlight = false
    private var resumeAlertSetup = false
    private var smsHelpDialog: AlertDialog? = null
    private val contactEdits = mutableListOf<Pair<EditText, EditText>>()
    private var contactDraft: List<TrustedContact>? = null
    private val profileEdits = linkedMapOf<String, EditText>()
    private var profileDraft: JSONObject? = null
    private var consentData: Switch? = null
    private var consentLocation: Switch? = null
    private var birthDate = ""
    private var birthLabel: TextView? = null
    private var pendingAction: String? = null
    private var importSlot = -2
    private var readingContacts = false
    private var countdownDialog: Dialog? = null
    private var countdownText: TextView? = null
    private var countdownDescription: TextView? = null
    private var countdownRing: CountdownRing? = null
    private var cameraController: CameraFallController? = null
    private var cameraPreviewView: PreviewView? = null
    private var cameraOverlayView: PoseOverlayView? = null
    private var cameraPlaceholder: TextView? = null
    private var cameraStatusTitle: TextView? = null
    private var cameraStatusDetail: TextView? = null
    private var cameraActionButton: Button? = null
    private var cameraRequested = false
    private var cameraFallHandled = false
    private var cameraSessionLogged = false
    private var shownIncident: String? = null
    private var locationListener: LocationListener? = null
    private val isCaregiver get() = store.role() == AppStore.ROLE_CAREGIVER
    private val isCameraRole get() = store.role() == AppStore.ROLE_CAMERA
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::processPairingCodeOrLink)
    }
    private enum class AuthMode { LOGIN, REGISTER, RECOVER, RESET }
    private val locationTimeout = Runnable {
        if (locationListener != null) { stopLocation(); toast("No se obtuvo una ubicación nueva. Los SMS siguen disponibles.", true) }
    }
    private val dark get() = store.prefs.getBoolean("dark_mode", false)
    private val ink get() = Color.parseColor(if (dark) "#DFE4DE" else "#1C1C15")
    private val muted get() = Color.parseColor(if (dark) "#BFC9BD" else "#414943")
    private val accent get() = Color.parseColor(if (dark) "#86D89B" else "#254D3A")
    private val paper get() = Color.parseColor(if (dark) "#101412" else "#FDF9EE")
    private val cardColor get() = Color.parseColor(if (dark) "#1C211E" else "#FFFFFF")
    private val mint get() = Color.parseColor(if (dark) "#263A2D" else "#E2F3DF")
    private val softSurface get() = Color.parseColor(if (dark) "#262B28" else "#F2EEE3")
    private val outline get() = Color.parseColor(if (dark) "#3F4940" else "#E4DDD0")
    private val onAccent get() = Color.parseColor(if (dark) "#00391A" else "#FFFFFF")
    private val critical get() = Color.parseColor(if (dark) "#E05353" else "#C83E45")

    override fun onCreate(savedInstanceState: Bundle?) {
        store = AppStore(this)
        sessions = EncryptedSessionStore(this)
        backend = SupabaseBackend(sessions)
        medicationRepository = MedicationRepository(this, backend, store)
        authMode = savedInstanceState?.getString("auth_mode")?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() }
            ?: if (backend.isConfigured && sessions.read() == null && !store.prefs.getBoolean("local_only_mode", false)) AuthMode.LOGIN else null
        if (dark) setTheme(R.style.AppTheme_Dark)
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: if (store.role().isBlank()) ROLE_TAB else 0
        pendingAction = savedInstanceState?.getString("action")
        profileReturnTab = savedInstanceState?.getInt("profile_return_tab", 0) ?: 0
        cameraReturnTab = savedInstanceState?.getInt("camera_return_tab", 0) ?: 0
        deviceReturnTab = savedInstanceState?.getInt("device_return_tab", 0) ?: 0
        medicationReturnTab = savedInstanceState?.getInt("medication_return_tab", 0) ?: 0
        activityFilter = savedInstanceState?.getInt("activity_filter", 0) ?: 0
        activitySection = savedInstanceState?.getInt("activity_section", 0) ?: 0
        editingContacts = savedInstanceState?.getBoolean("editing_contacts", false) ?: false
        smsRequestInFlight = savedInstanceState?.getBoolean("sms_request_pending") ?: false
        resumeAlertSetup = savedInstanceState?.getBoolean("resume_alert_setup") ?: false
        importSlot = savedInstanceState?.getInt("import_slot", -2) ?: -2
        savedInstanceState?.getString("contact_draft")?.let { json ->
            runCatching { val a = JSONArray(json); contactDraft = (0 until a.length()).map { TrustedContact(a.getJSONObject(it).optString("name"), a.getJSONObject(it).optString("phone")) } }
        }
        savedInstanceState?.getString("profile_draft")?.let { runCatching { profileDraft = JSONObject(it) } }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        frame = findViewById(R.id.content)
        root.setBackgroundColor(paper)
        appBarSubtitle = findViewById(R.id.app_bar_subtitle)
        appBarStatus = findViewById(R.id.app_bar_status)
        appBarProfile = findViewById(R.id.app_bar_profile)
        findViewById<View>(R.id.app_bar).setBackgroundColor(paper)
        appBarProfile?.apply {
            background = rounded(accent, 24)
            setTextColor(onAccent)
            setOnClickListener { openProfile() }
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val padding = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.stableInsetBottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        findViewById<LinearLayout>(R.id.navigation).setBackgroundColor(cardColor)
        listOf(R.id.nav_home, R.id.nav_activity, R.id.nav_contacts, R.id.nav_settings).forEachIndexed { index, id ->
            findViewById<Button>(id).setOnClickListener { selectTab(index) }
        }
        render()
        if (sessions.read() != null) SyncScheduler.enqueue(applicationContext)
        if (intent?.data != null) { processAuthCallback(intent); processPairingLink(intent.data) }
        if (has(Manifest.permission.SEND_SMS) && !store.prefs.getBoolean("contacts_offered", false)) handler.post { offerContacts() }
    }
    override fun onSaveInstanceState(out: Bundle) {
        saveDrafts()
        out.putInt("tab", tab); out.putInt("profile_return_tab", profileReturnTab); out.putInt("camera_return_tab", cameraReturnTab); out.putInt("device_return_tab", deviceReturnTab); out.putInt("medication_return_tab", medicationReturnTab)
        out.putInt("activity_filter", activityFilter); out.putInt("activity_section", activitySection); out.putBoolean("editing_contacts", editingContacts)
        out.putString("action", pendingAction); out.putInt("import_slot", importSlot)
        out.putBoolean("sms_request_pending", smsRequestInFlight)
        out.putBoolean("resume_alert_setup", resumeAlertSetup)
        authMode?.let { out.putString("auth_mode", it.name) }
        contactDraft?.let { rows -> out.putString("contact_draft", JSONArray().apply { rows.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) } }.toString()) }
        profileDraft?.let { out.putString("profile_draft", it.toString()) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() {
        super.onResume(); handler.post(live); refreshPermissionUi(); updateServiceIfActive()
        MedicationReminderScheduler.synchronizeAll(this)
        if (tab == CAMERA_TAB && cameraRequested && has(Manifest.permission.CAMERA)) launchCameraDetection()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumeAlertSetup && !smsRequestInFlight && !isFinishing && !isDestroyed) {
            resumeAlertSetup = false
            pendingAction?.let { action -> handler.post { if (!isFinishing && !isDestroyed) requestRemainingAlertPermissions(action) } }
        }
    }
    override fun onPause() {
        handler.removeCallbacks(live)
        if (tab == CAMERA_TAB && cameraRequested) cameraController?.stop()
        super.onPause()
    }
    override fun onDestroy() {
        caregiverLocationRealtime?.stop()
        caregiverLocationRealtime = null
        caregiverHealthRealtime?.stop()
        caregiverHealthRealtime = null
        handler.removeCallbacksAndMessages(null)
        countdownDialog?.dismiss()
        smsHelpDialog?.dismiss()
        stopLocation()
        cameraController?.shutdown()
        cameraController = null
        io.shutdownNow()
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        processAuthCallback(intent)
        processPairingLink(intent.data)
    }
    private fun processAuthCallback(intent: Intent) {
        val callback = intent.data ?: return
        if (callback.scheme == "com.guardian.tzindaapp.v2" && callback.host == "auth-callback") {
            io.execute {
                runCatching { backend.acceptPasswordRecovery(callback) }
                    .onSuccess { runOnUiThread { authMode = AuthMode.RESET; render() } }
                    .onFailure { failure -> runOnUiThread { authMode = AuthMode.LOGIN; render(); toast(failure.message ?: "No se pudo abrir el enlace.", true) } }
            }
        }
    }
    private fun processPairingLink(uri: Uri?) {
        if (uri?.scheme != "tzinda" || uri.host != "pair") return
        val type = uri.getQueryParameter("type").orEmpty()
        val code = uri.getQueryParameter("code").orEmpty()
        if (type !in setOf("caregiver", "camera") || code.filter(Char::isLetterOrDigit).length != 8) return
        processPairingCodeOrLink(uri.toString())
    }

    private fun processPairingCodeOrLink(value: String) {
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        val fromLink = uri?.scheme == "tzinda" && uri.host == "pair"
        val type = if (fromLink) uri?.getQueryParameter("type").orEmpty()
            else store.prefs.getString("pairing_expected_type", "").orEmpty().ifBlank { if (isCameraRole) "camera" else "caregiver" }
        val code = (if (fromLink) uri?.getQueryParameter("code").orEmpty() else value)
            .filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        if (type !in setOf("camera", "caregiver") || code.length != 8) {
            toast("El QR o código no es válido. Revisa que tenga 8 caracteres.", true); return
        }
        showPairingConfirmation(type, code)
    }

    private fun showPairingConfirmation(type: String, code: String) {
        if (type !in setOf("camera", "caregiver") || code.filter(Char::isLetterOrDigit).length != 8) return
        val camera = type == "camera"
        val deviceName = if (camera) "Cámara independiente" else "Teléfono de la persona protegida"
        val content = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
            addView(text("●  CÓDIGO RECONOCIDO", 12, true, accent).apply {
                gravity = Gravity.CENTER
                background = rounded(mint, 20)
                setPadding(dp(14), dp(9), dp(14), dp(9))
            })
            addView(text(if (camera) "▣" else "♥", 36, true, accent).apply {
                gravity = Gravity.CENTER
                background = rounded(softSurface, 40)
                setPadding(dp(18), dp(12), dp(18), dp(12))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            addView(text(deviceName, 22, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(text("Vinculación segura", 15, true, accent).apply { gravity = Gravity.CENTER })
            val displayCode = code.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).chunked(4).joinToString("-")
            addView(text(displayCode, 24, true, ink).apply {
                gravity = Gravity.CENTER
                background = rounded(cardColor, 18)
                setPadding(dp(18), dp(10), dp(18), dp(10))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(text("Código de 8 caracteres de un solo uso.", 13, color = muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        }
        AlertDialog.Builder(this).setTitle("¿Conectar este dispositivo?").setView(content)
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Conectar") { _, _ ->
                if (sessions.read() == null || !backend.isConfigured) {
                    store.prefs.edit().putString("pending_pairing_type", type).putString("pending_pairing_code", code).apply()
                    toast("Inicia sesión para terminar la vinculación.")
                    authMode = AuthMode.LOGIN; render()
                } else {
                    store.saveRole(if (camera) AppStore.ROLE_CAMERA else AppStore.ROLE_CAREGIVER)
                    if (camera) acceptCameraPairing(code) else acceptCaregiverPairing(code)
                }
            }.show()
    }

    private fun scanPairingQr(type: String) {
        store.prefs.edit().putString("pairing_expected_type", type).apply()
        qrScanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(if (type == "camera") "Escanea el QR de la persona protegida" else "Escanea el QR de vinculación")
            .setBeepEnabled(false).setOrientationLocked(false))
    }
    private fun acceptCaregiverPairing(code: String, status: TextView? = null) {
        if (!backend.isConfigured || sessions.read() == null) { status?.text = "Inicia sesión antes de vincular."; return }
        status?.text = "Vinculando cuenta…"
        io.execute {
            val result = runCatching {
                // The RPC already returns the created link. Use that authoritative result
                // instead of requiring a second SELECT that can fail independently.
                backend.acceptCaregiverInvite(code).apply {
                    if (!has("id")) put("id", optString("link_id"))
                }
            }
            runOnUiThread {
                result.onSuccess { link ->
                    caregiverLocationRealtime?.stop()
                    caregiverLocationRealtime = null
                    caregiverLocationRealtimeDevice = ""
                    caregiverHealthRealtime?.stop()
                    caregiverHealthRealtime = null
                    caregiverHealthRealtimeKey = ""
                    val deviceId = link.optString("device_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
                    store.prefs.edit().remove("caregiver_shared_location").putString("caregiver_link_id", link.optString("id"))
                        .putString("caregiver_owner_id", link.optString("owner_user_id"))
                        .putString("caregiver_link_device_id", deviceId).remove("caregiver_location_device_id")
                        .remove("pending_pairing_type").remove("pending_pairing_code").apply()
                    toast("Cuenta vinculada.")
                    tab = 0; render()
                }.onFailure { status?.text = it.message ?: "No se pudo vincular la cuenta."; if (status == null) toast(it.message ?: "Código inválido o vencido.", true) }
            }
        }
    }

    private fun acceptCameraPairing(code: String, status: TextView? = null) {
        if (!backend.isConfigured || sessions.read() == null) { status?.text = "Inicia sesión antes de vincular."; return }
        status?.text = "Vinculando cámara…"
        io.execute {
            val result = runCatching { backend.acceptCameraDeviceInvite(code) }
            runOnUiThread {
                result.onSuccess { linked ->
                    store.prefs.edit().putString("paired_camera_owner_id", linked.optString("owner_user_id"))
                        .putString("paired_camera_device_id", linked.optString("device_id"))
                        .putString("paired_camera_name", linked.optString("device_name"))
                        .remove("pending_pairing_type").remove("pending_pairing_code").apply()
                    toast("Cámara vinculada a ${linked.optString("device_name")}.")
                    tab = 0; render()
                }.onFailure { status?.text = it.message ?: "No se pudo vincular la cámara."; if (status == null) toast(it.message ?: "Código inválido o vencido.", true) }
            }
        }
    }

    private fun showPairingQr(title: String, type: String, code: String, expiresAt: String) {
        val normalizedCode = code.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        if (normalizedCode.length != 8) {
            toast("Supabase devolvió un código de vinculación incompleto. Inténtalo de nuevo.", true)
            return
        }
        val displayCode = normalizedCode.chunked(4).joinToString("-")
        val payload = Uri.Builder().scheme("tzinda").authority("pair")
            .appendQueryParameter("type", type).appendQueryParameter("code", normalizedCode).build().toString()
        val image = runCatching { createQrBitmap(payload, dp(232)) }.getOrNull()
        val content = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
            addView(text("●  LISTO PARA VINCULAR", 12, true, accent).apply {
                gravity = Gravity.CENTER; background = rounded(mint, 20); setPadding(dp(12), dp(8), dp(12), dp(8))
            })
            addView(text(title, 21, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(text("La otra persona puede escanear este QR desde TzindaApp, aunque esté en otra red Wi-Fi o use datos móviles.", 14, color = muted).apply { gravity = Gravity.CENTER })
            if (image != null) addView(ImageView(this@MainActivity).apply {
                setImageBitmap(image); contentDescription = "Código QR para $title"
                background = rounded(Color.WHITE, 24); setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(248), dp(248)).apply { topMargin = dp(12); bottomMargin = dp(6) })
            addView(text(displayCode, 30, true, accent).apply {
                gravity = Gravity.CENTER; background = rounded(softSurface, 16); setPadding(dp(16), dp(10), dp(16), dp(10))
            })
            addView(button("Copiar código", false) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Código de vinculación", normalizedCode))
                toast("Código copiado.")
            })
            addView(text("Código de un solo uso · vence a las $expiresAt\n\nSi no puede escanearlo, puede escribir los 8 caracteres.", 14, color = muted).apply {
                gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
        AlertDialog.Builder(this).setView(content).setPositiveButton("Listo", null).show()
    }
    private fun createQrBitmap(payload: String, size: Int): android.graphics.Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE,
            size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE
        }
        return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }
    private val live = object : Runnable {
        override fun run() { refreshLive(); handler.postDelayed(this, 1_000) }
    }
    private fun refreshLive() {
        if (tab == DEVICE_TAB) {
            val revision = store.prefs.getLong("camera_registry_revision", 0L)
            if (revision != cameraRegistryRenderedRevision) {
                cameraRegistryRenderedRevision = revision
                render()
                return
            }
        }
        if (isCaregiver && (tab == 0 || tab == 1) && System.currentTimeMillis() - locationFreshnessRenderAt >= 15_000L) {
            locationFreshnessRenderAt = System.currentTimeMillis()
            render()
        }
        homeStatus?.text = store.prefs.getString("status", "Configura tus contactos y activa el monitoreo.")
        braceletStatusViews.forEach { it.text = store.prefs.getString("arduino_status", "Pulsera sin conectar.").orEmpty() }
        if (!isCaregiver && tab in 0..1) refreshPatientVitals()
        homeHeadline?.text = if (GuardianMonitorService.monitoring) "Protección activa" else "Protección en pausa"
        monitorButton?.text = if (GuardianMonitorService.monitoring) "Detener monitoreo" else "Activar monitoreo"
        monitorToggle?.let { toggle ->
            updatingMonitorToggle = true
            toggle.isChecked = GuardianMonitorService.monitoring
            toggle.contentDescription = if (GuardianMonitorService.monitoring) "Pausar monitoreo" else "Activar monitoreo"
            updatingMonitorToggle = false
        }
        appBarStatus?.apply {
            val caregiverLinked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
            val cameraLinked = store.prefs.getString("paired_camera_device_id", "").orEmpty().isNotBlank()
            text = when {
                isCaregiver -> if (caregiverLinked) "● Compartido" else "○ Sin vínculo"
                isCameraRole -> if (cameraLinked) "● Vinculada" else "○ Sin vínculo"
                GuardianMonitorService.monitoring -> "● Activo"
                else -> "○ En pausa"
            }
            val unlinked = isCaregiver && !caregiverLinked || isCameraRole && !cameraLinked
            setTextColor(if (unlinked) muted else accent)
            background = rounded(if (unlinked) softSurface else mint, 22)
        }
        appBarProfile?.text = store.profile().text("fullName").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "T" }
        emergencyProgressCard?.visibility = if (store.prefs.getString("dispatch_id", "").orEmpty().isNotBlank()) View.VISIBLE else View.GONE
        emergencyProgressStatus?.text = store.prefs.getString("status", "Solicitando SMS y llamadas a tus contactos.")
        val id = store.prefs.getString("pending_id", "").orEmpty()
        if (id.isNotEmpty()) {
            if (id != shownIncident || countdownDialog?.isShowing != true) {
                countdownDialog?.dismiss()
                shownIncident = id
                showCountdownDialog()
                if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
                else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            val left = ((store.prefs.getLong("pending_deadline", 0) - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            countdownText?.text = left.toString()
            countdownDescription?.text = "Si no cancelas, TzindaApp solicitará SMS a tus contactos e intentará llamarles cuando termine la cuenta regresiva."
            countdownRing?.progress = (left / 20f).coerceIn(0f, 1f)
            countdownRing?.contentDescription = "Quedan $left segundos"
        } else {
            countdownDialog?.dismiss(); countdownDialog = null; shownIncident = null
            countdownText = null; countdownDescription = null; countdownRing = null
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false) }
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun showCountdownDialog() {
        if (isFinishing || isDestroyed) return
        val content = column().apply { setPadding(dp(18), dp(22), dp(18), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); setColorFilter(accent) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        header.addView(text("Alarma de caída activa", 20, true).apply { setPadding(dp(10), 0, 0, 0) })
        content.addView(header)

        val warning = card(content, if (dark) Color.parseColor("#3B2020") else Color.parseColor("#FBDCD8"))
        warning.addView(text("ALERTA AUTOMÁTICA", 13, true, critical))
        warning.addView(text("Posible caída detectada", 24, true, critical))
        warning.addView(text("El teléfono registró un movimiento compatible con una caída. Puedes cancelar el aviso si estás bien.", 16, color = ink))

        val timer = card(content)
        val timerFrame = FrameLayout(this).apply { minimumHeight = dp(220) }
        val ring = CountdownRing(this).apply { progress = 1f }
        countdownRing = ring
        timerFrame.addView(ring, FrameLayout.LayoutParams(dp(204), dp(204), Gravity.CENTER))
        val timerLabels = column().apply { gravity = Gravity.CENTER }
        val seconds = text("20", 44, true, critical).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }
        countdownText = seconds
        timerLabels.addView(seconds)
        timerLabels.addView(text("SEGUNDOS", 14, true, muted).apply { gravity = Gravity.CENTER })
        timerFrame.addView(timerLabels, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        timer.addView(timerFrame)
        val description = text("Si no cancelas, se solicitarán SMS y se intentarán llamadas a tus contactos.", 16, color = muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(8)) }
        countdownDescription = description
        timer.addView(description)
        timer.addView(text("El SMS y las llamadas dependen de tu SIM, permisos y conexión móvil.", 14, color = muted).apply { gravity = Gravity.CENTER })

        val cancel = button("✓   Estoy bien · cancelar alerta") {
            service(GuardianMonitorService.ACTION_CANCEL)
        }.apply {
            minHeight = dp(64); textSize = 18f; setTextColor(onAccent)
            background = RippleDrawable(ColorStateList.valueOf(0x3365A879), rounded(if (dark) Color.parseColor("#315C3C") else Color.parseColor("#BFEEC4"), 24), null)
        }
        content.addView(cancel)
        val needHelp = button("Pedir ayuda ahora", false) {
            AlertDialog.Builder(this).setTitle("Enviar alerta ahora")
                .setMessage("Se solicitarán SMS y se intentará llamar a tus contactos inmediatamente, sin esperar a que termine la cuenta regresiva.")
                .setNegativeButton("Seguir esperando", null)
                .setPositiveButton("Enviar alerta") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_ALERT_NOW) }
                .show()
        }.apply {
            minHeight = dp(58); textSize = 17f; setTextColor(Color.WHITE)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(critical, 24), null)
        }
        content.addView(needHelp)

        val dialog = Dialog(this)
        dialog.setContentView(scroll)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(paper))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setDimAmount(0f)
        }
        countdownDialog = dialog
    }
    private fun selectTab(index: Int) {
        if (tab == CAMERA_TAB && index != CAMERA_TAB) disposeCameraController()
        saveDrafts(); tab = index; hideKeyboard(); render()
    }
    private fun chooseRole(role: String) {
        if (role == store.role()) { tab = 0; render(); return }
        val applyRole = {
            if (GuardianMonitorService.monitoring || store.prefs.getBoolean("monitor_enabled", false))
                service(GuardianMonitorService.ACTION_STOP)
            store.saveRole(role)
            tab = 0
            render()
        }
        if (store.role().isNotBlank() && (GuardianMonitorService.monitoring || store.prefs.getBoolean("monitor_enabled", false))) {
            AlertDialog.Builder(this).setTitle("Cambiar tipo de cuenta")
                .setMessage("Al cambiar de rol se detendrá el monitoreo de caídas en este teléfono.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cambiar") { _, _ -> applyRole() }.show()
        } else applyRole()
    }
    private fun openCameraScreen() {
        if (tab != CAMERA_TAB) cameraReturnTab = if (tab in 0..3) tab else 0
        val continueToCamera = { selectTab(CAMERA_TAB) }
        if (store.prefs.getBoolean("camera_intro_seen", false)) {
            continueToCamera()
            return
        }
        val showOnce = CheckBox(this).apply {
            text = "Mostrar esta explicación solo una vez"
            setTextColor(muted)
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = true
        }
        AlertDialog.Builder(this)
            .setTitle("Cámara opcional")
            .setMessage("La cámara solo se activa cuando la abres. La imagen se procesa en este teléfono y no se guarda ni se comparte. La detección puede equivocarse; mantén también la pulsera puesta.")
            .setView(showOnce)
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Continuar") { _, _ ->
                if (showOnce.isChecked) store.prefs.edit().putBoolean("camera_intro_seen", true).apply()
                continueToCamera()
            }
            .show()
    }
    private fun openDeviceLinkScreen() {
        if (tab != DEVICE_TAB) deviceReturnTab = if (tab in 0..3) tab else 0
        selectTab(DEVICE_TAB)
    }
    private fun openMedicationScreen() {
        if (tab != MEDICATIONS_TAB) medicationReturnTab = if (tab in 0..3) tab else 0
        selectTab(MEDICATIONS_TAB)
    }
    private fun openProfile() {
        if (tab != 4) profileReturnTab = if (tab == CAMERA_TAB) cameraReturnTab else tab
        selectTab(4)
    }
    override fun onBackPressed() {
        if (tab == CAMERA_TAB) selectTab(cameraReturnTab.coerceIn(0, 3))
        else if (tab == DEVICE_TAB) selectTab(deviceReturnTab.coerceIn(0, 3))
        else if (tab == MEDICATIONS_TAB) selectTab(medicationReturnTab.coerceIn(0, 3))
        else if (tab == 4) selectTab(profileReturnTab.coerceIn(0, 3)) else super.onBackPressed()
    }
    private fun saveDrafts() {
        if (tab == 2 && contactEdits.size == 5) contactDraft = contactEdits.map { TrustedContact(it.first.text.toString(), it.second.text.toString()) }
        if (tab == 4 && profileEdits.isNotEmpty()) profileDraft = collectProfile()
    }
    private fun render() {
        frame.removeAllViews(); homeStatus = null; homeHeadline = null; monitorButton = null; monitorToggle = null
        patientPulseValue = null; patientSpo2Value = null; patientVitalsStatus = null; homePulseraStatus = null; patientVitalsRefreshAt = 0L
        caregiverSharedLocationView = null
        caregiverLocationCoordinatesView = null; caregiverLocationFreshnessView = null; caregiverMapButton = null
        braceletStatusViews.clear()
        emergencyProgressCard = null; emergencyProgressStatus = null
        smsPermissionStatus = null; smsPermissionButton = null
        contactEdits.clear(); profileEdits.clear()
        authMode?.let { renderAuth(it); return }
        val appBar = findViewById<View>(R.id.app_bar)
        val navigation = findViewById<LinearLayout>(R.id.navigation)
        val onboarding = tab == ROLE_TAB
        appBar.visibility = if (onboarding || tab == CAMERA_TAB) View.GONE else View.VISIBLE
        navigation.visibility = if (onboarding || tab == DEVICE_TAB || tab == CAMERA_TAB || tab == MEDICATIONS_TAB) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (tab == CAMERA_TAB) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else 0
        appBar.setBackgroundColor(paper)
        navigation.setBackgroundColor(cardColor)
        findViewById<TextView>(R.id.app_bar_title).setTextColor(ink)
        appBarSubtitle?.setTextColor(muted)
        findViewById<ImageView>(R.id.app_bar_logo).setColorFilter(accent)
        appBarProfile?.apply { setTextColor(onAccent); background = rounded(accent, 24) }
        val tabLabels = when {
            isCaregiver -> listOf("Inicio", "Actividad", "Persona", "Ajustes")
            isCameraRole -> listOf("Cámara", "Eventos", "Vincular", "Ajustes")
            else -> listOf("Inicio", "Actividad", "Contactos", "Ajustes")
        }
        appBarSubtitle?.text = when (tab) { 0 -> tabLabels[0]; 1 -> tabLabels[1]; 2 -> tabLabels[2]; 3 -> tabLabels[3]; CAMERA_TAB -> "Detección IA"; DEVICE_TAB -> "Dispositivos"; MEDICATIONS_TAB -> "Medicamentos"; 4 -> "Mi perfil"; else -> "Inicio" }
        listOf(R.id.nav_home, R.id.nav_activity, R.id.nav_contacts, R.id.nav_settings).forEachIndexed { index, id ->
            findViewById<Button>(id).apply {
                text = tabLabels[index]
                val selected = tab == index
                setTextColor(if (selected) accent else muted); textSize = 13f
                setTypeface(null, if (index == tab) Typeface.BOLD else Typeface.NORMAL)
                background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x3365A879 else 0x33254D3A), rounded(if (selected) mint else cardColor, 22), null)
                stateListAnimator = null; elevation = 0f
                setPadding(dp(2), dp(5), dp(2), dp(4))
                minHeight = dp(58)
                val icon = getDrawable(listOf(R.drawable.ic_home, R.drawable.ic_activity, R.drawable.ic_profile, R.drawable.ic_settings)[index])!!.mutate()
                icon.setTint(if (index == tab) accent else muted)
                icon.setBounds(0, 0, dp(23), dp(23))
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(3)
            }
        }
        val body = column().apply { setPadding(if (tab == CAMERA_TAB) 0 else dp(16), if (tab == CAMERA_TAB) 0 else dp(14), if (tab == CAMERA_TAB) 0 else dp(16), if (tab == CAMERA_TAB) 0 else dp(28)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body); tag = "screen_scroll" }
        frame.addView(scroll)
        when (tab) {
            ROLE_TAB -> roleSelection(body)
            0 -> home(body)
            1 -> activity(body)
            2 -> if (isCameraRole) cameraPairingScreen(body) else contacts(body)
            3 -> settings(body)
            CAMERA_TAB -> cameraScreen(body)
            DEVICE_TAB -> deviceLinkScreen(body)
            MEDICATIONS_TAB -> {
                val linkedOwner = store.prefs.getString("caregiver_owner_id", "").orEmpty()
                val isLinkedCaregiver = isCaregiver && sessions.read() != null && store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank() && linkedOwner.isNotBlank()
                val ownerId = if (isCaregiver) linkedOwner.takeIf { isLinkedCaregiver }.orEmpty() else medicationRepository.currentOwnerScope()
                MedicationModuleUi(this, medicationRepository, ownerId, isCaregiver, dark) {
                    selectTab(medicationReturnTab.coerceIn(0, 3))
                }.render(body)
            }
            else -> profile(body)
        }
        refreshLive(); refreshPermissionUi()
    }
    private fun renderAuth(mode: AuthMode) {
        findViewById<View>(R.id.app_bar).visibility = View.GONE
        findViewById<LinearLayout>(R.id.navigation).visibility = View.GONE
        val body = column().apply { setPadding(dp(24), dp(36), dp(24), dp(28)) }
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        body.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); setColorFilter(accent) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        gap(body, 18)
        val title = when(mode) { AuthMode.LOGIN -> "Inicia sesión"; AuthMode.REGISTER -> "Crea tu cuenta"; AuthMode.RECOVER -> "Recupera tu acceso"; AuthMode.RESET -> "Nueva contraseña" }
        heading(body, "TZINDAAPP · CUENTA SEGURA", title, when(mode) {
            AuthMode.LOGIN -> "Sincroniza tu perfil, contactos y alertas con tu cuenta."
            AuthMode.REGISTER -> "Crea una cuenta con tu correo y contraseña para sincronizar tus datos."
            AuthMode.RECOVER -> "Te enviaremos un enlace para restablecer tu contraseña."
            AuthMode.RESET -> "Elige una contraseña nueva para tu cuenta."
        })
        val form = card(body)
        if (mode == AuthMode.REGISTER) authName = field(form, "Nombre", "", tag = "auth_name") else authName = null
        if (mode != AuthMode.RESET) {
            authEmail = field(form, "Correo electrónico", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "auth_email")
        } else authEmail = null
        if (mode == AuthMode.LOGIN || mode == AuthMode.REGISTER || mode == AuthMode.RESET) {
            authPassword = field(form, if (mode == AuthMode.RESET) "Nueva contraseña (mínimo 8 caracteres)" else "Contraseña", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "auth_password")
            form.addView(CheckBox(this).apply {
                text = "Mostrar contraseña"; setTextColor(muted); buttonTintList = ColorStateList.valueOf(accent)
                setOnCheckedChangeListener { _, visible ->
                    authPassword?.let { input ->
                        val cursor = input.selectionStart.coerceAtLeast(0)
                        input.inputType = if (visible) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        input.setSelection(cursor.coerceAtMost(input.text.length))
                    }
                }
            })
        } else authPassword = null
        if (mode == AuthMode.REGISTER || mode == AuthMode.RESET) authConfirmation = field(form, "Confirma la contraseña", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "auth_confirmation") else authConfirmation = null
        if (mode == AuthMode.REGISTER) {
            authPrivacy = CheckBox(this).apply {
                text = "Leí el aviso de privacidad y acepto sincronizar los datos descritos."
                setTextColor(muted); buttonTintList = ColorStateList.valueOf(accent)
            }
            form.addView(authPrivacy)
            form.addView(button("Leer aviso de privacidad y uso", false) {
                AlertDialog.Builder(this).setTitle("Privacidad y uso de TzindaApp")
                    .setMessage("Al iniciar sesión, Supabase puede almacenar tu nombre y rol, contactos de confianza con teléfono, eventos de caída y estados técnicos de SMS/llamada, lecturas válidas de pulso/SpO₂ del UNO Q y la ubicación en vivo cuando la autorizas y el monitoreo está activo.\n\nSi registras medicamentos, el nombre, dosis indicada, presentación, horarios, instrucciones e historial de tomas se guardan en este teléfono y se sincronizan con Supabase. Cada medicamento permanece privado hasta que actives “Compartir con mi cuidador/a”; solo una cuenta con vínculo activo podrá consultarlo. El cuidador no puede editarlo ni confirmar tomas. Puedes retirar el permiso de compartir editando el registro. TzindaApp no recomienda ni modifica dosis.\n\nEl domicilio, fecha de nacimiento, video y muestras crudas de movimiento no se sincronizan. Las funciones locales siguen disponibles sin Internet. TzindaApp es experimental y no sustituye servicios de emergencia ni atención médica. Puedes cerrar sesión o solicitar la eliminación de la cuenta y los datos remotos desde Ajustes.")
                    .setPositiveButton("Entendido", null).show()
            })
        }
        authStatus = text("", 14, color = muted).also { form.addView(it) }
        val actionLabel = when(mode) { AuthMode.LOGIN -> "Iniciar sesión"; AuthMode.REGISTER -> "Crear cuenta"; AuthMode.RECOVER -> "Enviar enlace"; AuthMode.RESET -> "Guardar contraseña" }
        form.addView(button(actionLabel) { submitAuth(mode) })
        if (mode == AuthMode.LOGIN) body.addView(button("Crear una cuenta", false) { authMode = AuthMode.REGISTER; render() })
        if (mode == AuthMode.LOGIN) body.addView(button("Olvidé mi contraseña", false) { authMode = AuthMode.RECOVER; render() })
        if (mode == AuthMode.REGISTER || mode == AuthMode.RECOVER) body.addView(button("Volver a iniciar sesión", false) { authMode = AuthMode.LOGIN; render() })
        if (mode == AuthMode.RESET) body.addView(button("Volver a la app", false) { authMode = null; render() })
        if (mode == AuthMode.LOGIN) body.addView(button("Usar la app en este teléfono", false) {
            store.prefs.edit().putBoolean("local_only_mode", true).apply(); authMode = null; render()
        })
        body.addView(text("La detección, cuenta regresiva y avisos locales siguen funcionando en este teléfono. La sincronización solo empieza al iniciar sesión.", 14, color = muted).apply { setPadding(0, dp(16), 0, 0) })
    }
    private fun submitAuth(mode: AuthMode) {
        val email = authEmail?.text?.toString()?.trim().orEmpty()
        val password = authPassword?.text?.toString().orEmpty()
        val name = authName?.text?.toString()?.trim().orEmpty()
        val confirmation = authConfirmation?.text?.toString().orEmpty()
        if (mode != AuthMode.RESET && !AuthInputPolicy.validEmail(email)) { authStatus?.text = "Escribe un correo válido."; return }
        if (mode == AuthMode.REGISTER && (name.isBlank() || name.length > 120 || !authPrivacy!!.isChecked)) { authStatus?.text = "Completa tu nombre y acepta el aviso de sincronización."; return }
        if (mode == AuthMode.LOGIN && password.isBlank()) { authStatus?.text = "Escribe tu contraseña."; return }
        if (mode == AuthMode.REGISTER || mode == AuthMode.RESET) {
            if (!AuthInputPolicy.validPassword(password)) { authStatus?.text = "La contraseña debe tener entre 8 y 128 caracteres."; return }
            if (password != confirmation) { authStatus?.text = "Las contraseñas no coinciden."; return }
        }
        if (!backend.isConfigured) { authStatus?.text = "Supabase aún no está configurado. Puedes seguir en modo local."; return }
        authStatus?.text = "Conectando de forma segura…"
        io.execute {
            val result = runCatching {
                when (mode) {
                    AuthMode.LOGIN -> { backend.signIn(email, password); "Sesión iniciada." }
                    AuthMode.REGISTER -> {
                        if (backend.signUp(email, password, name) == null) {
                            throw BackendFailure(BackendFailure.Category.EMAIL_CONFIRMATION_REQUIRED)
                        }
                        "Cuenta creada."
                    }
                    AuthMode.RECOVER -> { backend.requestPasswordRecovery(email); "Si el correo está registrado, recibirás un enlace para restablecer la contraseña." }
                    AuthMode.RESET -> { backend.updatePassword(password); "Contraseña actualizada." }
                }
            }
            runOnUiThread {
                result.onSuccess { message ->
                    if (mode == AuthMode.REGISTER && authPrivacy?.isChecked == true) {
                        store.prefs.edit().putString("privacy_notice_version", "2026-10-21-v1")
                            .putLong("privacy_consent_at", System.currentTimeMillis()).apply()
                    }
                    authStatus?.text = message
                    if (mode in setOf(AuthMode.LOGIN, AuthMode.REGISTER, AuthMode.RESET) && sessions.read() != null) {
                        sessions.read()?.let { session ->
                            store.prefs.edit().putBoolean("local_only_mode", false).apply()
                            store.prepareContactsForUser(session.userId)
                            authMode = null
                            val pendingType = store.prefs.getString("pending_pairing_type", "").orEmpty()
                            val pendingCode = store.prefs.getString("pending_pairing_code", "").orEmpty()
                            if (pendingType in setOf("caregiver", "camera") && pendingCode.isNotBlank()) {
                                store.saveRole(if (pendingType == "camera") AppStore.ROLE_CAMERA else AppStore.ROLE_CAREGIVER)
                                tab = 0
                                SyncScheduler.enqueue(applicationContext)
                                render()
                                processPairingCodeOrLink("tzinda://pair?type=$pendingType&code=$pendingCode")
                            } else {
                                SyncScheduler.enqueue(applicationContext); render()
                            }
                        }
                    }
                }.onFailure { authStatus?.text = it.message ?: "No se pudo completar la operación." }
            }
        }
    }
    private fun heading(body: LinearLayout, eyebrow: String, title: String, subtitle: String) {
        body.addView(text(eyebrow.uppercase(Locale.getDefault()), 13, true, accent).apply { letterSpacing = .07f })
        body.addView(text(title, 29, true).apply { setPadding(0, dp(4), 0, dp(4)) })
        body.addView(text(subtitle, 16, color = muted))
    }
    private fun roleSelection(body: LinearLayout) {
        body.setPadding(dp(20), dp(34), dp(20), dp(28))
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_shield); setColorFilter(accent); contentDescription = "TzindaApp"
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        gap(body, 22)
        heading(body, "TZINDAAPP · CUIDADO CONECTADO", "¿Cómo usarás TzindaApp?", "Selecciona el rol para este dispositivo.")

        val wearer = card(body, cardColor)
        wearer.addView(text("LLEVO EL TELÉFONO", 13, true, accent))
        wearer.addView(text("Soy la persona protegida", 21, true))
        wearer.addView(text("Detector de caídas · ubicación · cámara", 15, color = muted))
        wearer.addView(button(if (store.role() == AppStore.ROLE_WEARER) "Continuar con este rol" else "Configurar como persona protegida") { chooseRole(AppStore.ROLE_WEARER) })

        val caregiver = card(body, softSurface)
        caregiver.addView(text("ACOMPAÑO A OTRA PERSONA", 13, true, accent))
        caregiver.addView(text("Soy cuidador o familiar", 21, true))
        caregiver.addView(text("Consulta de estado, ubicación y alertas de la persona vinculada.", 15, color = muted))
        caregiver.addView(button(if (store.role() == AppStore.ROLE_CAREGIVER) "Continuar con este rol" else "Configurar como cuidador", false) { chooseRole(AppStore.ROLE_CAREGIVER) })

        val camera = card(body, mint)
        camera.addView(text("USO COMO DISPOSITIVO INDEPENDIENTE", 13, true, accent))
        camera.addView(text("Esta app estará instalada en una cámara", 21, true))
        camera.addView(text("Vinculación con QR o código. Detección visual de posturas de riesgo.", 15, color = muted))
        camera.addView(button(if (store.role() == AppStore.ROLE_CAMERA) "Continuar con acceso de cámara" else "Configurar acceso de cámara", false) { chooseRole(AppStore.ROLE_CAMERA) })

        // Fin de seleccion de rol
    }
    private fun cameraHome(body: LinearLayout) {
        heading(body, "ACCESO DE CÁMARA", "Cámara vinculable", "Este teléfono funciona como un dispositivo de cámara independiente para la persona protegida.")
        val linkId = store.prefs.getString("paired_camera_device_id", "").orEmpty()
        val status = card(body, if (linkId.isBlank()) softSurface else mint)
        status.addView(text(if (linkId.isBlank()) "AÚN SIN VINCULAR" else "CÁMARA VINCULADA", 13, true, accent))
        status.addView(text(if (linkId.isBlank()) "Vincúlala con la cuenta de la persona protegida" else store.prefs.getString("paired_camera_name", "Cámara protegida").orEmpty(), 20, true))
        status.addView(text(if (linkId.isBlank()) "Escanea el código QR o ingresa los 8 dígitos generados en Dispositivos." else "Eventos de cámara sincronizados con la persona protegida.", 15, color = muted))
        status.addView(button("Vincular con QR o código  →") { selectTab(2) })
        val detector = card(body)
        detector.addView(text("Cámara opcional", 20, true))
        detector.addView(text("Solo se activa cuando la abres.", 15, color = muted))
        detector.addView(button("Abrir cámara de apoyo") { openCameraScreen() })
        if (linkId.isNotBlank()) status.addView(button("Desvincular cámara", false) { unlinkCameraAccess() })
    }

    private fun cameraPairingScreen(body: LinearLayout) {
        heading(body, "VINCULAR DISPOSITIVO", "Conecta esta cámara", "Escanea el QR de la persona protegida o escribe su código. Cada teléfono puede estar en una red distinta.")
        val current = store.prefs.getString("paired_camera_device_id", "").orEmpty()
        if (current.isNotBlank()) {
            val linked = card(body, mint)
            linked.addView(text("●  CÁMARA VINCULADA", 13, true, accent))
            linked.addView(text(store.prefs.getString("paired_camera_name", "Cámara protegida").orEmpty(), 20, true))
            linked.addView(text("Los avisos se sincronizan por Internet cuando este teléfono tiene conexión.", 14, color = muted))
            linked.addView(button("Desvincular cámara", false) { unlinkCameraAccess() })
        }
        val card = card(body)
        card.addView(text("Conexión sencilla", 20, true))
        card.addView(text("No necesitan estar en la misma red. Funciona con Wi-Fi o datos móviles si ambos teléfonos llegan a TzindaApp.", 15, color = muted))
        card.addView(button("▦  Escanear QR") { scanPairingQr("camera") })
        val code = field(card, "Código de vinculación", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, "camera_pairing_code")
        code.filters = arrayOf(android.text.InputFilter.LengthFilter(9))
        formatPairingCode(code)
        val status = text("El código se usa una vez y vence pronto.", 14, color = muted).also { card.addView(it) }
        card.addView(button("Continuar") {
            val clean = code.text.toString().filter(Char::isLetterOrDigit)
            if (clean.length != 8) status.text = "Escribe los 8 caracteres del código."
            else showPairingConfirmation("camera", clean)
        })
    }
    private fun formatPairingCode(code: EditText, maxChars: Int = 8) {
        var formatting = false
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                if (formatting) return
                val raw = editable?.toString().orEmpty().uppercase(Locale.ROOT).filter(Char::isLetterOrDigit).take(maxChars)
                val divider = if (raw.length <= 6) 3 else 4
                val formatted = if (raw.length > divider) raw.take(divider) + "-" + raw.drop(divider) else raw
                if (formatted != editable?.toString()) {
                    formatting = true; code.setText(formatted); code.setSelection(formatted.length); formatting = false
                }
            }
        })
    }

    private fun unlinkCameraAccess() {
        if (sessions.read() == null || !backend.isConfigured) {
            toast("Inicia sesión para quitar el vínculo de cámara.", true); return
        }
        io.execute {
            val result = runCatching { backend.unlinkPairedCamera() }
            runOnUiThread {
                result.onSuccess {
                    store.prefs.edit().remove("paired_camera_device_id").remove("paired_camera_owner_id")
                        .remove("paired_camera_name").apply()
                    toast("Cámara desvinculada."); render()
                }.onFailure { toast(it.message ?: "No se pudo desvincular la cámara.", true) }
            }
        }
    }

    private fun showCreateCameraInviteDialog() {
        val name = EditText(this).apply { setText("Cámara"); hint = "Ej. Cámara de sala"; setSingleLine(true) }
        val content = column().apply {
            setPadding(dp(8), dp(8), dp(8), dp(4))
            addView(text("Ponle un nombre fácil de reconocer", 14, true))
            addView(name)
            addView(text("Después comparte el QR. La cámara puede estar en cualquier red con conexión a TzindaApp.", 14, color = muted).apply { setPadding(0, dp(10), 0, 0) })
        }
        AlertDialog.Builder(this).setTitle("Vincular cámara independiente")
            .setView(content).setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear QR o código") { _, _ ->
                val deviceName = name.text.toString().trim().ifBlank { "Cámara" }
                showCreateOnlineCameraInviteDialog(deviceName)
            }.show()
    }
    private fun showCreateOnlineCameraInviteDialog(deviceName: String) {
        if (!backend.isConfigured || sessions.read() == null) { toast("Inicia sesión para vincular por Internet.", true); return }
        toast("Preparando código seguro para $deviceName…")
        io.execute {
            val result = runCatching { backend.createCameraDeviceInvite(deviceName) }
            runOnUiThread { result.onSuccess { (code, expiry) -> showPairingQr("Conectar $deviceName", "camera", code, expiry) }
                .onFailure { toast(it.message ?: "No se pudo crear el código de cámara.", true) } }
        }
    }

    private fun showCreateCaregiverInvite(parent: LinearLayout) {
        createCaregiverOnlineInvite()
    }
    private fun createCaregiverOnlineInvite() {
        if (!backend.isConfigured || sessions.read() == null) { toast("Inicia sesión para vincular por Internet.", true); return }
        SyncScheduler.enqueue(applicationContext, immediate = true)
        toast("Preparando código seguro para el cuidador/a…")
        io.execute {
            val result = runCatching { backend.createCaregiverAccountInvite() }
            runOnUiThread {
                result.onSuccess { (code, expiry) -> showPairingQr("Vincular cuidador/a", "caregiver", code, expiry) }
                    .onFailure { toast(it.message ?: "No se pudo crear el código.", true) }
            }
        }
    }

    private fun home(body: LinearLayout) {
        if (isCaregiver) { caregiverHome(body); return }
        if (isCameraRole) { cameraHome(body); return }
        val name = store.profile().text("fullName").trim().substringBefore(" ")
        val today = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "MX")).format(Date())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "MX")) else it.toString() }
        val todayRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        todayRow.addView(text("▢  $today", 14, true, muted), LinearLayout.LayoutParams(0, -2, 1f))
        val alertsToday = todayPossibleFallCount()
        val dayLabel = when {
            alertsToday > 0 -> "● Revisar alerta"
            GuardianMonitorService.monitoring -> "● Día sereno"
            else -> "○ En pausa"
        }
        todayRow.addView(text(dayLabel, 13, true, if (alertsToday > 0) critical else accent).apply {
            background = rounded(if (alertsToday > 0) (if (dark) Color.parseColor("#3B2020") else Color.parseColor("#FBDCD8")) else mint, 22)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        })
        body.addView(todayRow)
        heading(body, "TU RED DE APOYO", if (name.isBlank()) "Tu tranquilidad, primero" else "Hola, $name",
            if (GuardianMonitorService.monitoring) "Todo se encuentra en orden. Tu sistema de cuidado está funcionando." else "Activa el monitoreo y mantén cerca a quienes te apoyan.")
        val links = card(body, mint)
        val linkHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        linkHeader.addView(text("⌁", 30, true, accent).apply { gravity = Gravity.CENTER; background = rounded(cardColor, 28) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val linkTitle = column().apply { setPadding(dp(12), 0, 0, 0) }
        linkTitle.addView(text("Dispositivos y vínculos", 19, true))
        linkTitle.addView(text("Pulsera · cámara · cuidador/a", 14, color = muted))
        linkHeader.addView(linkTitle, LinearLayout.LayoutParams(0, -2, 1f))
        links.addView(linkHeader)
        links.addView(button("Conectar o administrar  →") { openDeviceLinkScreen() })
        if (smsPermissionState() != SmsPermissionPolicy.State.GRANTED) permissionCard(body)
        val status = card(body)
        val statusTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusTop.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); setColorFilter(accent); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(38), dp(38)))
        statusTop.addView(text(if (GuardianMonitorService.monitoring) "SUPERVISIÓN CONTINUA" else "SUPERVISIÓN LISTA PARA ACTIVAR", 13, true, accent).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        monitorToggle = switch("", GuardianMonitorService.monitoring) { enabled ->
            if (!updatingMonitorToggle) {
                if (enabled) prepareAlert(GuardianMonitorService.ACTION_START)
                else service(GuardianMonitorService.ACTION_STOP)
            }
        }.apply { contentDescription = if (GuardianMonitorService.monitoring) "Pausar monitoreo" else "Activar monitoreo" }
        statusTop.addView(monitorToggle)
        status.addView(statusTop)
        gap(status, 12)
        homeHeadline = text(if (GuardianMonitorService.monitoring) "Protección activa" else "Protección en pausa", 25, true, ink).also { status.addView(it) }
        homeStatus = text("", 15, color = muted).also { status.addView(it) }
        gap(status, 8)
        status.addView(text("Detecta caídas y avisa a tu red de apoyo.", 15, color = muted))
        val recipients = store.recipients()
        if (recipients.isEmpty()) status.addView(text("Agrega al menos un contacto antes de activar el monitoreo.", 14, true, critical))

        val sensorCard = card(body)
        sensorCard.addView(text("Pulsera de sensores", 19, true))
        val latestVitals = store.telemetry.latestArduinoVitals()
        val pulse = latestVitals.optDouble("bpm", Double.NaN)
        val oxygen = latestVitals.optDouble("spo2", Double.NaN)
        val sampleSummary = listOfNotNull(
            pulse.takeIf { it.isFinite() }?.let { "${it.toInt()} BPM" },
            oxygen.takeIf { it.isFinite() }?.let { "SpO₂ ${it.toInt()}%" },
        ).joinToString(" · ")
        homePulseraStatus = text(
            sampleSummary.ifBlank { store.prefs.getString("arduino_status", "Conecta el UNO Q para recibir lecturas.").orEmpty() },
            16, true, if (sampleSummary.isBlank()) muted else accent,
        ).also { sensorCard.addView(it) }
        sensorCard.addView(button("Ver signos vitales", false) { activitySection = 0; selectTab(1) })

        val medicationCard = card(body, softSurface)
        medicationCard.addView(text("Medicamentos", 20, true))
        medicationCard.addView(text("Horarios, recordatorios e historial.", 15, color = muted))
        medicationCard.addView(button("Abrir mis medicamentos  →") { openMedicationScreen() })

        val aiCard = card(body, softSurface)
        aiCard.addView(text("CÁMARA OPCIONAL", 13, true, accent))
        aiCard.addView(text("Revisa el espacio cuando lo necesites", 20, true))
        aiCard.addView(text("Se activa solo cuando la abres.", 15, color = muted))
        aiCard.addView(button("Abrir cámara de apoyo  →", false) { openCameraScreen() })

        val help = card(body)
        help.addView(text("¿Necesitas ayuda ahora?", 21, true))
        help.addView(text("Envío de SMS e intento de llamada a tus contactos.", 15, color = muted))
        val sos = button("✦   Pedir ayuda inmediata (SOS)") {
            AlertDialog.Builder(this).setTitle("Enviar SMS de ayuda")
                .setMessage("Se solicitarán SMS a ${store.recipients().size} contacto(s) y se intentará llamarles. La recepción depende de la SIM, la red y la disponibilidad del teléfono.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Enviar") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_ALERT_NOW) }.show()
        }.apply {
            minHeight = dp(66); textSize = 17f; setTextColor(Color.WHITE)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(critical, 24), null)
        }
        help.addView(sos)
        help.addView(button("Probar cuenta regresiva", false) {
            AlertDialog.Builder(this).setTitle("Prueba de 20 segundos")
                .setMessage("La prueba usa el mismo proceso de alerta. Si no pulsas Estoy bien, se solicitarán SMS reales y se intentarán llamadas a tus contactos.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Iniciar prueba") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_TEST) }.show()
        })

        addPeaceSummary(body, recipients)

        val contactCard = card(body)
        val contactTitle = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val contactTitleText = column()
        contactTitleText.addView(text("Contactos de confianza", 20, true))
        contactTitleText.addView(text("${recipients.size} destinatario(s) configurado(s)", 14, color = muted))
        contactTitle.addView(contactTitleText, LinearLayout.LayoutParams(0, -2, 1f))
        contactCard.addView(contactTitle)
        if (recipients.isEmpty()) contactCard.addView(text("Agrega personas para recibir los avisos.", 15, color = muted))
        recipients.take(2).forEachIndexed { index, contact ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
            row.addView(text(contact.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "${index + 1}", 17, true, accent).apply {
                gravity = Gravity.CENTER; background = rounded(mint, 24)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val details = column().apply { setPadding(dp(12), 0, 0, 0) }
            details.addView(text(contact.name.ifBlank { "Contacto ${index + 1}" }, 16, true))
            details.addView(text(contact.phone, 15, color = muted))
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f)); contactCard.addView(row)
        }
        contactCard.addView(button("Ver y editar contactos  →", false) { selectTab(2) })

        val location = card(body)
        location.addView(text("Ubicación del teléfono", 19, true))
        location.addView(text(if (store.prefs.getBoolean("include_location", false) && GuardianMonitorService.monitoring)
            "Actualizando mientras la protección está activa" else "Solo disponible en este teléfono hasta que se configure una vinculación", 15, color = muted))
        location.addView(text(store.locationText(), 14, color = muted))
        location.addView(button("Actualizar ubicación", false) { requestLocation() })
        body.addView(text("La detección es experimental y puede omitir algunas caídas. No sustituye servicios de emergencia ni atención médica.", 14, color = muted).apply { setPadding(dp(4), dp(18), dp(4), 0) })
    }
    private fun caregiverHome(body: LinearLayout) {
        refreshCaregiverSharedData()
        val today = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "MX")).format(Date())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "MX")) else it.toString() }
        body.addView(text("▢  $today", 14, true, muted))
        heading(body, "VISTA DE CUIDADOR", "Hola, cuidador/a", "Supervisión y estado en tiempo real.")

        val status = card(body, mint)
        val linked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        val ownerName = store.prefs.getString("caregiver_shared_name", "").orEmpty()
        status.addView(text(if (linked) "●  VÍNCULO ACTIVO" else "○  SIN VÍNCULO", 13, true, accent))
        status.addView(text(if (linked) ownerName.ifBlank { "Persona vinculada" } else "Aún no hay una persona conectada", 21, true))
        status.addView(text(if (linked) "Supervisión activa" else "Sin vínculo activo", 15, color = muted))
        if (linked) {
            val devices = runCatching { JSONArray(store.prefs.getString("caregiver_shared_devices", "[]")) }.getOrDefault(JSONArray())
            if (devices.length() > 0) {
                status.addView(text("Dispositivos", 16, true).apply { setPadding(0, dp(10), 0, dp(4)) })
                val now = System.currentTimeMillis()
                for (index in 0 until devices.length()) {
                    val device = devices.optJSONObject(index) ?: continue
                    val devName = device.optString("name").ifBlank { "Dispositivo" }
                    val devType = device.optString("device_type").replace('_', ' ')
                    val lastSyncStr = device.optString("last_sync_at").takeIf { it.isNotBlank() && it != "null" }
                        ?: device.optString("last_connected_at").takeIf { it.isNotBlank() && it != "null" }
                    val lastSyncMs = lastSyncStr?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
                    val ageMs = if (lastSyncMs > 0L) (now - lastSyncMs).coerceAtLeast(0L) else Long.MAX_VALUE
                    val statusText = when {
                        lastSyncMs == 0L -> "Sin datos de conexión"
                        ageMs < 120_000L -> "Conectado"
                        ageMs < 3_600_000L -> "Última comunicación: hace ${ageMs / 60_000L} min"
                        ageMs < 86_400_000L -> "Última comunicación: hace ${ageMs / 3_600_000L} h"
                        else -> "Sin conexión"
                    }
                    status.addView(text("• $devName ($devType) · $statusText", 14, color = muted))
                }
            }
        }
        status.addView(button(if (linked) "Administrar vínculo" else "Vincular persona protegida  →") { selectTab(2) })

        val sharedEvents = runCatching { JSONArray(store.prefs.getString("caregiver_shared_events", "[]")) }.getOrDefault(JSONArray())
        val recentFall = (0 until sharedEvents.length()).mapNotNull { sharedEvents.optJSONObject(it) }.firstOrNull { ev ->
            val cStatus = ev.optString("confirmation_status")
            val classif = ev.optString("classification")
            cStatus == "pending" || classif in setOf("possible_fall", "fall")
        }
        if (recentFall != null) {
            val alertCard = card(body, if (dark) Color.parseColor("#3B2020") else Color.parseColor("#FBDCD8"))
            alertCard.addView(text("⚠️  ALERTA RECIENTE", 13, true, critical))
            alertCard.addView(text("Posible caída detectada", 18, true, critical))
            val occ = recentFall.optString("occurred_at")
            val src = recentFall.optString("source").replace('_', ' ')
            val cStatus = recentFall.optString("confirmation_status")
            alertCard.addView(text("Fuente: $src · Estado: $cStatus\nFecha: $occ", 14, color = ink))
        }
        addSharedLocationCard(body)
        vitalsUnavailableCard(body, caregiver = true)
        val medicationCard = card(body, softSurface)
        medicationCard.addView(text("Medicamentos compartidos", 20, true))
        medicationCard.addView(text(if (linked) "Medicamentos autorizados por la persona vinculada" else "Requiere vínculo activo", 15, color = muted))
        medicationCard.addView(button("Consultar medicamentos  →") { openMedicationScreen() })
        addSharedPhoneMotionCard(body)
        addCaregiverSyncControls(body)

        val events = card(body)
        events.addView(text("Actividad reciente", 20, true))
        if (sharedEvents.length() == 0) {
            events.addView(text("Sin eventos compartidos", 16, true, muted))
            events.addView(text(if (linked) "Sin incidentes ni alertas registradas." else "Requiere vínculo activo.", 14, color = muted))
        } else {
            for (index in 0 until minOf(sharedEvents.length(), 4)) {
                val row = sharedEvents.optJSONObject(index) ?: continue
                val evName = row.optString("classification").replace('_', ' ')
                val evSource = row.optString("source").replace('_', ' ')
                val evStatus = row.optString("confirmation_status")
                val evTime = row.optString("occurred_at")
                events.addView(text("$evName · $evSource", 15, true))
                events.addView(text("$evTime · $evStatus", 13, color = muted).apply { setPadding(0, 0, 0, dp(4)) })
            }
        }
    }
    private fun refreshCaregiverSharedData(force: Boolean = false) {
        val linkId = store.prefs.getString("caregiver_link_id", "").orEmpty()
        val ownerId = store.prefs.getString("caregiver_owner_id", "").orEmpty()
        val selectedDeviceId = store.prefs.getString("caregiver_link_device_id", null)
            ?.takeUnless { it.isBlank() || it == "null" }
        val now = System.currentTimeMillis()
        if (linkId.isBlank() || ownerId.isBlank() || sessions.read() == null ||
            caregiverRefreshInFlight || !force && now - caregiverRefreshAt < 30_000L) return
        caregiverRefreshInFlight = true
        caregiverRefreshAt = now
        io.execute {
            val result = runCatching {
                val links = backend.linkedPeople()
                val stillLinked = (0 until links.length()).mapNotNull { links.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == linkId }
                    ?: throw BackendFailure(BackendFailure.Category.SESSION)
                val linkedOwnerId = stillLinked.optString("owner_user_id")
                val rawLinkedDeviceId = stillLinked.optString("device_id")
                val linkedDeviceId = rawLinkedDeviceId.takeUnless { it.isBlank() || it == "null" }
                if (selectedDeviceId != null && selectedDeviceId != linkedDeviceId) throw BackendFailure(BackendFailure.Category.SESSION)
                val profile = runCatching { backend.sharedProfile(linkedOwnerId) }.getOrNull()
                val devices = runCatching { backend.linkedDevices(linkedOwnerId) }.getOrDefault(JSONArray())
                val events = backend.sharedEvents(linkedOwnerId, linkedDeviceId)
                val measurements = backend.sharedMeasurements(linkedOwnerId, linkedDeviceId)
                val phoneMotion = runCatching { backend.sharedPhoneMotion(linkedOwnerId, linkedDeviceId) }.getOrNull()
                val phoneDevices = (0 until devices.length()).mapNotNull { devices.optJSONObject(it) }
                    .filter { it.optString("device_type") == "phone" }
                    .sortedByDescending { dev ->
                        val syncStr = dev.optString("last_sync_at").takeIf { it.isNotBlank() && it != "null" }
                            ?: dev.optString("last_connected_at").takeIf { it.isNotBlank() && it != "null" }
                        syncStr?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
                    }
                val locationDevice = linkedDeviceId ?: phoneDevices.firstOrNull()?.optString("id").orEmpty()
                val location = locationDevice.takeIf { it.isNotBlank() }
                    ?.let { runCatching { backend.sharedLocation(linkedOwnerId, it) }.getOrNull() }
                JSONObject().put("name", profile?.optString("full_name").orEmpty())
                    .put("devices", devices).put("events", events).put("measurements", measurements)
                    .put("phone_motion", phoneMotion ?: JSONObject.NULL)
                    .put("location", location ?: JSONObject.NULL).put("owner_id", linkedOwnerId)
                    .put("link_device_id", linkedDeviceId ?: "").put("device_id", locationDevice)
            }
            runOnUiThread {
                caregiverRefreshInFlight = false
                result.onSuccess { data ->
                    store.prefs.edit()
                        .putString("caregiver_shared_name", data.optString("name"))
                        .putString("caregiver_shared_devices", data.optJSONArray("devices")?.toString() ?: "[]")
                        .putString("caregiver_shared_events", data.optJSONArray("events")?.toString() ?: "[]")
                        .putString("caregiver_shared_measurements", data.optJSONArray("measurements")?.toString() ?: "[]")
                        .putString("caregiver_location_device_id", data.optString("device_id"))
                        .putString("caregiver_shared_status", "Actualizado · ${data.optJSONArray("measurements")?.length() ?: 0} lecturas y ${data.optJSONArray("events")?.length() ?: 0} eventos")
                        .putLong("caregiver_shared_fetched_at", System.currentTimeMillis())
                        .apply()
                    val phoneMotion = data.optJSONObject("phone_motion")
                    if (phoneMotion != null) store.prefs.edit().putString("caregiver_shared_phone_motion", phoneMotion.toString()).apply()
                    else store.prefs.edit().remove("caregiver_shared_phone_motion").apply()
                    val location = data.optJSONObject("location")
                    if (location != null) store.prefs.edit().putString("caregiver_shared_location", location.toString()).apply()
                    else store.prefs.edit().remove("caregiver_shared_location").apply()
                    ensureCaregiverLocationRealtime(data.optString("device_id"))
                    ensureCaregiverHealthRealtime(data.optString("owner_id"), data.optString("link_device_id").takeIf { it.isNotBlank() })
                    if (tab in 0..2) render()
                }.onFailure {
                    store.prefs.edit().putString("caregiver_shared_status", it.message?.takeIf(String::isNotBlank)
                        ?.let { message -> "No se pudo actualizar: $message" }
                        ?: "No se pudieron actualizar los datos compartidos.").apply()
                    if (tab in 0..2) render()
                }
            }
        }
    }
    private fun ensureCaregiverLocationRealtime(deviceId: String) {
        if (deviceId.isBlank() || caregiverLocationRealtimeDevice == deviceId) return
        caregiverLocationRealtime?.stop()
        caregiverLocationRealtimeDevice = deviceId
        caregiverLocationRealtime = CaregiverLocationRealtime(backend, sessions, onLocation = { location ->
            runOnUiThread {
                if (store.prefs.getString("caregiver_location_device_id", "").orEmpty() != deviceId) return@runOnUiThread
                val editor = store.prefs.edit()
                if (location == null) editor.remove("caregiver_shared_location")
                else editor.putString("caregiver_shared_location", location.toString())
                editor.apply()
                if (tab in 0..2) render()
            }
        }, onRealtimeError = { err -> runOnUiThread { store.prefs.edit().putString("caregiver_shared_status", "Aviso Realtime (ubicación): $err").apply(); if (tab in 0..2) render() } }).also { it.start(deviceId) }
    }
    private fun ensureCaregiverHealthRealtime(ownerId: String, deviceId: String?) {
        val key = "$ownerId:${deviceId.orEmpty()}"
        if (ownerId.isBlank() || caregiverHealthRealtimeKey == key) return
        caregiverHealthRealtime?.stop()
        caregiverHealthRealtimeKey = key
        caregiverHealthRealtime = CaregiverHealthRealtime(
            backend,
            sessions,
            onMeasurement = ::mergeCaregiverMeasurement,
            onFallEvent = { refreshCaregiverSharedData(force = true) },
            onPhoneMotion = ::mergeCaregiverPhoneMotion,
            onRealtimeError = { err -> runOnUiThread { store.prefs.edit().putString("caregiver_shared_status", "Aviso Realtime: $err").apply(); if (tab in 0..2) render() } },
        ).also { it.start(ownerId, deviceId) }
    }

    private fun mergeCaregiverMeasurement(measurement: JSONObject) {
        runOnUiThread {
            val current = runCatching {
                JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]"))
            }.getOrDefault(JSONArray())
            val rows = (0 until current.length()).mapNotNull { current.optJSONObject(it) }
                .filterNot { it.optString("id") == measurement.optString("id") }
                .toMutableList()
            rows.add(measurement)
            val sorted = rows.sortedByDescending { row ->
                runCatching { java.time.Instant.parse(row.optString("captured_at")).toEpochMilli() }.getOrDefault(0L)
            }.take(100)
            val merged = JSONArray().apply { sorted.forEach(::put) }
            store.prefs.edit().putString("caregiver_shared_measurements", merged.toString())
                .putLong("caregiver_shared_fetched_at", System.currentTimeMillis()).apply()
            if (tab in 0..2) render()
        }
    }
    private fun mergeCaregiverPhoneMotion(motion: JSONObject) {
        runOnUiThread {
            store.prefs.edit().putString("caregiver_shared_phone_motion", motion.toString())
                .putLong("caregiver_shared_fetched_at", System.currentTimeMillis()).apply()
            if (tab in 0..2) render()
        }
    }
    private fun addSharedLocationCard(parent: LinearLayout) {
        val locationCard = card(parent)
        locationCard.addView(text("Ubicación en vivo", 20, true))
        val row = runCatching { JSONObject(store.prefs.getString("caregiver_shared_location", "{}").orEmpty()) }.getOrDefault(JSONObject())
        val capturedAt = runCatching { java.time.Instant.parse(row.optString("captured_at")).toEpochMilli() }.getOrDefault(0L)
        val latitude = row.optDouble("latitude", Double.NaN)
        val longitude = row.optDouble("longitude", Double.NaN)
        val hasCoordinates = latitude.isFinite() && longitude.isFinite() && capturedAt > 0L
        val ageMs = if (hasCoordinates) (System.currentTimeMillis() - capturedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        val live = hasCoordinates && ageMs <= 90_000L
        caregiverSharedLocationView = text(
            if (!hasCoordinates) "Sin datos" else if (live) "●  EN VIVO" else "Última ubicación",
            17, true, if (live) accent else muted,
        ).also { locationCard.addView(it) }
        caregiverLocationCoordinatesView = text(if (hasCoordinates) "$latitude, $longitude" else "—", 15, true)
            .also { locationCard.addView(it) }
        val freshnessText = when {
            !hasCoordinates -> "Sin datos de ubicación"
            ageMs < 60_000L -> "Actualizada hace menos de un minuto"
            ageMs < 3_600_000L -> "Actualizada hace ${ageMs / 60_000L} min"
            else -> "Actualizada hace ${ageMs / 3_600_000L} h"
        }
        caregiverLocationFreshnessView = text(freshnessText, 14, color = muted).also { locationCard.addView(it) }
        caregiverMapButton = button("Abrir mapa  →", false) {
            if (hasCoordinates) {
                val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure { toast("No hay una aplicación de mapas disponible.", true) }
            } else toast("Todavía no hay una ubicación disponible.", true)
        }.also { it.isEnabled = hasCoordinates; locationCard.addView(it) }
    }
    private fun caregiverActivity(body: LinearLayout) {
        refreshCaregiverSharedData()
        val card = card(body, softSurface)
        card.addView(text("Estado de conexión", 13, true, accent))
        val linked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        card.addView(text(if (linked) "Vínculo activo" else "Sin conexión con una persona protegida", 20, true))
        card.addView(text(if (linked) "Historial de incidentes y avisos" else "Requiere vínculo activo", 15, color = muted))
        card.addView(button("Configurar vinculación  →") { selectTab(2) })
        val rows = runCatching { JSONArray(store.prefs.getString("caregiver_shared_events", "[]")) }.getOrDefault(JSONArray())
        if (rows.length() == 0) card.addView(text("Sin eventos registrados.", 15, color = muted))
        else for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val eventCard = card(body)
            eventCard.addView(text(row.optString("classification").replace('_', ' '), 18, true))
            eventCard.addView(text("${row.optString("occurred_at")} · ${row.optString("source").replace('_', ' ')} · ${row.optString("confirmation_status")}", 14, color = muted))
            row.optString("summary").takeIf { it.isNotBlank() }?.let { eventCard.addView(text(it, 14, color = muted)) }
        }
    }
    private fun caregiverPersonScreen(body: LinearLayout) {
        refreshCaregiverSharedData()
        heading(body, "VINCULACIÓN", "Conectar a una persona", "Usa el QR o código de 8 caracteres.")
        val remoteLinked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        val status = card(body, if (remoteLinked) mint else softSurface)
        status.addView(text(if (remoteLinked) "●  CONEXIÓN ACTIVA" else "○  LISTO PARA CONECTAR", 13, true, accent))
        status.addView(text(if (remoteLinked) store.prefs.getString("caregiver_shared_name", "Persona protegida").orEmpty().ifBlank { "Persona protegida" }
            else "Tu teléfono de cuidador/a", 20, true))
        status.addView(text(if (remoteLinked) "Sincronización activa" else "Listo para vincular", 14, color = muted))

        val connect = card(body)
        connect.addView(text("Vinculación", 19, true))
        connect.addView(text("Escanea el QR o escribe el código de 8 caracteres.", 15, color = muted))
        connect.addView(button("▦  Escanear QR") { scanPairingQr("caregiver") })
        val code = field(connect, "Código de vinculación", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, "caregiver_invite_code")
        code.filters = arrayOf(android.text.InputFilter.LengthFilter(9))
        formatPairingCode(code)
        val actionStatus = text("Código de un solo uso.", 14, color = muted).also { connect.addView(it) }
        connect.addView(button("Continuar") {
            val invite = code.text.toString().filter(Char::isLetterOrDigit)
            if (invite.length != 8) actionStatus.text = "Escribe los 8 caracteres del código."
            else showPairingConfirmation("caregiver", invite)
        })
        if (remoteLinked) connect.addView(button("Desvincular persona", false) {
            AlertDialog.Builder(this).setTitle("Desvincular persona")
                .setMessage("Se dejarán de mostrar sus eventos y mediciones compartidas.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Desvincular") { _, _ ->
                    val linkId = store.prefs.getString("caregiver_link_id", "").orEmpty()
                    io.execute {
                        val result = runCatching { backend.unlinkCaregiver(linkId) }
                        runOnUiThread {
                            result.onSuccess {
                                caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
                                caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
                                store.prefs.edit().remove("caregiver_shared_location").remove("caregiver_link_id").remove("caregiver_owner_id")
                    .remove("caregiver_location_device_id").remove("caregiver_shared_phone_motion")
                    .remove("caregiver_device_id").remove("caregiver_link_device_id").apply()
                                toast("Vínculo desconectado."); render()
                            }.onFailure { toast(it.message ?: "No se pudo desvincular.", true) }
                        }
                    }
                }.show()
        })
        vitalsUnavailableCard(body, caregiver = true)
        addSharedPhoneMotionCard(body)
        addCaregiverSyncControls(body)
        body.addView(button("Cambiar tipo de cuenta", false) { tab = ROLE_TAB; render() })
    }
    private fun vitalsUnavailableCard(parent: LinearLayout, caregiver: Boolean) {
        val card = card(parent)
        val shared = if (caregiver) runCatching { JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]")) }.getOrDefault(JSONArray()) else JSONArray()
        val localVitals = if (caregiver) JSONObject() else store.telemetry.latestArduinoVitals()
        card.addView(text(if (caregiver) "Signos vitales compartidos" else "Pulsera de sensores", 19, true))
        card.addView(text(if (caregiver && shared.length() > 0) "Últimas lecturas recibidas" else if (caregiver) "Lecturas de la persona protegida" else "Lecturas recibidas en este teléfono", 14, color = muted))
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun latest(type: String): String? {
            if (!caregiver) {
                val key = if (type == "heart_rate") "bpm" else "spo2"
                if (!localVitals.has(key)) return null
                val number = localVitals.optDouble(key)
                val value = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
                return "$value ${if (type == "heart_rate") "BPM" else "%"}"
            }
            for (i in 0 until shared.length()) {
                val row = shared.optJSONObject(i) ?: continue
                if (row.optString("measurement_type") == type && (row.isNull("is_valid") || row.optBoolean("is_valid"))) {
                    val numeric = row.optDouble("value")
                    return "${if (numeric % 1.0 == 0.0) numeric.toInt() else numeric} ${row.optString("unit")}"
                }
            }
            return null
        }
        listOf("Pulso" to (latest("heart_rate") ?: "— BPM"), "SpO₂" to (latest("spo2") ?: "— %")).forEach { (label, value) ->
            val metric = column().apply {
                background = rounded(softSurface, 16)
                setPadding(dp(14), dp(12), dp(8), dp(12))
                addView(text(label, 14, true, muted))
                val valueView = text(value, 24, true, ink)
                if (label == "Pulso") patientPulseValue = valueView
                if (label == "SpO₂") patientSpo2Value = valueView
                addView(valueView)
                addView(text(if (value.startsWith("—")) "Sin lectura disponible" else "Última lectura", 13, color = muted))
            }
            metrics.addView(metric, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (metrics.childCount > 0) leftMargin = dp(8)
            })
        }
        card.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val hasReading = latest("heart_rate") != null || latest("spo2") != null
        val latestRow = shared.optJSONObject(0)
        val capturedAt = if (caregiver) latestRow?.optString("captured_at")
            ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
        else maxOf(localVitals.optLong("heart_rate_at"), localVitals.optLong("spo2_at"))
        val measurementStatus = if (hasReading) {
            val now = System.currentTimeMillis()
            val ageMs = if (capturedAt > 0L) (now - capturedAt).coerceAtLeast(0L) else Long.MAX_VALUE
            val ageText = when {
                ageMs < 60_000L -> "hace menos de 1 min"
                ageMs < 3_600_000L -> "hace ${ageMs / 60_000L} min"
                ageMs < 86_400_000L -> "hace ${ageMs / 3_600_000L} h"
                else -> "hace más de 24 h"
            }
            if (caregiver) "Última lectura: $ageText.\nDatos informativos; no constituyen diagnóstico médico."
            else "Pulsera conectada · última lectura $ageText."
        } else if (caregiver) {
            "Sin datos de signos vitales compartidos."
        } else if (arduinoConnectionAvailable()) {
            "Pulsera conectada. Coloca el dedo en el sensor para medir."
        } else {
            store.prefs.getString("arduino_status", "").orEmpty().ifBlank { "Sin conexión con la pulsera UNO Q." }
        }
        patientVitalsStatus = text(measurementStatus, 14, color = muted).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(patientVitalsStatus)
    }

    private fun addCaregiverSyncControls(parent: LinearLayout) {
        if (store.prefs.getString("caregiver_link_id", "").orEmpty().isBlank()) return
        val controls = card(parent, softSurface)
        val fetchedAt = store.prefs.getLong("caregiver_shared_fetched_at", 0L)
        val now = System.currentTimeMillis()
        val syncAgeMs = if (fetchedAt > 0L) (now - fetchedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        val syncTimeText = when {
            fetchedAt == 0L -> "Sin datos"
            syncAgeMs < 60_000L -> "Última actualización: hace menos de 1 min"
            syncAgeMs < 3_600_000L -> "Última actualización: hace ${syncAgeMs / 60_000L} min"
            syncAgeMs < 86_400_000L -> "Última actualización: hace ${syncAgeMs / 3_600_000L} h"
            else -> "Última actualización: hace más de 24 h"
        }
        controls.addView(text("SINCRONIZACIÓN", 13, true, accent))
        controls.addView(text(syncTimeText, 17, true))
        caregiverSharedStatusView = text(
            store.prefs.getString("caregiver_shared_status", "Conexión lista para actualizar." )?.ifBlank { "Conexión lista para actualizar." }
                ?: "Conexión lista para actualizar.",
            14, color = muted,
        ).also { controls.addView(it) }
        controls.addView(button("Actualizar ahora", false) {
            caregiverSharedStatusView?.text = "Actualizando datos…"
            refreshCaregiverSharedData(force = true)
        })
    }

    private fun addSharedPhoneMotionCard(parent: LinearLayout) {
        val card = card(parent)
        card.addView(text("Movimiento del teléfono", 19, true))
        val motion = runCatching {
            JSONObject(store.prefs.getString("caregiver_shared_phone_motion", "{}").orEmpty())
        }.getOrDefault(JSONObject())
        val capturedAt = runCatching { java.time.Instant.parse(motion.optString("captured_at")).toEpochMilli() }.getOrDefault(0L)
        val ageMs = if (capturedAt > 0L) (System.currentTimeMillis() - capturedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        val hasMotion = capturedAt > 0L && motion.optInt("sample_count") > 0
        card.addView(text(
            when {
                !hasMotion -> "Esperando datos del sensor de movimiento."
                ageMs <= 90_000L -> "●  Datos recientes del teléfono"
                else -> "Últimos datos recibidos"
            },
            16, true, if (hasMotion && ageMs <= 90_000L) accent else muted,
        ))
        if (hasMotion) {
            val time = SimpleDateFormat("HH:mm:ss", Locale("es", "MX")).format(Date(capturedAt))
            val average = motion.optDouble("average_dynamic_g", Double.NaN)
            val peak = motion.optDouble("peak_dynamic_g", Double.NaN)
            val metrics = if (average.isFinite() && peak.isFinite())
                "Movimiento promedio ${"%.2f".format(Locale.US, average)} g · máximo ${"%.2f".format(Locale.US, peak)} g"
            else "Actividad de movimiento registrada"
            card.addView(text("$metrics\nÚltimo envío $time · ${motion.optInt("sample_count")} muestras", 14, color = muted))
        } else {
            card.addView(text("Activa el monitoreo en el teléfono de la persona para compartir el resumen del sensor.", 14, color = muted))
        }
    }
    private fun refreshPatientVitals() {
        val now = SystemClock.elapsedRealtime()
        if (now - patientVitalsRefreshAt < 5_000L) return
        patientVitalsRefreshAt = now
        val vitals = store.telemetry.latestArduinoVitals()
        fun number(key: String): String? {
            if (!vitals.has(key)) return null
            val value = vitals.optDouble(key)
            return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        }
        fun display(key: String, suffix: String): String {
            return number(key)?.let { "$it $suffix" } ?: "— $suffix"
        }
        val hasReading = vitals.has("bpm") || vitals.has("spo2")
        val capturedAt = maxOf(vitals.optLong("heart_rate_at"), vitals.optLong("spo2_at"))
        val homeSummary = when {
            hasReading -> {
                val time = if (capturedAt > 0L) SimpleDateFormat("HH:mm:ss", Locale("es", "MX")).format(Date(capturedAt)) else "hora no disponible"
                listOfNotNull(number("bpm")?.let { "$it BPM" }, number("spo2")?.let { "SpO₂ $it%" })
                    .joinToString(" · ") + " · $time"
            }
            arduinoConnectionAvailable() ->
                "Pulsera conectada · coloca el dedo en el sensor para medir."
            else -> store.prefs.getString("arduino_status", "").orEmpty().ifBlank { "Sin lecturas · activa el monitoreo y acerca la pulsera UNO Q." }
        }
        homePulseraStatus?.text = homeSummary
        val pulseView = patientPulseValue ?: return
        val spo2View = patientSpo2Value ?: return
        pulseView.text = display("bpm", "BPM")
        spo2View.text = display("spo2", "%")
        patientVitalsStatus?.text = when {
            hasReading -> {
                val time = if (capturedAt > 0L) SimpleDateFormat("HH:mm:ss", Locale("es", "MX")).format(Date(capturedAt)) else "hora no disponible"
                "Pulsera conectada · última lectura $time."
            }
            arduinoConnectionAvailable() ->
                "Pulsera conectada. Coloca el dedo en el sensor y espera unos segundos para recibir pulso y SpO₂."
            else -> store.prefs.getString("arduino_status", "").orEmpty().ifBlank { "Todavía no hay lecturas. Activa el monitoreo y acerca la pulsera UNO Q." }
        }
    }
    private fun deviceLinkScreen(body: LinearLayout) {
        cameraRegistryRenderedRevision = store.prefs.getLong("camera_registry_revision", 0L)
        body.addView(button("←  Atrás", false) { selectTab(deviceReturnTab.coerceIn(0, 3)) })
        gap(body, 12)
        heading(body, "CONECTAR DISPOSITIVOS", "Dispositivos y enlaces", "Gestión de pulsera, cámaras y vinculación.")
        if (isCaregiver) {
            val remote = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
            val link = card(body, if (remote) mint else softSurface)
            link.addView(text(if (remote) "●  CONEXIÓN LISTA" else "○  CONECTA A UNA PERSONA", 13, true, accent))
            link.addView(text(if (remote) "Vínculo activo" else "Sin persona vinculada", 20, true))
            link.addView(text(if (remote) "Sincronización de eventos y telemetría activa." else "Escanea un código para vincularte.", 14, color = muted))
            link.addView(button("Administrar vínculo  →") { selectTab(2) })
            return
        }
        val phones = card(body)
        phones.addView(text("Teléfonos", 19, true))
        phones.addView(text("Teléfono de la persona a cuidar", 16, true))
        phones.addView(text("Este teléfono · ubicación, pulsera y alertas", 14, color = muted))
        phones.addView(text("Teléfono del cuidador/a", 16, true).apply { setPadding(0, dp(12), 0, 0) })
        phones.addView(text(if (store.prefs.getString("caregiver_owner_id", "").orEmpty().isNotBlank()) "Cuidador/a vinculado" else "Sin vincular", 14, color = muted))

        val braceletStatus = store.prefs.getString("arduino_status", "").orEmpty()
        val braceletCard = addDeviceRegistryCard(body, "Pulsera de sensores · UNO Q", "Pulso, oxígeno y detección de caídas", "wearable", store.arduinoDeviceId(),
            when {
                braceletStatus.contains("conectada por Bluetooth", ignoreCase = true) -> "Conectada por Bluetooth · enviando lecturas"
                braceletStatus.contains("conexión activa", ignoreCase = true) -> "Conectada por Wi-Fi · enviando lecturas"
                braceletStatus.isNotBlank() -> braceletStatus
                store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE -> "Bluetooth elegido · pulsa Conectar para buscarla"
                else -> "Wi-Fi elegido · configura la dirección del UNO Q"
            })
        addBraceletConnectionControls(braceletCard, includeMethodOptions = false)
        val cameraCard = card(body)
        cameraCard.addView(text("Cámaras independientes", 20, true))
        cameraCard.addView(text("Vinculación mediante código QR o de 8 dígitos.", 14, color = muted))
        val registered = store.registeredCameras()
        if (registered.isEmpty()) cameraCard.addView(text("Todavía no hay cámaras agregadas.", 14, color = muted).apply { setPadding(0, dp(10), 0, 0) })
        registered.forEach { camera ->
            val row = card(cameraCard)
            row.addView(text(camera.name, 17, true))
            row.addView(text("Cámara · vinculación por Internet", 13, color = accent))
            row.addView(button("Quitar cámara", false) {
                AlertDialog.Builder(this).setTitle("Quitar cámara")
                    .setMessage("Se quitará ${camera.name} de este inventario.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Quitar") { _, _ ->
                        runCatching { store.removeRegisteredCamera(camera.id) }
                            .onSuccess { updateServiceIfActive(); toast("Cámara quitada."); render() }
                            .onFailure { toast(it.message ?: "No se pudo quitar la cámara.", true) }
                    }.show()
            })
        }
        cameraCard.addView(button("＋  Vincular una cámara independiente") { showCreateCameraInviteDialog() })
        cameraCard.addView(button("Registrar cámara física sin conexión", false) { showAddCameraDialog() })
        val note = card(body, softSurface)
        note.addView(text("Vinculación del cuidador", 18, true))
        note.addView(text("Crea un QR o código para compartir la cuenta. El cuidador/a puede estar en cualquier red con acceso a Internet.", 14, color = muted))
        note.addView(button("＋  Vincular teléfono del cuidador/a", false) { showCreateCaregiverInvite(note) })
    }
    private fun addBraceletConnectionControls(parent: LinearLayout, includeMethodOptions: Boolean) {
        if (includeMethodOptions) {
            parent.addView(text("Método de conexión", 16, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val bleOption = RadioButton(this).apply {
                id = 1101; text = "Bluetooth · recomendado, funciona sin Internet"; textSize = 15f
                setTextColor(ink); buttonTintList = ColorStateList.valueOf(accent); minHeight = dp(52)
            }
            val wifiOption = RadioButton(this).apply {
                id = 1102; text = "Wi-Fi · red local del UNO Q"; textSize = 15f
                setTextColor(ink); buttonTintList = ColorStateList.valueOf(accent); minHeight = dp(52)
            }
            group.addView(bleOption); group.addView(wifiOption)
            group.check(if (store.braceletConnectionMode() == AppStore.BRACELET_MODE_WIFI) wifiOption.id else bleOption.id)
            group.setOnCheckedChangeListener { _, checkedId ->
                val selected = if (checkedId == wifiOption.id) AppStore.BRACELET_MODE_WIFI else AppStore.BRACELET_MODE_BLE
                if (selected == store.braceletConnectionMode()) return@setOnCheckedChangeListener
                runCatching { store.saveBraceletConnectionMode(selected) }
                    .onSuccess {
                        val enabled = store.prefs.getBoolean("bracelet_connection_enabled", GuardianMonitorService.monitoring)
                        if (enabled) requestBraceletService(GuardianMonitorService.ACTION_BRACELET_RECONNECT)
                        else store.prefs.edit().putString("arduino_status", if (selected == AppStore.BRACELET_MODE_BLE)
                            "Bluetooth seleccionado. Pulsa Conectar para buscar la pulsera." else "Wi-Fi seleccionado. Configura la dirección y pulsa Conectar.").apply()
                        render()
                    }
                    .onFailure { toast(it.message ?: "No se pudo cambiar el método.", true) }
            }
            parent.addView(group)
        }
        val statusView = text(store.prefs.getString("arduino_status", "Pulsera sin conectar.").orEmpty(), 14, color = muted)
            .apply { setPadding(0, dp(10), 0, dp(4)) }
        braceletStatusViews += statusView
        parent.addView(statusView)
        parent.addView(button("Conectar o volver a conectar", true) {
            requestBraceletService(GuardianMonitorService.ACTION_BRACELET_RECONNECT)
            statusView.text = if (store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE) "Buscando la pulsera por Bluetooth…" else "Conectando a la pulsera por Wi-Fi…"
        })
        parent.addView(button("Desconectar pulsera", false) {
            service(GuardianMonitorService.ACTION_BRACELET_DISCONNECT)
            statusView.text = "Desconectando la pulsera…"
        })
        if (!includeMethodOptions || store.braceletConnectionMode() != AppStore.BRACELET_MODE_WIFI) return

        val arduinoUrl = field(parent, "Dirección Wi-Fi del UNO Q", store.arduinoBaseUrl(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "arduino_base_url")
        parent.addView(button("Guardar dirección Wi-Fi", false) {
            runCatching { store.saveArduinoBaseUrl(arduinoUrl.text.toString()) }
                .onSuccess { toast("Dirección guardada para la próxima conexión.") }
                .onFailure { toast(it.message ?: "No se pudo guardar la dirección.", true) }
        })
        val testStatus = text("", 14, color = muted)
        parent.addView(testStatus)
        parent.addView(button("Probar conexión Wi-Fi", false) {
            testStatus.text = "Conectando con la pulsera…"
            io.execute {
                val result = runCatching {
                    val connection = (URL("${store.arduinoBaseUrl().trimEnd('/')}/api/v1/sensor").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3_000; readTimeout = 3_000; requestMethod = "GET"
                    }
                    val code = connection.responseCode
                    val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (code !in 200..299) throw IllegalStateException("La pulsera respondió HTTP $code.")
                    val root = JSONObject(response)
                    val sample = root.optJSONObject("reading") ?: root.optJSONObject("data") ?: root
                    val bpm = sample.optDouble("bpm", sample.optDouble("heart_rate_bpm", 0.0))
                    val spo2 = sample.optDouble("spo2", sample.optDouble("spo2_percent", 0.0))
                    when { bpm > 0 && spo2 > 0 -> "Pulsera conectada · pulso $bpm BPM · SpO₂ $spo2%."
                        bpm > 0 -> "Pulsera conectada · pulso $bpm BPM · esperando oxígeno."
                        else -> "Pulsera conectada · coloca el dedo en el sensor para medir." }
                }
                runOnUiThread {
                    testStatus.text = result.getOrElse { it.message ?: "No se pudo conectar con la pulsera." }
                    if (result.isFailure) toast(testStatus.text.toString(), true)
                }
            }
        })
    }

    private fun requestBraceletService(action: String) {
        if (store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE) {
            val required = requiredBraceletBlePermissions()
            val missing = required.filterNot(::has).toTypedArray()
            if (missing.isNotEmpty()) {
                pendingBraceletAction = action
                runCatching { requestPermissions(missing, REQUEST_BRACELET_BLE) }
                    .onFailure { pendingBraceletAction = null; toast("Android no pudo solicitar permiso de Bluetooth.", true) }
                return
            }
        }
        service(action)
        handler.postDelayed({ refreshLive() }, 400)
    }

    private fun requiredBraceletBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun addDeviceRegistryCard(parent: LinearLayout, name: String, detail: String, type: String, id: String, status: String): LinearLayout {
        val row = card(parent)
        row.contentDescription = "$name, ID ${id.take(8)}, tipo $type"
        row.addView(text(name, 18, true))
            row.addView(text(detail, 14, color = muted))
        val statusView = text(status, 13, true, if (type == "phone" || type == "camera") accent else muted).apply { setPadding(0, dp(8), 0, 0) }
        row.addView(statusView)
        if (type == "wearable") braceletStatusViews += statusView
        return row
    }

    private fun showAddCameraDialog() {
        val input = EditText(this).apply {
            hint = "Ej. Cámara sala"
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(if (dark) Color.parseColor("#19231D") else Color.WHITE, 14)
        }
        val content = column().apply { setPadding(dp(22), dp(8), dp(22), dp(4)); addView(input) }
        AlertDialog.Builder(this).setTitle("Agregar cámara independiente")
            .setMessage("Asigna un nombre para identificarla. El registro se guarda separado; el video todavía requiere los datos de conexión de la cámara.")
            .setView(content).setNegativeButton("Cancelar", null)
            .setPositiveButton("Agregar") { _, _ ->
                runCatching { store.addRegisteredCamera(input.text.toString()) }
                    .onSuccess { toast("Cámara registrada por separado. Falta configurar su conexión."); render() }
                    .onFailure { toast(it.message ?: "No se pudo registrar la cámara.", true) }
            }.show()
    }
    private fun todayPossibleFallCount(): Int {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return store.events().let { events ->
            (0 until events.length()).mapNotNull { events.optJSONObject(it) }
                .filter { it.optLong("time") >= start && it.optString("message").contains("caída", ignoreCase = true) }
                .distinctBy { it.optString("incident").ifBlank { it.optLong("time").toString() } }
                .size
        }
    }
    private fun addPeaceSummary(body: LinearLayout, recipients: List<TrustedContact>) {
        val summary = card(body)
        summary.addView(text("Resumen de tranquilidad", 21, true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val falls = column().apply { setPadding(0, 0, dp(10), 0) }
        falls.addView(text("${todayPossibleFallCount()}", 28, true, accent))
        falls.addView(text("posibles caídas hoy", 14, color = muted))
        row.addView(falls, LinearLayout.LayoutParams(0, -2, 1f))
        val support = column()
        support.addView(text("${recipients.size}", 28, true, accent))
        support.addView(text("contactos de apoyo", 14, color = muted))
        row.addView(support, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(row)
        val last = store.events().optJSONObject(0)
        val lastText = last?.let {
            "Último registro · ${SimpleDateFormat("HH:mm", Locale("es", "MX")).format(Date(it.optLong("time")))} · ${it.optString("message")}"
        } ?: "Sin registros todavía · aquí verás el estado de tus alertas."
        summary.addView(text(lastText, 14, color = muted).apply { setPadding(0, dp(12), 0, 0) })

        val caregiver = card(body)
        caregiver.addView(text("Cuidadora principal", 14, true, accent))
        val primary = recipients.firstOrNull()
        caregiver.addView(text(primary?.name?.ifBlank { primary.phone } ?: "Agrega un contacto de confianza", 19, true))
        caregiver.addView(text(primary?.phone ?: "Tu red recibirá avisos si se activa una alerta.", 15, color = muted))
        caregiver.addView(button(if (primary == null) "Administrar contactos" else "Llamar con el teléfono", false) {
            if (primary == null) selectTab(2) else runCatching {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", primary.phone, null)))
            }.onFailure { toast("No se pudo abrir el marcador del teléfono.", true) }
        })
    }
    private fun activity(body: LinearLayout) {
        if (isCaregiver) refreshCaregiverSharedData()
        heading(body, if (isCaregiver) "SEGUIMIENTO" else "SUPERVISIÓN", if (isCaregiver) "Actividad de la persona" else "Historial y registros",
            if (isCaregiver) "Consulta los datos compartidos por la persona que acompañas." else "Consulta tus signos vitales o revisa por separado el historial de avisos.")
        val sections = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Signos vitales", "Notificaciones").forEachIndexed { index, label ->
            val selected = activitySection == index
            val chip = button(label, selected) { activitySection = index; render() }.apply {
                minHeight = dp(48); textSize = 14f
                setTextColor(if (selected) onAccent else accent)
                background = RippleDrawable(ColorStateList.valueOf(0x33254D3A), rounded(if (selected) accent else softSurface, 24), null)
            }
            sections.addView(chip, LinearLayout.LayoutParams(0, dp(50), 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        body.addView(sections, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (activitySection == 0) {
            vitalsUnavailableCard(body, caregiver = isCaregiver)
            if (isCaregiver) {
                addSharedLocationCard(body)
                addSharedPhoneMotionCard(body)
                addCaregiverSyncControls(body)
            }
            else body.addView(text("Las lecturas se guardan en este teléfono y se comparten con tu cuidador cuando hay conexión a Internet.", 14, color = muted)
                .apply { setPadding(dp(4), dp(10), dp(4), dp(8)) })
            if (!isCaregiver) refreshPatientVitals()
            return
        }
        if (isCaregiver) { caregiverActivity(body); return }
        val progress = card(body, if (dark) Color.parseColor("#3B2020") else Color.parseColor("#FBDCD8"))
        progress.addView(text("EMERGENCIA EN PROCESO", 13, true, critical))
        progress.addView(text("Avisos en curso", 20, true, critical))
        emergencyProgressStatus = text("Solicitando SMS y llamadas a tus contactos.", 15, color = ink).also { progress.addView(it) }
        progress.addView(text("El resultado depende de la SIM, la señal y la disponibilidad del teléfono.", 14, color = muted))
        emergencyProgressCard = progress
        progress.visibility = if (store.prefs.getString("dispatch_id", "").orEmpty().isNotBlank()) View.VISIBLE else View.GONE
        val events = store.events()
        val records = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
        val summary = card(body)
        val summaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val eventMetric = column().apply { setPadding(0, 0, dp(12), 0) }
        eventMetric.addView(text("${records.size}", 28, true, accent))
        eventMetric.addView(text("eventos guardados", 14, color = muted))
        summaryRow.addView(eventMetric, LinearLayout.LayoutParams(0, -2, 1f))
        val stateMetric = column()
        stateMetric.addView(text(if (GuardianMonitorService.monitoring) "Activo" else "En pausa", 21, true, accent))
        stateMetric.addView(text("estado del monitoreo", 14, color = muted))
        summaryRow.addView(stateMetric, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(summaryRow)

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Todos", "Alertas", "Cámara", "Pulsera").forEachIndexed { index, label ->
            val selected = activityFilter == index
            val chip = button(label, selected) { activityFilter = index; render() }.apply {
                minHeight = dp(48); textSize = 14f
                setTextColor(if (selected) onAccent else accent)
                background = RippleDrawable(ColorStateList.valueOf(0x33254D3A), rounded(if (selected) accent else softSurface, 24), null)
            }
            filters.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
        body.addView(filters, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val filtered = records.filter { event ->
            val message = event.optString("message").lowercase(Locale.ROOT)
            when (activityFilter) {
                1 -> listOf("caída", "alerta", "sms", "llamada", "cancelad").any { message.contains(it) }
                2 -> listOf("cámara ia", "detector con cámara", "modelo local", "cámara").any { message.contains(it) }
                3 -> listOf("arduino", "pulsera", "sensor", "servicio", "permiso").any { message.contains(it) }
                else -> true
            }
        }
        body.addView(text("Registro detallado · ${filtered.size}", 19, true).apply { setPadding(dp(2), dp(20), 0, dp(4)) })
        if (filtered.isEmpty()) {
            val empty = card(body)
            empty.addView(ImageView(this).apply { setImageResource(R.drawable.ic_activity); setColorFilter(accent); contentDescription = "" }, LinearLayout.LayoutParams(dp(40), dp(40)))
            gap(empty, 10)
            empty.addView(text(if (records.isEmpty()) "Todo empieza aquí" else "No hay eventos en este filtro", 20, true))
            empty.addView(text(if (records.isEmpty()) "Cuando actives el monitoreo o envíes una alerta, aquí aparecerá el registro del proceso." else "Prueba otra categoría para ver más actividad.", 16, color = muted))
        }
        filtered.forEach { event ->
            val row = card(body)
            val message = event.optString("message", "Evento de TzindaApp")
            val date = Date(event.optLong("time"))
            row.addView(text(SimpleDateFormat("EEEE, d MMM · HH:mm", Locale("es", "MX")).format(date), 14, true, accent))
            row.addView(text(message, 17, true))
        }
    }
    private fun contacts(body: LinearLayout) {
        if (isCaregiver) { caregiverPersonScreen(body); return }
        heading(body, "TU RED DE APOYO", "Contactos de confianza", "Estas personas reciben los SMS y las llamadas de alerta.")
        val recipients = store.recipients()
        val emergencyKey = PhoneNumbers.normalize(store.profile().text("emergencyContactPhone"))?.let { PhoneNumbers.key(it) }
        val protocol = card(body, mint)
        protocol.addView(text("◉  Protocolo de respuesta", 18, true, accent))
        protocol.addView(text("Si se confirma una posible caída, tienes 20 segundos para cancelar. Después se solicitan los SMS y comienzan las llamadas a tu red de apoyo.", 15, color = muted))
        if (recipients.isEmpty()) {
            val empty = card(body)
            empty.addView(text("Aún no tienes contactos", 20, true))
            empty.addView(text("Agrega al menos una persona para activar el monitoreo y enviar alertas.", 16, color = muted))
        } else {
            body.addView(text("Orden de aviso · ${recipients.size} destinatario(s)", 19, true).apply { setPadding(dp(2), dp(18), 0, dp(2)) })
            recipients.forEachIndexed { index, contact ->
                val item = card(body)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val initials = contact.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "${index + 1}" }
                row.addView(text(initials, 16, true, accent).apply { gravity = Gravity.CENTER; background = rounded(mint, 24) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                val detail = column().apply { setPadding(dp(12), 0, 0, 0) }
                detail.addView(text(contact.name.ifBlank { "Contacto ${index + 1}" }, 17, true))
                detail.addView(text(contact.phone, 15, color = muted))
                row.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
                val role = if (PhoneNumbers.key(contact.phone) == emergencyKey) "Contacto de emergencia del perfil" else "Prioridad ${index + 1}"
                item.addView(row)
                item.addView(text(role, 14, true, accent).apply { setPadding(dp(60), dp(6), 0, 0) })
                item.addView(button("Llamada rápida", false) {
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.phone, null))) }
                        .onFailure { toast("No se pudo abrir el marcador del teléfono.", true) }
                })
            }
        }

        val quick = card(body, softSurface)
        quick.addView(text("Ajustes rápidos de aviso", 19, true))
        quick.addView(text("La ubicación del teléfono se actualiza mientras la protección está activa. No se transmite a otro dispositivo sin sincronización configurada.", 14, color = muted))
        quick.addView(switch("Compartir ubicación en vivo y alertas", store.prefs.getBoolean("include_location", false)) { value -> setLocationConsent(value) })
        quick.addView(button("Actualizar ubicación", false) { requestLocation() })

        val test = card(body, softSurface)
        test.addView(text("Probar protocolo de aviso", 19, true))
        test.addView(text("La prueba inicia una cuenta regresiva. Si no la cancelas, puede solicitar SMS reales y realizar llamadas a tus contactos.", 14, color = muted))
        test.addView(button("Iniciar prueba de cuenta regresiva", false) {
            AlertDialog.Builder(this).setTitle("Iniciar prueba")
                .setMessage("La prueba usa tu SIM real: si no pulsas Estoy bien antes de que termine, TzindaApp solicitará SMS y llamadas a tus contactos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_TEST) }.show()
        })

        val editor = card(body)
        editor.addView(text(if (editingContacts) "Editar lista de contactos" else "Administrar contactos", 20, true))
        editor.addView(text("Hasta 5 contactos. El contacto principal se llama primero en caso de alerta.", 14, color = muted))
        if (!editingContacts) {
            editor.addView(button(if (recipients.isEmpty()) "Agregar contactos" else "Editar contactos", true) { editingContacts = true; render() })
            editor.addView(button("Importar desde mi agenda", false) { editingContacts = true; render(); handler.post { beginImport(-1) } })
            return
        }
        editor.addView(button("Importar desde mi agenda", false) { beginImport(-1) })
        val rows = contactDraft ?: store.contacts()
        for (i in 0 until 5) {
            val entry = card(editor, softSurface)
            entry.addView(text("CONTACTO ${i + 1}${if (i == 0) " · LLAMADA PRIORITARIA" else ""}", 13, true, accent))
            val name = field(entry, "Nombre (opcional)", rows.getOrNull(i)?.name.orEmpty(), tag = "contact_name_$i")
            val phone = field(entry, "Teléfono", rows.getOrNull(i)?.phone.orEmpty(), InputType.TYPE_CLASS_PHONE, "contact_phone_$i")
            contactEdits.add(name to phone)
            entry.addView(button("Elegir de la agenda", false) { beginImport(i) })
        }
        editor.addView(button("Guardar contactos") { saveContactForm() }.apply { tag = "save_contacts" })
        editor.addView(button("Cancelar edición", false) { contactDraft = null; editingContacts = false; hideKeyboard(); render() })
    }
    private fun settings(body: LinearLayout) {
        heading(body, when { isCaregiver -> "VISTA DE CUIDADOR"; isCameraRole -> "DISPOSITIVO DE CÁMARA"; else -> "PERSONALIZA TU PROTECCIÓN" }, "Ajustes", "Configura tu rol, privacidad y preferencias de la app.")
        val roleCard = card(body, mint)
        roleCard.addView(text("ROL DE ESTA APP", 13, true, accent))
        roleCard.addView(text(when { isCaregiver -> "Cuidador o familiar"; isCameraRole -> "Cámara independiente"; else -> "Persona protegida" }, 20, true))
        roleCard.addView(text("El rol decide qué acciones y datos aparecen en las pestañas principales.", 15, color = muted))
        roleCard.addView(button("Cambiar tipo de cuenta", false) { tab = ROLE_TAB; render() })

        val deviceAccess = card(body, softSurface)
        deviceAccess.addView(text("Dispositivos y vinculaciones", 20, true))
        deviceAccess.addView(text("Pulsera, cámara independiente y teléfono del cuidador/a.", 15, color = muted))
        deviceAccess.addView(button("Abrir conexiones  →") { openDeviceLinkScreen() })

        val account = card(body, softSurface)
        account.addView(text("Cuenta y sincronización", 20, true))
        val session = sessions.read()
        val syncState = store.syncState()
        account.addView(text(
            when {
                session != null -> "Sesión: ${session.email} · ${store.prefs.getString("sync_status", "Pendiente de sincronización").orEmpty()} · ${syncState.pending} pendiente(s), ${syncState.failed} con reintentos agotados"
                store.prefs.getBoolean("local_only_mode", false) -> "Modo local. Los datos permanecen en este teléfono."
                else -> "Sin sesión. Inicia sesión para sincronizar con Supabase."
            }, 14, color = muted,
        ))
        if (session == null) account.addView(button("Iniciar sesión o crear cuenta") { authMode = AuthMode.LOGIN; render() })
        else {
            val synced = store.prefs.getLong("last_sync_success_at", 0L).takeIf { it > 0L }
                ?.let { SimpleDateFormat("d MMM, HH:mm", Locale("es", "MX")).format(Date(it)) } ?: "Aún no sincronizado"
            account.addView(text("Última sincronización correcta: $synced", 14, color = muted))
            account.addView(button("Sincronizar ahora", false) { store.retryFailedSyncItems(); render() })
            if (!isCaregiver && !isCameraRole) account.addView(button("▦  Crear QR o código para cuidador/a") { showCreateCaregiverInvite(account) })
            account.addView(button("Cerrar sesión", false) {
                io.execute {
                    runCatching { backend.signOut() }
                    runOnUiThread {
                        caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
                        caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
                        store.prefs.edit().putBoolean("local_only_mode", true).apply(); render()
                    }
                }
            })
            account.addView(button("Eliminar mi cuenta y datos remotos", false) {
                AlertDialog.Builder(this).setTitle("Eliminar cuenta de Supabase")
                    .setMessage("Se eliminará la cuenta y los datos remotos asociados. Los contactos y registros locales de este teléfono se conservarán para que las alertas locales sigan disponibles. Esta acción no se puede deshacer.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar cuenta") { _, _ ->
                        io.execute {
                            val result = runCatching { backend.deleteAccount() }
                            runOnUiThread {
                                result.onSuccess {
                                    caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
                                    caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
                                    store.prefs.edit().putBoolean("local_only_mode", true).remove("caregiver_link_id")
                                        .remove("caregiver_owner_id").remove("caregiver_device_id").remove("caregiver_link_device_id")
                                        .remove("caregiver_location_device_id").remove("caregiver_shared_phone_motion").apply()
                                    toast("Cuenta eliminada. La app sigue en modo local."); render()
                                }.onFailure { toast(it.message ?: "No se pudo eliminar la cuenta.", true) }
                            }
                        }
                    }.show()
            })
        }

        val preferences = card(body)
        preferences.addView(text("Preferencias", 20, true))
        preferences.addView(switch("Modo oscuro", dark) { value ->
            store.prefs.edit().putBoolean("dark_mode", value).commit(); saveDrafts(); recreate()
        })
        preferences.addView(text("Las alertas usan SMS y llamadas del teléfono. La app no puede confirmar que otra persona haya respondido.", 15, color = muted))

        if (!isCaregiver && !isCameraRole) {
            val location = card(body)
            location.addView(text("Ubicación en tiempo real", 20, true))
            location.addView(text("Con tu autorización, el teléfono protegido actualiza la posición mientras el monitoreo está activo. La ubicación sigue siendo local y se adjunta a los avisos SMS.", 15, color = muted))
            location.addView(switch("Compartir ubicación en vivo durante el monitoreo", store.prefs.getBoolean("include_location", false)) { value -> setLocationConsent(value) })
            location.addView(text(store.locationText(), 15, color = muted))
            location.addView(button("Actualizar ubicación ahora", false) { requestLocation() })

            val localData = card(body, softSurface)
            val summary = store.telemetry.summary()
            localData.addView(text("Pulsera y datos del teléfono", 20, true))
            localData.addView(text(
                "Lecturas locales: ${summary.arduino}. Signos vitales sincronizados con cuidador.",
                14,
                color = muted,
            ))
            addBraceletConnectionControls(localData, includeMethodOptions = true)

            val alerts = card(body)
            alerts.addView(text("Detección y alertas", 20, true))
            alerts.addView(text("Después de detectar una secuencia de movimiento compatible con una caída, inicia una cuenta regresiva de 20 segundos. Puedes cancelarla con “Estoy bien”.", 15, color = muted))
            alerts.addView(text("La detección es una heurística del movimiento del teléfono y puede omitir caídas suaves o producir falsas alarmas.", 15, color = muted))
            alerts.addView(button("Probar cuenta regresiva", false) {
                AlertDialog.Builder(this).setTitle("Prueba de 20 segundos")
                    .setMessage("Si no cancelas la prueba, se solicitarán SMS reales y se intentarán llamadas a tus contactos.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Iniciar prueba") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_TEST) }.show()
            })

            val sms = card(body)
            sms.addView(text("SMS y llamadas", 20, true))
            sms.addView(text("Los SMS se envían directamente por la SIM configurada. Las llamadas se realizan en orden de prioridad.", 15, color = muted))
            sms.addView(button("Configurar SIM para SMS", false) { configureSim() })
            sms.addView(button("Permisos de Android", false) { openAppSettings() })
            if (Build.VERSION.SDK_INT >= 34) sms.addView(button("Permitir alertas a pantalla completa", false) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
                    .onFailure { openAppSettings() }
            })
            permissionCard(sms)
        } else {
            val connection = card(body, softSurface)
            connection.addView(text("Vinculación de una persona", 20, true))
            connection.addView(text("No hay backend configurado. La ubicación, cámara, alertas y lecturas de otro teléfono todavía no se reciben aquí.", 15, color = muted))
            connection.addView(button("Ver requisitos de conexión  →", false) { selectTab(2) })
        }

        val profile = card(body)
        profile.addView(text("Mis datos", 20, true))
        profile.addView(text("Consulta o actualiza tu información personal y tu contacto de emergencia.", 15, color = muted))
        profile.addView(button("Abrir mi perfil  →", false) { openProfile() })
        body.addView(text("TzindaApp 2.1.5 · Android nativo", 14, color = muted).apply { setPadding(dp(4), dp(18), dp(4), 0) })
    }
    private fun cameraScreen(body: LinearLayout) {
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Vista en vivo de la cámara"
        }
        cameraPreviewView = preview
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val overlay = PoseOverlayView(this)
        cameraOverlayView = overlay
        previewFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        cameraPlaceholder = text("Cámara apagada\nPulsa “Iniciar cámara” cuando quieras usarla", 17, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = rounded(0x99000000.toInt(), 18)
        }
        previewFrame.addView(cameraPlaceholder, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val close = TextView(this).apply {
            text = "‹  Cerrar"
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(0xB3000000.toInt(), 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { selectTab(cameraReturnTab.coerceIn(0, 3)) }
            contentDescription = "Cerrar cámara"
        }
        previewFrame.addView(close, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(16); topMargin = dp(18)
        })
        cameraStatusTitle = text("Cámara apagada", 14, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = rounded(0xB3000000.toInt(), 24)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        previewFrame.addView(cameraStatusTitle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(16); topMargin = dp(18)
        })
        val screenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        val previewHeight = dp((screenDp - 68).coerceAtLeast(260))
        body.addView(previewFrame, LinearLayout.LayoutParams(-1, previewHeight))

        cameraActionButton = button("Iniciar cámara") { toggleCameraDetection() }.apply {
            tag = "camera_ai_toggle"
            minHeight = dp(58)
            layoutParams = LinearLayout.LayoutParams(-1, dp(64))
        }.also { body.addView(it) }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            cameraStatusTitle?.text = "Cámara no disponible"
            cameraActionButton?.isEnabled = false
        }
    }
    private fun toggleCameraDetection() {
        if (cameraRequested) {
            stopCameraDetection()
            store.event("Detección con cámara IA detenida por el usuario")
            return
        }
        cameraFallHandled = false
        cameraRequested = true
        if (!has(Manifest.permission.CAMERA)) {
            cameraActionButton?.text = "Esperando permiso de cámara…"
            cameraStatusTitle?.text = "Permiso de cámara"
            cameraStatusDetail?.text = "Android te preguntará si permites usar la cámara."
            try { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA) }
            catch (_: RuntimeException) {
                cameraRequested = false
                cameraActionButton?.text = "Iniciar cámara"
                cameraStatusTitle?.text = "No se pudo solicitar el permiso"
                cameraStatusDetail?.text = "Abre Ajustes de Android > Aplicaciones > TzindaApp > Permisos > Cámara."
            }
            return
        }
        launchCameraDetection()
    }
    private fun launchCameraDetection() {
        if (tab != CAMERA_TAB || !cameraRequested || !has(Manifest.permission.CAMERA)) return
        if (!cameraSessionLogged) {
            store.event("Cámara de apoyo iniciada")
            cameraSessionLogged = true
        }
        cameraPlaceholder?.visibility = View.GONE
        cameraActionButton?.text = "Detener cámara"
        if (cameraController == null) {
            val preview = cameraPreviewView ?: return
            val overlay = cameraOverlayView ?: return
            cameraController = CameraFallController(
                activity = this,
                previewView = preview,
                overlay = overlay,
                onStatus = { title, _, running ->
                    cameraStatusTitle?.text = when {
                        title.contains("caída", ignoreCase = true) -> "Posible caída"
                        title.contains("prepar", ignoreCase = true) -> "Preparando cámara"
                        running -> "Cámara activa"
                        else -> "Cámara detenida"
                    }
                    cameraPlaceholder?.visibility = if (running) View.GONE else View.VISIBLE
                    cameraActionButton?.text = if (cameraRequested) "Detener cámara" else "Iniciar cámara"
                },
                onFall = {
                    if (!cameraFallHandled) {
                        cameraFallHandled = true
                        cameraRequested = false
                        cameraController?.stop()
                        cameraPlaceholder?.visibility = View.VISIBLE
                        cameraStatusTitle?.text = "Posible caída detectada"
                        cameraActionButton?.text = "Iniciar cámara"
                        store.event("Detección con cámara IA señaló una posible caída")
                        if (isCameraRole) {
                            toast("Aviso de caída registrado.")
                        } else prepareAlert(GuardianMonitorService.ACTION_TEST)
                    }
                },
            )
        }
        cameraController?.start()
    }
    private fun stopCameraDetection() {
        cameraRequested = false
        cameraSessionLogged = false
        cameraController?.stop()
        cameraPlaceholder?.visibility = View.VISIBLE
        cameraActionButton?.text = "Iniciar cámara"
        if (cameraStatusTitle?.text?.toString() != "Posible caída detectada") {
            cameraStatusTitle?.text = "Cámara detenida"
            cameraStatusDetail?.text = "La cámara se apagó."
        }
    }
    private fun disposeCameraController() {
        cameraRequested = false
        cameraSessionLogged = false
        cameraController?.shutdown()
        cameraController = null
        cameraPreviewView = null
        cameraOverlayView = null
        cameraPlaceholder = null
        cameraStatusTitle = null
        cameraStatusDetail = null
        cameraActionButton = null
    }
    private fun saveContactForm() {
        try {
            val rows = contactEdits.map { TrustedContact(it.first.text.toString(), it.second.text.toString()) }
            val saved = store.saveContacts(rows)
            contactDraft = saved
            editingContacts = false
            hideKeyboard()
            toast("${saved.size} contacto(s) guardado(s) correctamente")
            updateServiceIfActive()
            render()
        } catch (e: Exception) { toast(e.message ?: "No se pudieron guardar los contactos.", true) }
    }
    private fun profile(body: LinearLayout) {
        heading(body, "INFORMACIÓN PERSONAL", "Mi perfil", "Guarda tus datos y contacto de emergencia en este teléfono.")
        val p = profileDraft ?: store.profile()
        var group = card(body)
        group.addView(text("Datos personales", 20, true))
        listOf("fullName" to "Nombre completo", "phoneNumber" to "Teléfono personal (opcional)", "address" to "Domicilio (opcional)").forEach { (key, label) ->
            profileEdits[key] = field(group, label, p.text(key),
                if (key == "phoneNumber") InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, key)
        }
        group = card(body)
        group.addView(text("Contacto de emergencia", 20, true))
        group.addView(text("Este número se agrega al protocolo de aviso y tiene prioridad.", 15, color = muted))
        listOf("emergencyContactName" to "Nombre del contacto", "emergencyContactPhone" to "Teléfono de emergencia").forEach { (key, label) ->
            profileEdits[key] = field(group, label, p.text(key),
                if (key.contains("Phone")) InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, key)
        }
        group = card(body)
        group.addView(text("Salud y monitoreo", 20, true))
        listOf("fallHistory" to "Antecedentes de caídas (opcional)", "allergies" to "Alergias (opcional)",
            "medicalConditions" to "Condiciones médicas (opcional)", "monitoringDevice" to "Dispositivo de monitoreo (opcional)").forEach { (key, label) ->
            profileEdits[key] = field(group, label, p.text(key), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, key)
        }
        group = card(body)
        group.addView(text("Fecha de nacimiento y privacidad", 20, true))
        birthDate = p.text("dateOfBirth").take(10)
        birthLabel = text(birthDescription(), 15).also { group.addView(it) }
        group.addView(button("Elegir fecha de nacimiento", false) {
            val calendar = Calendar.getInstance()
            runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(birthDate) }.getOrNull()?.let { calendar.time = it }
            DatePickerDialog(this, { _, year, month, day ->
                birthDate = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)
                birthLabel?.text = birthDescription()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis(); show()
            }
        })
        consentData = switch("Autorizo guardar estos datos en el teléfono", p.optBoolean("dataConsent")) {}.also { group.addView(it) }
        consentLocation = switch("Autorizo compartir mi ubicación en las alertas", p.optBoolean("locationConsent")) {}.also { group.addView(it) }
        group.addView(button("Guardar perfil") {
            try {
                val profile = collectProfile()
                for (key in listOf("phoneNumber", "emergencyContactPhone")) {
                    val raw = profile.optString(key)
                    if (raw.isNotBlank()) profile.put(key, PhoneNumbers.normalize(raw) ?: throw IllegalArgumentException("Escribe un teléfono válido de 7 a 15 dígitos."))
                }
                if (profile.optString("emergencyContactName").isNotBlank() && profile.optString("emergencyContactPhone").isBlank())
                    throw IllegalArgumentException("Completa el teléfono del contacto de emergencia.")
                store.saveProfile(profile); profileDraft = null; hideKeyboard(); toast("Perfil guardado correctamente")
                updateServiceIfActive(); render()
                if (profile.optBoolean("locationConsent") && !hasLocation()) requestLocation()
            } catch (e: Exception) { toast(e.message ?: "No se pudo guardar el perfil.", true) }
        }.apply { tag = "save_profile" })
        if (store.profile().length() > 0) group.addView(button("Eliminar perfil", false) {
            AlertDialog.Builder(this).setTitle("Eliminar perfil")
                .setMessage("Se eliminarán los datos del perfil y su contacto de emergencia. Los contactos de confianza se conservarán.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ ->
                    store.prefs.edit().remove("profile").putBoolean("include_location", false).commit()
                    profileDraft = null; profileReturnTab = 0; updateServiceIfActive(); selectTab(0)
                }.show()
        })
    }
    private fun collectProfile() = JSONObject().apply {
        profileEdits.forEach { (key, field) -> put(key, field.text.toString().trim()) }
        put("dateOfBirth", birthDate); put("dataConsent", consentData?.isChecked == true)
        put("locationConsent", consentLocation?.isChecked == true)
    }
    private fun birthDescription(): String {
        if (birthDate.isBlank()) return "Fecha de nacimiento: sin configurar"
        val parsed = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(birthDate) }.getOrNull()
            ?: return "Fecha de nacimiento: $birthDate"
        val birth = Calendar.getInstance().apply { time = parsed }
        val now = Calendar.getInstance()
        var age = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        if (now.get(Calendar.MONTH) < birth.get(Calendar.MONTH) || (now.get(Calendar.MONTH) == birth.get(Calendar.MONTH) && now.get(Calendar.DAY_OF_MONTH) < birth.get(Calendar.DAY_OF_MONTH))) age--
        return "Fecha de nacimiento: $birthDate · ${age.coerceAtLeast(0)} años"
    }
    private fun offerContacts() {
        if (isFinishing || isDestroyed) return
        store.prefs.edit().putBoolean("contacts_offered", true).apply()
        if (store.contacts().size >= 5) return
        AlertDialog.Builder(this).setTitle("Configura tus contactos de confianza")
            .setMessage("Permite leer tu agenda para elegir hasta cinco contactos de confianza. Solo se guardarán los números que selecciones. Puedes escribirlos manualmente desde Contactos.")
            .setNegativeButton("Más tarde", null).setPositiveButton("Elegir contactos") { _, _ -> editingContacts = true; selectTab(2); handler.post { beginImport(-1) } }.show()
    }
    private fun beginImport(slot: Int) {
        if (readingContacts) return
        importSlot = slot
        if (!has(Manifest.permission.READ_CONTACTS)) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
        else readContacts(slot)
    }
    private fun readContacts(slot: Int) {
        val existingRows = contactEdits.map { TrustedContact(it.first.text.toString(), it.second.text.toString()) }
        val free = existingRows.count { it.name.isBlank() && it.phone.isBlank() }
        val limit = if (slot >= 0) 1 else free
        if (limit <= 0) { toast("Ya tienes cinco filas ocupadas. Usa el botón de una fila para reemplazarla.", true); return }
        val excluded = existingRows.mapIndexedNotNull { i, c -> if (i == slot) null else PhoneNumbers.normalize(c.phone)?.let { PhoneNumbers.key(it) } }.toSet()
        readingContacts = true
        toast("Leyendo contactos…")
        io.execute {
            val result = runCatching { ContactsRepository.read(contentResolver, excluded) }
            runOnUiThread {
                readingContacts = false
                if (isFinishing || isDestroyed || tab != 2 || contactEdits.size != 5) return@runOnUiThread
                result.onSuccess { if (it.isEmpty()) toast("No hay contactos nuevos con teléfono válido. Puedes escribirlos manualmente.", true) else contactPicker(it, limit, slot) }
                    .onFailure { toast("No se pudo leer la agenda. Revisa el permiso o escribe los contactos manualmente.", true) }
            }
        }
    }
    private fun contactPicker(entries: List<TrustedContact>, limit: Int, slot: Int) {
        val group = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        val search = EditText(this).apply { hint = "Buscar nombre o teléfono"; setSingleLine(true) }
        val count = text("0 de $limit seleccionados", 14)
        val list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        val selected = linkedMapOf<String, TrustedContact>()
        var visible = entries
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        list.adapter = adapter
        fun filter(query: String) {
            visible = entries.filter { "${it.name} ${it.phone}".contains(query, ignoreCase = true) }
            adapter.clear(); adapter.addAll(visible.map { "${it.name.ifBlank { "Sin nombre" }}\n${it.phone}" })
            list.clearChoices(); visible.forEachIndexed { i, c -> list.setItemChecked(i, selected.containsKey(PhoneNumbers.key(c.phone))) }
        }
        filter("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val c = visible[position]; val key = PhoneNumbers.key(c.phone)
            if (selected.containsKey(key)) selected.remove(key)
            else if (selected.size < limit) selected[key] = c
            else { list.setItemChecked(position, false); toast("Puedes elegir hasta $limit contacto(s).") }
            count.text = "${selected.size} de $limit seleccionados · el orden define la prioridad"
        }
        group.addView(search); group.addView(count); group.addView(list, LinearLayout.LayoutParams(-1, dp(340)))
        val dialog = AlertDialog.Builder(this).setTitle("Elegir contactos de confianza").setView(group)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar selección", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (selected.isEmpty()) { toast("Selecciona al menos un contacto."); return@setOnClickListener }
            val destinations = if (slot >= 0) listOf(slot) else contactEdits.indices.filter { contactEdits[it].first.text.isBlank() && contactEdits[it].second.text.isBlank() }
            selected.values.zip(destinations).forEach { (c, i) -> contactEdits[i].first.setText(c.name); contactEdits[i].second.setText(c.phone) }
            dialog.dismiss(); saveContactForm()
        } }
        dialog.show()
    }
    private fun prepareAlert(action: String) {
        if (smsRequestInFlight || resumeAlertSetup) return
        if (store.recipients().isEmpty()) { toast("Guarda al menos un contacto de confianza o de emergencia.", true); editingContacts = true; selectTab(2); return }
        pendingAction = action
        if (smsPermissionState() != SmsPermissionPolicy.State.GRANTED) { requestSmsPermission(REQUEST_ALERT_SMS); return }
        requestRemainingAlertPermissions(action)
    }
    private fun requestRemainingAlertPermissions(action: String) {
        val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        permissions.add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33 && action != GuardianMonitorService.ACTION_ALERT_NOW) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (action == GuardianMonitorService.ACTION_START && store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE) {
            if (Build.VERSION.SDK_INT >= 31) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Android 6–11 requires location permission to scan BLE devices.
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (store.prefs.getBoolean("include_location", false) && !hasLocation()) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION); permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filterNot { has(it) }.distinct().toTypedArray()
        if (missing.isNotEmpty()) {
            try { requestPermissions(missing, REQUEST_ALERT) }
            catch (_: RuntimeException) {
                pendingAction = null
                toast("Android no pudo abrir los permisos de las alertas. Puedes volver a intentarlo.", true)
            }
        }
        else completeAlertSetup(action)
    }
    private fun completeAlertSetup(action: String) {
        pendingAction = null
        if (smsPermissionState() != SmsPermissionPolicy.State.GRANTED) { showSmsHelp(); return }
        if (!has(Manifest.permission.CALL_PHONE)) { permissionHelp("Permite realizar llamadas para activar las alertas automáticas con SMS y llamada."); return }
        if (Build.VERSION.SDK_INT >= 33 && action != GuardianMonitorService.ACTION_ALERT_NOW && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            permissionHelp("Permite las notificaciones para ver la cuenta regresiva y cancelar la alerta."); return
        }
        if (store.prefs.getBoolean("include_location", false) && !hasLocation()) toast("El envío funcionará sin ubicación hasta que concedas ese permiso.", true)
        service(action)
        handler.postDelayed({ refreshLive() }, 400)
    }
    private fun service(action: String) {
        try {
            val intent = Intent(this, GuardianMonitorService::class.java).setAction(action).putExtra("user_visible", true)
            if (action == GuardianMonitorService.ACTION_STOP || action == GuardianMonitorService.ACTION_CANCEL ||
                action == GuardianMonitorService.ACTION_BRACELET_DISCONNECT) startService(intent)
            else if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { toast("Android no permitió iniciar la acción: ${e.javaClass.simpleName}", true) }
    }
    private fun updateServiceIfActive() { if (GuardianMonitorService.instance != null) service(GuardianMonitorService.ACTION_UPDATE) }
    private fun configureSim() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_SIM); return }
        try {
            val sims = (getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
            if (sims.isEmpty()) { toast("No hay SIM activas. Los SMS requieren una SIM con servicio.", true); return }
            val labels = arrayOf("Automática: predeterminada o primera SIM") + sims.map { "SIM ${it.simSlotIndex + 1} · ${it.displayName}" }
            val selected = sims.indexOfFirst { it.subscriptionId == store.prefs.getInt("sms_subscription", -1) } + 1
            AlertDialog.Builder(this).setTitle("SIM para envío automático").setSingleChoiceItems(labels, selected) { dialog, which ->
                store.prefs.edit().putInt("sms_subscription", if (which == 0) -1 else sims[which - 1].subscriptionId).commit()
                dialog.dismiss(); toast("SIM configurada para SMS automáticos")
            }.setNegativeButton("Cancelar", null).show()
        } catch (_: Exception) { toast("No se pudieron consultar las SIM. Configura una SIM predeterminada para SMS en Android.", true) }
    }
    private fun setLocationConsent(value: Boolean) {
        store.prefs.edit().putBoolean("include_location", value).commit()
        val profile = store.profile()
        if (profile.length() > 0) {
            profile.put("locationConsent", value)
            store.prefs.edit().putString("profile", profile.toString()).apply()
        }
        if (value) requestLocation() else {
            stopLocation()
            updateServiceIfActive()
            io.execute {
                runCatching {
                    if (backend.isConfigured && backend.currentSession() != null) backend.deleteCaregiverLocation(store.localDeviceId())
                }
            }
        }
    }
    private fun requestLocation() {
        if (!store.prefs.getBoolean("include_location", false)) {
            AlertDialog.Builder(this).setTitle("Compartir ubicación")
                .setMessage("¿Quieres incluir tu ubicación disponible en los SMS de ayuda?")
                .setNegativeButton("Cancelar", null).setPositiveButton("Permitir") { _, _ ->
                    store.prefs.edit().putBoolean("include_location", true).commit(); requestLocation()
                }.show(); return
        }
        if (!hasLocation()) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION); return }
        fetchLocation(); updateServiceIfActive()
    }
    @Suppress("DEPRECATION")
    private fun fetchLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = manager.getProviders(true).filter { it == LocationManager.NETWORK_PROVIDER || it == LocationManager.GPS_PROVIDER }
            if (providers.isEmpty()) { toast("Activa la ubicación del teléfono.", true); return }
            manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { saveLocation(it) }
            stopLocation()
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) { saveLocation(location); stopLocation(); toast("Ubicación actualizada"); if (tab == 0) render() }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            manager.requestSingleUpdate(providers.first(), locationListener!!, Looper.getMainLooper())
            toast("Buscando ubicación…")
            handler.postDelayed(locationTimeout, 15_000)
        } catch (_: Exception) { toast("Ubicación no disponible. Los SMS siguen disponibles.", true) }
    }
    private fun saveLocation(location: Location) { store.prefs.edit().putString("latitude", location.latitude.toString()).putString("longitude", location.longitude.toString()).putLong("location_time", location.time).apply() }
    private fun stopLocation() { handler.removeCallbacks(locationTimeout); locationListener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }; locationListener = null }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS_ONLY || requestCode == REQUEST_ALERT_SMS) {
            smsRequestInFlight = false
            // Android can return empty arrays when a request is interrupted. This is not a denial.
            if (grantResults.isNotEmpty() && permissions.contains(Manifest.permission.SEND_SMS)) {
                smsRequestedInSession = !has(Manifest.permission.SEND_SMS)
                runCatching { File(noBackupFilesDir, "sms-permission-requested").writeText(if (smsRequestedInSession) "requested" else "granted") }
            }
            refreshPermissionUi()
            if (!has(Manifest.permission.SEND_SMS)) {
                pendingAction = null
                resumeAlertSetup = false
                toast(if (grantResults.isEmpty()) "Solicitud cancelada. Puedes volver a pulsar Permitir SMS." else "SMS no autorizado. Puedes seguir usando la app y habilitarlo más adelante.", true)
                return
            }
        }
        if (isFinishing || isDestroyed) return
        when (requestCode) {
            REQUEST_BRACELET_BLE -> {
                val action = pendingBraceletAction
                pendingBraceletAction = null
                if (action != null && requiredBraceletBlePermissions().all(::has)) requestBraceletService(action)
                else if (action != null) toast("Permite dispositivos cercanos para conectar la pulsera por Bluetooth.", true)
            }
            REQUEST_CAMERA -> {
                if (has(Manifest.permission.CAMERA)) {
                    cameraRequested = true
                    launchCameraDetection()
                } else {
                    cameraRequested = false
                    cameraPlaceholder?.visibility = View.VISIBLE
                    cameraActionButton?.text = "Iniciar detección con IA"
                    cameraStatusTitle?.text = "Permiso no concedido"
                    cameraStatusDetail?.text = "Puedes seguir usando los sensores del teléfono. Autoriza Cámara en Ajustes si quieres probar el modelo."
                    if (grantResults.isNotEmpty()) toast("Sin permiso de cámara. La detección del teléfono sigue disponible.", true)
                }
            }
            REQUEST_CONTACTS -> if (has(Manifest.permission.READ_CONTACTS)) readContacts(importSlot) else permissionHelp("Sin permiso de agenda. Puedes escribir los números manualmente.")
            REQUEST_SMS_ONLY -> {
                refreshPermissionUi()
                if (has(Manifest.permission.SEND_SMS)) {
                    toast("Permiso de SMS habilitado")
                }
            }
            REQUEST_ALERT_SMS -> {
                refreshPermissionUi()
                val action = pendingAction
                if (has(Manifest.permission.SEND_SMS)) {
                    if (action != null) {
                        resumeAlertSetup = true
                        // Wait until PermissionController returns focus before opening another request.
                        if (hasWindowFocus()) onWindowFocusChanged(true)
                    } else toast("Permiso de SMS habilitado")
                }
            }
            REQUEST_ALERT -> pendingAction?.let { completeAlertSetup(it) }
            REQUEST_LOCATION -> if (hasLocation()) { fetchLocation(); updateServiceIfActive() } else toast("Sin ubicación. Los SMS se enviarán sin coordenadas.", true)
            REQUEST_SIM -> if (has(Manifest.permission.READ_PHONE_STATE)) configureSim() else permissionHelp("Configura una SIM predeterminada para SMS en Android.")
            REQUEST_CALLS -> if (!has(Manifest.permission.CALL_PHONE)) toast("Las llamadas no están autorizadas. El envío por SMS sigue disponible.", true)
        }
    }
    private fun smsPermissionState(): SmsPermissionPolicy.State {
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        return SmsPermissionPolicy.evaluate(
            packageManager.hasSystemFeature(feature), has(Manifest.permission.SEND_SMS),
            runCatching { packageManager.isPermissionRevokedByPolicy(Manifest.permission.SEND_SMS, packageName) }.getOrDefault(false),
            smsRequestedInSession || runCatching { File(noBackupFilesDir, "sms-permission-requested").readText() == "requested" }.getOrDefault(false),
            shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS))
    }
    private fun permissionCard(parent: LinearLayout) {
        val panel = card(parent, mint)
        panel.addView(text("Permiso de SMS", 18, true))
        smsPermissionStatus = text("", 14, color = muted).apply { tag = "sms_permission_status" }.also { panel.addView(it) }
        smsPermissionButton = button("Permitir SMS") { requestSmsPermission(REQUEST_SMS_ONLY) }
            .apply { tag = "enable_sms" }.also { panel.addView(it) }
    }
    private fun refreshPermissionUi() {
        if (!::store.isInitialized) return
        if (has(Manifest.permission.SEND_SMS)) {
            smsRequestedInSession = false
            val marker = File(noBackupFilesDir, "sms-permission-requested")
            runCatching { if (!marker.isFile || marker.readText() != "granted") marker.writeText("granted") }
        }
        val state = smsPermissionState()
        smsPermissionStatus?.text = when (state) {
            SmsPermissionPolicy.State.GRANTED -> "SMS habilitado en este dispositivo. No necesitas volver a concederlo mientras Android lo conserve."
            SmsPermissionPolicy.State.UNSUPPORTED -> "Este dispositivo no dispone de telefonía compatible con SMS."
            SmsPermissionPolicy.State.POLICY_BLOCKED -> "SMS bloqueado por una política de administración del dispositivo."
            SmsPermissionPolicy.State.SETTINGS_REQUIRED -> "SMS pendiente: Android no vuelve a mostrar la solicitud. Revisa cómo habilitarlo."
            SmsPermissionPolicy.State.REQUESTABLE -> "Permite enviar alertas por tu SIM. La app mostrará la solicitud de Android; basta con elegir Permitir."
        }
        smsPermissionButton?.apply {
            isEnabled = !smsRequestInFlight && state != SmsPermissionPolicy.State.GRANTED && state != SmsPermissionPolicy.State.UNSUPPORTED
            alpha = if (isEnabled) 1f else .65f
            text = if (smsRequestInFlight) "Esperando respuesta de Android…" else when (state) {
                SmsPermissionPolicy.State.GRANTED -> "SMS habilitado"
                SmsPermissionPolicy.State.UNSUPPORTED -> "SMS no disponible"
                SmsPermissionPolicy.State.POLICY_BLOCKED, SmsPermissionPolicy.State.SETTINGS_REQUIRED -> "Cómo habilitar SMS"
                SmsPermissionPolicy.State.REQUESTABLE -> "Permitir SMS"
            }
        }
    }
    private fun requestSmsPermission(requestCode: Int) {
        if (smsRequestInFlight || isFinishing || isDestroyed) return
        when (smsPermissionState()) {
            SmsPermissionPolicy.State.GRANTED -> {
                refreshPermissionUi()
                if (requestCode == REQUEST_ALERT_SMS) pendingAction?.let { requestRemainingAlertPermissions(it) }
            }
            SmsPermissionPolicy.State.REQUESTABLE -> {
                smsRequestInFlight = true
                refreshPermissionUi()
                try {
                    requestPermissions(arrayOf(Manifest.permission.SEND_SMS), requestCode)
                } catch (_: RuntimeException) {
                    smsRequestInFlight = false
                    pendingAction = null
                    refreshPermissionUi()
                    toast("Android no pudo abrir la solicitud de SMS. Intenta de nuevo o revisa los permisos en Ajustes.", true)
                }
            }
            else -> { if (requestCode == REQUEST_ALERT_SMS) pendingAction = null; showSmsHelp() }
        }
    }
    private fun showSmsHelp() {
        if (isFinishing || isDestroyed || smsHelpDialog?.isShowing == true) return
        val message = when (smsPermissionState()) {
            SmsPermissionPolicy.State.UNSUPPORTED -> "El dispositivo no admite SMS por SIM. Puedes revisar la interfaz, pero las alertas por SMS necesitan un teléfono compatible con servicio móvil."
            SmsPermissionPolicy.State.POLICY_BLOCKED -> "La administración del dispositivo bloquea SMS. La app no puede cambiar esa política; debes consultarlo con quien administra el teléfono."
            SmsPermissionPolicy.State.SETTINGS_REQUIRED -> "Android no permite mostrar otra solicitud. En los ajustes de TzindaApp abre Permisos > SMS y selecciona Permitir. Si SMS no aparece o está bloqueado, el instalador del dispositivo puede estar restringiéndolo. Para tus dispositivos de prueba puedes usar la instalación por USB incluida con esta versión."
            else -> "Para activar el envío automático necesitas autorizar SMS. Pulsa Permitir SMS en Inicio y elige Permitir en la solicitud de Android."
        }
        smsHelpDialog = AlertDialog.Builder(this).setTitle("Habilitar SMS").setMessage(message)
            .setNegativeButton("Cerrar", null).setPositiveButton("Abrir ajustes") { _, _ -> openAppSettings() }
            .setNeutralButton("Pruebas por USB") { _, _ ->
                AlertDialog.Builder(this).setTitle("Instalación en dispositivos de prueba")
                    .setMessage("En tu computadora, extrae el paquete Pruebas USB de TzindaApp. Conecta el teléfono, habilita Depuración USB y autoriza la conexión. Ejecuta Instalar-TzindaApp-Pruebas.ps1: instala el APK y autoriza únicamente SMS mediante las herramientas de Android. No se envían mensajes durante la instalación. Esto no elimina políticas de administración ni agrega telefonía a un dispositivo que no la tiene.")
                    .setPositiveButton("Entendido", null).show()
            }.show()
    }
    private fun permissionHelp(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Permisos").setMessage(message).setNegativeButton("Cerrar", null)
            .setPositiveButton("Abrir ajustes") { _, _ -> openAppSettings() }.show()
    }
    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { toast("Abre Ajustes de Android > Aplicaciones > TzindaApp > Permisos.", true) }
    }
    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun hasLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun arduinoConnectionAvailable(): Boolean {
        val status = store.prefs.getString("arduino_status", "").orEmpty()
        return status.contains("conexión activa", ignoreCase = true) ||
            status.contains("conectada por Bluetooth", ignoreCase = true)
    }
    private fun hideKeyboard() { currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Int = 16, bold: Boolean = false, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(3), 0, dp(5))
        includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1f)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun card(parent: LinearLayout, color: Int = cardColor) = column().apply {
        background = rounded(color, 16).apply { setStroke(dp(1), outline) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(2) })
    }
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun button(label: String, primary: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; minHeight = dp(54)
        typeface = Typeface.create("sans-serif", Typeface.BOLD); stateListAnimator = null; elevation = 0f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(if (primary) onAccent else accent)
        val fill = if (primary) accent else softSurface
        val shape = rounded(fill, 28).apply { if (!primary) setStroke(dp(1), outline) }
        background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x3365A879 else 0x33254D3A), shape, null)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) }
        setOnClickListener { action() }
    }
    private fun field(parent: LinearLayout, label: String, value: String, input: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, tag: String? = null): EditText {
        parent.addView(text(label, 14, true, muted).apply { setPadding(0, dp(14), 0, dp(5)) })
        return EditText(this).apply {
        hint = when {
            input == InputType.TYPE_CLASS_PHONE -> "+52 55 1234 5678"
            tag == "fullName" -> "Tu nombre y apellidos"
            tag == "address" -> "Calle, número y ciudad"
            tag == "emergencyContactName" -> "Nombre y apellidos"
            tag?.startsWith("contact_name_") == true -> "Nombre del contacto"
            else -> "Escribe aquí"
        }
        setText(value); inputType = input; textSize = 17f; typeface = Typeface.create("sans-serif", Typeface.NORMAL); setTextColor(ink); setHintTextColor(muted)
        minHeight = dp(58); setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(if (dark) Color.parseColor("#131B16") else Color.WHITE, 14).apply { setStroke(dp(1), outline) }
        this.tag = tag; contentDescription = label
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
    }
    }
    private fun switch(label: String, value: Boolean, action: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 16f; setTextColor(ink); minHeight = dp(62); isChecked = value; switchPadding = dp(16)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, muted))
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, checked -> action(checked) }
    }
    private fun toast(message: String, long: Boolean = false) { Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    private inner class CountdownRing(context: Context) : View(context) {
        var progress: Float = 1f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(13).toFloat(); color = softSurface }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(13).toFloat(); color = critical }
        private val bounds = RectF()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = minOf(width, height).toFloat()
            val inset = dp(10).toFloat() + track.strokeWidth / 2f
            bounds.set((width - side) / 2f + inset, (height - side) / 2f + inset, (width + side) / 2f - inset, (height + side) / 2f - inset)
            canvas.drawArc(bounds, -90f, 360f, false, track)
            canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
        }
    }
    companion object {
        private const val CAMERA_TAB = 5
        private const val ROLE_TAB = 6
        private const val DEVICE_TAB = 7
        private const val MEDICATIONS_TAB = 8
        private const val REQUEST_CAMERA = 108
        private const val REQUEST_CONTACTS = 101
        private const val REQUEST_ALERT = 102
        private const val REQUEST_LOCATION = 103
        private const val REQUEST_SIM = 104
        private const val REQUEST_CALLS = 105
        private const val REQUEST_SMS_ONLY = 106
        private const val REQUEST_ALERT_SMS = 107
        private const val REQUEST_BRACELET_BLE = 111
    }
}
