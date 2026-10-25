package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MedicationSchedulePolicyTest {
    private val zone = ZoneId.of("America/Mexico_City")

    @Test
    fun validatesRequiredFieldsAndDailyTimes() {
        assertNull(MedicationSchedulePolicy.validate("Medicamento", "1 tableta", "Diaria", listOf("08:00", "20:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("", "1 tableta", "Diaria", listOf("08:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "", "Diaria", listOf("08:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "1", "Diaria", listOf("08:00", "08:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "1", "Diaria", listOf("25:65"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "1", "Diaria", listOf("08:00"), "2026-10-25", "2026-10-24"))
    }

    @Test
    fun schedulesDailyAndEveryOtherDayFromStartDate() {
        val start = LocalDate.parse("2026-10-24")
        val firstMorning = start.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        val daily = medication("Diaria")
        val dailyAtEight = MedicationSchedulePolicy.nextOccurrence(daily, "08:00", firstMorning, zone)
        assertEquals(start.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), dailyAtEight)

        val afterFirstEvening = start.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        val everyOtherDay = MedicationSchedulePolicy.nextOccurrence(medication("Cada 2 días"), "08:00", afterFirstEvening, zone)
        assertEquals(start.plusDays(2).atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), everyOtherDay)
    }

    @Test
    fun respectsEndDateAndDoesNotGenerateInvalidSchedules() {
        val afterEnd = LocalDate.parse("2026-10-25").atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        assertNull(MedicationSchedulePolicy.nextOccurrence(medication("Diaria", end = "2026-10-25"), "08:00", afterEnd, zone))
        assertNull(MedicationSchedulePolicy.nextOccurrence(medication("Diaria"), "25:00", System.currentTimeMillis(), zone))
    }

    @Test
    fun idsAreStablePerMedicationAndScheduledInstance() {
        val time = 1_798_000_000_000L
        val first = MedicationSchedulePolicy.scheduleInstanceId("med-1", time)
        assertEquals(first, MedicationSchedulePolicy.scheduleInstanceId("med-1", time))
        assertFalse(first == MedicationSchedulePolicy.scheduleInstanceId("med-2", time))
        assertFalse(first == MedicationSchedulePolicy.scheduleInstanceId("med-1", time + 60_000L))
        assertTrue(runCatching { java.util.UUID.fromString(first) }.isSuccess)
    }

    private fun medication(frequency: String, end: String? = null) = Medication(
        id = "med-1", ownerUserId = "owner-1", name = "Prueba", prescribedDose = "Como indicada",
        presentation = "", frequency = frequency, scheduleTimes = listOf("08:00"),
        startDate = "2026-10-24", endDate = end, instructions = "", isActive = true,
    )
}
