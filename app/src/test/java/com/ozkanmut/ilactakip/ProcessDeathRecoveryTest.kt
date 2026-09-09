package com.ozkanmut.ilactakip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ProcessDeathRecoveryTest {
    private lateinit var c: Context
    private val pendingMed = Medication("med-pending", "Pending Med", "1 tablet", listOf("08:00"))
    private val snoozedMed = Medication("med-snooze", "Snoozed Med", "1 tablet", listOf("09:00"))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_alarm_scheduler",
            "dosefolk_program_rules",
            "dosefolk_care_baton",
            "dosefolk_attention_budget",
            "dosefolk_undo_recovery",
            "dosefolk_temporary_care",
            "dosefolk_delivery_ledger",
            "dosefolk_alert_outbox"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(pendingMed, snoozedMed))
    }

    private fun event(
        id: String,
        type: String,
        time: String,
        med: Medication,
        syncState: String = "synced",
        snoozeUntil: Long = 0L
    ) = DoseEvent(
        eventId = id,
        type = type,
        time = time,
        actor = "Tester",
        actorTopic = "tester-topic",
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = syncState,
        revision = System.currentTimeMillis(),
        scheduledDate = LocalDate.now().toString(),
        snoozeUntil = snoozeUntil
    )

    private fun escalation(time: String, stage: Int): PendingIntent? {
        val date = LocalDate.now().toString()
        return PendingIntent.getBroadcast(
            c,
            ("dosefolk-escalation-$date-$time-$stage").hashCode(),
            Intent(c, EscalationReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun snooze(time: String): PendingIntent? {
        val date = LocalDate.now().toString()
        return PendingIntent.getBroadcast(
            c,
            AlarmScheduler.snoozeKey(time, date).hashCode(),
            Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Test
    fun bootRestoresPendingEscalation_withoutResettingAttentionBudget() {
        EventStore.append(c, event("alarm-pending", "alarm", "08:00", pendingMed, syncState = "pending"))
        val date = LocalDate.now().toString()
        AttentionBudget.mark(c, "08:00", "care-topic", 0, date)

        assertNull(escalation("08:00", 0))
        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNotNull(escalation("08:00", 0))
        assertNotNull(escalation("08:00", 1))
        assertFalse(AttentionBudget.allow(c, "08:00", "care-topic", 0, date))
        assertTrue(EventStore.pending(c).any { it.eventId == "alarm-pending" })
    }

    @Test
    fun bootRestoresSnoozeAndCareBatonTogether() {
        val now = System.currentTimeMillis()
        val date = LocalDate.now().toString()
        EventStore.append(c, event("alarm-snooze", "alarm", "09:00", snoozedMed))
        EventStore.append(c, event("snooze-event", "snoozed", "09:00", snoozedMed, snoozeUntil = now + 30 * 60_000L))

        CareBatonStore.applyRemoteClaim(
            c,
            DoseEvent(
                eventId = "baton",
                type = "care_claimed",
                time = "08:00",
                actor = "Caregiver",
                actorTopic = "care-topic",
                timestamp = now,
                medications = emptyList(),
                syncState = "synced",
                revision = 1L,
                scheduledDate = date
            )
        )

        // Simulate AlarmManager state loss while persistent stores survive.
        snooze("09:00")?.cancel()
        escalation("08:00", 0)?.cancel()
        escalation("08:00", 1)?.cancel()

        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNotNull(snooze("09:00"))
        assertNotNull(CareBatonStore.active(c, "08:00", date))
        assertNull(escalation("09:00", 0))
    }

    @Test
    fun resolvedDose_doesNotRecreateEscalationAfterBoot() {
        EventStore.append(c, event("alarm-resolved", "alarm", "08:00", pendingMed))
        EventStore.append(c, event("taken-resolved", "taken", "08:00", pendingMed))

        BootReceiver().onReceive(c, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(escalation("08:00", 0))
        assertNull(escalation("08:00", 1))
    }
}
