package com.ozkanmut.ilactakip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyReprovisionPolicyTest {
    @Test
    fun `existing credentials may short circuit normal enrollment`() {
        assertTrue(
            NtfyProvisioning.shouldReuseExistingCredentials(
                alreadyProvisioned = true,
                installIdMatches = true,
                gatewayCredentialPresent = true,
                reprovisionRequired = false
            )
        )
    }

    @Test
    fun `reprovision required bypasses existing credential short circuit`() {
        assertFalse(
            NtfyProvisioning.shouldReuseExistingCredentials(
                alreadyProvisioned = true,
                installIdMatches = true,
                gatewayCredentialPresent = true,
                reprovisionRequired = true
            )
        )
    }
}
