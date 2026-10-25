package com.example.guardian_iot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MedicationDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: MedicationDatabase

    @Before fun createFreshDatabase() {
        context.deleteDatabase("tzinda_medications.db")
        database = MedicationDatabase(context)
    }

    @After fun closeDatabase() {
        database.close()
        context.deleteDatabase("tzinda_medications.db")
    }

    @Test fun offlineSaveAndScheduleInstanceAreStableAndIdempotent() {
        val medication = medication()
        database.saveMedication(medication)
        val first = database.ensureIntake(medication, 1_800_000_000_000L)
        val second = database.ensureIntake(medication, 1_800_000_000_000L)
        assertEquals(first.id, second.id)
        assertEquals(1, database.intakes(OWNER, first.scheduledAtMillis - 1, first.scheduledAtMillis + 1).size)
        assertEquals("pending", database.getIntake(first.id)?.status)
        assertEquals("pending", database.pendingMedications(OWNER).single().syncState)
    }

    @Test fun takenSkippedAndSnoozedActionsAreExplicitAndIdempotent() {
        val medication = medication()
        database.saveMedication(medication)
        val taken = database.ensureIntake(medication, 1_800_000_000_000L)
        assertTrue(database.recordAction(taken.id, "taken", 1_800_000_100_000L))
        assertFalse(database.recordAction(taken.id, "taken", 1_800_000_200_000L))
        assertEquals("taken", database.getIntake(taken.id)?.status)

        val skipped = database.ensureIntake(medication, 1_800_000_300_000L)
        assertTrue(database.recordAction(skipped.id, "skipped", 1_800_000_400_000L))
        assertEquals("skipped", database.getIntake(skipped.id)?.status)

        val snoozed = database.ensureIntake(medication, 1_800_000_500_000L)
        assertTrue(database.recordAction(snoozed.id, "snoozed", 1_800_000_600_000L, 1_800_001_200_000L))
        assertFalse(database.recordAction(snoozed.id, "snoozed", 1_800_000_700_000L, 1_800_001_300_000L))
        assertEquals("snoozed", database.getIntake(snoozed.id)?.status)
        assertEquals(1, database.getIntake(snoozed.id)?.snoozeCount)
    }

    @Test fun overdueAndArchiveKeepPreviouslyConfirmedHistory() {
        val medication = medication()
        database.saveMedication(medication)
        val past = database.ensureIntake(medication, 1_700_000_000_000L)
        assertTrue(database.recordAction(past.id, "taken", 1_700_000_100_000L))
        val future = database.ensureIntake(medication, 1_900_000_000_000L)
        database.deleteMedication(OWNER, medication.id)
        assertTrue(database.getMedication(OWNER, medication.id)!!.isDeleted)
        assertEquals("taken", database.getIntake(past.id)?.status)
        assertEquals("cancelled", database.getIntake(future.id)?.status)
    }

    private fun medication() = Medication(
        id = "medication-test-id", ownerUserId = OWNER, name = "Test medicine", prescribedDose = "As prescribed",
        presentation = "", frequency = "Diaria", scheduleTimes = listOf("08:00"), startDate = "2026-10-24",
        endDate = null, instructions = "", isActive = true,
    )

    companion object { private const val OWNER = "owner-test-id" }
}
