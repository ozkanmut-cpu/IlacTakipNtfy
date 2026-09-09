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
class DoseStateUnknownOrderingTest {
    private lateinit var c: Context
    private val date get() = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("ilac_takip", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun event(id: String, revision: Long, timestamp: Long) = DoseEvent(
        eventId = id,
        type = "care_claimed",
        time = "08:00",
        actor = "Caregiver",
        actorTopic = "remote-device",
        timestamp = timestamp,
        medications = emptyList(),
        syncState = "synced",
        revision = revision,
        scheduledDate = date,
        ownerId = "owner"
    )

    @Test
    fun unknownStateLatestEvent_usesLogicalOrderNotInsertionOrWallClock() {
        val highRevision = event("logical-newer", 7L, 1_000L)
        val futureClockLowRevision = event("future-clock-older", 6L, Long.MAX_VALUE / 4)

        EventStore.append(c, highRevision)
        EventStore.append(c, futureClockLowRevision)

        val state = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now())
        assertEquals(DoseSessionStatus.UNKNOWN, state.status)
        assertEquals("logical-newer", state.latestEvent?.eventId)
    }
}
