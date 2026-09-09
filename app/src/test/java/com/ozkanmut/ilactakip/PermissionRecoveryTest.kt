package com.ozkanmut.ilactakip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionRecoveryTest {
    private lateinit var c: Context
    private val med = Medication("med-perm", "Permission Med", "1 tablet", listOf("08:00"))

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
            "dosefolk_alert_outbox",
            "dosefolk_sync_health"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun groupAlarm(time: String): PendingIntent? = PendingIntent.getBroadcast(
        c,
        "group-$time".hashCode(),
        Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    @Test
    fun exactAlarmPermissionStateChange_rebuildsPersistedAlarmPlan() {
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        assertNotNull(groupAlarm("08:00"))

        // Simulate process/scheduler bookkeeping loss while medication data survives.
        c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE).edit().clear().commit()

        BootReceiver().onReceive(c, Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertEquals(setOf("08:00"), scheduled)
        assertNotNull(groupAlarm("08:00"))
    }

    @Test
    fun alarmPlanRepairIssue_disappearsAfterFix() {
        c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .edit().putStringSet("scheduled_times", emptySet()).commit()

        val before = DosefolkCheck.issues(c)
        val repair = before.firstOrNull { it.id == "alarm_plan" }
        assertNotNull(repair)

        repair!!.fix(c)

        val after = DosefolkCheck.issues(c)
        assertFalse(after.any { it.id == "alarm_plan" })
        assertNotNull(groupAlarm("08:00"))
    }

    @Test
    fun localOnlyMode_doesNotNagAboutCircleBatteryReliability() {
        Store.savePeople(c, emptyList())

        val ids = DosefolkCheck.issues(c).map { it.id }.toSet()

        assertFalse("background_restricted" in ids)
        assertFalse("battery_optimization" in ids)
    }

    @Test
    fun pendingOfflineEvent_isReportedAsSafeRetryableState() {
        EventStore.append(
            c,
            DoseEvent(
                eventId = "pending-permission-test",
                type = "taken",
                time = "08:00",
                actor = "Tester",
                actorTopic = Store.topic(c),
                timestamp = System.currentTimeMillis(),
                medications = listOf(med),
                syncState = "pending",
                revision = 1L,
                ownerId = Store.topic(c)
            )
        )

        val pending = DosefolkCheck.issues(c).firstOrNull { it.id == "pending_sync" }
        assertNotNull(pending)
        assertTrue(pending!!.detail.isNotBlank())
    }
}
