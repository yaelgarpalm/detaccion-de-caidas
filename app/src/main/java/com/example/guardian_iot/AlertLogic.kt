package com.example.guardian_iot

data class TrustedContact(
    val name: String,
    val phone: String,
    val id: String = java.util.UUID.nameUUIDFromBytes(
        PhoneNumbers.key(PhoneNumbers.normalize(phone) ?: phone).toByteArray(Charsets.UTF_8),
    ).toString(),
)

object PhoneNumbers {
    fun normalize(value: String): String? {
        val raw = value.trim()
        if (!Regex("^\\+?[0-9\\s().-]+$").matches(raw)) return null
        val digits = raw.filter { it in '0'..'9' }
        return if (digits.length in 7..15) (if (raw.startsWith('+')) "+" else "") + digits else null
    }
    fun key(phone: String) = phone.removePrefix("+")
    fun validateRows(rows: List<TrustedContact>): List<TrustedContact> {
        val seen = mutableSetOf<String>()
        val valid = rows.mapIndexedNotNull { i, row ->
            if (row.name.isBlank() && row.phone.isBlank()) return@mapIndexedNotNull null
            val phone = normalize(row.phone)
                ?: throw IllegalArgumentException("Contacto ${i + 1}: escribe un teléfono válido de 7 a 15 dígitos.")
            if (seen.add(key(phone))) TrustedContact(row.name.trim(), phone, row.id) else null
        }
        require(valid.isNotEmpty()) { "Agrega al menos un teléfono." }
        require(valid.size <= 5) { "Puedes guardar hasta cinco contactos." }
        return valid
    }
    fun recipients(trusted: List<TrustedContact>, emergency: TrustedContact?): List<TrustedContact> {
        val seen = mutableSetOf<String>()
        return (listOfNotNull(emergency) + trusted.take(5)).mapNotNull {
            val phone = normalize(it.phone) ?: return@mapNotNull null
            if (seen.add(key(phone))) TrustedContact(it.name, phone, it.id) else null
        }
    }
}

/** Reject isolated spikes and short hand swings. Confirm a marked fall sequence
 * with 300 ms of settling before starting the separate 20-second countdown.
 * Thresholds are engineering heuristics, not a clinically validated classifier.
 */
class FallDetector {
    private var freeFallSince: Long? = null
    private var freeFallSamples = 0
    private var freeFallConfirmedAt: Long? = null
    private var impactSince: Long? = null
    private var impactSamples = 0
    private var candidateAt: Long? = null
    private var stableSince: Long? = null
    private var stableSamples = 0
    private var previousSampleAt: Long? = null
    private var lastAlert: Long? = null
    fun sample(magnitude: Double, now: Long, includesGravity: Boolean = true): Boolean {
        if (!magnitude.isFinite() || magnitude < 0) { clearEvidence(); return false }
        val gap = previousSampleAt?.let { now - it }
        previousSampleAt = now
        if (gap != null && gap <= 0) { clearEvidence(); return false }
        if (gap != null && gap > 80) clearStreaks()
        if (lastAlert?.let { now - it < 45_000 } == true) return false
        candidateAt?.let { impact ->
            if (now - impact > 1_500) { clearEvidence(); return false }
            val settled = if (includesGravity) magnitude in 6.8..12.8 else magnitude <= 3.0
            if (settled) {
                if (stableSince == null) stableSince = now
                stableSamples++
                if (now - stableSince!! >= 300 && stableSamples >= 6) {
                    clearEvidence(); lastAlert = now; return true
                }
            } else { stableSince = null; stableSamples = 0 }
            return false
        }
        if (freeFallConfirmedAt?.let { now - it > 800 } == true) freeFallConfirmedAt = null
        if (includesGravity && magnitude < 4.0) {
            if (freeFallSince == null) freeFallSince = now
            freeFallSamples++
            if (now - freeFallSince!! >= 100 && freeFallSamples >= 5) freeFallConfirmedAt = now
        } else { freeFallSince = null; freeFallSamples = 0 }
        val landing = includesGravity && freeFallConfirmedAt != null && magnitude >= 22.0
        val markedImpact = magnitude >= if (includesGravity) 28.0 else 20.0
        if (markedImpact) {
            if (impactSince == null) impactSince = now
            impactSamples++
        } else { impactSince = null; impactSamples = 0 }
        val sustainedImpact = impactSince?.let { now - it >= 40 && impactSamples >= 3 } == true
        if (landing || sustainedImpact) {
            clearEvidence(); candidateAt = now
        }
        return false
    }
    private fun clearStreaks() {
        freeFallSince = null; freeFallSamples = 0
        impactSince = null; impactSamples = 0
        stableSince = null; stableSamples = 0
    }
    private fun clearEvidence() { clearStreaks(); freeFallConfirmedAt = null; candidateAt = null }
}

class IncidentGate {
    enum class State { IDLE, COUNTDOWN, DISPATCHING }
    var state = State.IDLE
        private set
    var id: String? = null
        private set
    fun begin(token: String, immediate: Boolean = false): Boolean {
        if (state != State.IDLE) return false
        id = token
        state = if (immediate) State.DISPATCHING else State.COUNTDOWN
        return true
    }
    fun expire(): Boolean {
        if (state != State.COUNTDOWN) return false
        state = State.DISPATCHING
        return true
    }
    fun cancel(): Boolean {
        if (state != State.COUNTDOWN) return false
        finish()
        return true
    }
    fun finish() { state = State.IDLE; id = null }
}

fun interface SmsTransport { fun queue(contact: TrustedContact, message: String) }
data class DispatchSummary(val requested: Int, val failures: Int)
object DirectSmsDispatcher {
    fun dispatch(recipients: List<TrustedContact>, message: (TrustedContact) -> String, transport: SmsTransport): DispatchSummary {
        var requested = 0
        var failures = 0
        for (contact in recipients) {
            try { transport.queue(contact, message(contact)); requested++ }
            catch (_: Exception) { failures++ }
        }
        return DispatchSummary(requested, failures)
    }
}
