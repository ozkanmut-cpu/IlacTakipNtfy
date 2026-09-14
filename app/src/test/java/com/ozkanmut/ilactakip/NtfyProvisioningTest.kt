package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtfyProvisioningTest {
    @Test
    fun parsesEnrollmentLink() {
        val value = NtfyProvisioning.parseEnrollmentUrl(
            "dosefolk://enroll?installId=install-1234&ticket=one%20time%20ticket"
        )
        assertEquals("install-1234", value?.installId)
        assertEquals("one time ticket", value?.ticket)
    }

    @Test
    fun rejectsWrongSchemeAndInvalidInstallId() {
        assertNull(NtfyProvisioning.parseEnrollmentUrl("https://enroll?installId=install-1234&ticket=t"))
        assertNull(NtfyProvisioning.parseEnrollmentUrl("dosefolk://enroll?installId=x&ticket=t"))
    }

    @Test
    fun validatesProvisioningResponseBinding() {
        assertEquals(
            "secret-token",
            NtfyProvisioning.decodeProvisioningResponse(
                "{\"installId\":\"install-1234\",\"credential\":\" secret-token \"}",
                "install-1234"
            )
        )
        assertNull(
            NtfyProvisioning.decodeProvisioningResponse(
                "{\"installId\":\"other-install\",\"credential\":\"secret-token\"}",
                "install-1234"
            )
        )
        assertNull(
            NtfyProvisioning.decodeProvisioningResponse(
                "{\"installId\":\"install-1234\",\"credential\":\"   \"}",
                "install-1234"
            )
        )
    }
}
