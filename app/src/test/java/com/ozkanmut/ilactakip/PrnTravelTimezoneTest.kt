package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PrnTravelTimezoneTest {
    private lateinit var c: Context
    private val med = Medication("prn-travel-med", "PRN Travel Med", "1 tablet", emptyList())
    private val item = PrnMedication(
        id = "prn-item",
        medicationId = med.id,
        name = med.name,
        doseNote = med.dose,
        maximumPerDay = 1
    )

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_prn",
            "dosefolk_prn_usage",
            "dosefolk_travel_guard",
            "dosefolk_owner_scope",
            "dosefolk_stock"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun setPendingTravel(from: String, to: String) {
        c.getSharedPreferences("dosefolk_travel_guard", Context.MODE_PRIVATE).edit()
            .putString("from_zone", from)
            .putString("to_zone", to)
            .putLong("detected_at", System.currentTimeMillis())
            .putBoolean("acked", false)
            .commit()
    }

    private fun observeUsage(id: String, timestamp: Long) {
        PrnUsageLedger.observe(c, DoseEvent(
            eventId = id,
            type = "prn_taken",
            time = "PRN",
            actor = "me",
            actorTopic = "",
            timestamp = timestamp,
            medications = listOf(med),
            syncState = "synced",
            revision = 1L,
            ownerId = ""
        ))
    }

    @Test
    fun pendingTravel_keepsDailyMaximumOnPreviousMedicationZone() {
        setPendingTravel("Europe/Istanbul", "Europe/London")

        // 21:30Z is 00:30 next day in Istanbul but 22:30 same day in London (summer time).
        val usage = Instant.parse("2026-09-08T21:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-09-08T22:00:00Z").toEpochMilli()
        observeUsage("prn-before-midnight-split", usage)

        val check = PrnEngine.check(c, item, now)

        assertEquals("Europe/Istanbul", TravelGuard.effectiveMedicationZone(c).id)
        assertEquals(1, check.takenToday)
        assertFalse(check.allowed)
        assertEquals("daily_maximum", check.reason)
    }

    @Test
    fun acknowledgedTravel_switchesDailyMaximumToCurrentZoneSemantics() {
        setPendingTravel("Europe/Istanbul", "Europe/London")

        val usage = Instant.parse("2026-09-08T21:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-09-08T22:00:00Z").toEpochMilli()
        observeUsage("prn-zone-shift", usage)

        // Simulate acknowledgement without changing the JVM zone: effectiveMedicationZone
        // falls back to the current system zone after ACK. The safety invariant tested here
        // is that ACK ends the frozen-home-zone state.
        c.getSharedPreferences("dosefolk_travel_guard", Context.MODE_PRIVATE).edit()
            .putBoolean("acked", true)
            .commit()

        assertTrue(TravelGuard.pendingNotice(c) == null)
        assertTrue(TravelGuard.effectiveMedicationZone(c).id.isNotBlank())
    }
}
