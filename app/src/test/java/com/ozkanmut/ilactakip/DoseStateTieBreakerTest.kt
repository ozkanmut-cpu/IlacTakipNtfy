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
class DoseStateTieBreakerTest {
    private lateinit var context: Context
    private val date = LocalDate.of(2026, 9, 9)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ilac_takip", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sameRevisionUnresolvedEvents_useStableIdsInsteadOfTimestamp() {
        EventStore.append(
            context,
            DoseEvent(
                eventId = "a",
                type = "alarm",
                time = "08:00",
                actor = "A",
                actorTopic = "peer",
                timestamp = 9_999_999_999L,
                medications = emptyList(),
                syncState = "synced",
                revision = 42,
                scheduledDate = date.toString()
            )
        )
        EventStore.append(
            context,
            DoseEvent(
                eventId = "z",
                type = "snoozed",
                time = "08:00",
                actor = "A",
                actorTopic = "peer",
                timestamp = 1L,
                medications = emptyList(),
                syncState = "synced",
                revision = 42,
                scheduledDate = date.toString()
            )
        )

        val state = DoseStateEngine.stateForTime(context, "08:00", date)

        assertEquals(DoseSessionStatus.SNOOZED, state.status)
        assertEquals("z", state.latestEvent?.eventId)
    }

    @Test
    fun sameActorSameRevisionUndo_usesEventIdInsteadOfFutureClock() {
        EventStore.append(
            context,
            DoseEvent(
                eventId = "taken-1",
                type = "taken",
                time = "09:00",
                actor = "A",
                actorTopic = "peer",
                timestamp = 10L,
                medications = emptyList(),
                syncState = "synced",
                revision = 50,
                scheduledDate = date.toString()
            )
        )
        EventStore.append(
            context,
            DoseEvent(
                eventId = "undo-a",
                type = "undo_taken",
                time = "09:00",
                actor = "A",
                actorTopic = "peer",
                timestamp = 9_999_999_999L,
                medications = emptyList(),
                syncState = "synced",
                revision = 51,
                scheduledDate = date.toString()
            )
        )
        EventStore.append(
            context,
            DoseEvent(
                eventId = "undo-z",
                type = "undo_taken",
                time = "09:00",
                actor = "A",
                actorTopic = "peer",
                timestamp = 1L,
                medications = emptyList(),
                syncState = "synced",
                revision = 51,
                scheduledDate = date.toString()
            )
        )

        val state = DoseStateEngine.stateForTime(context, "09:00", date)

        assertEquals(DoseSessionStatus.PENDING, state.status)
        assertEquals("undo-z", state.latestEvent?.eventId)
    }
}
