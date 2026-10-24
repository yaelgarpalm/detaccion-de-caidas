package com.example.guardian_iot

import org.junit.Assert.*
import org.junit.Test

class AlertLogicTest {
    @Test fun internationalNumbersAndInvalidInput() {
        assertEquals("+525512345678", PhoneNumbers.normalize("+52 (55) 1234-5678"))
        for (phone in listOf("", "123", "55+12345678", "++525512345678", "abc5512345678", "1234567890123456")) assertNull(PhoneNumbers.normalize(phone))
    }
    @Test fun emptyRowsAndDuplicates() {
        val contacts = PhoneNumbers.validateRows(listOf(TrustedContact(" A ", "+525512345678"), TrustedContact("B", "52 55 1234 5678"), TrustedContact("", "")))
        assertEquals(listOf(TrustedContact("A", "+525512345678")), contacts)
    }
    @Test fun rejectPartialOrEmptyForms() {
        for (rows in listOf(emptyList(), listOf(TrustedContact("Sin teléfono", "")), listOf(TrustedContact("A", "5512345678"), TrustedContact("B", "123")))) {
            assertThrows(IllegalArgumentException::class.java) { PhoneNumbers.validateRows(rows) }
        }
    }
    @Test fun maximumFiveContacts() {
        assertEquals(5, PhoneNumbers.validateRows((0..4).map { TrustedContact("$it", "551234567$it") }).size)
        assertThrows(IllegalArgumentException::class.java) { PhoneNumbers.validateRows((0..5).map { TrustedContact("$it", "551234567$it") }) }
    }
    @Test fun emergencyFirstAndNoDuplicateSms() {
        val a = TrustedContact("A", "+525512345678")
        val emergency = TrustedContact("Emergencia", "525512345678")
        assertEquals(listOf(emergency, TrustedContact("B", "5587654321")), PhoneNumbers.recipients(listOf(a, TrustedContact("B", "5587654321")), emergency))
    }
    @Test fun dispatchQueuesEveryRecipientWithoutChooserAndContinuesAfterFailure() {
        val recipients = (0..4).map { TrustedContact("C$it", "551234567$it") }
        val called = mutableListOf<String>()
        val result = DirectSmsDispatcher.dispatch(recipients, { "Mensaje para ${it.name}" }, SmsTransport { contact, body ->
            called.add(contact.phone)
            assertTrue(body.contains(contact.name))
            if (contact == recipients[1]) throw IllegalStateException("sin servicio")
        })
        assertEquals(recipients.map { it.phone }, called)
        assertEquals(4, result.requested)
        assertEquals(1, result.failures)
    }
    @Test fun countdownOnlyExpiresOnceAndCanDetectLaterIncidents() {
        val gate = IncidentGate()
        assertTrue(gate.begin("one")); assertFalse(gate.begin("duplicate"))
        assertTrue(gate.expire()); assertFalse(gate.expire()); assertFalse(gate.cancel())
        gate.finish(); assertTrue(gate.begin("two")); assertTrue(gate.cancel()); assertFalse(gate.expire())
        assertTrue(gate.begin("manual", immediate = true)); assertEquals(IncidentGate.State.DISPATCHING, gate.state)
    }
    private fun settle(detector: FallDetector, start: Long, linear: Boolean = false): Boolean {
        var triggered = false
        for (offset in 0L..300L step 20) {
            val detected = detector.sample(if (linear) 0.2 else 9.81, start + offset, !linear)
            if (offset < 300) assertFalse("No alert before 300 ms of settling", detected)
            triggered = triggered || detected
        }
        return triggered
    }
    private fun freeFall(detector: FallDetector, start: Long) {
        for (offset in 0L..100L step 20) assertFalse(detector.sample(0.8, start + offset))
    }
    @Test fun markedLandingAfterFreeFallRequiresBriefConfirmation() {
        val detector = FallDetector()
        freeFall(detector, 0)
        assertFalse(detector.sample(24.0, 140))
        assertTrue(settle(detector, 160))
    }
    @Test fun sustainedMarkedImpactCanTriggerWithoutFreeFall() {
        val detector = FallDetector()
        for (now in 0L..40L step 20) assertFalse(detector.sample(30.0, now))
        assertTrue(settle(detector, 60))
    }
    @Test fun isolatedImpactDoesNotTriggerEvenWhenItSettlesAfterwards() {
        for (peak in listOf(25.0, 30.0, 45.0, 80.0)) {
            val detector = FallDetector()
            assertFalse(detector.sample(peak, 0))
            for (now in 20L..2_000L step 20) assertFalse(detector.sample(9.81, now))
        }
    }
    @Test fun walkingWithPeriodicStepsAndOccasionalSpikesDoesNotTrigger() {
        val detector = FallDetector()
        for (i in 0..3_000) {
            val magnitude = if (i % 500 == 250) 31.0 else 9.81 + 4.0 * kotlin.math.sin(i * .25)
            assertFalse("Walking sample $i", detector.sample(magnitude, i * 20L))
        }
    }
    @Test fun lightHandSwingThatPreviouslyTriggeredDoesNotTrigger() {
        val detector = FallDetector()
        for (cycle in 0..50) {
            val start = cycle * 1_000L
            for (t in 0L..60L step 20) assertFalse(detector.sample(0.8, start + t))
            assertFalse(detector.sample(18.0, start + 80))
            for (t in 100L..980L step 20) assertFalse(detector.sample(9.81, start + t))
        }
    }
    @Test fun softLandingAfterFreeFallDoesNotMeetFirmerProfile() {
        val detector = FallDetector()
        freeFall(detector, 0)
        assertFalse(detector.sample(14.0, 140))
        for (now in 160L..2_000L step 20) assertFalse(detector.sample(9.81, now))
    }
    @Test fun oldFreeFallCannotCombineWithLaterMovement() {
        val detector = FallDetector()
        freeFall(detector, 0)
        for (now in 120L..1_000L step 20) assertFalse(detector.sample(9.81, now))
        assertFalse(detector.sample(24.0, 1_020))
        assertFalse(settle(detector, 1_040))
    }
    @Test fun sparseMeasurementsDoNotInventSustainedImpact() {
        val detector = FallDetector()
        assertFalse(detector.sample(30.0, 0))
        assertFalse(detector.sample(30.0, 200))
        assertFalse(detector.sample(30.0, 400))
        assertFalse(settle(detector, 420))
    }
    @Test fun movementAfterImpactMustSettleBeforeConfirmationExpires() {
        val detector = FallDetector()
        for (t in 0L..40L step 20) assertFalse(detector.sample(30.0, t))
        for (t in 60L..1_560L step 20) assertFalse(detector.sample(18.0, t))
        assertFalse(settle(detector, 1_580))
    }
    @Test fun gapsCannotCountAsContinuousSettling() {
        val detector = FallDetector()
        freeFall(detector, 0); detector.sample(24.0, 140)
        assertFalse(detector.sample(9.81, 160))
        assertFalse(detector.sample(9.81, 800))
        for (t in 820L..1_080L step 20) assertFalse(detector.sample(9.81, t))
        assertTrue(detector.sample(9.81, 1_100))
    }
    @Test fun secondConfirmedFallAfterCooldownIsAllowed() {
        val detector = FallDetector()
        for (t in 0L..40L step 20) detector.sample(30.0, t)
        assertTrue(settle(detector, 60))
        for (t in 1_000L..1_040L step 20) assertFalse(detector.sample(30.0, t))
        assertFalse(settle(detector, 1_060))
        for (t in 46_000L..46_040L step 20) assertFalse(detector.sample(30.0, t))
        assertTrue(settle(detector, 46_060))
    }
    @Test fun linearAccelerationRequiresSustainedImpactAndSettling() {
        val detector = FallDetector()
        for (t in 0L..1_000L step 20) assertFalse(detector.sample(0.0, t, false))
        for (t in 1_020L..1_060L step 20) assertFalse(detector.sample(21.0, t, false))
        assertTrue(settle(detector, 1_080, linear = true))
    }
    @Test fun invalidOrOutOfOrderMeasurementsClearPartialEvidence() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val detector = FallDetector()
            freeFall(detector, 0)
            assertFalse(detector.sample(invalid, 120))
            assertFalse(detector.sample(24.0, 140))
            assertFalse(settle(detector, 160))
        }
        val detector = FallDetector()
        freeFall(detector, 100)
        assertFalse(detector.sample(9.81, 80))
        assertFalse(detector.sample(24.0, 220))
        assertFalse(settle(detector, 240))
    }
}
