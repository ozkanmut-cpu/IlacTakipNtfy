package com.ozkanmut.ilactakip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmRegistrationPolicyTest {
    @Test
    fun unprovisionedInstallDoesNotRegister() {
        assertFalse(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = false,
                hasInstallId = true,
                hasGatewayCredential = true,
                tokenPresent = true,
                tokenHashChanged = true,
                stale = true
            )
        )
    }

    @Test
    fun missingInstallIdDoesNotRegister() {
        assertFalse(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = true,
                hasInstallId = false,
                hasGatewayCredential = true,
                tokenPresent = true,
                tokenHashChanged = true,
                stale = true
            )
        )
    }

    @Test
    fun missingGatewayCredentialDoesNotRegister() {
        assertFalse(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = true,
                hasInstallId = true,
                hasGatewayCredential = false,
                tokenPresent = true,
                tokenHashChanged = true,
                stale = true
            )
        )
    }

    @Test
    fun missingFirebaseTargetDoesNotRegister() {
        assertFalse(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = true,
                hasInstallId = true,
                hasGatewayCredential = true,
                tokenPresent = false,
                tokenHashChanged = true,
                stale = true
            )
        )
    }

    @Test
    fun firstFirebaseTargetRegisters() {
        assertTrue(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = true,
                hasInstallId = true,
                hasGatewayCredential = true,
                tokenPresent = true,
                tokenHashChanged = true,
                stale = false
            )
        )
    }

    @Test
    fun changedFirebaseTargetRegisters() {
        assertTrue(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = true,
                hasInstallId = true,
                hasGatewayCredential = true,
                tokenPresent = true,
                tokenHashChanged = true,
                stale = false
            )
        )
    }

    @Test
    fun unchangedFreshFirebaseTargetDoesNotRegister() {
        assertFalse(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = true,
                hasInstallId = true,
                hasGatewayCredential = true,
                tokenPresent = true,
                tokenHashChanged = false,
                stale = false
            )
        )
    }

    @Test
    fun unchangedStaleFirebaseTargetRegisters() {
        assertTrue(
            FcmRegistrationPolicy.shouldRegister(
                isProvisioned = true,
                hasInstallId = true,
                hasGatewayCredential = true,
                tokenPresent = true,
                tokenHashChanged = false,
                stale = true
            )
        )
    }
}
