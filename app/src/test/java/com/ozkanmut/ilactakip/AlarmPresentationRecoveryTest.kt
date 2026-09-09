package com.ozkanmut.ilactakip

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun alarmEvent(deliveryId: String, revision: Long = 7L, medications: List<Medication> = listOf(med)) = DoseEvent(
        eventId = deliveryId,
        type = "alarm",
        time = "08:00",
        actor = "Local",
        actorTopic = Store.topic(c),
        timestamp = System.currentTimeMillis(),
        medications = medications,
        syncState = "pending",
        revision = revision,
        scheduledDate = date,
        ownerId = OwnerScopeStore.localOwnerId(c)
    )

    @Test
    fun persistedAlarmWithoutPresentationReceipt_isPresentedOnRedeliveryWithoutRevisionChurn() {
        val deliveryId = "group-08:00|123456789"
        EventStore.append(c, alarmEvent(deliveryId))
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

    @Test
    fun canonicalAlarmPayloadWinsOverStaleIntentExtrasOnRedelivery() {
        val deliveryId = "group-08:00|stale-extras"
        val canonicalMed = med.copy(name = "Canonical Med", dose = "2 tablets")
        Store.save(c, listOf(canonicalMed))
        EventStore.append(c, alarmEvent(deliveryId, medications = listOf(canonicalMed)))

        val staleIntent = Intent(c, AlarmReceiver::class.java)
            .putExtra("time", "08:00")
            .putExtra("names", "Stale Med (1 tablet)")
            .putExtra("ids", canonicalMed.id)
            .putExtra("scheduledDate", date)
            .putExtra("isSnooze", false)
            .putExtra("deliveryId", deliveryId)

        AlarmReceiver().onReceive(c, staleIntent)

        val manager = c.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.first { it.id == ("group-$date-08:00").hashCode() }.notification
        assertEquals("Canonical Med (2 tablets)", notification.extras.getCharSequence("android.text")?.toString())
        assertTrue(AlarmPresentationLedger.isPresented(c, deliveryId))
    }

    @Test
    fun terminalDecisionBeforeRedelivery_doesNotResurrectNotificationAndCompletesReceipt() {
        val deliveryId = "group-08:00|terminal-race"
        EventStore.append(c, alarmEvent(deliveryId, 7L))
        EventStore.append(c, DoseEvent(
            eventId = "taken-after-alarm",
            type = "taken",
            time = "08:00",
            actor = "Local",
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis() + 1,
            medications = listOf(med),
            syncState = "pending",
            revision = 8L,
            scheduledDate = date,
            ownerId = OwnerScopeStore.localOwnerId(c)
        ))

        AlarmReceiver().onReceive(c, alarmIntent(deliveryId))

        val manager = c.getSystemService(NotificationManager::class.java)
        assertEquals(0, manager.activeNotifications.count { it.id == ("group-$date-08:00").hashCode() })
        assertTrue(AlarmPresentationLedger.isPresented(c, deliveryId))
    }

    @Test
    fun orderedReceiptRetention_evictsOnlyOldestReceipt() {
        repeat(AlarmPresentationLedger.MAX_IDS + 1) { index ->
            AlarmPresentationLedger.markPresented(c, "delivery-$index")
        }

        val ids = AlarmPresentationLedger.orderedIds(c)
        assertEquals(AlarmPresentationLedger.MAX_IDS, ids.size)
        assertFalse(ids.contains("delivery-0"))
        assertTrue(ids.contains("delivery-1"))
        assertTrue(ids.contains("delivery-${AlarmPresentationLedger.MAX_IDS}"))
        assertEquals("delivery-1", ids.first())
        assertEquals("delivery-${AlarmPresentationLedger.MAX_IDS}", ids.last())
    }
}
