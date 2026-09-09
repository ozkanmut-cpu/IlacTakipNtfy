package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CapabilityEventGateTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("dosefolk_capability_order", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun event(id: String, type: String, revision: Long, timestamp: Long = revision) = DoseEvent(
        eventId = id,
        type = type,
        time = "EDIT_PROGRAM",
        actor = "owner",
        actorTopic = "owner-topic",
        timestamp = timestamp,
        medications = emptyList(),
        syncState = "synced",
        revision = revision,
        ownerId = "owner-topic"
    )

    @Test
    fun staleGrantReplayCannotOverrideNewerRevoke() {
        val grant = event("grant-1", "capability_edit_program_granted", 1L, 9_000L)
        val revoke = event("revoke-2", "capability_edit_program_revoked", 2L, 1_000L)

        assertTrue(CapabilityEventGate.accept(c, grant))
        assertTrue(CapabilityEventGate.accept(c, revoke))
        assertFalse(CapabilityEventGate.accept(c, grant))
    }

    @Test
    fun revisionWinsEvenWhenOlderEventHasFutureClock() {
        val revoke = event("revoke-5", "capability_edit_program_revoked", 5L, 1_000L)
        val staleGrant = event("grant-4", "capability_edit_program_granted", 4L, 9_999_999L)

        assertTrue(CapabilityEventGate.accept(c, revoke))
        assertFalse(CapabilityEventGate.accept(c, staleGrant))
    }

    @Test
    fun clearingPeerAllowsFreshPairingRevisionSequence() {
        val old = event("old-revoke", "capability_edit_program_revoked", 50L)
        assertTrue(CapabilityEventGate.accept(c, old))

        CapabilityEventGate.clearPeer(c, "owner-topic")

        val fresh = event("fresh-grant", "capability_edit_program_granted", 1L)
        assertTrue(CapabilityEventGate.accept(c, fresh))
    }
}
