package com.ozkanmut.ilactakip

import android.app.AlarmManager
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
class ProgramRescheduleRaceTest {
    private lateinit var c: Context
    private val med = Medication("med-race", "Race Med", "1 tablet", listOf("08:00"))

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
            "dosefolk_owner_scope"
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
    fun movingMedication_replacesOldAlarmWithNewAlarm() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        assertTrue(existingGroup("08:00") != null)

        Store.save(c, listOf(med.copy(times = listOf("09:00"))))
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)

        assertNull(existingGroup("08:00"))
        assertTrue(existingGroup("09:00") != null)

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("09:00"), scheduled)
    }

    @Test
    fun repeatedReschedule_doesNotLeaveGhostAlarmKeys() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        Store.save(c, listOf(med.copy(times = listOf("09:00"))))
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        Store.save(c, listOf(med.copy(times = listOf("10:00"))))
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)

        assertNull(existingGroup("08:00"))
        assertNull(existingGroup("09:00"))
        assertTrue(existingGroup("10:00") != null)

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("10:00"), scheduled)
    }

    @Test
    fun removingMedication_cancelsItsAlarmCompletely() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        assertTrue(existingGroup("08:00") != null)

        Store.save(c, emptyList())
        AlarmScheduler.scheduleAll(c, emptyList(), observeProgramChanges = false)

        assertNull(existingGroup("08:00"))
        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertTrue(scheduled.isEmpty())
    }
}
