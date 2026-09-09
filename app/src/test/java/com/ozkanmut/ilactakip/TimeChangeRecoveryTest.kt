package com.ozkanmut.ilactakip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimeChangeRecoveryTest {
    private lateinit var c: Context
    private val med = Medication("med-time", "Time Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_program_rules",
            "dosefolk_program_sync",
            "dosefolk_owner_scope",
            "dosefolk_travel_guard"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun existingGroup(time: String): PendingIntent? = PendingIntent.getBroadcast(
        c,
        "group-$time".hashCode(),
        Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    @Test
    fun timeSet_rebuildsFromPersistedProgramAndRemovesStaleAlarm() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        assertTrue(existingGroup("08:00") != null)

        // Simulate persisted program being updated while an old platform alarm still exists.
        Store.save(c, listOf(med.copy(times = listOf("09:00"))))
        BootReceiver().onReceive(c, Intent(Intent.ACTION_TIME_CHANGED))

        assertNull(existingGroup("08:00"))
        assertTrue(existingGroup("09:00") != null)
        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("09:00"), scheduled)
    }

    @Test
    fun timezoneChanged_rebuildsFromPersistedProgramAndRemovesStaleAlarm() {
        TravelGuard.initialize(c)
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        assertTrue(existingGroup("08:00") != null)

        Store.save(c, listOf(med.copy(times = listOf("10:00"))))
        BootReceiver().onReceive(c, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertNull(existingGroup("08:00"))
        assertTrue(existingGroup("10:00") != null)
        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("10:00"), scheduled)
    }

    @Test
    fun repeatedTimeSignals_doNotDuplicatePlatformAlarmIdentity() {
        BootReceiver().onReceive(c, Intent(Intent.ACTION_TIME_CHANGED))
        val first = existingGroup("08:00")
        assertTrue(first != null)

        BootReceiver().onReceive(c, Intent(Intent.ACTION_TIME_CHANGED))
        val second = existingGroup("08:00")
        assertTrue(second != null)

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("08:00"), scheduled)
    }
}
