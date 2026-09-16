package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
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

    @Test
    fun lifecycleSchedulesOnlyWhenNtfyPrerequisitesExist() {
        assertFalse(FcmRegistrationPolicy.shouldSchedule(false, true, true))
        assertFalse(FcmRegistrationPolicy.shouldSchedule(true, false, true))
        assertFalse(FcmRegistrationPolicy.shouldSchedule(true, true, false))
        assertTrue(FcmRegistrationPolicy.shouldSchedule(true, true, true))
    }

    @Test
    fun firebaseUnavailableRetriesWithoutInvalidatingNtfyProvisioning() {
        val decision = FcmRegistrationPolicy.lifecycleDecision(
            isProvisioned = true,
            hasInstallId = true,
            hasGatewayCredential = true,
            firebaseTargetAvailable = false
        )
        assertEquals(FcmRegistrationLifecycleDecision.RETRY_FIREBASE, decision)
        assertTrue(decision.preserveNtfyProvisioning)
    }

    @Test
    fun missingNtfyPrerequisiteIsNoopNotFirebaseFailure() {
        val decision = FcmRegistrationPolicy.lifecycleDecision(
            isProvisioned = true,
            hasInstallId = false,
            hasGatewayCredential = true,
            firebaseTargetAvailable = false
        )
        assertEquals(FcmRegistrationLifecycleDecision.NOOP, decision)
        assertTrue(decision.preserveNtfyProvisioning)
    }
}
