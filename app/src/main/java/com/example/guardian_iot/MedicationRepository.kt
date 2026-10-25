package com.example.guardian_iot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Coordinates the offline SQLite source of truth with the authenticated Supabase session. */
class MedicationRepository(
    context: Context,
    private val backend: SupabaseBackend,
    private val store: AppStore = AppStore(context),
) {
    private val app = context.applicationContext
    val database = MedicationDatabase(app)

    fun currentOwnerScope(): String {
        val userId = runCatching { EncryptedSessionStore(app).read()?.userId }.getOrNull()
        if (!userId.isNullOrBlank()) {
            val localScope = localScope()
            val claimedBy = store.prefs.getString(LOCAL_DATA_CLAIM_PREF, "").orEmpty()
            if (claimedBy.isBlank()) {
                database.claimLocalData(localScope, userId)
                store.prefs.edit().putString(LOCAL_DATA_CLAIM_PREF, userId).apply()
            } else if (claimedBy == userId) database.claimLocalData(localScope, userId)
            return userId
        }
        return store.prefs.getString(LOCAL_DATA_CLAIM_PREF, "").orEmpty().ifBlank { localScope() }
    }

    fun localScope(): String = "local:${store.localDeviceId()}"

    fun createMedication(
        ownerId: String,
        name: String,
        dose: String,
        presentation: String,
        frequency: String,
        times: List<String>,
        startDate: String,
        endDate: String?,
        instructions: String,
        isActive: Boolean = true,
        shareWithCaregiver: Boolean = false,
        existing: Medication? = null,
    ): Medication {
        MedicationSchedulePolicy.validate(name, dose, frequency, times, startDate, endDate)?.let { throw IllegalArgumentException(it) }
        if (database.medications(ownerId).any { it.id != existing?.id && !it.isDeleted && it.isActive && it.name.equals(name.trim(), ignoreCase = true) && it.scheduleTimes == times.sorted() })
            throw IllegalArgumentException("Ya hay un medicamento activo con ese nombre y horario. Revisa la lista antes de agregar otro.")
        val normalizedTimes = times.distinct().sorted()
        val now = System.currentTimeMillis()
        val item = Medication(
            id = existing?.id ?: UUID.randomUUID().toString(), ownerUserId = ownerId,
            name = name.trim(), prescribedDose = dose.trim(), presentation = presentation.trim().take(80),
            frequency = frequency.trim().take(80), scheduleTimes = normalizedTimes,
            startDate = startDate.trim(), endDate = endDate?.trim()?.takeIf(String::isNotBlank),
            instructions = instructions.trim().take(500), isActive = isActive, shareWithCaregiver = shareWithCaregiver,
            isDeleted = false, createdAtMillis = existing?.createdAtMillis ?: now, updatedAtMillis = now,
        )
        existing?.let { database.cancelFutureIntakes(it.id, now) }
        return database.saveMedication(item)
    }

    fun deleteMedication(ownerId: String, medicationId: String): Medication? = database.deleteMedication(ownerId, medicationId)

    fun acceptIntakeAction(intakeId: String, status: String): Boolean {
        val intake = database.getIntake(intakeId) ?: return false
        val snoozeUntil = if (status == "snoozed") System.currentTimeMillis() + SNOOZE_MS else null
        val accepted = database.recordAction(intakeId, status, System.currentTimeMillis(), snoozeUntil)
        if (accepted) SyncScheduler.enqueue(app)
        return accepted
    }

    /** Last-write-wins is explicit: pending local edits win; otherwise the newer updated_at wins. */
    fun synchronize(ownerId: String, readOnly: Boolean) {
        if (ownerId.isBlank() || ownerId.startsWith("local:")) return
        val session = backend.currentSession() ?: return
        if (!readOnly && session.userId != ownerId) throw BackendFailure(BackendFailure.Category.SESSION)
        if (!readOnly) {
            database.pendingMedications(ownerId).forEach { item ->
                try {
                    backend.upsertMedication(item)
                    database.markMedicationSynced(item.id, item.updatedAtMillis)
                } catch (failure: BackendFailure) {
                    database.failMedication(item.id)
                    throw failure
                }
            }
            database.pendingIntakes(ownerId).forEach { intake ->
                try {
                    backend.upsertMedicationIntake(intake)
                    database.markIntakeSynced(intake.id, intake.updatedAtMillis)
                } catch (failure: BackendFailure) {
                    database.failIntake(intake.id)
                    throw failure
                }
            }
        }

        val medicationRows = backend.medicationRecords(ownerId)
        val remoteMedicationIds = mutableSetOf<String>()
        for (index in 0 until medicationRows.length()) {
            parseMedication(medicationRows.optJSONObject(index) ?: continue)?.let {
                remoteMedicationIds.add(it.id)
                database.mergeRemoteMedication(it)
            }
        }
        val intakeRows = backend.medicationIntakeRecords(ownerId)
        val remoteIntakeIds = mutableSetOf<String>()
        for (index in 0 until intakeRows.length()) {
            parseIntake(intakeRows.optJSONObject(index) ?: continue)?.let {
                remoteIntakeIds.add(it.id)
                database.mergeRemoteIntake(it)
            }
        }
        if (readOnly) database.pruneRemoteSnapshot(ownerId, remoteMedicationIds, remoteIntakeIds)
        if (database.syncPendingCount(ownerId) > 0) throw BackendFailure(BackendFailure.Category.TEMPORARY)
    }

    fun applyNotificationAction(intakeId: String, status: String): Boolean = acceptIntakeAction(intakeId, status)

    private fun parseMedication(row: JSONObject): Medication? = runCatching {
        val times = row.optJSONArray("schedule_times") ?: JSONArray()
        val timeList = (0 until times.length()).map { times.optString(it).take(5) }.filter(String::isNotBlank)
        val id = row.optString("id")
        val owner = row.optString("owner_user_id")
        if (id.isBlank() || owner.isBlank() || timeList.isEmpty()) return null
        Medication(
            id = id, ownerUserId = owner, name = row.optString("name"), prescribedDose = row.optString("prescribed_dose"),
            presentation = row.optString("presentation"), frequency = row.optString("frequency"), scheduleTimes = timeList,
            startDate = row.optString("start_date"), endDate = row.optString("end_date").takeIf { !row.isNull("end_date") && it.isNotBlank() && it != "null" },
            instructions = row.optString("instructions"), isActive = row.optBoolean("is_active"),
            shareWithCaregiver = row.optBoolean("share_with_caregiver"), isDeleted = row.optBoolean("is_deleted"),
            createdAtMillis = row.optString("created_at").toEpochMillisOrNull() ?: System.currentTimeMillis(),
            updatedAtMillis = row.optString("updated_at").toEpochMillisOrNull() ?: System.currentTimeMillis(), syncState = "synced",
        )
    }.getOrNull()

    private fun parseIntake(row: JSONObject): MedicationIntake? = runCatching {
        val id = row.optString("id")
        val medicationId = row.optString("medication_id")
        val owner = row.optString("owner_user_id")
        if (id.isBlank() || medicationId.isBlank() || owner.isBlank()) return null
        MedicationIntake(
            id = id, medicationId = medicationId, ownerUserId = owner,
            scheduleInstanceId = row.optString("schedule_instance_id"),
            scheduledAtMillis = row.optString("scheduled_at").toEpochMillisOrNull() ?: return null,
            actionAtMillis = row.optString("action_at").toEpochMillisOrNull(), status = row.optString("status"),
            snoozedUntilMillis = row.optString("snoozed_until").toEpochMillisOrNull(), snoozeCount = row.optInt("snooze_count"),
            createdAtMillis = row.optString("created_at").toEpochMillisOrNull() ?: System.currentTimeMillis(),
            updatedAtMillis = row.optString("updated_at").toEpochMillisOrNull() ?: System.currentTimeMillis(), syncState = "synced",
        )
    }.getOrNull()

    companion object {
        const val SNOOZE_MS = 10 * 60 * 1000L
        private const val LOCAL_DATA_CLAIM_PREF = "medication_local_data_claimed_by"
    }
}
