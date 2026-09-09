package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class InboundCanonicalReplayTest {
    private lateinit var c: Context
    private val date get() = LocalDate.now().toString()
    private val med = Medication("canonical-med", "Canonical Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_events", "ilac_takip").forEach {
            c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        Store.save(c, listOf(med))
    }

    private fun event(type: String, revision: Long, meds: List<Medication> = listOf(med)) = DoseEvent(
        eventId = "same-remote-event",
        type = type,
        time = "08:00",
        actor = "Remote",
        actorTopic = "remote-topic",
        timestamp = System.currentTimeMillis(),
        medications = meds,
        syncState = "synced",
        revision = revision,
        scheduledDate = date,
        ownerId = Store.topic(c)
    )

    @Test
    fun duplicateWithHigherRevision_doesNotAdvanceLamportClock() {
        val canonical = SyncEngine.persistCanonicalIncoming(c, event("taken", 7L))
        assertEquals(7L, canonical.revision)

        val replay = SyncEngine.persistCanonicalIncoming(c, event("missed", 99L))
        assertEquals("taken", replay.type)
        assertEquals(7L, replay.revision)

        assertEquals(8L, EventStore.nextRevision(c))
    }

    @Test
    fun duplicateWithAlteredPayload_returnsFirstDurableEvent() {
        val first = SyncEngine.persistCanonicalIncoming(c, event("taken", 2L))
        val fakeMed = Medication("fake-med", "Fake", "9 tablets", listOf("23:59"))
        val replay = SyncEngine.persistCanonicalIncoming(c, event("missed", 3L, listOf(fakeMed)))

        assertEquals(first.eventId, replay.eventId)
        assertEquals("taken", replay.type)
        assertEquals(2L, replay.revision)
        assertEquals("canonical-med", replay.medications.single().id)
        assertEquals(1, EventStore.load(c).count { it.eventId == first.eventId })
    }
}
