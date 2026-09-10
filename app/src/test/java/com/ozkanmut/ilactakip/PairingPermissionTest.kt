package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingPermissionTest {
    private lateinit var c: Context
    private val peer = Person("peer-1", "Caregiver", "peer-topic", canEdit = false)

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_permissions",
            "dosefolk_remote_capabilities",
            "dosefolk_owner_scope",
            "dosefolk_remote_event_receipts",
            "dosefolk_events",
            "dosefolk_alert_outbox",
            "dosefolk_revoked_peers"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.savePeople(c, listOf(peer))
    }

    private fun remoteEvent(id: String, type: String) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = peer.name,
        actorTopic = peer.topic,
        timestamp = System.currentTimeMillis(),
        medications = emptyList(),
        syncState = "synced",
        revision = 1L,
        ownerId = Store.topic(c)
    )

    @Test
    fun pairedViewer_canSetStatusButCannotEditProgramByDefault() {
        assertTrue(PermissionPolicy.allowed(c, peer.topic, CirclePermission.SET_STATUS))
        assertFalse(PermissionPolicy.allowed(c, peer.topic, CirclePermission.EDIT_PROGRAM))
        assertTrue(IncomingEventGuard.shouldProcess(c, remoteEvent("taken-1", "taken")))
        assertFalse(IncomingEventGuard.shouldProcess(c, remoteEvent("program-1", "program_updated")))
    }

    @Test
    fun explicitPermissionOverride_isEnforcedLocally() {
        PermissionPolicy.set(c, peer.topic, CirclePermission.SET_STATUS, false)
        assertFalse(PermissionPolicy.allowed(c, peer.topic, CirclePermission.SET_STATUS))
        assertFalse(IncomingEventGuard.shouldProcess(c, remoteEvent("taken-2", "taken")))

        PermissionPolicy.set(c, peer.topic, CirclePermission.EDIT_PROGRAM, true)
        assertTrue(PermissionPolicy.allowed(c, peer.topic, CirclePermission.EDIT_PROGRAM))
        assertTrue(IncomingEventGuard.shouldProcess(c, remoteEvent("program-2", "program_updated")))
    }

    @Test
    fun revoke_removesPeerAndBlocksFutureEventsFromOldTopic() {
        PairingLifecycle.revoke(c, peer)

        assertTrue(Store.people(c).none { it.topic == peer.topic })
        CirclePermission.entries.forEach { permission ->
            assertFalse(PermissionPolicy.allowed(c, peer.topic, permission))
        }
        assertFalse(IncomingEventGuard.shouldProcess(c, remoteEvent("taken-after-revoke", "taken")))
    }

    @Test
    fun revokedPeer_isExcludedFromNormalSyncButSelectedForExplicitRePairDrain() {
        PairingLifecycle.revoke(c, peer)

        assertFalse(CircleTransport.subscriptionTopics(c).contains(peer.topic))
        assertEquals(listOf(peer.topic), CircleTransport.revokedDrainTopics(peer.topic))
        assertTrue(RevokedPeerFence.isRevoked(c, peer.topic))
    }

    @Test
    fun remoteRevoke_cleansRelationshipAndBlocksPeer() {
        PairingLifecycle.applyRemoteRevoke(c, remoteEvent("revoke-1", "circle_revoked"))

        assertTrue(Store.people(c).none { it.topic == peer.topic })
        assertFalse(PermissionPolicy.allowed(c, peer.topic, CirclePermission.VIEW))
        assertFalse(IncomingEventGuard.shouldProcess(c, remoteEvent("late-event", "taken")))
    }
}
