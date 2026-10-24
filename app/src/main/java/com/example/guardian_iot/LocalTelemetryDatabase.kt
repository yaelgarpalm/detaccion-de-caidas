package com.example.guardian_iot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.UUID

/** On-device telemetry and durable outbox for background Supabase synchronization. */
class LocalTelemetryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private var writesSincePrune = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                event_type TEXT,
                accel_x REAL,
                accel_y REAL,
                accel_z REAL,
                heart_rate_bpm REAL,
                spo2_percent REAL,
                latitude REAL,
                longitude REAL,
                payload_json TEXT
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX readings_captured_at_idx ON readings(captured_at)")
        db.execSQL("CREATE INDEX readings_source_idx ON readings(source)")
        createSyncTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSyncTables(db)
    }

    @Synchronized
    fun recordMotion(x: Float, y: Float, z: Float, capturedAt: Long) {
        insert(
            ContentValues().apply {
                put("captured_at", capturedAt)
                put("source", SOURCE_PHONE_ACCELEROMETER)
                put("accel_x", x.toDouble())
                put("accel_y", y.toDouble())
                put("accel_z", z.toDouble())
            },
        )
    }

    /** Summarizes phone accelerometer samples since the last successful remote upload. */
    @Synchronized
    fun phoneMotionSummary(afterCapturedAt: Long): PhoneMotionSummary? {
        var count = 0
        var totalDynamicG = 0.0
        var peakDynamicG = 0.0
        var latestCapturedAt = 0L
        readableDatabase.rawQuery(
            "SELECT captured_at, accel_x, accel_y, accel_z FROM readings " +
                "WHERE source = ? AND captured_at > ? AND accel_x IS NOT NULL AND accel_y IS NOT NULL AND accel_z IS NOT NULL " +
                "ORDER BY captured_at ASC",
            arrayOf(SOURCE_PHONE_ACCELEROMETER, afterCapturedAt.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val magnitude = sqrt(
                    cursor.getDouble(1) * cursor.getDouble(1) +
                        cursor.getDouble(2) * cursor.getDouble(2) +
                        cursor.getDouble(3) * cursor.getDouble(3),
                )
                if (!magnitude.isFinite()) continue
                val dynamicG = abs(magnitude - STANDARD_GRAVITY) / STANDARD_GRAVITY
                totalDynamicG += dynamicG
                peakDynamicG = maxOf(peakDynamicG, dynamicG)
                latestCapturedAt = cursor.getLong(0)
                count++
            }
        }
        if (count == 0) return null
        return PhoneMotionSummary(count, totalDynamicG / count, peakDynamicG, latestCapturedAt)
    }

    @Synchronized
    fun recordLocation(latitude: Double, longitude: Double, capturedAt: Long) {
        insert(
            ContentValues().apply {
                put("captured_at", capturedAt)
                put("source", SOURCE_PHONE_LOCATION)
                put("latitude", latitude)
                put("longitude", longitude)
            },
        )
    }

    @Synchronized
    fun recordEvent(message: String, incident: String, capturedAt: Long = System.currentTimeMillis()) {
        val payload = JSONObject().put("message", message).put("incident", incident)
        insert(
            ContentValues().apply {
                put("captured_at", capturedAt)
                put("source", SOURCE_APP_EVENT)
                put("event_type", "app_event")
                put("payload_json", payload.toString())
            },
        )
    }

    @Synchronized
    fun queueFallEvent(event: LocalFallEvent) {
        writableDatabase.insertWithOnConflict(
            "sync_events",
            null,
            ContentValues().apply {
                put("id", event.id)
                put("incident_id", event.incidentId)
                put("device_id", event.deviceId)
                put("occurred_at", event.occurredAtMillis)
                put("source", event.source)
                put("classification", event.classification)
                put("confirmation_status", event.confirmationStatus)
                put("summary", event.summary)
                put("sync_state", "pending")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    @Synchronized
    fun queueNotificationAttempt(attempt: LocalNotificationAttempt) {
        writableDatabase.insertWithOnConflict(
            "sync_attempts",
            null,
            ContentValues().apply {
                put("id", attempt.id)
                put("incident_id", attempt.incidentId)
                put("device_id", attempt.deviceId)
                put("channel", attempt.channel)
                put("status", attempt.status)
                put("attempted_at", attempt.attemptedAtMillis)
                put("confirmed_at", attempt.confirmedAtMillis)
                put("error_code", attempt.errorCode)
                put("sync_state", "pending")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    @Synchronized
    fun pendingFallEvents(limit: Int = MAX_SYNC_BATCH): List<LocalFallEvent> = readableDatabase.rawQuery(
        "SELECT id, incident_id, device_id, occurred_at, source, classification, confirmation_status, summary " +
            "FROM sync_events WHERE sync_state = 'pending' AND retry_count < ? ORDER BY occurred_at ASC LIMIT ?",
        arrayOf(MAX_SYNC_ATTEMPTS.toString(), limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                LocalFallEvent(
                    id = cursor.getString(0),
                    incidentId = cursor.getString(1),
                    deviceId = cursor.getString(2),
                    occurredAtMillis = cursor.getLong(3),
                    source = cursor.getString(4),
                    classification = cursor.getString(5),
                    confirmationStatus = cursor.getString(6),
                    summary = if (cursor.isNull(7)) null else cursor.getString(7),
                ),
            )
        }
    }

    @Synchronized
    fun pendingNotificationAttempts(limit: Int = MAX_SYNC_BATCH): List<LocalNotificationAttempt> = readableDatabase.rawQuery(
        "SELECT id, incident_id, device_id, channel, status, attempted_at, confirmed_at, error_code " +
            "FROM sync_attempts WHERE sync_state = 'pending' AND retry_count < ? ORDER BY attempted_at ASC LIMIT ?",
        arrayOf(MAX_SYNC_ATTEMPTS.toString(), limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                LocalNotificationAttempt(
                    id = cursor.getString(0), incidentId = cursor.getString(1), deviceId = cursor.getString(2),
                    channel = cursor.getString(3), status = cursor.getString(4), attemptedAtMillis = cursor.getLong(5),
                    confirmedAtMillis = if (cursor.isNull(6)) null else cursor.getLong(6),
                    errorCode = if (cursor.isNull(7)) null else cursor.getString(7),
                ),
            )
        }
    }

    @Synchronized
    fun markFallEventSynced(id: String) = markSyncState("sync_events", id, "synced")

    @Synchronized
    fun markAttemptSynced(id: String) = markSyncState("sync_attempts", id, "synced")

    @Synchronized
    fun markFallEventFailure(id: String) = incrementSyncFailure("sync_events", id)

    @Synchronized
    fun markAttemptFailure(id: String) = incrementSyncFailure("sync_attempts", id)

    @Synchronized
    fun firstFallEventId(incidentId: String): String? = readableDatabase.rawQuery(
        "SELECT id FROM sync_events WHERE incident_id = ? ORDER BY occurred_at ASC LIMIT 1",
        arrayOf(incidentId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized
    fun latestAlertState(incidentId: String): String = readableDatabase.rawQuery(
        "SELECT classification FROM sync_events WHERE incident_id = ? ORDER BY occurred_at DESC LIMIT 1",
        arrayOf(incidentId),
    ).use { cursor ->
        when (if (cursor.moveToFirst()) cursor.getString(0) else "") {
            "cancelled", "false_alarm" -> "cancelled"
            "manual", "possible_fall", "fall" -> "created"
            else -> "actions_requested"
        }
    }

    @Synchronized
    fun ensureContacts(contacts: List<LocalContactRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            contacts.forEach { contact ->
                db.insertWithOnConflict("sync_contacts", null, contactValues(contact, "pending"), SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun replaceContacts(contacts: List<LocalContactRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE sync_contacts SET deleted = 1, updated_at = ?, sync_state = 'pending'", arrayOf(System.currentTimeMillis()))
            contacts.forEach { contact ->
                db.insertWithOnConflict("sync_contacts", null, contactValues(contact, "pending"), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun pendingContacts(): List<LocalContactRecord> = readableDatabase.rawQuery(
        "SELECT id, name, phone, preferences_json, updated_at, deleted FROM sync_contacts " +
            "WHERE sync_state = 'pending' AND retry_count < ? ORDER BY updated_at ASC LIMIT ?",
        arrayOf(MAX_SYNC_ATTEMPTS.toString(), MAX_SYNC_BATCH.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                LocalContactRecord(
                    id = cursor.getString(0), name = cursor.getString(1), phone = cursor.getString(2),
                    preferencesJson = cursor.getString(3), updatedAtMillis = cursor.getLong(4), deleted = cursor.getInt(5) != 0,
                ),
            )
        }
    }

    @Synchronized
    fun replaceContactsFromRemote(contacts: List<LocalContactRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sync_contacts", null, null)
            contacts.forEach { contact ->
                db.insertWithOnConflict("sync_contacts", null, contactValues(contact, "synced"), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun markContactSynced(id: String, deleted: Boolean) {
        val db = writableDatabase
        if (deleted) db.delete("sync_contacts", "id = ?", arrayOf(id))
        else db.update("sync_contacts", ContentValues().apply { put("sync_state", "synced") }, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun markContactFailure(id: String) = incrementSyncFailure("sync_contacts", id)

    /** Stores an Arduino/API sample without discarding its original JSON contract. */
    @Synchronized
    fun recordArduinoSample(payload: JSONObject, capturedAt: Long = System.currentTimeMillis()): Boolean {
        val serialized = payload.toString()
        val previous = readableDatabase.rawQuery(
            "SELECT payload_json FROM readings WHERE source = ? ORDER BY id DESC LIMIT 1",
            arrayOf(SOURCE_ARDUINO),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (serialized == previous) return false
        val measuredAt = payload.numberFor("captured_at_ms", "timestamp", "captured_at")?.let {
            (if (it > 10_000_000_000) it else it * 1000).toLong()
        } ?: capturedAt
        insert(
            ContentValues().apply {
                put("captured_at", measuredAt)
                put("source", SOURCE_ARDUINO)
                put("heart_rate_bpm", payload.numberFor("heart_rate_bpm", "heartRate", "heart_rate", "bpm", "pulse"))
                put("spo2_percent", payload.numberFor("spo2_percent", "spo2", "oxygen_saturation", "oxygenSaturation"))
                put("latitude", payload.numberFor("latitude", "lat"))
                put("longitude", payload.numberFor("longitude", "lon", "lng"))
                put("payload_json", serialized)
            },
        )
        payload.numberFor("heart_rate_bpm", "heartRate", "heart_rate", "bpm", "pulse")
            ?.takeIf { it > 0 && it < 300 }
            ?.let { queueMeasurement(UUID.randomUUID().toString(), "heart_rate", it, "bpm", measuredAt) }
        payload.numberFor("spo2_percent", "spo2", "oxygen_saturation", "oxygenSaturation")
            ?.takeIf { it > 0 && it <= 100 }
            ?.let { queueMeasurement(UUID.randomUUID().toString(), "spo2", it, "percent", measuredAt) }
        return true
    }

    /** Latest valid biometric values collected from the UNO Q on this phone. */
    @Synchronized
    fun latestArduinoVitals(): JSONObject {
        val result = JSONObject()
        val db = readableDatabase
        listOf(
            Triple("heart_rate_bpm", "bpm", "heart_rate_at"),
            Triple("spo2_percent", "spo2", "spo2_at"),
        ).forEach { (column, key, timeKey) ->
            val validRange = when (column) {
                "heart_rate_bpm" -> "$column > 0 AND $column < 300"
                "spo2_percent" -> "$column > 0 AND $column <= 100"
                else -> "$column > 0"
            }
            db.rawQuery(
                "SELECT $column, captured_at FROM readings WHERE source = ? AND $validRange ORDER BY captured_at DESC, id DESC LIMIT 1",
                arrayOf(SOURCE_ARDUINO),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    result.put(key, cursor.getDouble(0))
                    result.put(timeKey, cursor.getLong(1))
                }
            }
        }
        return result
    }

    @Synchronized
    fun pendingMeasurements(limit: Int = MAX_SYNC_BATCH): List<LocalMeasurementRecord> = readableDatabase.rawQuery(
        "SELECT id, measurement_type, value, unit, captured_at, source, is_valid FROM sync_measurements " +
            "WHERE sync_state = 'pending' AND retry_count < ? ORDER BY captured_at ASC LIMIT ?",
        arrayOf(MAX_SYNC_ATTEMPTS.toString(), limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                LocalMeasurementRecord(
                    id = cursor.getString(0), type = cursor.getString(1), value = cursor.getDouble(2), unit = cursor.getString(3),
                    capturedAtMillis = cursor.getLong(4), source = cursor.getString(5),
                    isValid = if (cursor.isNull(6)) null else cursor.getInt(6) != 0,
                ),
            )
        }
    }

    @Synchronized
    fun markMeasurementSynced(id: String) = markSyncState("sync_measurements", id, "synced")

    @Synchronized
    fun markMeasurementFailure(id: String) = incrementSyncFailure("sync_measurements", id)

    @Synchronized
    fun syncStateSummary(): SyncStateSummary {
        fun count(table: String, state: String): Int = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM " + table + " WHERE sync_state = ?",
            arrayOf(state),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return SyncStateSummary(
            pending = count("sync_events", "pending") + count("sync_attempts", "pending") +
                count("sync_contacts", "pending") + count("sync_measurements", "pending"),
            failed = count("sync_events", "failed") + count("sync_attempts", "failed") +
                count("sync_contacts", "failed") + count("sync_measurements", "failed"),
        )
    }

    @Synchronized
    fun clearAllData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("readings", "sync_events", "sync_attempts", "sync_contacts", "sync_measurements").forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun retryFailedSyncItems() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("sync_events", "sync_attempts", "sync_contacts", "sync_measurements").forEach { table ->
                db.execSQL("UPDATE " + table + " SET retry_count = 0, sync_state = 'pending' WHERE sync_state = 'failed'")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun queueMeasurement(id: String, type: String, value: Double, unit: String, capturedAt: Long) {
        writableDatabase.insertWithOnConflict(
            "sync_measurements", null,
            ContentValues().apply {
                put("id", id); put("measurement_type", type); put("value", value); put("unit", unit)
                put("captured_at", capturedAt); put("source", SOURCE_ARDUINO); putNull("is_valid"); put("sync_state", "pending")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun contactValues(contact: LocalContactRecord, state: String) = ContentValues().apply {
        put("id", contact.id); put("name", contact.name); put("phone", contact.phone); put("preferences_json", contact.preferencesJson)
        put("updated_at", contact.updatedAtMillis); put("deleted", if (contact.deleted) 1 else 0); put("sync_state", state)
    }

    private fun markSyncState(table: String, id: String, state: String) {
        writableDatabase.update(table, ContentValues().apply { put("sync_state", state) }, "id = ?", arrayOf(id))
    }

    private fun incrementSyncFailure(table: String, id: String) {
        writableDatabase.execSQL(
            "UPDATE " + table + " SET retry_count = retry_count + 1, " +
                "sync_state = CASE WHEN retry_count + 1 >= ? THEN 'failed' ELSE 'pending' END WHERE id = ?",
            arrayOf(MAX_SYNC_ATTEMPTS.toString(), id),
        )
    }

    private fun createSyncTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_events (
            id TEXT PRIMARY KEY NOT NULL, incident_id TEXT, device_id TEXT NOT NULL, occurred_at INTEGER NOT NULL,
            source TEXT NOT NULL, classification TEXT NOT NULL, confirmation_status TEXT NOT NULL,
            summary TEXT, sync_state TEXT NOT NULL DEFAULT 'pending', retry_count INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_events_pending_idx ON sync_events(sync_state, occurred_at)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_attempts (
            id TEXT PRIMARY KEY NOT NULL, incident_id TEXT NOT NULL, device_id TEXT NOT NULL, channel TEXT NOT NULL,
            status TEXT NOT NULL, attempted_at INTEGER NOT NULL, confirmed_at INTEGER, error_code TEXT,
            sync_state TEXT NOT NULL DEFAULT 'pending', retry_count INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_attempts_pending_idx ON sync_attempts(sync_state, attempted_at)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_contacts (
            id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, preferences_json TEXT NOT NULL DEFAULT '{}',
            updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
            sync_state TEXT NOT NULL DEFAULT 'pending', retry_count INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_contacts_pending_idx ON sync_contacts(sync_state, updated_at)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_measurements (
            id TEXT PRIMARY KEY NOT NULL, measurement_type TEXT NOT NULL, value REAL NOT NULL, unit TEXT NOT NULL,
            captured_at INTEGER NOT NULL, source TEXT NOT NULL, is_valid INTEGER,
            sync_state TEXT NOT NULL DEFAULT 'pending', retry_count INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_measurements_pending_idx ON sync_measurements(sync_state, captured_at)")
    }

    @Synchronized
    fun summary(): TelemetrySummary {
        val db = readableDatabase
        fun count(source: String): Int = db.rawQuery(
            "SELECT COUNT(*) FROM readings WHERE source = ?",
            arrayOf(source),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val latest = db.rawQuery("SELECT MAX(captured_at) FROM readings", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
        return TelemetrySummary(
            motion = count(SOURCE_PHONE_ACCELEROMETER),
            locations = count(SOURCE_PHONE_LOCATION),
            events = count(SOURCE_APP_EVENT),
            arduino = count(SOURCE_ARDUINO),
            latestCapturedAt = latest,
        )
    }

    private fun insert(values: ContentValues) {
        val db = writableDatabase
        db.insertOrThrow("readings", null, values)
        if (++writesSincePrune >= PRUNE_EVERY_WRITES) {
            writesSincePrune = 0
            val oldestKept = System.currentTimeMillis() - RETENTION_MS
            db.delete("readings", "captured_at < ?", arrayOf(oldestKept.toString()))
            db.execSQL(
                "DELETE FROM readings WHERE id IN (SELECT id FROM readings ORDER BY captured_at DESC LIMIT -1 OFFSET ?)",
                arrayOf(MAX_ROWS),
            )
        }
    }

    private fun JSONObject.numberFor(vararg keys: String): Double? {
        for (key in keys) {
            if (has(key) && !isNull(key)) {
                val value = optDouble(key, Double.NaN)
                if (value.isFinite()) return value
            }
        }
        val names = this.keys()
        while (names.hasNext()) {
            val nested = optJSONObject(names.next()) ?: continue
            nested.numberFor(*keys)?.let { return it }
        }
        return null
    }

    companion object {
        private const val DATABASE_NAME = "tzinda_local_telemetry.db"
        private const val DATABASE_VERSION = 2
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val PRUNE_EVERY_WRITES = 500
        private const val MAX_ROWS = 100_000
        const val MAX_SYNC_ATTEMPTS = 8
        private const val MAX_SYNC_BATCH = 100

        const val SOURCE_PHONE_ACCELEROMETER = "phone_accelerometer"
        const val SOURCE_PHONE_LOCATION = "phone_location"
        const val SOURCE_APP_EVENT = "app_event"
        const val SOURCE_ARDUINO = "arduino"
        private const val STANDARD_GRAVITY = 9.80665
    }
}

data class LocalFallEvent(
    val id: String, val incidentId: String?, val deviceId: String, val occurredAtMillis: Long,
    val source: String, val classification: String, val confirmationStatus: String, val summary: String?,
)

data class LocalNotificationAttempt(
    val id: String, val incidentId: String, val deviceId: String, val channel: String, val status: String,
    val attemptedAtMillis: Long, val confirmedAtMillis: Long?, val errorCode: String?,
)

data class LocalContactRecord(
    val id: String, val name: String, val phone: String, val preferencesJson: String = "{}",
    val updatedAtMillis: Long = System.currentTimeMillis(), val deleted: Boolean = false,
)

data class LocalMeasurementRecord(
    val id: String, val type: String, val value: Double, val unit: String, val capturedAtMillis: Long,
    val source: String, val isValid: Boolean?,
)

data class SyncStateSummary(val pending: Int, val failed: Int)

data class PhoneMotionSummary(
    val sampleCount: Int,
    val averageDynamicG: Double,
    val peakDynamicG: Double,
    val capturedAtMillis: Long,
)

data class TelemetrySummary(
    val motion: Int,
    val locations: Int,
    val events: Int,
    val arduino: Int,
    val latestCapturedAt: Long,
)
