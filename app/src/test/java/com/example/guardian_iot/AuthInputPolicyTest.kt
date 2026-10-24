package com.example.guardian_iot

import org.junit.Assert.*
import org.junit.Test

class AuthInputPolicyTest {
    @Test fun validatesEmailWithoutAcceptingWhitespaceOrMissingDomain() {
        assertTrue(AuthInputPolicy.validEmail(" user@example.com "))
        assertFalse(AuthInputPolicy.validEmail("user@localhost"))
        assertFalse(AuthInputPolicy.validEmail("user example.com"))
    }

    @Test fun enforcesPasswordLengthAndConfirmation() {
        assertFalse(AuthInputPolicy.validPassword("short"))
        assertFalse(AuthInputPolicy.validPassword("x".repeat(129)))
        assertTrue(AuthInputPolicy.validPassword("correct-horse"))
        assertFalse(AuthInputPolicy.validRegistration("Ana", "ana@example.com", "correct-horse", "different", true))
    }

    @Test fun registrationRequiresNameAndExplicitPrivacyConsent() {
        assertFalse(AuthInputPolicy.validRegistration("Ana", "ana@example.com", "correct-horse", "correct-horse", false))
        assertFalse(AuthInputPolicy.validRegistration(" ", "ana@example.com", "correct-horse", "correct-horse", true))
        assertTrue(AuthInputPolicy.validRegistration("Ana", "ana@example.com", "correct-horse", "correct-horse", true))
    }
}
