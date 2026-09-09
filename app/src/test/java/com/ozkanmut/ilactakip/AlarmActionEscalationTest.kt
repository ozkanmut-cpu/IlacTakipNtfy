package com.ozkanmut.ilactakip

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
    fun terminalAction_clearsAttentionBudgetSoOldEscalationCannotRemainConsumed() {
        AttentionBudget.mark(c, "08:00", "care-topic", 0, date)
        assertFalse(AttentionBudget.allow(c, "08:00", "care-topic", 0, date))

        ActionReceiver().onReceive(c, actionIntent("taken"))

        assertTrue(AttentionBudget.allow(c, "08:00", "care-topic", 0, date))
    }

    @Test
    fun careBatonRelease_removesClaimAndKeepsDoseUnresolved() {
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
        CareBatonStore.claim(c, "08:00", minutes = 30, scheduledDate = date)
        assertNotNull(CareBatonStore.active(c, "08:00", date))

        CareBatonStore.release(c, "08:00", date)

        assertEquals(null, CareBatonStore.active(c, "08:00", date))
        assertEquals(DoseSessionStatus.PENDING, DoseStateEngine.stateForTime(c, "08:00", LocalDate.now()).status)
    }
}
