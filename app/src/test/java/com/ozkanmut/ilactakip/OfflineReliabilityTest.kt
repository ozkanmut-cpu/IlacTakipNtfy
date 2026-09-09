package com.ozkanmut.ilactakip

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class OfflineReliabilityTest {
    private lateinit var c: Context
    private val med = Medication("med-offline", "Offline Med", "1 tablet", listOf("08:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_delivery_ledger",
            "dosefolk_alert_outbox",
            "dosefolk_attention_budget",
            "dosefolk_care_baton",
            "dosefolk_alarm_scheduler",
            "dosefolk_program_rules",
            "dosefolk_permissions"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun partialDeliveryLedger_retriesOnlyMissingTopics() {
        val eventId = "event-1"
        DeliveryLedger.markDelivered(c, eventId, "topic-a")

        assertTrue(DeliveryLedger.delivered(c, eventId, "topic-a"))
        assertFalse(DeliveryLedger.delivered(c, eventId, "topic-b"))

        // Marking the same pair again must stay idempotent.
        DeliveryLedger.markDelivered(c, eventId, "topic-a")
        assertTrue(DeliveryLedger.delivered(c, eventId, "topic-a"))
        assertFalse(DeliveryLedger.delivered(c, eventId, "topic-b"))

        DeliveryLedger.clearEvent(c, eventId)
        assertFalse(DeliveryLedger.delivered(c, eventId, "topic-a"))
    }

    @Test
    fun caregiverAlertOutbox_survivesUntilItsTopicIsRemoved() {
        AlertOutbox.enqueue(c, "care-a", "title", "one")
        AlertOutbox.enqueue(c, "care-a", "title", "two")
        AlertOutbox.enqueue(c, "care-b", "title", "three")
        assertEquals(3, AlertOutbox.pendingCount(c))

        AlertOutbox.dropTopic(c, "care-a")
        assertEquals(1, AlertOutbox.pendingCount(c))

        // Re-opening the store must still see the durable pending row.
        assertEquals(1, AlertOutbox.pendingCount(c.applicationContext))
        AlertOutbox.dropTopic(c, "care-b")
        assertEquals(0, AlertOutbox.pendingCount(c))
    }

    @Test
    fun attentionBudget_blocksDuplicateEscalationButClearRearmsIt() {
        val date = LocalDate.now().toString()
        assertTrue(AttentionBudget.allow(c, "08:00", "care-a", 0, date))
        AttentionBudget.mark(c, "08:00", "care-a", 0, date)
        assertFalse(AttentionBudget.allow(c, "08:00", "care-a", 0, date))

        // Different stage and caregiver are independent.
        assertTrue(AttentionBudget.allow(c, "08:00", "care-a", 1, date))
        assertTrue(AttentionBudget.allow(c, "08:00", "care-b", 0, date))

        AttentionBudget.clear(c, "08:00", date)
        assertTrue(AttentionBudget.allow(c, "08:00", "care-a", 0, date))
    }

    @Test
    fun expiredCareBaton_isCleanedAndCannotSuppressFutureEscalation() {
        val date = LocalDate.now().toString()
        val prefs = c.getSharedPreferences("dosefolk_care_baton", Context.MODE_PRIVATE)
        val expired = JSONObject()
            .put("doseKey", "$date|08:00")
            .put("time", "08:00")
            .put("actor", "Old caregiver")
            .put("actorTopic", "care-old")
            .put("claimedAt", System.currentTimeMillis() - 120_000L)
            .put("expiresAt", System.currentTimeMillis() - 60_000L)
            .put("scheduledDate", date)
        prefs.edit().putString("claims", JSONArray().put(expired).toString()).commit()

        assertEquals(null, CareBatonStore.active(c, "08:00", date))
        assertTrue(CareBatonStore.load(c).isEmpty())
    }

    @Test
    fun bootReceiver_rebuildsAlarmPlanAfterSchedulerStateLoss() {
        Store.save(c, listOf(med))
        c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE).edit().clear().commit()

        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))

        val scheduled = c.getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("scheduled_times", emptySet())
            .orEmpty()
        assertTrue("08:00" in scheduled)
    }
}
