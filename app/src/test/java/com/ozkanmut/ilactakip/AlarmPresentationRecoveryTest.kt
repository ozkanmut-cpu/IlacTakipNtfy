package com.ozkanmut.ilactakip

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class AlarmPresentationRecoveryTest {
    private lateinit var c: Context
    private val med = Medication("med-alarm-recovery", "Recovery Med", "1 tablet", listOf("08:00"))
    private val date get() = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_alarm_presentation",
            "dosefolk_attention_budget",
            "dosefolk_care_baton",
            "dosefolk_owner_scope",
            "dosefolk_program_rules",
            "dosefolk_alert_outbox",
            "dosefolk_delivery_ledger"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun alarmIntent(deliveryId: String) = Intent(c, AlarmReceiver::class.java)
        .putExtra("time", "08:00")
        .putExtra("names", "${med.name} (${med.dose})")
        .putExtra("ids", med.id)
        .putExtra("scheduledDate", date)
        .putExtra("isSnooze", false)
        .putExtra("deliveryId", deliveryId)

    @Test
    fun persistedAlarmWithoutPresentationReceipt_isPresentedOnRedeliveryWithoutRevisionChurn() {
        val deliveryId = "group-08:00|123456789"
        EventStore.append(c, DoseEvent(
            eventId = deliveryId,
            type = "alarm",
            time = "08:00",
            actor = "Local",
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = listOf(med),
            syncState = "pending",
            revision = 7L,
            scheduledDate = date,
            ownerId = OwnerScopeStore.localOwnerId(c)
        ))
        assertEquals(7L, c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).getLong("local_revision", 0L))

        AlarmReceiver().onReceive(c, alarmIntent(deliveryId))

        val manager = c.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.count { it.id == ("group-$date-08:00").hashCode() })
        assertTrue(AlarmPresentationLedger.isPresented(c, deliveryId))
        assertEquals(1, EventStore.load(c).count { it.eventId == deliveryId && it.type == "alarm" })
        assertEquals(7L, c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).getLong("local_revision", 0L))

        AlarmReceiver().onReceive(c, alarmIntent(deliveryId))

        assertEquals(1, EventStore.load(c).count { it.eventId == deliveryId && it.type == "alarm" })
        assertEquals(7L, c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).getLong("local_revision", 0L))
    }
}
