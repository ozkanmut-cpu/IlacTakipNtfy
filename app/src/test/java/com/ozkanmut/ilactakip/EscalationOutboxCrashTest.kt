package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class EscalationOutboxCrashTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_alert_outbox", "dosefolk_attention_budget")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun crashBeforeBudgetMark_reenqueueKeepsSingleAlertRow() {
        val date = LocalDate.now().toString()
        val id = "escalation|$date|08:00|peer-a|0"

        assertTrue(AlertOutbox.enqueue(c, "peer-a", "title", "body", id = id, kick = false))
        assertEquals(1, AlertOutbox.pendingCount(c))

        // Simulated retry after process death before AttentionBudget.mark().
        assertFalse(AlertOutbox.enqueue(c, "peer-a", "title", "body", id = id, kick = false))
        assertEquals(1, AlertOutbox.pendingCount(c))

        AttentionBudget.mark(c, "08:00", "peer-a", 0, date)
        assertFalse(AttentionBudget.allow(c, "08:00", "peer-a", 0, date))
    }

    @Test
    fun differentStagesRemainIndependentAlerts() {
        val date = LocalDate.now().toString()
        AlertOutbox.enqueue(c, "peer-a", "title", "body", id = "escalation|$date|08:00|peer-a|0", kick = false)
        AlertOutbox.enqueue(c, "peer-b", "title", "body", id = "escalation|$date|08:00|peer-b|1", kick = false)

        assertEquals(2, AlertOutbox.pendingCount(c))
    }
}
