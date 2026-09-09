package com.ozkanmut.ilactakip

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class NotificationGroupingRecoveryTest {
    private lateinit var c: Context
    private val medA = Medication("med-a", "Vasoxen", "5 mg", listOf("08:00"))
    private val medB = Medication("med-b", "Euthyrox", "50 mcg", listOf("08:00"))
    private val date = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_program_rules",
            "dosefolk_attention_budget",
            "dosefolk_care_baton",
            "dosefolk_delivery_ledger",
            "dosefolk_alert_outbox"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(medA, medB))
    }

    private fun alarmIntent(deliveryId: String? = null, isSnooze: Boolean = false) = Intent(c, AlarmReceiver::class.java)
        .putExtra("time", "08:00")
        .putExtra("names", "${medA.name} (${medA.dose})|#|${medB.name} (${medB.dose})")
        .putExtra("ids", "${medA.id}|#|${medB.id}")
        .putExtra("scheduledDate", date)
        .putExtra("isSnooze", isSnooze)
        .apply { if (deliveryId != null) putExtra("deliveryId", deliveryId) }

    private fun snoozeAction() = Intent(c, ActionReceiver::class.java)
        .putExtra("action", "snooze")
        .putExtra("time", "08:00")
        .putExtra("names", "${medA.name} (${medA.dose})|#|${medB.name} (${medB.dose})")
        .putExtra("ids", "${medA.id}|#|${medB.id}")
        .putExtra("scheduledDate", date)

    @Test
    fun sameTimeMedications_createSingleNotificationAndSingleGroupedAlarmEvent() {
        AlarmReceiver().onReceive(c, alarmIntent())

        val notificationManager = c.getSystemService(NotificationManager::class.java)
        val active = notificationManager.activeNotifications
        assertEquals(1, active.size)

        val notification = active.single().notification
        assertEquals(3, notification.actions?.size ?: 0)
        val actionIntents = notification.actions!!.map { it.actionIntent }
        assertEquals(3, actionIntents.distinct().size)
        assertNotEquals(actionIntents[0], actionIntents[1])
        assertNotEquals(actionIntents[1], actionIntents[2])

        val alarmEvents = EventStore.load(c).filter { it.type == "alarm" && it.time == "08:00" && it.scheduledDate == date }
        assertEquals(1, alarmEvents.size)
        assertEquals(setOf(medA.id, medB.id), alarmEvents.single().medications.map { it.id }.toSet())
    }

    @Test
    fun duplicateSameAlarmBroadcast_createsOnlyOneAlarmEvent() {
        val intent = alarmIntent(deliveryId = "group-08:00|123456")

        AlarmReceiver().onReceive(c, intent)
        AlarmReceiver().onReceive(c, intent)

        val alarmEvents = EventStore.load(c).filter { it.type == "alarm" && it.time == "08:00" && it.scheduledDate == date }
        assertEquals(1, alarmEvents.size)
        assertEquals("group-08:00|123456", alarmEvents.single().eventId)
    }

    @Test
    fun laterSnoozeDelivery_withDifferentDeliveryId_isNotMistakenForDuplicate() {
        AlarmReceiver().onReceive(c, alarmIntent(deliveryId = "group-08:00|100"))
        ActionReceiver().onReceive(c, snoozeAction())

        AlarmReceiver().onReceive(c, alarmIntent(deliveryId = "snooze-$date-08:00|200", isSnooze = true))

        val alarmEvents = EventStore.load(c).filter { it.type == "alarm" && it.time == "08:00" && it.scheduledDate == date }
        assertEquals(2, alarmEvents.size)
        assertEquals(setOf("group-08:00|100", "snooze-$date-08:00|200"), alarmEvents.map { it.eventId }.toSet())
    }

    @Test
    fun snoozeThenBoot_preservesSnoozedStateAndRebuildsRegularSchedule() {
        AlarmReceiver().onReceive(c, alarmIntent())
        ActionReceiver().onReceive(c, snoozeAction())

        val beforeBoot = DoseStateEngine.stateForTime(c, "08:00", LocalDate.parse(date))
        assertEquals(DoseSessionStatus.SNOOZED, beforeBoot.status)
        val snoozedEvent = beforeBoot.latestEvent!!
        assertTrue(snoozedEvent.snoozeUntil > System.currentTimeMillis())
        assertEquals(setOf(medA.id, medB.id), snoozedEvent.medications.map { it.id }.toSet())

        c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE).edit().clear().commit()
        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))

        val afterBoot = DoseStateEngine.stateForTime(c, "08:00", LocalDate.parse(date))
        assertEquals(DoseSessionStatus.SNOOZED, afterBoot.status)
        assertEquals(snoozedEvent.snoozeUntil, afterBoot.latestEvent?.snoozeUntil)

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet()).orEmpty()
        assertTrue("08:00" in scheduled)
    }
}
