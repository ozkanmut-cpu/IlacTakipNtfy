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

    private fun event(id: String, type: String, actorTopic: String, timestamp: Long, revision: Long = timestamp) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = actorTopic,
        actorTopic = actorTopic,
        timestamp = timestamp,
        medications = listOf(med),
        syncState = "synced",
        revision = revision,
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
    fun oppositeTerminalEventsRemainConflictEvenWhenPhoneClocksDifferByMinutes() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 700_000L))

        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
    }

    @Test
    fun reverseBatchArrivalProducesSameConflictState() {
        val taken = event("taken-a", "taken", "phone-a", 100_000L, 5L)
        val missed = event("missed-b", "missed", "phone-b", 700_000L, 3L)

        EventStore.append(c, missed)
        EventStore.append(c, taken)

        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
    }

    @Test
    fun explicitConflictResolution_winsWithoutDeletingOriginalEvents() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L))
        EventStore.append(c, event("resolve", "conflict_resolved_taken", "owner", 200_000L, 10L))

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.TAKEN, state.status)
        assertEquals("conflict_resolved_taken", state.latestEvent?.type)
        assertEquals(3, EventStore.load(c).size)
    }

    @Test
    fun simultaneousOppositeResolutionsFromDifferentDevicesRemainConflict() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L, 1L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L, 1L))
        EventStore.append(c, event("resolve-a", "conflict_resolved_taken", "phone-a", 200_000L, 2L))
        EventStore.append(c, event("resolve-b", "conflict_resolved_missed", "phone-b", 900_000L, 2L))

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.CONFLICT, state.status)
        assertEquals(2, state.conflictEvents.size)
    }

    @Test
    fun sameActorCorrectionUsesRevisionNotWallClock() {
        EventStore.append(c, event("taken-old", "taken", "phone-a", 900_000L, 1L))
        EventStore.append(c, event("missed-correction", "missed", "phone-a", 100_000L, 2L))

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.MISSED, state.status)
        assertEquals("missed-correction", state.latestEvent?.eventId)
    }

    @Test
    fun sameActorUndoUsesRevisionNotArrivalOrWallClock() {
        EventStore.append(c, event("taken", "taken", "phone-a", 900_000L, 4L))
        EventStore.append(c, event("undo", "undo_taken", "phone-a", 100_000L, 5L))

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.PENDING, state.status)
        assertEquals("undo", state.latestEvent?.eventId)
    }

    @Test
    fun staleFutureClockTerminalCannotReopenResolvedConflict() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L))
        EventStore.append(c, event("resolve", "conflict_resolved_taken", "owner", 200_000L, 10L))
        EventStore.append(c, event("late-stale", "missed", "phone-b", 9_999_999L, 2L))

        val state = DoseStateEngine.stateForTime(c, "08:00")
        assertEquals(DoseSessionStatus.TAKEN, state.status)
        assertEquals("conflict_resolved_taken", state.latestEvent?.type)
    }

    @Test
    fun undoAfterExplicitResolution_returnsDoseToPending() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L))
        EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L))
        EventStore.append(c, event("resolve", "conflict_resolved_taken", "owner", 200_000L, 10L))
        EventStore.append(c, event("undo", "undo_taken", "owner", 250_000L, 11L))

        assertEquals(DoseSessionStatus.PENDING, DoseStateEngine.stateForTime(c, "08:00").status)
    }
}
