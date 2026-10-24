package com.example.guardian_iot

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class NativeAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val names = listOf("tzinda_native", "FlutterSharedPreferences", "guardian_monitor_settings")
    private lateinit var backups: Map<String, Map<String, *>>
    private var activity: MainActivity? = null
    @Before fun isolateData() {
        assertFalse("Run on an idle test emulator", GuardianMonitorService.monitoring)
        backups = names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        names.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    @After fun restoreData() {
        context.stopService(Intent(context, GuardianMonitorService::class.java))
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        instrumentation.waitForIdleSync()
        backups.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, 0).edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
        }
    }
    @Test fun contactStorageSurvivesNewStoreAndRejectsInvalidReplacement() {
        val store = AppStore(context)
        val rows = (0..4).map { TrustedContact("Persona $it", "550000112$it") }
        store.saveContacts(rows)
        assertEquals(rows, AppStore(context).contacts())
        assertThrows(IllegalArgumentException::class.java) { store.saveContacts(listOf(TrustedContact("Inválido", "123"))) }
        assertEquals(rows, store.contacts())
    }
    @Test fun legacyFlutterContactsAndFullProfileAreMigratedOnce() {
        val contacts = JSONArray().put(JSONObject().put("name", "Contacto heredado").put("phone", "+525500001122"))
        val profile = JSONObject().put("fullName", "Persona heredada").put("allergies", "Dato de prueba")
            .put("emergencyContactName", "Emergencia").put("emergencyContactPhone", "5500002233")
            .put("dataConsent", true).put("locationConsent", true).put("dateOfBirth", "1950-05-10T00:00:00.000").put("phoneNumber", JSONObject.NULL)
        context.getSharedPreferences("FlutterSharedPreferences", 0).edit()
            .putString("flutter.trusted_contacts", contacts.toString()).putString("flutter.person_profile", profile.toString())
            .putBoolean("flutter.dark_mode", true).commit()
        val store = AppStore(context)
        assertEquals("Contacto heredado", store.contacts().first().name)
        assertEquals(profile.toString(), store.profile().toString())
        assertEquals("", store.profile().text("phoneNumber"))
        assertTrue(store.prefs.getBoolean("dark_mode", false))
        assertTrue(store.prefs.getBoolean("include_location", false))
        assertEquals("5500002233", store.recipients().first().phone)
        store.saveContacts(listOf(TrustedContact("Nuevo", "5500003344")))
        assertEquals("Nuevo", AppStore(context).contacts().first().name)
    }
    @Test fun saveFromNativeSettingsAndReopenActivity() {
        val store = AppStore(context)
        store.prefs.edit().putBoolean("contacts_offered", true).commit()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity!!.findViewById<Button>(R.id.nav_settings).performClick()
            activity!!.findViewById<android.view.View>(R.id.root).findViewWithTag<EditText>("contact_name_0").setText("Contacto prueba")
            activity!!.findViewById<android.view.View>(R.id.root).findViewWithTag<EditText>("contact_phone_0").setText("+52 55 0000 1122")
            activity!!.findViewById<android.view.View>(R.id.root).findViewWithTag<Button>("save_contacts").performClick()
        }
        assertEquals(listOf(TrustedContact("Contacto prueba", "+525500001122")), store.contacts())
        instrumentation.runOnMainSync { activity!!.finish() }
        instrumentation.waitForIdleSync()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity!!.findViewById<Button>(R.id.nav_settings).performClick()
            assertEquals("+525500001122", activity!!.findViewById<android.view.View>(R.id.root).findViewWithTag<EditText>("contact_phone_0").text.toString())
        }
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun realContactsProviderReturnsPhoneNumbersWithoutWritingFromApp() {
        val resolver = context.contentResolver
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        var raw: android.net.Uri? = null
        try {
            raw = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply { putNull("account_type"); putNull("account_name") })!!
            val id = ContentUris.parseId(raw)
            fun data(type: String, value: String) {
                resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, id); put(ContactsContract.Data.MIMETYPE, type)
                    put(ContactsContract.Data.DATA1, value)
                })
            }
            val name = "Tzinda prueba nativa 2026"
            data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, name)
            data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "55 0000 1122")
            data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+52 55 0000 2233")
            data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "123")
            val phones = ContactsRepository.read(resolver).filter { it.name == name }.map { it.phone }.toSet()
            assertEquals(setOf("5500001122", "+525500002233"), phones)
        } finally {
            raw?.let { resolver.delete(it.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null) }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
    @Test fun callbacksAreDeduplicatedAndHistoryPersists() {
        val store = AppStore(context)
        assertTrue(store.acceptCallback("incident|phone|0|sent"))
        assertFalse(store.acceptCallback("incident|phone|0|sent"))
        assertTrue(store.acceptCallback("incident|phone|0|delivered"))
        store.event("SMS de prueba confirmado", "incident")
        assertEquals("SMS de prueba confirmado", AppStore(context).events().getJSONObject(0).getString("message"))
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun countdownCanBeCancelledWithoutQueuingSms() {
        val store = AppStore(context)
        store.saveContacts(listOf(TrustedContact("Prueba cancelación", "5500001122")))
        store.prefs.edit().putBoolean("contacts_offered", true).putBoolean("calls_enabled", false).commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.SEND_SMS)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity!!.startForegroundService(Intent(context, GuardianMonitorService::class.java)
                .setAction(GuardianMonitorService.ACTION_TEST).putExtra("user_visible", true))
        }
        val until = android.os.SystemClock.elapsedRealtime() + 5_000
        while (store.prefs.getString("pending_id", "").isNullOrEmpty() && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(50)
        assertFalse(store.prefs.getString("pending_id", "").isNullOrEmpty())
        instrumentation.runOnMainSync { activity!!.startService(Intent(context, GuardianMonitorService::class.java).setAction(GuardianMonitorService.ACTION_CANCEL)) }
        instrumentation.waitForIdleSync()
        val cancelledUntil = android.os.SystemClock.elapsedRealtime() + 3_000
        while (!store.prefs.getString("pending_id", "").isNullOrEmpty() && android.os.SystemClock.elapsedRealtime() < cancelledUntil) android.os.SystemClock.sleep(50)
        assertTrue(store.prefs.getString("pending_id", "").isNullOrEmpty())
        val events = store.events()
        assertFalse((0 until events.length()).any { events.getJSONObject(it).optString("message").contains("SMS solicitado") || events.getJSONObject(it).optString("message").startsWith("Alerta:") })
    }
    private fun startInterceptedService(): GuardianMonitorService {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.SEND_SMS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CALL_PHONE)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_PHONE_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        AppStore(context).prefs.edit().putBoolean("contacts_offered", true).commit()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity!!.startForegroundService(Intent(context, GuardianMonitorService::class.java)
                .setAction(GuardianMonitorService.ACTION_UPDATE).putExtra("user_visible", true))
        }
        val until = android.os.SystemClock.elapsedRealtime() + 10_000
        while (GuardianMonitorService.instance == null && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(50)
        return checkNotNull(GuardianMonitorService.instance)
    }
    private fun injectDrop(service: GuardianMonitorService) {
        instrumentation.runOnMainSync {
            val now = android.os.SystemClock.elapsedRealtime()
            for (offset in 0L..100L step 20) service.acceptSensorSample(0.8, now + offset)
            service.acceptSensorSample(24.0, now + 140)
            for (offset in 160L..460L step 20) service.acceptSensorSample(9.81, now + offset)
        }
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun detectedDropWaitsTwentySecondsThenRequestsSmsAndCallWithoutSmsConfirmation() {
        val store = AppStore(context)
        val contacts = listOf(TrustedContact("Primero", "5500001122"), TrustedContact("Segundo", "5500002233"))
        store.saveContacts(contacts)
        // An old disabled-calls preference must not defeat the new mandatory fall protocol.
        store.prefs.edit().putBoolean("calls_enabled", false).commit()
        val service = startInterceptedService()
        val sms = java.util.concurrent.CopyOnWriteArrayList<String>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val firstAction = java.util.concurrent.atomic.AtomicLong(0)
        instrumentation.runOnMainSync {
            service.smsTransportOverride = SmsTransport { contact, _ ->
                firstAction.compareAndSet(0, android.os.SystemClock.elapsedRealtime())
                sms.add(contact.phone)
                if (contact == contacts[1]) throw IllegalStateException("simulated SMS failure")
                // Deliberately never deliver a sent callback for the first SMS.
            }
            service.callTransportOverride = { calls.add(it.phone) }
        }
        val started = android.os.SystemClock.elapsedRealtime()
        injectDrop(service)
        assertFalse(store.prefs.getString("pending_id", "").isNullOrEmpty())
        val wallLeft = store.prefs.getLong("pending_deadline", 0) - System.currentTimeMillis()
        assertTrue("A full 20-second cancellation window", wallLeft in 19_000..20_000)
        android.os.SystemClock.sleep(19_000)
        assertTrue(sms.isEmpty()); assertTrue(calls.isEmpty())
        val until = started + 25_000
        while ((calls.isEmpty() || sms.size < 2) && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(50)
        assertEquals(contacts.map { it.phone }, sms.toList())
        assertEquals(listOf(contacts[0].phone), calls.toList())
        assertTrue("Nothing may dispatch before the countdown", firstAction.get() - started >= 20_000)
        assertTrue("Do not wait another 30 seconds for SMS callbacks", android.os.SystemClock.elapsedRealtime() - started < 25_000)
        assertTrue(store.prefs.getString("pending_id", "").isNullOrEmpty())
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun cancellingDetectedDropPreventsBothActionsEvenAfterDeadline() {
        val store = AppStore(context)
        store.saveContacts(listOf(TrustedContact("Cancelación", "5500001122")))
        val service = startInterceptedService()
        val actions = java.util.concurrent.atomic.AtomicInteger(0)
        instrumentation.runOnMainSync {
            service.smsTransportOverride = SmsTransport { _, _ -> actions.incrementAndGet() }
            service.callTransportOverride = { actions.incrementAndGet() }
        }
        injectDrop(service)
        assertFalse(store.prefs.getString("pending_id", "").isNullOrEmpty())
        instrumentation.runOnMainSync {
            service.onStartCommand(Intent(context, GuardianMonitorService::class.java).setAction(GuardianMonitorService.ACTION_CANCEL), 0, 100)
        }
        android.os.SystemClock.sleep(21_000)
        assertEquals(0, actions.get())
        assertTrue(store.prefs.getString("pending_id", "").isNullOrEmpty())
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun expiredCountdownIsRecoveredWithoutWaitingAnotherTwentySeconds() {
        val store = AppStore(context)
        store.saveContacts(listOf(TrustedContact("Recuperación", "5500001122")))
        val service = startInterceptedService()
        val actions = java.util.concurrent.atomic.AtomicInteger(0)
        instrumentation.runOnMainSync {
            service.smsTransportOverride = SmsTransport { _, _ -> actions.incrementAndGet() }
            service.callTransportOverride = { actions.incrementAndGet() }
            store.prefs.edit().putString("pending_id", "interrupted-test")
                .putLong("pending_deadline", System.currentTimeMillis() - 1_000).commit()
            service.onStartCommand(null, 0, 101)
            service.onStartCommand(null, 0, 102)
        }
        assertEquals("Exactly one SMS and one call, no duplicate restart", 2, actions.get())
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun walkingAndLightHandSwingDoNotStartCountdownOrDispatch() {
        val store = AppStore(context)
        store.saveContacts(listOf(TrustedContact("Movimiento normal", "5500001122")))
        val service = startInterceptedService()
        val actions = java.util.concurrent.atomic.AtomicInteger(0)
        instrumentation.runOnMainSync {
            service.smsTransportOverride = SmsTransport { _, _ -> actions.incrementAndGet() }
            service.callTransportOverride = { actions.incrementAndGet() }
            val now = android.os.SystemClock.elapsedRealtime()
            for (i in 0..1_500) {
                val phase = i % 50
                val value = when {
                    phase <= 3 -> 0.8 // brief hand swing: only 60 ms
                    phase == 4 -> 18.0
                    i % 500 == 275 -> 31.0 // isolated step/bump spike
                    else -> 9.81 + 3 * kotlin.math.sin(i * .25)
                }
                service.acceptSensorSample(value, now + i * 20L)
            }
        }
        assertTrue(store.prefs.getString("pending_id", "").isNullOrEmpty())
        assertTrue(store.prefs.getString("dispatch_id", "").isNullOrEmpty())
        assertEquals(0, actions.get())
    }
    @Test @SdkSuppress(minSdkVersion = 29)
    fun smsPermissionCardShowsActualAndroidGrantAndDoesNotAskAgain() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.SEND_SMS)
        val store = AppStore(context)
        store.prefs.edit().putBoolean("contacts_offered", true).putBoolean("sms_permission_requested", false).commit()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity!!.findViewById<Button>(R.id.nav_settings).performClick()
            val root = activity!!.findViewById<android.view.View>(R.id.root)
            val status = root.findViewWithTag<android.widget.TextView>("sms_permission_status")
            val enable = root.findViewWithTag<Button>("enable_sms")
            assertTrue(status.text.toString().startsWith("SMS habilitado"))
            assertFalse(enable.isEnabled)
            assertEquals("SMS habilitado", enable.text.toString())
        }
        assertFalse("Grant status must come from Android, not a preference", store.prefs.getBoolean("sms_permission_requested", false))
    }
    @Test fun apkContainsNoFlutterRuntimeAndNoExportedSmsReceiver() {
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.contains("libflutter.so") || it.name.startsWith("assets/flutter_assets/") })
        }
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, SmsStatusReceiver::class.java), PackageManager.GET_META_DATA)
        assertFalse(receiver.exported)
    }
}
