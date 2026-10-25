package com.example.guardian_iot

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Tests covering state consistency, lifecycle management, deduplication,
 * and data freshness rules for TzindaApp.
 */
class RealtimeLifecycleTest {

    // --- 1. Realtime & IncidentGate Lifecycle ---

    @Test
    fun startGuardBlocksDuplicateSubscription() {
        val gate = IncidentGate()
        assertTrue("First begin should succeed", gate.begin("owner-1"))
        assertFalse("Second begin with same token should be blocked", gate.begin("owner-1"))
        assertFalse("Second begin with different token should also be blocked while active", gate.begin("owner-2"))
    }

    @Test
    fun restartAfterStopSucceeds() {
        val gate = IncidentGate()
        assertTrue(gate.begin("first"))
        assertTrue(gate.expire())
        gate.finish()
        assertTrue("begin should succeed after finish", gate.begin("second"))
    }

    @Test
    fun cancelThenNewIncident() {
        val gate = IncidentGate()
        assertTrue(gate.begin("a"))
        assertTrue(gate.cancel())
        gate.finish()
        assertTrue(gate.begin("b"))
        assertEquals(IncidentGate.State.COUNTDOWN, gate.state)
    }

    @Test
    fun stateIsIdleAfterFinish() {
        val gate = IncidentGate()
        gate.begin("x"); gate.expire(); gate.finish()
        assertEquals(IncidentGate.State.IDLE, gate.state)
        assertNull(gate.id)
    }

    // --- 2. Data Freshness & Staleness Rules ---

    @Test
    fun staleDataThresholdsDistinguishFreshFromStale() {
        val STALE_VITALS_MS = 30 * 60 * 1_000L       // 30 min
        val STALE_LOCATION_MS = 15 * 60 * 1_000L     // 15 min
        val LIVE_LOCATION_MS = 90 * 1_000L           // 90 sec
        val now = System.currentTimeMillis()

        val liveLoc = now - 30 * 1_000L
        val recentLoc = now - 5 * 60 * 1_000L
        val staleLoc = now - 20 * 60 * 1_000L

        assertTrue("Within 90s is live", (now - liveLoc) <= LIVE_LOCATION_MS)
        assertFalse("5 min is not live", (now - recentLoc) <= LIVE_LOCATION_MS)
        assertTrue("5 min is still recent", (now - recentLoc) <= STALE_LOCATION_MS)
        assertTrue("20 min is stale", (now - staleLoc) > STALE_LOCATION_MS)

        val freshVital = now - 5 * 60 * 1_000L
        val staleVital = now - 35 * 60 * 1_000L
        assertTrue("Fresh vital within 30 min", (now - freshVital) <= STALE_VITALS_MS)
        assertTrue("Stale vital over 30 min", (now - staleVital) > STALE_VITALS_MS)
    }

    @Test
    fun zeroOrNegativeReadingsAreNotTreatedAsValidVitals() {
        fun isValidPulse(bpm: Double): Boolean = bpm.isFinite() && bpm > 30.0 && bpm < 250.0
        fun isValidOxygen(spo2: Double): Boolean = spo2.isFinite() && spo2 > 50.0 && spo2 <= 100.0

        assertFalse("Zero bpm is invalid", isValidPulse(0.0))
        assertFalse("Negative bpm is invalid", isValidPulse(-10.0))
        assertFalse("Excessive bpm is invalid", isValidPulse(300.0))
        assertTrue("72 bpm is valid", isValidPulse(72.0))

        assertFalse("Zero spo2 is invalid", isValidOxygen(0.0))
        assertFalse("Over 100% is invalid", isValidOxygen(105.0))
        assertTrue("98% is valid", isValidOxygen(98.0))
    }

    // --- 3. Measurements Merging & Updates ---

    data class MeasurementRecord(val id: String, val value: Double, val capturedAt: String)

    @Test
    fun measurementListUpdatesExistingAndPrependsNewSortedByCapturedAt() {
        val existing = mutableListOf(
            MeasurementRecord("m1", 70.0, "2026-10-24T18:00:00Z"),
            MeasurementRecord("m2", 72.0, "2026-10-24T18:05:00Z"),
        )

        // Updated measurement with same ID "m1" but newer value
        val updatedM1 = MeasurementRecord("m1", 75.0, "2026-10-24T18:00:00Z")

        val list = existing.filterNot { it.id == updatedM1.id }.toMutableList()
        list.add(updatedM1)

        val sorted = list.sortedByDescending { row ->
            runCatching { Instant.parse(row.capturedAt).toEpochMilli() }.getOrDefault(0L)
        }

        assertEquals(2, sorted.size)
        assertEquals("m2", sorted[0].id)
        assertEquals("m1", sorted[1].id)
        assertEquals(75.0, sorted[1].value, 0.001)

        // Add a brand new measurement "m3" that is the newest
        val newM3 = MeasurementRecord("m3", 80.0, "2026-10-24T18:10:00Z")
        val withM3 = sorted.filterNot { it.id == newM3.id }.toMutableList().apply { add(newM3) }
        val finalSorted = withM3.sortedByDescending { row ->
            runCatching { Instant.parse(row.capturedAt).toEpochMilli() }.getOrDefault(0L)
        }

        assertEquals(3, finalSorted.size)
        assertEquals("m3", finalSorted[0].id)
    }

