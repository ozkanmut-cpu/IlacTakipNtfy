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
            "dosefolk_permissions",
            "dosefolk_stock",
            "dosefolk_owner_scope"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun doseEvent(
        id: String,
        type: String = "alarm",
        date: String = LocalDate.now().minusDays(1).toString(),
        syncState: String = "synced",
        actorTopic: String = "device-a",
        revision: Long = 1L
    ) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = actorTopic,
        actorTopic = actorTopic,
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = syncState,
        revision = revision,
        scheduledDate = date,
        ownerId = Store.topic(c)
    )

    private fun localStockEvent(id: String, type: String) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = "local",
        actorTopic = "",
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        revision = 1L,
        scheduledDate = LocalDate.now().toString(),
        ownerId = ""
    )

    private fun seedStock(remaining: Int) {
        val stock = MedicationStock(med.id, med.name, remaining, 30, 5)
        c.getSharedPreferences("dosefolk_stock", Context.MODE_PRIVATE)
            .edit().putString("stock", JSONArray().put(StockEngine.toJson(stock)).toString()).commit()
    }

    private fun seedEvents(events: List<DoseEvent>) {
        val array = JSONArray()
        events.forEach { array.put(EventStore.payload(it)) }
        c.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE)
            .edit().putString("events", array.toString()).commit()
    }

    @Test
    fun partialDeliveryLedger_retriesOnlyMissingTopics() {
        val eventId = "event-1"
        DeliveryLedger.markDelivered(c, eventId, "topic-a")

        assertTrue(DeliveryLedger.delivered(c, eventId, "topic-a"))
        assertFalse(DeliveryLedger.delivered(c, eventId, "topic-b"))

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

        assertEquals(1, AlertOutbox.pendingCount(c.applicationContext))
        AlertOutbox.dropTopic(c, "care-b")
        assertEquals(0, AlertOutbox.pendingCount(c))
    }

    @Test
    fun caregiverAlertOutbox_neverTrimsPendingAlertsAtFormerTwoHundredLimit() {
        repeat(250) { i -> AlertOutbox.enqueue(c, "care-a", "title", "alert-$i") }

        assertEquals(250, AlertOutbox.pendingCount(c))
    }

    @Test
    fun attentionBudget_blocksDuplicateEscalationButClearRearmsIt() {
        val date = LocalDate.now().toString()
        assertTrue(AttentionBudget.allow(c, "08:00", "care-a", 0, date))
        AttentionBudget.mark(c, "08:00", "care-a", 0, date)
        assertFalse(AttentionBudget.allow(c, "08:00", "care-a", 0, date))

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
    fun circleRevoke_clearsRuleOrderingSoRepairingCanStartFresh() {
        val peer = "peer-old"
        val ownerPrefs = c.getSharedPreferences("dosefolk_owner_scope", Context.MODE_PRIVATE)
        ownerPrefs.edit()
            .putLong("rule_stamp|$peer|${med.id}", 999_999L)
            .putLong("rule_rev|$peer|${med.id}", 50L)
            .putString("rule_actor|$peer|${med.id}", peer)
            .putString("rule_event|$peer|${med.id}", "old-event")
            .commit()

        RevocationCleanup.clearPeer(c, peer)

        assertFalse(ownerPrefs.contains("rule_stamp|$peer|${med.id}"))
        assertFalse(ownerPrefs.contains("rule_rev|$peer|${med.id}"))
        assertFalse(ownerPrefs.contains("rule_actor|$peer|${med.id}"))
        assertFalse(ownerPrefs.contains("rule_event|$peer|${med.id}"))

        val freshRule = ProgramRule(medicationId = med.id, everyNDays = 2, routineLabel = "fresh")
        val freshEvent = DoseEvent(
            eventId = "fresh-event",
            type = "program_rule_updated",
            time = "program",
            actor = "peer",
            actorTopic = peer,
            timestamp = 1L,
            medications = listOf(med),
            syncState = "synced",
            revision = 1L,
            ownerId = peer
        )
        assertTrue(OwnerScopeStore.applyRemoteRule(c, peer, freshRule, freshEvent))
        assertEquals("fresh", OwnerScopeStore.remoteRule(c, peer, med.id).routineLabel)
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

    @Test
    fun oldPendingEvent_survivesMoreThanThousandSyncedHistoryRows() {
        val pending = doseEvent("pending-old", type = "taken", syncState = "pending")
        val history = (0 until 1000).map { doseEvent("history-$it") }
        seedEvents(listOf(pending) + history)

        EventStore.append(c, doseEvent("new-history"))

        assertTrue(EventStore.pending(c).any { it.eventId == "pending-old" })
        assertTrue(EventStore.contains(c, "pending-old"))
        assertTrue(EventStore.load(c).size <= 1000)
    }

    @Test
    fun todaysConflictState_survivesLargeHistoricalCatchUpBatch() {
        Store.save(c, listOf(med))
        val today = LocalDate.now().toString()
        val taken = doseEvent("today-taken", "taken", today, actorTopic = "phone-a", revision = 10L)
        val missed = doseEvent("today-missed", "missed", today, actorTopic = "phone-b", revision = 11L)
        val oldHistory = (0 until 1000).map { doseEvent("old-$it") }
        seedEvents(listOf(taken, missed) + oldHistory)

        EventStore.append(c, doseEvent("new-old-history"))

        assertTrue(EventStore.contains(c, "today-taken"))
        assertTrue(EventStore.contains(c, "today-missed"))
        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00").status)
    }

    @Test
    fun protectedPendingRow_becomesCompactableAfterSuccessfulSync() {
        val pending = doseEvent("pending-to-sync", type = "taken", syncState = "pending")
        val history = (0 until 1000).map { doseEvent("history-sync-$it") }
        seedEvents(listOf(pending) + history)

        EventStore.append(c, doseEvent("trigger-compaction"))
        assertTrue(EventStore.contains(c, "pending-to-sync"))

        EventStore.markSynced(c, "pending-to-sync")

        assertTrue(EventStore.load(c).size <= 1000)
        assertFalse(EventStore.pending(c).any { it.eventId == "pending-to-sync" })
    }

    @Test
    fun replayedTakenEvent_decrementsStockOnlyOnce() {
        seedStock(10)
        val event = localStockEvent("stock-taken-1", "taken")

        StockEngine.applyEvent(c, event)
        StockEngine.applyEvent(c, event)

        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }

    @Test
    fun replayedUndoTakenEvent_restoresStockOnlyOnce() {
        seedStock(9)
        val event = localStockEvent("stock-undo-1", "undo_taken")

        StockEngine.applyEvent(c, event)
        StockEngine.applyEvent(c, event)

        assertEquals(10, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }
}
