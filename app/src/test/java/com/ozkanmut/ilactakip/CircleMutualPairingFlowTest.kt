package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CircleMutualPairingFlowTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("dosefolk_circle_presence", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun presenceRequiresCorrectTargetBeforePeerIsConfirmed() {
        val local = Store.topic(c)
        val peer = "dosefolk-peer-a"
        val wrong = "dosefolk-peer-b"
        assertFalse(CirclePresence.confirmed(c, peer))

        val wrongTarget = DoseEvent(
            eventId = "presence-wrong",
            type = "circle_presence",
            time = "",
            medications = emptyList(),
            actor = "Peer A",
            timestamp = 1L,
            actorTopic = peer,
            ownerId = peer,
            targetTopic = wrong
        )
        CirclePresence.markSeen(c, wrongTarget)
        assertFalse(CirclePresence.confirmed(c, peer))

        val correctTarget = wrongTarget.copy(eventId = "presence-correct", targetTopic = local)
        CirclePresence.markSeen(c, correctTarget)
        assertTrue(CirclePresence.confirmed(c, peer))
    }

    @Test
    fun revokeClearsPresenceAndRepairsRequireFreshConfirmation() {
        val local = Store.topic(c)
        val peer = "dosefolk-peer-repair"
        val event = DoseEvent(
            eventId = "presence-before-revoke",
            type = "circle_presence",
            time = "",
            medications = emptyList(),
            actor = "Peer",
            timestamp = 2L,
            actorTopic = peer,
            ownerId = peer,
            targetTopic = local
        )
        CirclePresence.markSeen(c, event)
        assertTrue(CirclePresence.confirmed(c, peer))

        CirclePresence.clear(c, peer)
        assertFalse(CirclePresence.confirmed(c, peer))

        CirclePresence.markSeen(c, event.copy(eventId = "presence-after-repair", timestamp = 3L))
        assertTrue(CirclePresence.confirmed(c, peer))
    }
}
