package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseEventLegacyOrderingTest {
    private fun event(id: String, actor: String, timestamp: Long, revision: Long) = DoseEvent(
        eventId = id,
        type = "taken",
        time = "08:00",
        actor = actor,
        actorTopic = actor,
        timestamp = timestamp,
        medications = emptyList(),
        revision = revision
    )

    @Test
    fun legacyRevisionZeroEvents_useTimestampBeforeStableIds() {
        val older = event("z-event", "z-actor", 1_000L, 0L)
        val newer = event("a-event", "a-actor", 2_000L, 0L)

        assertEquals(newer, listOf(newer, older).maxWithOrNull(DoseEventOrder.global))
        assertEquals(newer, listOf(older, newer).maxWithOrNull(DoseEventOrder.global))
    }

    @Test
    fun versionedEvents_keepRevisionBeforeClockSkew() {
        val futureClockLowerRevision = event("z-event", "z-actor", 9_999_999L, 4L)
        val pastClockHigherRevision = event("a-event", "a-actor", 1L, 5L)

        assertEquals(pastClockHigherRevision, listOf(futureClockLowerRevision, pastClockHigherRevision).maxWithOrNull(DoseEventOrder.global))
    }

    @Test
    fun withinActorLegacyEvents_useTimestampBeforeEventId() {
        val older = event("z-event", "same", 1_000L, 0L)
        val newer = event("a-event", "same", 2_000L, 0L)

        assertEquals(newer, listOf(newer, older).maxWithOrNull(DoseEventOrder.withinActor))
    }
}
