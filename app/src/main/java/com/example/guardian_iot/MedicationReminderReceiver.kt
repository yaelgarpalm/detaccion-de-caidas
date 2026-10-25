package com.example.guardian_iot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MedicationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val intakeId = intent.getStringExtra(EXTRA_INTAKE_ID) ?: return
        val pendingResult = goAsync()
        Thread {
            try {
                val database = MedicationDatabase.getInstance(context)
                when (intent.action) {
                    ACTION_FIRE -> showReminder(context, database, intakeId, intent.getBooleanExtra(EXTRA_IS_SNOOZE, false))
                    ACTION_MARK_OVERDUE -> {
                        if (database.markIntakeOverdue(intakeId)) SyncScheduler.enqueue(context)
                    }
                    ACTION_TAKEN, ACTION_SKIPPED -> {
                        val status = if (intent.action == ACTION_TAKEN) "taken" else "skipped"
                        if (MedicationRepository(context, SupabaseBackend(EncryptedSessionStore(context))).applyNotificationAction(intakeId, status)) {
                            MedicationReminderScheduler.cancelOverdueCheck(context, intakeId)
                            dismissNotification(context, intakeId)
                        }
                    }
                    ACTION_SNOOZE -> {
                        database.getIntake(intakeId) ?: return@Thread
                        if (MedicationRepository(context, SupabaseBackend(EncryptedSessionStore(context))).applyNotificationAction(intakeId, "snoozed")) {
                            val snoozedUntil = database.getIntake(intakeId)?.snoozedUntilMillis ?: System.currentTimeMillis() + MedicationRepository.SNOOZE_MS
                            MedicationReminderScheduler.scheduleSnooze(context, intakeId, snoozedUntil)
                            database.getIntake(intakeId)?.let { MedicationReminderScheduler.scheduleOverdueCheck(context, it) }
                            dismissNotification(context, intakeId)
                        }
                    }
                }
            } finally { pendingResult.finish() }
        }.start()
    }

    private fun showReminder(context: Context, database: MedicationDatabase, intakeId: String, snoozed: Boolean) {
        val intake = database.getIntake(intakeId) ?: return
        if (intake.status in setOf("taken", "skipped", "cancelled")) return
        val medication = database.getMedication(intake.ownerUserId, intake.medicationId) ?: return
        if (!medication.isActive || medication.isDeleted) return
        if (!snoozed) {
            // AlarmManager may deliver inexact alarms slightly early. Schedule the next occurrence after this slot.
            val scheduledLocalTime = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(intake.scheduledAtMillis))
            if (scheduledLocalTime in medication.scheduleTimes)
                MedicationReminderScheduler.scheduleSlotAfter(context, medication, scheduledLocalTime, maxOf(System.currentTimeMillis(), intake.scheduledAtMillis))
        }
        SyncScheduler.enqueue(context)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recordatorios de medicamentos", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisos de los horarios guardados por la persona"
                enableVibration(true)
            })
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val dose = medication.prescribedDose + if (medication.presentation.isBlank()) "" else " · ${medication.presentation}"
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(intake.scheduledAtMillis))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hora de tomar: ${medication.name}")
            .setContentText("$dose · horario $time${if (snoozed) " · aviso pospuesto" else ""}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$dose\nHorario guardado: $time${medication.instructions.takeIf(String::isNotBlank)?.let { "\n${it.take(180)}" }.orEmpty()}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        builder.addAction(0, "Marcar tomada", actionPendingIntent(context, intakeId, ACTION_TAKEN))
        if (intake.snoozeCount == 0) builder.addAction(0, "Posponer 10 min", actionPendingIntent(context, intakeId, ACTION_SNOOZE))
        builder.addAction(0, "No tomada", actionPendingIntent(context, intakeId, ACTION_SKIPPED))
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(intakeId), builder.build()) }
    }

    private fun actionPendingIntent(context: Context, intakeId: String, action: String) =
        android.app.PendingIntent.getBroadcast(
            context, ("$action|$intakeId").hashCode(),
            Intent(context, MedicationReminderReceiver::class.java).setAction(action)
                .setData(android.net.Uri.parse("tzinda://medication-action/$action/$intakeId"))
                .putExtra(EXTRA_INTAKE_ID, intakeId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ACTION_FIRE = "com.guardian.tzindaapp.v2.MEDICATION_REMINDER"
        const val ACTION_TAKEN = "com.guardian.tzindaapp.v2.MEDICATION_TAKEN"
        const val ACTION_SKIPPED = "com.guardian.tzindaapp.v2.MEDICATION_SKIPPED"
        const val ACTION_SNOOZE = "com.guardian.tzindaapp.v2.MEDICATION_SNOOZE"
        const val ACTION_MARK_OVERDUE = "com.guardian.tzindaapp.v2.MEDICATION_MARK_OVERDUE"
        const val EXTRA_INTAKE_ID = "medication_intake_id"
        const val EXTRA_IS_SNOOZE = "medication_is_snooze"
        private const val CHANNEL_ID = "medication_reminders_v1"
        fun notificationId(intakeId: String) = 45_000 + (intakeId.hashCode() and 0x0fffffff)
        fun dismissNotification(context: Context, intakeId: String) = NotificationManagerCompat.from(context).cancel(notificationId(intakeId))
    }
}
