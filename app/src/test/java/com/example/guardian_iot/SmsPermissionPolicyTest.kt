package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsPermissionPolicyTest {
    @Test fun freshInstallationCanRequestSms() {
        assertEquals(SmsPermissionPolicy.State.REQUESTABLE, SmsPermissionPolicy.evaluate(true, false, false, false, false))
    }
    @Test fun firstDenialAllowsAnExplicitRetry() {
        assertEquals(SmsPermissionPolicy.State.REQUESTABLE, SmsPermissionPolicy.evaluate(true, false, false, true, true))
    }
    @Test fun noMoreSystemPromptLeadsToSettingsInsteadOfRequestLoop() {
        assertEquals(SmsPermissionPolicy.State.SETTINGS_REQUIRED, SmsPermissionPolicy.evaluate(true, false, false, true, false))
    }
    @Test fun administrationBlockIsReportedSeparately() {
        assertEquals(SmsPermissionPolicy.State.POLICY_BLOCKED, SmsPermissionPolicy.evaluate(true, false, true, false, false))
    }
    @Test fun noTelephonyCannotBecomeSmsCapableByGrantingPermission() {
        assertEquals(SmsPermissionPolicy.State.UNSUPPORTED, SmsPermissionPolicy.evaluate(false, true, false, true, false))
    }
    @Test fun actualGrantDoesNotDependOnSavedRequestedFlag() {
        assertEquals(SmsPermissionPolicy.State.GRANTED, SmsPermissionPolicy.evaluate(true, true, false, false, false))
    }
}
