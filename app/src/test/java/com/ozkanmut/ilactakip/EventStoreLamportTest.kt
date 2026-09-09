package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventStoreLamportTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun event(id: String, revision: Long) = DoseEvent(
        eventId = id,
        type = "alarm",
        time = "08:00",
        actor = "seed",
        actorTopic = "seed-topic",
        timestamp = 1L,
        medications = emptyList(),
        syncState = "synced",
        revision = revision,
        scheduledDate = "2026-09-09"
    )

    @Test
    fun appendedRevision_advancesNextLocalRevision() {
        EventStore.append(c, event("seed-7", 7L))
        assertEquals(8L, EventStore.nextRevision(c))
    }

    @Test
    fun duplicateAppend_doesNotAdvanceClockAgain() {
        EventStore.append(c, event("seed-7", 7L))
        EventStore.append(c, event("seed-7", 99L))
        assertEquals(8L, EventStore.nextRevision(c))
    }
}
