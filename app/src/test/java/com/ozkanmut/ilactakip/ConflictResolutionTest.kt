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
        listOf("ilac_takip", "dosefolk_events", "dosefolk_program_rules", "dosefolk_owner_scope", "dosefolk_stock")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun event(id: String, type: String, actorTopic: String, timestamp: Long, revision: Long = timestamp) = DoseEvent(
        eventId = id, type = type, time = "08:00", actor = actorTopic, actorTopic = actorTopic,
        timestamp = timestamp, medications = listOf(med), syncState = "synced", revision = revision,
        scheduledDate = today, ownerId = Store.topic(c)
    )

    @Test fun oppositeTerminalEventsFromDifferentActorsWithinTwoMinutes_createConflict() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L)); EventStore.append(c, event("missed-b", "missed", "phone-b", 150_000L))
        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
    }
    @Test fun oppositeTerminalEventsRemainConflictEvenWhenPhoneClocksDifferByMinutes() {
        EventStore.append(c, event("taken-a", "taken", "phone-a", 100_000L)); EventStore.append(c, event("missed-b", "missed", "phone-b", 700_000L))
        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
    }
    @Test fun reverseBatchArrivalProducesSameConflictState() {
        val taken=event("taken-a","taken","phone-a",100_000L,5L); val missed=event("missed-b","missed","phone-b",700_000L,3L)
        EventStore.append(c,missed); EventStore.append(c,taken); assertEquals(DoseSessionStatus.CONFLICT,DoseStateEngine.stateForTime(c,"08:00").status)
    }
    @Test fun explicitConflictResolution_winsWithoutDeletingOriginalEvents() {
        EventStore.append(c,event("taken-a","taken","phone-a",100_000L)); EventStore.append(c,event("missed-b","missed","phone-b",150_000L)); EventStore.append(c,event("resolve","conflict_resolved_taken","owner",200_000L,10L))
        val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.TAKEN,state.status); assertEquals("conflict_resolved_taken",state.latestEvent?.type); assertEquals(3,EventStore.load(c).size)
    }
    @Test fun simultaneousOppositeResolutionsFromDifferentDevicesRemainConflict() {
        EventStore.append(c,event("taken-a","taken","phone-a",100_000L,1L)); EventStore.append(c,event("missed-b","missed","phone-b",150_000L,1L)); EventStore.append(c,event("resolve-a","conflict_resolved_taken","phone-a",200_000L,2L)); EventStore.append(c,event("resolve-b","conflict_resolved_missed","phone-b",900_000L,2L))
        val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.CONFLICT,state.status); assertEquals(2,state.conflictEvents.size)
    }
    @Test fun newerResolution_settlesOlderResolutionConflict() {
        EventStore.append(c,event("taken-a","taken","phone-a",100_000L,1L)); EventStore.append(c,event("missed-b","missed","phone-b",150_000L,1L)); EventStore.append(c,event("resolve-a","conflict_resolved_taken","phone-a",200_000L,2L)); EventStore.append(c,event("resolve-b","conflict_resolved_missed","phone-b",210_000L,2L))
        assertEquals(DoseSessionStatus.CONFLICT,DoseStateEngine.stateForTime(c,"08:00").status)
        EventStore.append(c,event("resolve-final","conflict_resolved_taken","phone-a",220_000L,3L))
        val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.TAKEN,state.status); assertEquals("resolve-final",state.latestEvent?.eventId)
    }
    @Test fun newerResolution_alsoReconcilesStockToFinalDecision() {
        StockEngine.configure(c,med,10,10,2)
        val rTaken=event("resolve-a","conflict_resolved_taken","phone-a",200_000L,2L); val rMissed=event("resolve-b","conflict_resolved_missed","phone-b",210_000L,2L)
        EventStore.append(c,rTaken); StockEngine.applyEvent(c,rTaken); EventStore.append(c,rMissed); StockEngine.applyEvent(c,rMissed)
        assertEquals(DoseSessionStatus.CONFLICT,DoseStateEngine.stateForTime(c,"08:00").status)
        val finalTaken=event("resolve-final","conflict_resolved_taken","phone-a",220_000L,3L); EventStore.append(c,finalTaken); StockEngine.applyEvent(c,finalTaken)
        assertEquals(DoseSessionStatus.TAKEN,DoseStateEngine.stateForTime(c,"08:00").status); assertEquals(9,StockEngine.forMedication(c,med.id)?.remainingDoses)
    }
    @Test fun sameActorCorrectionUsesRevisionNotWallClock() {
        EventStore.append(c,event("taken-old","taken","phone-a",900_000L,1L)); EventStore.append(c,event("missed-correction","missed","phone-a",100_000L,2L)); val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.MISSED,state.status); assertEquals("missed-correction",state.latestEvent?.eventId)
    }
    @Test fun sameActorUndoUsesRevisionNotArrivalOrWallClock() {
        EventStore.append(c,event("taken","taken","phone-a",900_000L,4L)); EventStore.append(c,event("undo","undo_taken","phone-a",100_000L,5L)); val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.PENDING,state.status); assertEquals("undo",state.latestEvent?.eventId)
    }
    @Test fun staleFutureClockTerminalCannotReopenResolvedConflict() {
        EventStore.append(c,event("taken-a","taken","phone-a",100_000L)); EventStore.append(c,event("missed-b","missed","phone-b",150_000L)); EventStore.append(c,event("resolve","conflict_resolved_taken","owner",200_000L,10L)); EventStore.append(c,event("late-stale","missed","phone-b",9_999_999L,2L)); val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.TAKEN,state.status); assertEquals("conflict_resolved_taken",state.latestEvent?.type)
    }
    @Test fun undoAfterExplicitResolution_returnsDoseToPending() {
        EventStore.append(c,event("taken-a","taken","phone-a",100_000L)); EventStore.append(c,event("missed-b","missed","phone-b",150_000L)); EventStore.append(c,event("resolve","conflict_resolved_taken","owner",200_000L,10L)); EventStore.append(c,event("undo","undo_taken","owner",250_000L,11L)); assertEquals(DoseSessionStatus.PENDING,DoseStateEngine.stateForTime(c,"08:00").status)
    }
    @Test fun resolveConflictAsMissed_restoresPreviouslyConsumedStock() {
        StockEngine.configure(c,med,10,10,2); val taken=event("taken-stock","taken","phone-a",100_000L,1L); StockEngine.applyEvent(c,taken); assertEquals(9,StockEngine.forMedication(c,med.id)?.remainingDoses)
        val resolvedMissed=event("resolve-missed-stock","conflict_resolved_missed","owner",200_000L,2L); StockEngine.applyEvent(c,resolvedMissed); assertEquals(10,StockEngine.forMedication(c,med.id)?.remainingDoses); StockEngine.applyEvent(c,resolvedMissed.copy(eventId="resolve-missed-replay",revision=3L)); assertEquals(10,StockEngine.forMedication(c,med.id)?.remainingDoses)
    }
    @Test fun resolveConflictAsTaken_consumesStockWhenNoTakenEventWasAppliedLocally() {
        StockEngine.configure(c,med,10,10,2); val resolvedTaken=event("resolve-taken-stock","conflict_resolved_taken","owner",200_000L,2L); StockEngine.applyEvent(c,resolvedTaken); assertEquals(9,StockEngine.forMedication(c,med.id)?.remainingDoses); StockEngine.applyEvent(c,resolvedTaken.copy(eventId="resolve-taken-replay",revision=3L)); assertEquals(9,StockEngine.forMedication(c,med.id)?.remainingDoses)
    }
    @Test fun futureClockAlarmRetryCannotEraseTakenState() {
        EventStore.append(c,event("alarm-old","alarm","phone-a",100_000L,1L)); EventStore.append(c,event("taken","taken","phone-a",200_000L,2L)); EventStore.append(c,event("alarm-future","alarm","phone-b",9_999_999L,1L)); val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.TAKEN,state.status); assertEquals("taken",state.latestEvent?.eventId)
    }
    @Test fun futureClockAlarmRetryCannotEraseMissedState() {
        EventStore.append(c,event("alarm-old","alarm","phone-a",100_000L,1L)); EventStore.append(c,event("missed","missed","phone-a",200_000L,2L)); EventStore.append(c,event("alarm-future","alarm","phone-b",9_999_999L,1L)); val state=DoseStateEngine.stateForTime(c,"08:00"); assertEquals(DoseSessionStatus.MISSED,state.status); assertEquals("missed",state.latestEvent?.eventId)
    }
    @Test fun repeatedAlarmWithoutTerminalRemainsPending() {
        EventStore.append(c,event("alarm-1","alarm","phone-a",100_000L,1L)); EventStore.append(c,event("alarm-2","alarm","phone-b",9_999_999L,1L)); assertEquals(DoseSessionStatus.PENDING,DoseStateEngine.stateForTime(c,"08:00").status)
    }
}
