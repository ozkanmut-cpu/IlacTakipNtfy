package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CareBatonCrashRecoveryTest {
    private lateinit var c: Context
    private val time = "08:00"
    private val date = LocalDate.now().toString()

    @Before fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_events", "dosefolk_care_baton", "dosefolk_attention_budget", "dosefolk_escalation_anchor")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun event(id: String, type: String, revision: Long, expiry: Long = 0L) = DoseEvent(
        eventId = id,
        type = type,
        time = time,
        actor = "Me",
        actorTopic = Store.topic(c),
        timestamp = System.currentTimeMillis(),
        medications = emptyList(),
        syncState = "pending",
        revision = revision,
        scheduledDate = date,
        snoozeUntil = expiry,
        ownerId = OwnerScopeStore.localOwnerId(c)
    )

    @Test fun durableClaimEvent_rebuildsMissingLocalClaimAfterCrash() {
        val expiry = System.currentTimeMillis() + 30 * 60_000L
        EventStore.append(c, event("claim-1", "care_claimed", 1L, expiry))
        c.getSharedPreferences("dosefolk_care_baton", Context.MODE_PRIVATE).edit().clear().commit()

        val claim = CareBatonStore.active(c, time, date)
        assertNotNull(claim)
        assertEquals(expiry, claim?.expiresAt)
        assertEquals(Store.topic(c), claim?.actorTopic)
    }

    @Test fun durableReleaseEvent_removesRecoveredClaimAfterCrash() {
        val expiry = System.currentTimeMillis() + 30 * 60_000L
        EventStore.append(c, event("claim-1", "care_claimed", 1L, expiry))
        assertNotNull(CareBatonStore.active(c, time, date))

        EventStore.append(c, event("release-1", "care_released", 2L))
        assertNull(CareBatonStore.active(c, time, date))
    }
}
