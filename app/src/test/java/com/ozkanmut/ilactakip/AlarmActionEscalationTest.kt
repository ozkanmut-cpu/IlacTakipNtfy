package com.ozkanmut.ilactakip

import android.app.NotificationManager
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
import java.time.LocalDate
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class AlarmActionEscalationTest {
    private lateinit var c: Context
    private val med = Medication("med-action", "Vasoxen", "5 mg", listOf("08:00"))
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
            "dosefolk_delivery_ledger"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    private fun actionIntent(action: String) = Intent(c, ActionReceiver::class.java)
        .putExtra("action", action)
        .putExtra("time", "08:00")
        .putExtra("names", med.name)
        .putExtra("ids", med.id)
        .putExtra("scheduledDate", date)

    private fun alarmIntent(deliveryId: String = "alarm-delivery-$date") = Intent(c, AlarmReceiver::class.java)
        .putExtra("time", "08:00")
        .putExtra("names", "${med.name} (${med.dose})")
        .putExtra("ids", med.id)
        .putExtra("scheduledDate", date)
        .putExtra("deliveryId", deliveryId)

    private fun pendingAlarm() {
        EventStore.append(c, DoseEvent(
            eventId = "alarm-1",
            type = "alarm",
            time = "08:00",
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = listOf(med),
            syncState = "synced",
            revision = 1L,
            scheduledDate = date,
            ownerId = Store.topic(c)
        ))
    }

    private fun remoteBatonEvent(id: String, type: String) = DoseEvent(
        eventId = id,
        type = type,
        time = "08:00",
        actor = "Remote caregiver",
        actorTopic = "care-remote",
        timestamp = System.currentTimeMillis(),
        medications = emptyList(),
        syncState = "synced",
        revision = 1L,
        scheduledDate = date,
        ownerId = Store.topic(c)
    )

    @Test
    fun takenNotificationAction_recordsTakenAndClearsActiveCareBaton() {
        val now = System.currentTimeMillis()
        c.getSharedPreferences("dosefolk_care_baton", Context.MODE_PRIVATE).edit().putString(
            "claims",
            "[{\"doseKey\":\"$date|08:00\",\"time\":\"08:00\",\"actor\":\"Caregiver\",\"actorTopic\":\"care-topic\",\"claimedAt\":$now,\"expiresAt\":${now + 600000},\"scheduledDate\":\"$date\"}]"
        ).commit()

        ActionReceiver().onReceive(c, actionIntent("taken"))

        val state = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now())
        assertEquals(DoseSessionStatus.TAKEN, state.status)
        assertEquals(null, CareBatonStore.active(c, "08:00", date))
    }

    @Test
    fun missedNotificationAction_recordsMissed() {
        ActionReceiver().onReceive(c, actionIntent("missed"))

        val state = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now())
        assertEquals(DoseSessionStatus.MISSED, state.status)
    }

    @Test
    fun snoozeNotificationAction_recordsSnoozeWithFutureDeadline() {
        val before = System.currentTimeMillis()
        ActionReceiver().onReceive(c, actionIntent("snooze"))

        val state = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now())
        assertEquals(DoseSessionStatus.SNOOZED, state.status)
        val event = state.latestEvent
        assertNotNull(event)
        assertTrue(event!!.snoozeUntil >= before + 29 * 60_000L)
    }

    @Test
    fun doubleTakenNotificationAction_onlyCreatesOneDoseConsumption() {
        pendingAlarm()
        StockEngine.configure(c, med, packSize = 10, currentDoses = 10, lowThreshold = 2)

        ActionReceiver().onReceive(c, actionIntent("taken"))
        ActionReceiver().onReceive(c, actionIntent("taken"))

        assertEquals(DoseSessionStatus.TAKEN, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)
        assertEquals(1, EventStore.load(c).count { it.type == "taken" && it.time == "08:00" && it.scheduledDate == date })
        assertEquals(9, StockEngine.forMedication(c, med.id)?.remainingDoses)
    }

    @Test
    fun staleMissedAction_afterTaken_isIgnored() {
        pendingAlarm()
        ActionReceiver().onReceive(c, actionIntent("taken"))

        ActionReceiver().onReceive(c, actionIntent("missed"))

        val state = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now())
        assertEquals(DoseSessionStatus.TAKEN, state.status)
        assertEquals(0, EventStore.load(c).count { it.type == "missed" && it.time == "08:00" && it.scheduledDate == date })
    }

    @Test
    fun duplicateSnoozeAction_doesNotExtendDeadlineOrCreateSecondEvent() {
        pendingAlarm()
        ActionReceiver().onReceive(c, actionIntent("snooze"))
        val first = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).latestEvent
        assertNotNull(first)

        ActionReceiver().onReceive(c, actionIntent("snooze"))

        val second = DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).latestEvent
        assertEquals(first?.eventId, second?.eventId)
        assertEquals(first?.snoozeUntil, second?.snoozeUntil)
        assertEquals(1, EventStore.load(c).count { it.type == "snoozed" && it.time == "08:00" && it.scheduledDate == date })
    }

    @Test
    fun terminalAction_clearsVisibleMedicationNotification() {
        AlarmReceiver().onReceive(c, alarmIntent("visible-notification"))
        val manager = c.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.count { it.id == ("group-$date-08:00").hashCode() })

        ActionReceiver().onReceive(c, actionIntent("taken"))

        assertEquals(0, manager.activeNotifications.count { it.id == ("group-$date-08:00").hashCode() })
    }

    @Test
    fun appAndNotificationRace_createsOnlyOneTerminalFact() {
        pendingAlarm()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val t1 = Thread {
            start.await()
            Ntfy.sendEvent(c, "taken", "08:00", listOf(med), date)
            done.countDown()
        }
        val t2 = Thread {
            start.await()
            Ntfy.sendEvent(c, "missed", "08:00", listOf(med), date)
            done.countDown()
        }
        t1.start(); t2.start(); start.countDown(); done.await()

        val terminal = EventStore.load(c).filter {
            it.time == "08:00" && it.scheduledDate == date && it.type in setOf("taken", "missed")
        }
        assertEquals(1, terminal.size)
        assertTrue(DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status in setOf(DoseSessionStatus.TAKEN, DoseSessionStatus.MISSED))
    }

    @Test
    fun terminalAction_clearsAttentionBudgetSoOldEscalationCannotRemainConsumed() {
        AttentionBudget.mark(c, "08:00", "care-topic", 0, date)
        assertFalse(AttentionBudget.allow(c, "08:00", "care-topic", 0, date))

        ActionReceiver().onReceive(c, actionIntent("taken"))

        assertTrue(AttentionBudget.allow(c, "08:00", "care-topic", 0, date))
    }

    @Test
    fun careBatonRelease_removesClaimAndKeepsDoseUnresolved() {
        pendingAlarm()
        CareBatonStore.claim(c, "08:00", minutes = 30, scheduledDate = date)
        assertNotNull(CareBatonStore.active(c, "08:00", date))

        CareBatonStore.release(c, "08:00", date)

        assertEquals(null, CareBatonStore.active(c, "08:00", date))
        assertEquals(DoseSessionStatus.PENDING, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)
    }

    @Test
    fun remoteCareClaimReplay_keepsSingleClaim() {
        val claim = remoteBatonEvent("remote-claim", "care_claimed")

        CareBatonStore.applyRemoteClaim(c, claim)
        CareBatonStore.applyRemoteClaim(c, claim)

        assertEquals(1, CareBatonStore.load(c).count { it.time == "08:00" && it.scheduledDate == date })
        assertEquals("care-remote", CareBatonStore.active(c, "08:00", date)?.actorTopic)
    }

    @Test
    fun remoteCareRelease_resumesUnresolvedDose_andReplayDoesNotResetBudgetAgain() {
        pendingAlarm()
        val claim = remoteBatonEvent("remote-claim", "care_claimed")
        CareBatonStore.applyRemoteClaim(c, claim)
        assertNotNull(CareBatonStore.active(c, "08:00", date))

        val release = remoteBatonEvent("remote-release", "care_released")
        CareBatonStore.applyRemoteRelease(c, release)

        assertEquals(null, CareBatonStore.active(c, "08:00", date))
        assertEquals(DoseSessionStatus.PENDING, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)

        AttentionBudget.mark(c, "08:00", "care-topic", 0, date)
        assertFalse(AttentionBudget.allow(c, "08:00", "care-topic", 0, date))

        CareBatonStore.applyRemoteRelease(c, release)

        assertFalse(AttentionBudget.allow(c, "08:00", "care-topic", 0, date))
        assertEquals(null, CareBatonStore.active(c, "08:00", date))
    }
}
