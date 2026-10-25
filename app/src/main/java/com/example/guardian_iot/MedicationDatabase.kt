package com.example.guardian_iot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.time.Instant

/** Private, durable store for prescriptions, scheduled instances, and their sync outbox. */
class MedicationDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "tzinda_medications.db", null, 1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE medications (
            id TEXT PRIMARY KEY NOT NULL, owner_user_id TEXT NOT NULL, name TEXT NOT NULL, prescribed_dose TEXT NOT NULL,
            presentation TEXT NOT NULL DEFAULT '', frequency TEXT NOT NULL, schedule_times TEXT NOT NULL,
            start_date TEXT NOT NULL, end_date TEXT, instructions TEXT NOT NULL DEFAULT '',
            is_active INTEGER NOT NULL DEFAULT 1, share_with_caregiver INTEGER NOT NULL DEFAULT 0, is_deleted INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            sync_state TEXT NOT NULL DEFAULT 'pending', retry_count INTEGER NOT NULL DEFAULT 0
        )""".trimIndent())
        db.execSQL("CREATE INDEX medications_owner_active_idx ON medications(owner_user_id, is_deleted, is_active, updated_at)")
        db.execSQL("""CREATE TABLE medication_intakes (
            id TEXT PRIMARY KEY NOT NULL, medication_id TEXT NOT NULL, owner_user_id TEXT NOT NULL,
            schedule_instance_id TEXT NOT NULL, scheduled_at INTEGER NOT NULL, action_at INTEGER,
            status TEXT NOT NULL, snoozed_until INTEGER, snooze_count INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            sync_state TEXT NOT NULL DEFAULT 'pending', retry_count INTEGER NOT NULL DEFAULT 0,
            UNIQUE(owner_user_id, schedule_instance_id)
        )""".trimIndent())
        db.execSQL("CREATE INDEX medication_intakes_owner_schedule_idx ON medication_intakes(owner_user_id, scheduled_at DESC)")
        db.execSQL("CREATE INDEX medication_intakes_pending_idx ON medication_intakes(sync_state, scheduled_at)")
        db.execSQL("CREATE INDEX medication_intakes_medication_idx ON medication_intakes(medication_id, scheduled_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun saveMedication(medication: Medication): Medication {
        val row = medication.copy(updatedAtMillis = System.currentTimeMillis(), syncState = "pending")
        writableDatabase.insertWithOnConflict("medications", null, row.values(), SQLiteDatabase.CONFLICT_REPLACE)
        return row
    }

    @Synchronized
    fun getMedication(ownerId: String, medicationId: String): Medication? = readableDatabase.rawQuery(
        "SELECT * FROM medications WHERE owner_user_id=? AND id=? LIMIT 1", arrayOf(ownerId, medicationId),
    ).use { if (it.moveToFirst()) it.medication() else null }

    @Synchronized
    fun medications(ownerId: String, includeDeleted: Boolean = false): List<Medication> {
        val deletedFilter = if (includeDeleted) "" else " AND is_deleted=0"
        return readableDatabase.rawQuery(
            "SELECT * FROM medications WHERE owner_user_id=?$deletedFilter ORDER BY is_active DESC, name COLLATE NOCASE ASC",
            arrayOf(ownerId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.medication()) } }
    }

    @Synchronized
    fun activeMedications(ownerId: String): List<Medication> = readableDatabase.rawQuery(
        "SELECT * FROM medications WHERE owner_user_id=? AND is_active=1 AND is_deleted=0 ORDER BY name COLLATE NOCASE ASC", arrayOf(ownerId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.medication()) } }

    @Synchronized
    fun allActiveMedications(): List<Medication> = readableDatabase.rawQuery(
        "SELECT * FROM medications WHERE is_active=1 AND is_deleted=0 ORDER BY updated_at DESC", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.medication()) } }

    @Synchronized
    fun pendingMedications(ownerId: String, limit: Int = 100): List<Medication> = readableDatabase.rawQuery(
        "SELECT * FROM medications WHERE owner_user_id=? AND sync_state='pending' AND retry_count<8 ORDER BY updated_at ASC LIMIT ?",
        arrayOf(ownerId, limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.medication()) } }

    @Synchronized
    fun markMedicationSynced(id: String, updatedAtMillis: Long) {
        writableDatabase.update("medications", ContentValues().apply {
            put("sync_state", "synced"); put("retry_count", 0); put("updated_at", updatedAtMillis)
        }, "id=?", arrayOf(id))
    }

    @Synchronized
    fun failMedication(id: String) = writableDatabase.execSQL(
        "UPDATE medications SET retry_count=retry_count+1, sync_state=CASE WHEN retry_count+1>=8 THEN 'failed' ELSE 'pending' END WHERE id=?", arrayOf(id),
    )

    @Synchronized
    fun mergeRemoteMedication(remote: Medication) {
        val local = getMedication(remote.ownerUserId, remote.id)
        if (local != null && (local.syncState == "pending" || local.syncState == "failed" || local.updatedAtMillis >= remote.updatedAtMillis)) return
        writableDatabase.insertWithOnConflict("medications", null, remote.copy(syncState = "synced").values(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun cancelFutureIntakes(medicationId: String, now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            "UPDATE medication_intakes SET status='cancelled', updated_at=?, sync_state='pending' WHERE medication_id=? AND status IN ('pending','snoozed') AND scheduled_at>?",
            arrayOf<Any>(now, medicationId, now),
        )
    }

    @Synchronized
    fun deleteMedication(ownerId: String, medicationId: String): Medication? {
        val old = getMedication(ownerId, medicationId) ?: return null
        val now = System.currentTimeMillis()
        val deleted = old.copy(isActive = false, isDeleted = true, updatedAtMillis = now, syncState = "pending")
        writableDatabase.update("medications", deleted.values(), "owner_user_id=? AND id=?", arrayOf(ownerId, medicationId))
        cancelFutureIntakes(medicationId, now)
        return deleted
    }

    @Synchronized
    fun ensureIntake(medication: Medication, scheduledAtMillis: Long): MedicationIntake {
        val instanceId = MedicationSchedulePolicy.scheduleInstanceId(medication.id, scheduledAtMillis)
        val id = MedicationSchedulePolicy.intakeId(medication.id, scheduledAtMillis)
        val now = System.currentTimeMillis()
        val row = ContentValues().apply {
            put("id", id); put("medication_id", medication.id); put("owner_user_id", medication.ownerUserId)
            put("schedule_instance_id", instanceId); put("scheduled_at", scheduledAtMillis); putNull("action_at")
            put("status", "pending"); putNull("snoozed_until"); put("snooze_count", 0)
            put("created_at", now); put("updated_at", now); put("sync_state", "pending")
        }
        writableDatabase.insertWithOnConflict("medication_intakes", null, row, SQLiteDatabase.CONFLICT_IGNORE)
        return getIntake(id) ?: error("No se pudo guardar la toma programada.")
    }

    @Synchronized
    fun getIntake(id: String): MedicationIntake? = readableDatabase.rawQuery(
        "SELECT * FROM medication_intakes WHERE id=? LIMIT 1", arrayOf(id),
    ).use { if (it.moveToFirst()) it.intake() else null }

    @Synchronized
    fun intakes(ownerId: String, fromMillis: Long = 0L, toMillis: Long = Long.MAX_VALUE, limit: Int = 300): List<MedicationIntake> = readableDatabase.rawQuery(
        "SELECT * FROM medication_intakes WHERE owner_user_id=? AND scheduled_at>=? AND scheduled_at<? AND status!='cancelled' ORDER BY scheduled_at DESC LIMIT ?",
        arrayOf(ownerId, fromMillis.toString(), toMillis.toString(), limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.intake()) } }

    @Synchronized
    fun pendingIntakes(ownerId: String, limit: Int = 200): List<MedicationIntake> = readableDatabase.rawQuery(
        "SELECT * FROM medication_intakes WHERE owner_user_id=? AND sync_state='pending' AND retry_count<8 ORDER BY updated_at ASC LIMIT ?",
        arrayOf(ownerId, limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.intake()) } }

    @Synchronized
    fun markIntakeSynced(id: String, updatedAtMillis: Long) {
        writableDatabase.update("medication_intakes", ContentValues().apply {
            put("sync_state", "synced"); put("retry_count", 0); put("updated_at", updatedAtMillis)
        }, "id=?", arrayOf(id))
    }

    @Synchronized
    fun failIntake(id: String) = writableDatabase.execSQL(
        "UPDATE medication_intakes SET retry_count=retry_count+1, sync_state=CASE WHEN retry_count+1>=8 THEN 'failed' ELSE 'pending' END WHERE id=?", arrayOf(id),
    )

    @Synchronized
    fun mergeRemoteIntake(remote: MedicationIntake) {
        val local = getIntake(remote.id)
        if (local != null && (local.syncState == "pending" || local.syncState == "failed" || local.updatedAtMillis >= remote.updatedAtMillis)) return
        writableDatabase.insertWithOnConflict("medication_intakes", null, remote.values(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Remove stale read-only caregiver cache rows after remote RLS/consent changes. */
    @Synchronized
    fun pruneRemoteSnapshot(ownerId: String, medicationIds: Set<String>, intakeIds: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteSyncedRowsMissing(db, "medication_intakes", ownerId, intakeIds)
            deleteSyncedRowsMissing(db, "medications", ownerId, medicationIds)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun deleteSyncedRowsMissing(db: SQLiteDatabase, table: String, ownerId: String, ids: Set<String>) {
        require(table in setOf("medications", "medication_intakes"))
        val notIn = if (ids.isEmpty()) "" else " AND id NOT IN (${ids.joinToString(",") { "?" }})"
        db.execSQL(
            "DELETE FROM $table WHERE owner_user_id=? AND sync_state='synced'$notIn",
            arrayOf(ownerId, *ids.toTypedArray()),
        )
    }

    /** Returns false for duplicate, stale, or already-completed notification actions. */
    @Synchronized
    fun recordAction(id: String, status: String, actionAt: Long = System.currentTimeMillis(), snoozeUntil: Long? = null): Boolean {
        require(status in setOf("taken", "skipped", "snoozed"))
        val row = getIntake(id) ?: return false
        val allowed = when (status) {
            "snoozed" -> row.status in setOf("pending", "overdue") && row.snoozeCount == 0
            else -> row.status in setOf("pending", "snoozed", "overdue")
        }
        if (!allowed) return false
        val values = ContentValues().apply {
            put("status", status); put("action_at", actionAt); put("updated_at", actionAt)
            put("sync_state", "pending"); put("retry_count", 0)
            if (status == "snoozed") { put("snoozed_until", snoozeUntil ?: actionAt + 10 * 60_000L); put("snooze_count", row.snoozeCount + 1) }
        }
        return writableDatabase.update("medication_intakes", values, "id=? AND status IN ('pending','snoozed','overdue')", arrayOf(id)) == 1
    }

    @Synchronized
    fun markOverdue(now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            "UPDATE medication_intakes SET status='overdue', updated_at=?, sync_state='pending' WHERE status IN ('pending','snoozed') AND scheduled_at<=?",
            arrayOf(now, now - OVERDUE_AFTER_MS),
        )
    }

    @Synchronized
    fun markIntakeOverdue(id: String, now: Long = System.currentTimeMillis()): Boolean = writableDatabase.update(
        "medication_intakes",
        ContentValues().apply { put("status", "overdue"); put("updated_at", now); put("sync_state", "pending") },
        "id=? AND status IN ('pending','snoozed') AND scheduled_at<=?",
        arrayOf(id, (now - OVERDUE_AFTER_MS).toString()),
    ) == 1

    @Synchronized
    fun claimLocalData(localScope: String, ownerUserId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE medications SET owner_user_id=?, sync_state='pending', retry_count=0 WHERE owner_user_id=?", arrayOf(ownerUserId, localScope))
            db.execSQL("UPDATE medication_intakes SET owner_user_id=?, sync_state='pending', retry_count=0 WHERE owner_user_id=?", arrayOf(ownerUserId, localScope))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun syncPendingCount(ownerId: String): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM medications WHERE owner_user_id=? AND sync_state IN ('pending','failed')) + " +
            "(SELECT COUNT(*) FROM medication_intakes WHERE owner_user_id=? AND sync_state IN ('pending','failed'))",
        arrayOf(ownerId, ownerId),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun ContentValues.putNullable(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }

    private fun medicationValues(row: Medication) = ContentValues().apply {
        put("id", row.id); put("owner_user_id", row.ownerUserId); put("name", row.name); put("prescribed_dose", row.prescribedDose)
        put("presentation", row.presentation); put("frequency", row.frequency)
        put("schedule_times", JSONArray(row.scheduleTimes).toString()); put("start_date", row.startDate)
        putNullable("end_date", row.endDate); put("instructions", row.instructions); put("is_active", if (row.isActive) 1 else 0)
        put("share_with_caregiver", if (row.shareWithCaregiver) 1 else 0)
        put("is_deleted", if (row.isDeleted) 1 else 0); put("created_at", row.createdAtMillis); put("updated_at", row.updatedAtMillis)
        put("sync_state", row.syncState)
    }
    private fun Medication.values() = medicationValues(this)

    private fun android.database.Cursor.medication(): Medication {
        val times = runCatching { JSONArray(getString(getColumnIndexOrThrow("schedule_times"))) }.getOrDefault(JSONArray())
        val scheduleTimes = (0 until times.length()).mapNotNull { times.optString(it).takeIf(String::isNotBlank) }
        return Medication(
            id = getString(getColumnIndexOrThrow("id")), ownerUserId = getString(getColumnIndexOrThrow("owner_user_id")),
            name = getString(getColumnIndexOrThrow("name")), prescribedDose = getString(getColumnIndexOrThrow("prescribed_dose")),
            presentation = getString(getColumnIndexOrThrow("presentation")), frequency = getString(getColumnIndexOrThrow("frequency")),
            scheduleTimes = scheduleTimes, startDate = getString(getColumnIndexOrThrow("start_date")),
            endDate = getString(getColumnIndexOrThrow("end_date"))?.takeIf(String::isNotBlank),
            instructions = getString(getColumnIndexOrThrow("instructions")), isActive = getInt(getColumnIndexOrThrow("is_active")) != 0,
            shareWithCaregiver = getInt(getColumnIndexOrThrow("share_with_caregiver")) != 0,
            isDeleted = getInt(getColumnIndexOrThrow("is_deleted")) != 0, createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")), syncState = getString(getColumnIndexOrThrow("sync_state")),
        )
    }

    private fun MedicationIntake.values() = ContentValues().apply {
        put("id", id); put("medication_id", medicationId); put("owner_user_id", ownerUserId); put("schedule_instance_id", scheduleInstanceId)
        put("scheduled_at", scheduledAtMillis); if (actionAtMillis == null) putNull("action_at") else put("action_at", actionAtMillis)
        put("status", status); if (snoozedUntilMillis == null) putNull("snoozed_until") else put("snoozed_until", snoozedUntilMillis)
        put("snooze_count", snoozeCount); put("created_at", createdAtMillis); put("updated_at", updatedAtMillis); put("sync_state", syncState)
    }

    private fun android.database.Cursor.intake() = MedicationIntake(
        id = getString(getColumnIndexOrThrow("id")), medicationId = getString(getColumnIndexOrThrow("medication_id")),
        ownerUserId = getString(getColumnIndexOrThrow("owner_user_id")), scheduleInstanceId = getString(getColumnIndexOrThrow("schedule_instance_id")),
        scheduledAtMillis = getLong(getColumnIndexOrThrow("scheduled_at")),
        actionAtMillis = if (isNull(getColumnIndexOrThrow("action_at"))) null else getLong(getColumnIndexOrThrow("action_at")),
        status = getString(getColumnIndexOrThrow("status")),
        snoozedUntilMillis = if (isNull(getColumnIndexOrThrow("snoozed_until"))) null else getLong(getColumnIndexOrThrow("snoozed_until")),
        snoozeCount = getInt(getColumnIndexOrThrow("snooze_count")), createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
        updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")), syncState = getString(getColumnIndexOrThrow("sync_state")),
    )

    companion object {
        const val OVERDUE_AFTER_MS = 60 * 60 * 1000L

        @Volatile private var instance: MedicationDatabase? = null
        fun getInstance(context: Context): MedicationDatabase =
            instance ?: synchronized(this) {
                instance ?: MedicationDatabase(context.applicationContext).also { instance = it }
            }
    }
}

internal fun String?.toEpochMillisOrNull(): Long? = this?.takeIf(String::isNotBlank)?.let { value ->
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
