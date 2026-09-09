package com.ozkanmut.ilactakip

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DateBoundaryTravelTest {
    private lateinit var c: Context
    private val med = Medication("med-date", "Date Med", "1 tablet", listOf("23:55"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_program_rules",
            "dosefolk_travel_guard",
            "dosefolk_attention_budget",
            "dosefolk_care_baton"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    @Test
    fun yesterdayTerminalEvent_doesNotResolveTodaysDose() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        EventStore.append(c, DoseEvent(
            eventId = "yesterday-taken",
            type = "taken",
            time = "23:55",
            actor = "me",
            actorTopic = "local",
            timestamp = System.currentTimeMillis() - 86_400_000L,
            medications = listOf(med),
            syncState = "synced",
            revision = 1L,
            scheduledDate = yesterday
        ))

        val today = DoseStateEngine.stateForTime(c, "23:55", LocalDate.now())
        assertTrue(today.status == DoseSessionStatus.UNKNOWN || today.status == DoseSessionStatus.PENDING)
    }

    @Test
    fun yesterdaySnooze_isNotRestoredForToday() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        EventStore.append(c, DoseEvent(
            eventId = "old-snooze",
            type = "snoozed",
            time = "23:55",
            actor = "me",
            actorTopic = "local",
            timestamp = System.currentTimeMillis() - 60_000L,
            medications = listOf(med),
            syncState = "synced",
            revision = 1L,
            scheduledDate = yesterday,
            snoozeUntil = System.currentTimeMillis() + 30 * 60_000L
        ))

        AlarmScheduler.restoreActiveSnoozes(c)
        val today = DoseStateEngine.stateForTime(c, "23:55", LocalDate.now())
        assertTrue(today.status == DoseSessionStatus.UNKNOWN || today.status == DoseSessionStatus.PENDING)
    }

    @Test
    fun bootRebuild_doesNotDuplicateScheduledTimeKey() {
        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("23:55"), scheduled)
    }

    @Test
    fun travelGuard_initializesWithoutSpuriousNotice() {
        TravelGuard.initialize(c)
        assertNull(TravelGuard.pendingNotice(c))
    }

    @Test
    fun acknowledgeTravelNotice_rearmsCurrentSchedule() {
        val p = c.getSharedPreferences("dosefolk_travel_guard", Context.MODE_PRIVATE)
        p.edit()
            .putString("from_zone", "Europe/Istanbul")
            .putString("to_zone", "Europe/London")
            .putLong("detected_at", System.currentTimeMillis())
            .putBoolean("acked", false)
            .commit()

        assertNotNull(TravelGuard.pendingNotice(c))
        TravelGuard.acknowledge(c)
        assertNull(TravelGuard.pendingNotice(c))

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertTrue("23:55" in scheduled)
    }
}
