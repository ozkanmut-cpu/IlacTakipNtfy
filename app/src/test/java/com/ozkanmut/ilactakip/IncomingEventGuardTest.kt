package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class IncomingEventGuardTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_events", "dosefolk_remote_event_receipts", "dosefolk_permissions")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun selfEvent(id: String) = DoseEvent(
        eventId = id,
        type = "taken",
        time = "08:00",
        actor = "Me",
        actorTopic = Store.topic(c),
        timestamp = System.currentTimeMillis(),
        medications = emptyList(),
        syncState = "synced",
        revision = 1L,
        scheduledDate = LocalDate.now().toString(),
        ownerId = Store.topic(c)
    )

    @Test
    fun unseenSelfEvent_isRejectedAsSpoofed() {
        assertFalse(IncomingEventGuard.shouldProcess(c, selfEvent("forged-self")))
        assertFalse(RemoteEventReceiptStore.processed(c, "forged-self"))
    }

    @Test
    fun knownLocalEcho_isSkippedAndRemembered() {
        val e = selfEvent("known-self")
        EventStore.append(c, e)
        assertFalse(IncomingEventGuard.shouldProcess(c, e))
        assertTrue(RemoteEventReceiptStore.processed(c, e.eventId))
    }

    @Test
    fun processedReceipt_blocksReplay() {
        RemoteEventReceiptStore.markProcessed(c, "remote-1")
        val remote = selfEvent("remote-1").copy(actorTopic = "peer-topic", ownerId = "peer-topic")
        assertFalse(IncomingEventGuard.shouldProcess(c, remote))
    }

    @Test
    fun revokedPeerEvent_isRejectedBeforePersistence() {
        val topic = "peer-revoked"
        val person = Person("person-1", "Peer", topic)
        Store.savePeople(c, listOf(person))

        val authorized = selfEvent("authorized-before-revoke").copy(
            actor = "Peer",
            actorTopic = topic,
            ownerId = Store.topic(c)
        )
        assertTrue(IncomingEventGuard.shouldProcess(c, authorized))

        Store.savePeople(c, emptyList())
        PermissionPolicy.clearPeer(c, topic)
        RevocationCleanup.clearPeer(c, topic)

        val delayed = authorized.copy(eventId = "delayed-after-revoke", revision = 2L)
        assertFalse(IncomingEventGuard.shouldProcess(c, delayed))
        assertFalse(EventStore.contains(c, delayed.eventId))
    }

    @Test
    fun futureProtocolVersion_isRejected() {
        assertTrue(IncomingEventGuard.supportedDosePayload(JSONObject().put("v", 9)))
        assertFalse(IncomingEventGuard.supportedDosePayload(JSONObject().put("v", 10)))
        assertTrue(IncomingEventGuard.supportedDosePayload(JSONObject()))
    }
}
