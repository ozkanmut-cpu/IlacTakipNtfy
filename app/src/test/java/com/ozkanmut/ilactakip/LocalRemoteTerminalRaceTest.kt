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
class LocalRemoteTerminalRaceTest {
    private lateinit var c: Context
    private val med = Medication("race-med", "Race Med", "1 tablet", listOf("08:00"))
    private val date get() = LocalDate.now().toString()

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_attention_budget",
            "dosefolk_care_baton",
            "dosefolk_stock",
            "dosefolk_owner_scope",
            "dosefolk_program_rules",
            "dosefolk_alert_outbox",
            "dosefolk_delivery_ledger",
            "dosefolk_prn_usage"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
        StockEngine.configure(c, med, packSize = 10, currentDoses = 10, lowThreshold = 2)
    }

    private fun alarmIntent() = Intent(c, AlarmReceiver::class.java)
        .putExtra("time", "08:00")
        .putExtra("names", "${med.name} (${med.dose})")
        .putExtra("ids", med.id)
        .putExtra("scheduledDate", date)
        .putExtra("deliveryId", "race-alarm-$date")

    private fun event(id: String, type: String, actorTopic: String, revision: Long) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = actorTopic,
        actorTopic = actorTopic,
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        revision = revision,
        scheduledDate = date,
        ownerId = Store.topic(c)
    )

    private fun apply(event: DoseEvent) {
        EventStore.append(c, event)
        OwnerScopeStore.remember(c, event)
        StockEngine.applyEvent(c, event)
        SyncEngine.applyRemoteState(c, event)
    }

    @Test
    fun concurrentLocalAndRemoteTaken_consumesStockOnceAndLeavesTakenState() {
        AlarmReceiver().onReceive(c, alarmIntent())
        apply(event("local-taken", "taken", Store.topic(c), 2L))
        apply(event("remote-taken", "taken", "remote-device", 2L))

        assertEquals(DoseSessionStatus.TAKEN, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)
        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
        val manager = c.getSystemService(NotificationManager::class.java)
        assertEquals(0, manager.activeNotifications.count { it.id == ("group-$date-08:00").hashCode() })
    }

    @Test
    fun concurrentOppositeTerminalFacts_preserveConflictWithoutDoubleStockSideEffect() {
        AlarmReceiver().onReceive(c, alarmIntent())
        // Same logical revision from independent devices models true concurrency.
        apply(event("local-taken", "taken", Store.topic(c), 2L))
        apply(event("remote-missed", "missed", "remote-device", 2L))

        assertEquals(DoseSessionStatus.CONFLICT, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)
        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
        val manager = c.getSystemService(NotificationManager::class.java)
        assertEquals(0, manager.activeNotifications.count { it.id == ("group-$date-08:00").hashCode() })
        assertTrue(EventStore.load(c).any { it.type == "taken" && it.scheduledDate == date })
        assertTrue(EventStore.load(c).any { it.type == "missed" && it.scheduledDate == date })
    }

    @Test
    fun localDecisionAfterObservedRemoteFact_getsHigherRevisionAndSettlesState() {
        AlarmReceiver().onReceive(c, alarmIntent())
        val remote = event("remote-missed", "missed", "remote-device", 2L)
        apply(remote)

        val localRevision = EventStore.nextRevision(c)
        assertEquals(3L, localRevision)
        apply(event("local-taken-after-observe", "taken", Store.topic(c), localRevision))

        assertEquals(DoseSessionStatus.TAKEN, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)
        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }
}
