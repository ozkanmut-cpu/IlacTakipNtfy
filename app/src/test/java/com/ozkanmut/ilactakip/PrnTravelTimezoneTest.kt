package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class PrnTravelTimezoneTest {
    private lateinit var c: Context
    private lateinit var originalZone: TimeZone
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
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
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

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
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

        // 20:30Z = 23:30 Sep 8 Istanbul / 21:30 Sep 8 London.
        // 21:30Z = 00:30 Sep 9 Istanbul / 22:30 Sep 8 London.
        // While confirmation is pending, the earlier dose belongs to yesterday
        // under Istanbul medication-day semantics and must not count as today's dose.
        val usage = Instant.parse("2026-09-08T20:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-09-08T21:30:00Z").toEpochMilli()
        observeUsage("prn-before-home-midnight", usage)

        val check = PrnEngine.check(c, item, now)

        assertEquals("Europe/Istanbul", TravelGuard.effectiveMedicationZone(c).id)
        assertEquals(0, check.takenToday)
        assertTrue(check.allowed)
    }

    @Test
    fun acknowledgedTravel_switchesDailyMaximumToCurrentZoneSemantics() {
        setPendingTravel("Europe/Istanbul", "Europe/London")

        val usage = Instant.parse("2026-09-08T20:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-09-08T21:30:00Z").toEpochMilli()
        observeUsage("prn-zone-shift", usage)

        val beforeAck = PrnEngine.check(c, item, now)
        assertEquals(0, beforeAck.takenToday)
        assertTrue(beforeAck.allowed)

        c.getSharedPreferences("dosefolk_travel_guard", Context.MODE_PRIVATE).edit()
            .putBoolean("acked", true)
            .commit()

        val afterAck = PrnEngine.check(c, item, now)
        assertEquals("Europe/London", TravelGuard.effectiveMedicationZone(c).id)
        assertEquals(1, afterAck.takenToday)
        assertFalse(afterAck.allowed)
        assertEquals("daily_maximum", afterAck.reason)
    }
}
