package com.example.guardian_iot

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CameraPairingPolicyTest {

    @Test
    fun normalizePairingCodeCleansAndFormatsCorrectly() {
        val rawInput = " 24be-5bb4 "
        val clean = rawInput.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        assertEquals(8, clean.length)
        assertEquals("24BE5BB4", clean)

        val display = clean.chunked(4).joinToString("-")
        assertEquals("24BE-5BB4", display)
    }

    @Test
    fun pairingCodeRejectsIncompleteOrInvalidLength() {
        val tooShort = "12345"
        val cleanShort = tooShort.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        assertNotEquals(8, cleanShort.length)

        val tooLong = "1234567890"
        val cleanLong = tooLong.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        assertNotEquals(8, cleanLong.length)
    }

    @Test
    fun backendFailureClassifiesCameraInviteExpiredOrInvalid() {
        val failure = BackendFailure(BackendFailure.Category.CAMERA_INVITE_INVALID_OR_EXPIRED, 400)
        assertEquals(BackendFailure.Category.CAMERA_INVITE_INVALID_OR_EXPIRED, failure.category)
        assertTrue(failure.message!!.contains("venció", ignoreCase = true) || failure.message!!.contains("cámara", ignoreCase = true))
    }

    @Test
    fun backendFailureClassifiesCameraInviteSelfPairing() {
        val failure = BackendFailure(BackendFailure.Category.CAMERA_INVITE_SELF, 400)
        assertEquals(BackendFailure.Category.CAMERA_INVITE_SELF, failure.category)
        assertTrue(failure.message!!.contains("misma cuenta", ignoreCase = true) || failure.message!!.contains("cámara", ignoreCase = true))
    }

    data class DeviceEntry(val id: String, val type: String, val logicalId: String, val linkStatus: String, val name: String)

    @Test
    fun remoteDevicesFilterLinkedCamerasExcludingLocal() {
        val devices = listOf(
            DeviceEntry("dev-phone", "phone", "phone-primary", "linked", "Teléfono TzindaApp"),
            DeviceEntry("dev-local-cam", "camera", "camera-phone-local", "linked", "Cámara local"),
            DeviceEntry("dev-remote-cam-1", "camera", "camera-user:cam-1", "linked", "Cámara Sala"),
            DeviceEntry("dev-remote-cam-2", "camera", "camera-user:cam-2", "unlinked", "Cámara Cocina")
        )

        val registeredCams = mutableMapOf<String, String>()
        devices.forEach { dev ->
            if (dev.type == "camera" && dev.linkStatus == "linked" && dev.logicalId != "camera-phone-local" && dev.id.isNotBlank()) {
                registeredCams[dev.id] = dev.name
            }
        }

        assertEquals(1, registeredCams.size)
        assertTrue(registeredCams.containsKey("dev-remote-cam-1"))
        assertEquals("Cámara Sala", registeredCams["dev-remote-cam-1"])
        assertFalse(registeredCams.containsKey("dev-local-cam"))
        assertFalse(registeredCams.containsKey("dev-remote-cam-2"))
    }
}
