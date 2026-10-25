package com.example.guardian_iot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

object MedicationReminderScheduler {
    fun synchronizeAll(context: Context) {
        val database = MedicationDatabase.getInstance(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        database.allActiveMedications().forEach { medication ->
            val start = runCatching { LocalDate.parse(medication.startDate) }.getOrNull()
            val end = medication.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val interval = MedicationSchedulePolicy.frequencyIntervalDays(medication.frequency) ?: 1
            if (start != null && !today.isBefore(start) && (end == null || !today.isAfter(end)) &&
                ChronoUnit.DAYS.between(start, today) % interval == 0L) {
                medication.scheduleTimes.forEach { time ->
                    runCatching { today.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli() }
                        .getOrNull()?.let { scheduledAt ->
                            val intake = database.ensureIntake(medication, scheduledAt)
                            scheduleOverdueCheck(context, intake)
                        }
                }
            }
            scheduleMedication(context, medication)
        }
        database.markOverdue()
    }

    fun scheduleMedication(context: Context, medication: Medication) {
        if (!medication.isActive || medication.isDeleted) return
        val now = System.currentTimeMillis()
        medication.scheduleTimes.forEach { time -> scheduleSlotAfter(context, medication, time, now) }
    }

    fun scheduleSlotAfter(context: Context, medication: Medication, time: String, afterMillis: Long): Long? {
        val next = MedicationSchedulePolicy.nextOccurrence(medication, time, afterMillis, ZoneId.systemDefault()) ?: return null
        val intake = MedicationDatabase.getInstance(context).ensureIntake(medication, next)
        setAlarm(context, medication, time, intake.id, next)
        scheduleOverdueCheck(context, intake)
        return next
    }

    fun scheduleOverdueCheck(context: Context, intake: MedicationIntake) {
        if (intake.status !in setOf("pending", "snoozed")) return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, MedicationReminderReceiver::class.java)
            .setAction(MedicationReminderReceiver.ACTION_MARK_OVERDUE)
            .setData(Uri.parse("tzinda://medication-overdue/${intake.id}"))
            .putExtra(MedicationReminderReceiver.EXTRA_INTAKE_ID, intake.id)
        val pending = PendingIntent.getBroadcast(
            context, ("overdue|${intake.id}").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val atMillis = intake.scheduledAtMillis + MedicationDatabase.OVERDUE_AFTER_MS
        try { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) }
        catch (_: SecurityException) { runCatching { alarmManager.set(AlarmManager.RTC_WAKEUP, atMillis, pending) } }
    }

    fun cancelOverdueCheck(context: Context, intakeId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, MedicationReminderReceiver::class.java)
            .setAction(MedicationReminderReceiver.ACTION_MARK_OVERDUE)
            .setData(Uri.parse("tzinda://medication-overdue/$intakeId"))
        val pending = PendingIntent.getBroadcast(
            context, ("overdue|$intakeId").hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    fun cancelMedication(context: Context, medication: Medication) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        medication.scheduleTimes.forEach { time ->
            val pending = slotPendingIntent(context, medication.id, time, create = false) ?: return@forEach
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    fun scheduleSnooze(context: Context, intakeId: String, atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, MedicationReminderReceiver::class.java)
            .setAction(MedicationReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("tzinda://medication-snooze/$intakeId"))
            .putExtra(MedicationReminderReceiver.EXTRA_INTAKE_ID, intakeId)
            .putExtra(MedicationReminderReceiver.EXTRA_IS_SNOOZE, true)
        val pending = PendingIntent.getBroadcast(
            context, ("snooze|$intakeId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms())
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    fun canUseExactAlarms(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun setAlarm(context: Context, medication: Medication, time: String, intakeId: String, atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = slotPendingIntent(context, medication.id, time, create = true, intakeId = intakeId) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms())
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } catch (_: SecurityException) {
            // Exact-alarm authorization can be revoked between checking and scheduling.
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) }
        }
    }

    private fun slotPendingIntent(
        context: Context,
        medicationId: String,
        time: String,
        create: Boolean,
        intakeId: String? = null,
    ): PendingIntent? {
        val key = "$medicationId|$time"
        val intent = Intent(context, MedicationReminderReceiver::class.java)
            .setAction(MedicationReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("tzinda://medication-slot/${Uri.encode(key)}"))
        if (intakeId != null) intent.putExtra(MedicationReminderReceiver.EXTRA_INTAKE_ID, intakeId)
        val flags = if (create) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, key.hashCode(), intent, flags)
    }
}
