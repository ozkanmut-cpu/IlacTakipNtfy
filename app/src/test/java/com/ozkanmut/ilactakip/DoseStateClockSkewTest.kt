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
class DoseStateClockSkewTest {
    private lateinit var c: Context
    private val date = LocalDate.now()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_events", "ilac_takip")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun conflictLatestEvent_usesHigherRevisionEvenWhenOtherClockIsFarInFuture() {
        val logicalWinnerMed = Medication("m-logical", "Logical Med", "1 tablet", listOf("08:00"))
        val skewedMed = Medication("m-skew", "Skewed Med", "1 tablet", listOf("08:00"))

        EventStore.append(
            c,
            DoseEvent(
                eventId = "taken-r10",
                type = "taken",
                time = "08:00",
                actor = "A",
                actorTopic = "topic-a",
                timestamp = 1_000L,
                medications = listOf(logicalWinnerMed),
                syncState = "synced",
                revision = 10L,
                scheduledDate = date.toString(),
                ownerId = "owner"
            )
        )
        EventStore.append(
            c,
            DoseEvent(
                eventId = "missed-r9-future-clock",
                type = "missed",
                time = "08:00",
                actor = "B",
                actorTopic = "topic-b",
                timestamp = 9_999_999_999_999L,
                medications = listOf(skewedMed),
                syncState = "synced",
                revision = 9L,
                scheduledDate = date.toString(),
                ownerId = "owner"
            )
        )

        val state = DoseStateEngine.stateForTime(c, "08:00", date)

        assertEquals(DoseSessionStatus.CONFLICT, state.status)
        assertEquals("taken-r10", state.latestEvent?.eventId)
        assertEquals(listOf("m-logical"), state.medications.map { it.id })
    }

    @Test
    fun equalRevisionConflict_usesStableActorAndEventTieBreakNotTimestamp() {
        val medA = Medication("m-a", "Med A", "1", listOf("09:00"))
        val medB = Medication("m-b", "Med B", "1", listOf("09:00"))

        EventStore.append(
            c,
            DoseEvent(
                eventId = "event-z",
                type = "taken",
                time = "09:00",
                actor = "A",
                actorTopic = "topic-a",
                timestamp = 99_999_999L,
                medications = listOf(medA),
                syncState = "synced",
                revision = 7L,
                scheduledDate = date.toString(),
                ownerId = "owner"
            )
        )
        EventStore.append(
            c,
            DoseEvent(
                eventId = "event-a",
                type = "missed",
                time = "09:00",
                actor = "B",
                actorTopic = "topic-b",
                timestamp = 1L,
                medications = listOf(medB),
                syncState = "synced",
                revision = 7L,
                scheduledDate = date.toString(),
                ownerId = "owner"
            )
        )

        val state = DoseStateEngine.stateForTime(c, "09:00", date)

        assertEquals(DoseSessionStatus.CONFLICT, state.status)
        assertEquals("topic-b", state.latestEvent?.actorTopic)
        assertEquals(listOf("m-b"), state.medications.map { it.id })
    }
}
