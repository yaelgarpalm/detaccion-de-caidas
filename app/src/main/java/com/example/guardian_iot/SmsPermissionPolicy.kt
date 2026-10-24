package com.example.guardian_iot

/** A manifest declaration or a saved preference never substitutes a runtime grant. */
object SmsPermissionPolicy {
    enum class State { GRANTED, UNSUPPORTED, POLICY_BLOCKED, SETTINGS_REQUIRED, REQUESTABLE }
    fun evaluate(supported: Boolean, granted: Boolean, policyBlocked: Boolean, requested: Boolean, rationale: Boolean): State = when {
        !supported -> State.UNSUPPORTED
        granted -> State.GRANTED
        policyBlocked -> State.POLICY_BLOCKED
        requested && !rationale -> State.SETTINGS_REQUIRED
        else -> State.REQUESTABLE
    }
}
