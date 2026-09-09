package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StaleCaregiverAlertCleanupTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun resolvedSession_dropsOnlyMatchingEscalationRows() {
        AlertOutbox.enqueue(c, "care-a", "t", "m", id = "escalation|2026-09-09|08:00|care-a|0", kick = false)
        AlertOutbox.enqueue(c, "care-b", "t", "m", id = "escalation|2026-09-09|08:00|care-b|1", kick = false)
        AlertOutbox.enqueue(c, "care-a", "t", "m", id = "escalation|2026-09-09|20:00|care-a|0", kick = false)
        AlertOutbox.enqueue(c, "care-a", "t", "m", id = "circle-revoked-message", kick = false)

        assertEquals(4, AlertOutbox.pendingCount(c))

        AlertOutbox.dropEscalationSession(c, "08:00", "2026-09-09")

        assertEquals(2, AlertOutbox.pendingCount(c))
    }

    @Test
    fun smartEscalationCancel_removesQueuedSessionAlert() {
        AlertOutbox.enqueue(c, "care-a", "t", "m", id = "escalation|2026-09-09|08:00|care-a|0", kick = false)

        SmartEscalation.cancel(c, "08:00", "2026-09-09")

        assertEquals(0, AlertOutbox.pendingCount(c))
    }
}
