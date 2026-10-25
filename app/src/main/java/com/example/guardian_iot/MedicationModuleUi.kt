package com.example.guardian_iot

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Small view/controller module kept outside MainActivity to avoid growing the main screen logic. */
class MedicationModuleUi(
    private val activity: Activity,
    private val repository: MedicationRepository,
    private val ownerId: String,
    private val readOnly: Boolean,
    private val dark: Boolean,
    private val onBack: () -> Unit,
) {
    private val db = repository.database
    private val ink = Color.parseColor(if (dark) "#DFE4DE" else "#1C1C15")
    private val muted = Color.parseColor(if (dark) "#BFC9BD" else "#414943")
    private val accent = Color.parseColor(if (dark) "#86D89B" else "#254D3A")
    private val paper = Color.parseColor(if (dark) "#101412" else "#FDF9EE")
    private val cardColor = Color.parseColor(if (dark) "#1C211E" else "#FFFFFF")
    private val mint = Color.parseColor(if (dark) "#263A2D" else "#E2F3DF")
    private val softSurface = Color.parseColor(if (dark) "#262B28" else "#F2EEE3")
    private val outline = Color.parseColor(if (dark) "#3F4940" else "#E4DDD0")
    private var syncState: TextView? = null
    private var parent: LinearLayout? = null
    private var initialSyncStarted = false

    fun render(target: LinearLayout) {
        parent = target
        MedicationReminderScheduler.synchronizeAll(activity)
        db.markOverdue()
        target.addView(button("‹  Volver", false, onBack))
        heading(target, "CUIDADO Y RUTINA", "Mis medicamentos", "Guarda los horarios indicados y lleva un registro de cada toma.")
        if (readOnly && ownerId.isBlank()) {
            val linkCard = card(target, softSurface)
            linkCard.addView(label("Vincula primero una cuenta de persona protegida para consultar sus medicamentos.", muted, 16, true))
            return
        }
        val summary = card(target, mint)
        summary.addView(label("PRÓXIMAS TOMAS", accent, 13, true))
        val next = nextIntakes()
        if (next.isEmpty()) summary.addView(label("No hay tomas programadas próximas.", ink, 17, true))
        next.take(3).forEach { (med, at) ->
            summary.addView(label("${formatTime(at)} · ${med.name} · ${med.prescribedDose}", ink, 16, true).apply { setPadding(0, dp(6), 0, 0) })
        }
        if (!readOnly) target.addView(button("＋  Agregar medicamento") { showMedicationForm(null) })
        val syncCard = card(target, softSurface)
        syncCard.addView(label(if (readOnly) "Datos compartidos" else "Guardado y sincronización", ink, 18, true))
        syncState = label(
            when {
                readOnly -> "Solo lectura para cuidador/a. La persona protegida conserva el control de horarios y tomas."
                ownerId.startsWith("local:") -> "Disponible sin Internet. Se guarda en este teléfono."
                else -> "Los cambios se guardan primero en este teléfono y se sincronizan con Supabase."
            }, muted, 14,
        ).also(syncCard::addView)
        if (!readOnly && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            syncCard.addView(button("Permitir recordatorios", false) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            })
        }
        if (!readOnly && Build.VERSION.SDK_INT >= 31 && !MedicationReminderScheduler.canUseExactAlarms(activity)) {
            syncCard.addView(label("Android puede retrasar avisos. Si quieres horarios más precisos, permite alarmas exactas; si no, la app usará avisos aproximados.", muted, 14))
            syncCard.addView(button("Configurar horarios precisos", false) { requestExactAlarmPermission() })
        }
        if (!readOnly) {
            syncCard.addView(button("Sincronizar ahora", false) { syncNow() })
        }

        val today = card(target)
        today.addView(label("Tomas de hoy", ink, 20, true))
        val todayIntakes = todayIntakes()
        if (todayIntakes.isEmpty()) today.addView(label("Todavía no hay horarios registrados para hoy.", muted, 15))
        todayIntakes.forEach { intake -> intakeCard(today, intake) }

        target.addView(label("Lista de medicamentos", ink, 21, true).apply { setPadding(dp(4), dp(10), 0, dp(4)) })
        val meds = db.medications(ownerId)
        if (meds.isEmpty()) {
            val empty = card(target)
            empty.addView(label(if (readOnly) "La persona protegida aún no comparte medicamentos." else "Aún no has agregado medicamentos.", muted, 16))
        }
        meds.forEach { medicationCard(target, it) }
        target.addView(button("Ver historial de tomas", false) { showHistory() })
        target.addView(label("TzindaApp registra la dosis que tú escribes. No recomienda ni cambia tratamientos.", muted, 13).apply { setPadding(dp(4), dp(16), dp(4), 0) })
        if (!initialSyncStarted && ownerId.isNotBlank() && !ownerId.startsWith("local:")) {
            initialSyncStarted = true
            syncNow(silent = true)
        }
    }

    private fun medicationCard(target: LinearLayout, medication: Medication) {
        val item = card(target)
        val tag = when { medication.isDeleted -> "Archivado"; medication.isActive -> "Activo"; else -> "Pausado" }
        item.addView(label(medication.name, ink, 21, true))
        item.addView(label("${medication.prescribedDose}${medication.presentation.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", accent, 16, true))
        item.addView(label("$tag · ${medication.frequency}", muted, 14))
        item.addView(label(if (medication.shareWithCaregiver) "Compartido con cuidador/a vinculado/a" else "Privado en esta cuenta", muted, 14))
        item.addView(label("Horario: ${medication.scheduleTimes.joinToString(" · ")}", muted, 15))
        item.addView(label("Desde ${medication.startDate}${medication.endDate?.let { " hasta $it" }.orEmpty()}", muted, 14))
        if (medication.instructions.isNotBlank()) item.addView(label("${medication.instructions}", muted, 14))
        item.addView(button("Consultar detalles", false) { showDetails(medication) })
        if (!readOnly && !medication.isDeleted) {
            item.addView(button("Editar", false) { showMedicationForm(medication) })
            item.addView(button("Eliminar medicamento", false) { confirmDelete(medication) })
        }
    }

    private fun intakeCard(target: LinearLayout, intake: MedicationIntake) {
        val medication = db.getMedication(ownerId, intake.medicationId) ?: return
        val tile = card(target, softSurface)
        val status = displayStatus(intake)
        tile.addView(label("${formatTime(intake.scheduledAtMillis)} · ${medication.name}", ink, 18, true))
        tile.addView(label("${medication.prescribedDose}${medication.presentation.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", muted, 15))
        tile.addView(label("Estado: $status", accent, 15, true))
        if (!readOnly && intake.status in setOf("pending", "snoozed", "overdue")) {
            val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("Tomada", true) { actOnIntake(intake.id, "taken") }, LinearLayout.LayoutParams(0, dp(54), 1f))
            actions.addView(button("Omitida", false) { actOnIntake(intake.id, "skipped") }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(8) })
            if (intake.snoozeCount == 0) tile.addView(button("Posponer 10 minutos", false) { actOnIntake(intake.id, "snoozed") })
            tile.addView(actions)
        }
    }

    private fun showMedicationForm(existing: Medication?) {
        if (readOnly) return
        var selectedStart = existing?.startDate ?: LocalDate.now().toString()
        var selectedEnd = existing?.endDate
        val selectedTimes = (existing?.scheduleTimes ?: emptyList()).toMutableList()
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(24).toFloat(); setStroke(dp(1), outline) }
        }
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(if (existing == null) "Nuevo medicamento" else "Editar medicamento", ink, 21, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Cerrar", false) { dialog.dismiss() })
        root.addView(header)

        val scroll = ScrollView(activity).apply { isFillViewport = false; clipToPadding = false }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(label("Medicamento", accent, 14, true))
        val name = input(content, "Nombre del medicamento", existing?.name.orEmpty())
        val dose = input(content, "Dosis indicada", existing?.prescribedDose.orEmpty())
        val presentation = input(content, "Presentación o unidad (opcional)", existing?.presentation.orEmpty())
        gap(content, 6)
        content.addView(label("Días y horarios", accent, 14, true))
        content.addView(label("Elige cuándo empieza y agrega cada horario. TzindaApp usará la frecuencia desde ese día.", muted, 14))
        content.addView(label("Frecuencia", ink, 15, true))
        val frequencyOptions = listOf("Diaria", "Cada 2 días", "Cada 3 días", "Semanal")
        val frequency = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, frequencyOptions)
            setSelection(existing?.frequency?.let(frequencyOptions::indexOf)?.takeIf { it >= 0 } ?: 0)
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(14).toFloat(); setStroke(dp(1), outline) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        content.addView(frequency, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        val startButton = button("", false) {}
        fun refreshStartButton() { startButton.text = "Comienza · ${formatLocalDate(selectedStart)}" }
        refreshStartButton()
        startButton.setOnClickListener {
            showDatePicker(selectedStart) { chosen -> selectedStart = chosen; refreshStartButton() }
        }
        content.addView(startButton, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })

        val endRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val endButton = button("", false) {}
        var removeEndButton: Button? = null
        fun refreshEndButton() {
            endButton.text = selectedEnd?.let { "Termina · ${formatLocalDate(it)}" } ?: "＋  Agregar fecha de fin (opcional)"
            removeEndButton?.visibility = if (selectedEnd == null) View.GONE else View.VISIBLE
        }
        val clearEndButton = button("Quitar", false) { selectedEnd = null; refreshEndButton() }
        removeEndButton = clearEndButton
        refreshEndButton()
        endButton.setOnClickListener {
            showDatePicker(selectedEnd ?: selectedStart) { chosen -> selectedEnd = chosen; refreshEndButton() }
        }
        endRow.addView(endButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        endRow.addView(clearEndButton, LinearLayout.LayoutParams(-2, dp(54)).apply { leftMargin = dp(6) })
        content.addView(endRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        content.addView(label("Recordatorios", ink, 15, true))
        val timesContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderTimes() {
            timesContainer.removeAllViews()
            if (selectedTimes.isEmpty()) {
                timesContainer.addView(label("Aún no agregas horarios.", muted, 14))
            } else selectedTimes.sorted().forEach { time ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label("◷   $time", ink, 17, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("Quitar", false) { selectedTimes.remove(time); renderTimes() })
                timesContainer.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
            }
        }
        renderTimes()
        content.addView(timesContainer)
        content.addView(button("＋  Agregar horario", false) {
            val last = selectedTimes.lastOrNull()?.split(":")
            val hour = last?.getOrNull(0)?.toIntOrNull() ?: 8
            val minute = last?.getOrNull(1)?.toIntOrNull() ?: 0
            TimePickerDialog(activity, { _, chosenHour, chosenMinute ->
                val time = "%02d:%02d".format(Locale.ROOT, chosenHour, chosenMinute)
                if (time in selectedTimes) toast("Ese horario ya está agregado.")
                else { selectedTimes.add(time); selectedTimes.sort(); renderTimes() }
            }, hour, minute, DateFormat.is24HourFormat(activity)).show()
        },)
        content.addView(label("Una notificación te permitirá confirmar cada toma. La app no marca ninguna como tomada automáticamente.", muted, 13))

        gap(content, 6)
        content.addView(label("Detalles opcionales", accent, 14, true))
        val instructions = input(content, "Indicaciones personales (opcional)", existing?.instructions.orEmpty(), multiline = true)
        val active = Switch(activity).apply {
            text = "Activar recordatorios"
            textSize = 16f
            setTextColor(ink)
            isChecked = existing?.isActive ?: true
        }.also(content::addView)
        content.addView(label("Compartir con cuidador/a", ink, 15, true))
        val shareWithCaregiver = CheckBox(activity).apply {
            text = "Permitir que vea este medicamento y sus tomas"
            textSize = 15f
            setTextColor(ink)
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = existing?.shareWithCaregiver ?: false
        }.also(content::addView)
        content.addView(label("Empieza desactivado. Solo se comparte con un cuidador vinculado si lo permites.", muted, 13))

        val footer = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        footer.addView(button("Cancelar", false) { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        footer.addView(button(if (existing == null) "Guardar" else "Guardar cambios") {
            val schedule = selectedTimes.distinct().sorted()
            val validation = MedicationSchedulePolicy.validate(name.text.toString(), dose.text.toString(), frequency.selectedItem.toString(), schedule, selectedStart, selectedEnd)
            if (validation != null) { toast(validation); return@button }
            if (existing != null) MedicationReminderScheduler.cancelMedication(activity, existing)
            val saved = runCatching {
                repository.createMedication(ownerId, name.text.toString(), dose.text.toString(), presentation.text.toString(),
                    frequency.selectedItem.toString(), schedule, selectedStart, selectedEnd,
                    instructions.text.toString(), active.isChecked, shareWithCaregiver.isChecked, existing)
            }
            saved.onFailure { toast(it.message ?: "No se pudo guardar el medicamento.") }
            saved.onSuccess { item ->
                if (item.isActive) MedicationReminderScheduler.scheduleMedication(activity, item)
                db.markOverdue()
                SyncScheduler.enqueue(activity)
                dialog.dismiss()
                rerender()
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1.2f).apply { leftMargin = dp(8) })
        root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((activity.resources.displayMetrics.widthPixels - dp(28)).coerceAtLeast(dp(280)), (activity.resources.displayMetrics.heightPixels * .88f).toInt())
        }
    }

    private fun showDatePicker(initial: String, onSelected: (String) -> Unit) {
        val date = runCatching { LocalDate.parse(initial) }.getOrDefault(LocalDate.now())
        DatePickerDialog(activity, { _, year, month, day ->
            onSelected(LocalDate.of(year, month + 1, day).toString())
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun confirmDelete(medication: Medication) {
        AlertDialog.Builder(activity)
            .setTitle("Eliminar ${medication.name}?")
            .setMessage("Se cancelarán sus próximos recordatorios. El historial de tomas anteriores se conservará.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                MedicationReminderScheduler.cancelMedication(activity, medication)
                repository.deleteMedication(ownerId, medication.id)
                SyncScheduler.enqueue(activity)
                rerender()
            }.show()
    }

    private fun showDetails(medication: Medication) {
        val latest = db.intakes(ownerId, limit = 500).filter { it.medicationId == medication.id }.maxByOrNull { it.scheduledAtMillis }
        val message = buildString {
            append("Dosis indicada: ${medication.prescribedDose}\n")
            if (medication.presentation.isNotBlank()) append("Presentación: ${medication.presentation}\n")
            append("Frecuencia: ${medication.frequency}\nHorarios: ${medication.scheduleTimes.joinToString(" · ")}\n")
            append("Inicio: ${medication.startDate}\n")
            medication.endDate?.let { append("Fin: $it\n") }
            if (medication.instructions.isNotBlank()) append("Instrucciones: ${medication.instructions}\n")
            append("Estado: ${if (medication.isActive && !medication.isDeleted) "activo" else "pausado o archivado"}\n")
            if (latest != null) append("Último horario registrado: ${formatDateTime(latest.scheduledAtMillis)} · ${displayStatus(latest)}")
        }
        AlertDialog.Builder(activity).setTitle(medication.name).setMessage(message).setPositiveButton("Listo", null).show()
    }

    private fun showHistory() {
        val all = db.intakes(ownerId, limit = 500).filter { it.status != "cancelled" }
        val history = all.mapNotNull { intake ->
            val medication = db.getMedication(ownerId, intake.medicationId) ?: return@mapNotNull null
            Triple(intake, medication.name, displayStatus(intake))
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        if (history.isEmpty()) content.addView(label("Aún no hay tomas en el historial.", muted, 15))
        history.forEach { (intake, name, status) ->
            content.addView(label("${formatDateTime(intake.scheduledAtMillis)} · $name", ink, 16, true))
            content.addView(label("$status${intake.actionAtMillis?.let { " · acción ${formatTime(it)}" }.orEmpty()}", muted, 14))
            gap(content, 8)
        }
        AlertDialog.Builder(activity).setTitle("Historial de tomas")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Cerrar", null).show()
    }

    private fun actOnIntake(id: String, status: String) {
        val apply = {
            val ok = repository.acceptIntakeAction(id, status)
            if (ok && status == "snoozed") {
                val intake = db.getIntake(id)
                if (intake?.snoozedUntilMillis != null) MedicationReminderScheduler.scheduleSnooze(activity, id, intake.snoozedUntilMillis)
            }
            if (ok && status != "snoozed") MedicationReminderScheduler.cancelOverdueCheck(activity, id)
            if (ok) MedicationReminderReceiver.dismissNotification(activity, id)
            if (!ok) toast("Esta toma ya tiene un estado registrado.")
            rerender()
        }
        if (status == "taken") AlertDialog.Builder(activity).setTitle("¿Confirmas que la tomaste?")
            .setMessage("La app guardará esta acción en el historial.")
            .setNegativeButton("Volver", null).setPositiveButton("Sí, marcar tomada") { _, _ -> apply() }.show()
        else if (status == "skipped") AlertDialog.Builder(activity).setTitle("¿Registrar que no la tomaste?")
            .setNegativeButton("Volver", null).setPositiveButton("Registrar") { _, _ -> apply() }.show()
        else apply()
    }

    private fun syncNow(silent: Boolean = false) {
        if (readOnly && ownerId.isBlank()) return
        val sessionId = runCatching { EncryptedSessionStore(activity).read()?.userId }.getOrNull()
        if (sessionId.isNullOrBlank()) {
            syncState?.text = "Modo local: al iniciar sesión y recuperar Internet podrás sincronizar."
            return
        }
        syncState?.text = "Sincronizando de forma segura…"
        syncExecutor.execute {
            val result = runCatching { repository.synchronize(ownerId, readOnly) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                syncState?.text = when {
                    result.isSuccess && readOnly -> "Datos compartidos actualizados desde Supabase."
                    result.isSuccess -> if (db.syncPendingCount(ownerId) == 0) "Sincronizado con Supabase." else "Hay cambios locales pendientes de sincronizar."
                    result.exceptionOrNull() is BackendFailure && (result.exceptionOrNull() as BackendFailure).category == BackendFailure.Category.NETWORK -> "Sin Internet. Los medicamentos y recordatorios siguen guardados aquí."
                    else -> "No se pudo sincronizar. Tus datos locales se conservaron; puedes reintentar."
                }
                if (!silent) toast(if (result.isSuccess) "Actualizado" else "Sincronización pendiente")
                rerender(keepSyncState = true)
            }
        }
    }

    private fun rerender(keepSyncState: Boolean = false) {
        val target = parent ?: return
        val previous = syncState?.text?.toString()
        target.removeAllViews()
        render(target)
        if (keepSyncState && !previous.isNullOrBlank()) syncState?.text = previous
    }

    private fun todayIntakes(): List<MedicationIntake> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return db.intakes(ownerId, start, end).filter { it.status != "cancelled" }.sortedBy { it.scheduledAtMillis }
    }

    private fun nextIntakes(): List<Pair<Medication, Long>> {
        val now = System.currentTimeMillis()
        return db.activeMedications(ownerId).flatMap { medication ->
            medication.scheduleTimes.mapNotNull { time ->
                MedicationSchedulePolicy.nextOccurrence(medication, time, now, ZoneId.systemDefault())?.let { medication to it }
            }
        }.sortedBy { it.second }
    }

    private fun displayStatus(intake: MedicationIntake): String {
        if (intake.status == "pending" && System.currentTimeMillis() - intake.scheduledAtMillis > MedicationDatabase.OVERDUE_AFTER_MS) return "Vencida · sin confirmar"
        return when (intake.status) {
            "pending" -> "Pendiente"
            "taken" -> "Tomada · confirmada"
            "skipped" -> "No tomada · confirmada"
            "overdue" -> "Vencida · sin confirmar"
            "snoozed" -> "Pospuesta · aviso pendiente"
            else -> "Estado: ${intake.status}"
        }
    }

    private fun requestExactAlarmPermission() {
        AlertDialog.Builder(activity).setTitle("Horarios precisos")
            .setMessage("Android puede exigir un permiso especial para avisar cerca de la hora indicada. Si no lo autorizas, TzindaApp usará recordatorios aproximados.")
            .setNegativeButton("Seguir con avisos aproximados", null)
            .setPositiveButton("Abrir ajustes") { _, _ ->
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
                }.onFailure { toast("Abre Ajustes de Android para permitir alarmas y recordatorios.") }
            }.show()
    }

    private fun input(parent: LinearLayout, title: String, value: String, multiline: Boolean = false): EditText {
        parent.addView(label(title, ink, 15, true))
        val input = EditText(activity).apply {
            setText(value); textSize = 16f; setTextColor(ink); setHintTextColor(muted)
            hint = title
            inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (multiline) minLines = 2
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(14).toFloat(); setStroke(dp(1), outline) }
        }
        parent.addView(input, LinearLayout.LayoutParams(-1, if (multiline) dp(82) else dp(54)).apply { bottomMargin = dp(10) })
        return input
    }

    private fun heading(parent: LinearLayout, eyebrow: String, title: String, subtitle: String) {
        parent.addView(label(eyebrow, accent, 13, true))
        parent.addView(label(title, ink, 29, true))
        parent.addView(label(subtitle, muted, 16).apply { setPadding(0, dp(4), 0, dp(12)) })
    }

    private fun card(parent: LinearLayout, color: Int = cardColor) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), outline) }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun label(value: String, color: Int = ink, size: Int = 16, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3)); contentDescription = value
    }

    private fun button(title: String, primary: Boolean = true, action: () -> Unit) = Button(activity).apply {
        text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
        setTextColor(if (primary) if (dark) Color.parseColor("#00391A") else Color.WHITE else accent)
        background = GradientDrawable().apply { setColor(if (primary) accent else softSurface); cornerRadius = dp(28).toFloat(); setStroke(dp(1), outline) }
        minHeight = dp(54); stateListAnimator = null; elevation = 0f
        setOnClickListener { action() }
    }

    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(activity), LinearLayout.LayoutParams(1, dp(height))) }
    private fun formatTime(millis: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    private fun formatDateTime(millis: Long) = SimpleDateFormat("EEE d MMM · HH:mm", Locale("es", "MX")).format(Date(millis))
    private fun formatLocalDate(value: String): String = runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d 'de' MMM yyyy", Locale("es", "MX")))
    }.getOrDefault(value)
    private fun toast(message: String) = android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 503
        private val syncExecutor = Executors.newSingleThreadExecutor()
    }
}