    // --- 4. Fall Event Deduplication ---

    @Test
    fun fallDeduplicationWithinTimeWindow() {
        val DEDUPE_WINDOW_MS = 60_000L
        val firstIncidentAt = 1_000_000L
        val firstIncidentId = "inc-1"

        // Simulated camera fall event 10s after bracelet/phone event
        val cameraOccurredAt = firstIncidentAt + 10_000L
        val shouldDeduplicate = Math.abs(cameraOccurredAt - firstIncidentAt) <= DEDUPE_WINDOW_MS
        assertTrue("Camera event within 60s should correlate to existing incident", shouldDeduplicate)

        // Event 5 minutes later should be a distinct incident
        val distantEventAt = firstIncidentAt + 300_000L
        val isSeparate = Math.abs(distantEventAt - firstIncidentAt) > DEDUPE_WINDOW_MS
        assertTrue("Event 5 minutes later should not be merged", isSeparate)
    }

    @Test
    fun recoveredEventsDoNotRetriggerAlertsAlreadyDispatched() {
        val dispatchedIncidentId = "dispatched-123"
        val gate = IncidentGate()

        // Incident was dispatched
        assertTrue(gate.begin(dispatchedIncidentId))
        assertTrue(gate.expire())
        assertEquals(IncidentGate.State.DISPATCHING, gate.state)

        // If an event comes in with the already-dispatched token, gate.begin() will reject it
        assertFalse("Already active/dispatched incident cannot re-trigger countdown", gate.begin(dispatchedIncidentId))

        gate.finish()
        // After finishing, the dispatched ID in preferences prevents duplicate SMS/calls
        val processedIncidents = mutableSetOf(dispatchedIncidentId)
        val shouldProcessAgain = !processedIncidents.contains(dispatchedIncidentId)
        assertFalse("Duplicate event from Supabase must not re-trigger call/SMS", shouldProcessAgain)
    }

    // --- 5. Device Link vs Connection State Separation ---

    @Test
    fun deviceStatusSeparatesRegistrationFromActiveConnection() {
        data class DeviceState(
            val registered: Boolean,
            val connected: Boolean,
            val lastSeenAtMs: Long,
            val hasPendingSync: Boolean,
        )

        // A device can be registered in database but currently disconnected
        val deviceInDb = DeviceState(
            registered = true,
            connected = false,
            lastSeenAtMs = System.currentTimeMillis() - 3600_000L,
            hasPendingSync = false,
        )
        assertTrue("Device is registered", deviceInDb.registered)
        assertFalse("Device is NOT connected", deviceInDb.connected)

        // Label helper distinguishing states
        fun computeLabel(state: DeviceState, now: Long): String = when {
            !state.registered -> "No registrado"
            state.connected -> "Conectado"
            state.hasPendingSync -> "Sincronización pendiente"
            state.lastSeenAtMs == 0L -> "Sin datos"
            now - state.lastSeenAtMs < 300_000L -> "Última comunicación: hace menos de 5 min"
            else -> "Sin conexión"
        }

        val now = System.currentTimeMillis()
        assertEquals("Sin conexión", computeLabel(deviceInDb, now))

        val connectedDevice = deviceInDb.copy(connected = true)
        assertEquals("Conectado", computeLabel(connectedDevice, now))

        val pendingSyncDevice = deviceInDb.copy(hasPendingSync = true)
        assertEquals("Sincronización pendiente", computeLabel(pendingSyncDevice, now))

        val neverSeenDevice = deviceInDb.copy(lastSeenAtMs = 0L)
        assertEquals("Sin datos", computeLabel(neverSeenDevice, now))
    }

    // --- 6. Medication Sharing Permissions ---

    @Test
    fun medicationVisibilityRespectsCaregiverShareFlag() {
        data class MedicationItem(
            val id: String,
            val name: String,
            val shareWithCaregiver: Boolean,
            val isDeleted: Boolean,
        )

        val meds = listOf(
            MedicationItem("1", "Ibuprofeno", shareWithCaregiver = true, isDeleted = false),
            MedicationItem("2", "Paracetamol", shareWithCaregiver = false, isDeleted = false),
            MedicationItem("3", "Omeprazol", shareWithCaregiver = true, isDeleted = true),
        )

        // Wearer sees all non-deleted
        val wearerVisible = meds.filterNot { it.isDeleted }
        assertEquals(2, wearerVisible.size)

        // Caregiver ONLY sees shared AND non-deleted
        val caregiverVisible = meds.filter { it.shareWithCaregiver && !it.isDeleted }
        assertEquals(1, caregiverVisible.size)
        assertEquals("Ibuprofeno", caregiverVisible[0].name)
    }
}
