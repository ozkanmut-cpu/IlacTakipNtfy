package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SmartEscalationRestartTest {
    private lateinit var c: Context
    private val date = LocalDate.now().toString()
    private val time = "08:00"
    private val med = Medication("med-escalation-restart", "Restart Med", "1 tablet", listOf(time))

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(c)
        listOf(
            "ilac_takip",
            "dosefolk_events",
            "dosefolk_smart_escalation",
            "dosefolk_attention_budget",
            "dosefolk_alert_outbox",
            "dosefolk_care_baton",
            "dosefolk_owner_scope",
            "dosefolk_delivery_ledger"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Store.save(c, listOf(med))
    }

    @Test
    fun scheduleRecoversAnchorFromCanonicalAlarmAndDoesNotResetIt() {
        val originalAlarmAt = System.currentTimeMillis() - 8 * 60_000L
        EventStore.append(c, DoseEvent(
            eventId = "alarm-restart-anchor",
            type = "alarm",
            time = time,
            actor = "Local",
            actorTopic = Store.topic(c),
            timestamp = originalAlarmAt,
            medications = listOf(med),
            syncState = "pending",
            revision = 1L,
            scheduledDate = date,
            ownerId = OwnerScopeStore.localOwnerId(c)
        ))

        SmartEscalation.schedule(c, time, date)
        assertEquals(originalAlarmAt, SmartEscalation.storedAnchor(c, time, date))

        SmartEscalation.schedule(c, time, date)
        assertEquals(originalAlarmAt, SmartEscalation.storedAnchor(c, time, date))
    }

    @Test
    fun overdueStageIsScheduledImmediatelyInsteadOfRestartingFullDelay() {
        val now = 1_000_000L
        val anchor = now - 20 * 60_000L
        assertEquals(now + 1_000L, SmartEscalation.triggerFor(anchor, 10L, now))
    }

    @Test
    fun notYetDueStageKeepsOriginalAlarmRelativeDeadline() {
        val anchor = 1_000_000L
        val now = anchor + 8 * 60_000L
        assertEquals(anchor + 10 * 60_000L, SmartEscalation.triggerFor(anchor, 10L, now))
    }
}
