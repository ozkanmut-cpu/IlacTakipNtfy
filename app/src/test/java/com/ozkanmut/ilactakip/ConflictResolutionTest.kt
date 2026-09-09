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
class ConflictResolutionTest {
    private lateinit var c: Context
    private val med = Medication("med-1", "Test Med", "1 tablet", listOf("08:00"))
    private val today = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_events", "dosefolk_program_rules", "dosefolk_owner_scope")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun event(id: String, type: String, actorTopic: String, timestamp: Long) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = actorTopic,
        actorTopic = actorTopic,
        timestamp = timestamp,
        medications = listOf(med),
        syncState = "synced",
        revision = timestamp,
        scheduledDate = today,
        ownerId = Store.topic(c)
    )

    @Test
    fun oppositeTerminalEventsFromDifferentActorsWithinTwoMinutes_createConflict() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L))

        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
    }

    @Test
    fun oppositeTerminalEventsMoreThanTwoMinutesApart_doNotCreateConflict() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 221_000L))

        assertEquals(DoseSessionStatus.MISSED, DoseStateEngine.stateForTime(c, "08:00").status)
    }

    @Test
    fun explicitConflictResolution_winsWithoutDeletingOriginalEvents() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L))
        EventStore.append(c, event("resolve", "conflict_resolved_taken", "owner", 200_000L))

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.TAKEN, state.status)
        assertEquals("conflict_resolved_taken", state.latestEvent?.type)
        assertEquals(3, EventStore.load(c).size)
    }

    @Test
    fun undoAfterExplicitResolution_returnsDoseToPending() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L))
        EventStore.append(c, event("resolve", "conflict_resolved_taken", "owner", 200_000L))
        EventStore.append(c, event("undo", "undo_taken", "owner", 250_000L))

        assertEquals(DoseSessionStatus.PENDING, DoseStateEngine.stateForTime(c, "08:00").status)
    }
}
