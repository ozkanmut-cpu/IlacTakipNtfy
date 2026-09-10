package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            "dosefolk_revoked_peers",
            "dosefolk_stock"
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
    fun remoteRevoke_cleansRelationshipAndBlocksPeer() {
        PairingLifecycle.applyRemoteRevoke(c, remoteEvent("revoke-1", "circle_revoked"))

        assertTrue(Store.people(c).none { it.topic == peer.topic })
        assertFalse(PermissionPolicy.allowed(c, peer.topic, CirclePermission.VIEW))
        assertFalse(IncomingEventGuard.shouldProcess(c, remoteEvent("late-event", "taken")))
    }

    @Test
    fun revokedStockSnapshot_isReceiptedAndCannotReplayAfterRePair() {
        PairingLifecycle.revoke(c, peer)
        val eventId = "old-stock-event"
        val payload = JSONObject()
            .put("protocolVersion", 2)
            .put("eventId", eventId)
            .put("type", StockSync.EVENT_TYPE)
            .put("ownerId", peer.topic)
            .put("actorTopic", peer.topic)
            .put("revision", 7L)
            .put("stock", StockEngine.toJson(MedicationStock("med-1", "Drug", 3, 10)))

        assertTrue(StockSync.applyIncoming(c, payload))
        assertTrue(RemoteEventReceiptStore.processed(c, eventId))
        assertNull(StockEngine.remoteForMedication(c, peer.topic, "med-1"))

        Store.savePeople(c, listOf(peer))
        PairingLifecycle.completeRePair(c, peer.topic)
        assertTrue(StockSync.applyIncoming(c, payload))
        assertNull(StockEngine.remoteForMedication(c, peer.topic, "med-1"))
    }
}
