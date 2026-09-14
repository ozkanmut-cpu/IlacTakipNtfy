package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtfyAuthTest {
    @Test
    fun bearerValue_trimsCredentialAndBuildsHeader() {
        assertEquals("Bearer secret-token", NtfyAuth.bearerValue("  secret-token  "))
    }

    @Test
    fun bearerValue_rejectsMissingOrBlankCredential() {
        assertNull(NtfyAuth.bearerValue(null))
        assertNull(NtfyAuth.bearerValue("   "))
    }
}
