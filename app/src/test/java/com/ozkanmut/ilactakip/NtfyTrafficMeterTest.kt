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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NtfyTrafficMeterTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_ntfy_traffic", "dosefolk_ntfy_rate", "dosefolk_alert_outbox")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun successfulPosts_incrementWithinSameUtcDay() {
        val t = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
        assertEquals(0, NtfyTrafficMeter.successfulPostsToday(c, t))
        assertEquals(1, NtfyTrafficMeter.recordSuccessfulPost(c, t))
        assertEquals(2, NtfyTrafficMeter.recordSuccessfulPost(c, t + 1_000L))
        assertEquals(2, NtfyTrafficMeter.successfulPostsToday(c, t + 2_000L))
    }

    @Test
    fun utcMidnight_resetsCounter() {
        val before = Instant.parse("2026-09-10T23:59:59Z").toEpochMilli()
        val after = Instant.parse("2026-09-11T00:00:01Z").toEpochMilli()
        NtfyTrafficMeter.recordSuccessfulPost(c, before)
        NtfyTrafficMeter.recordSuccessfulPost(c, before)
        assertEquals(2, NtfyTrafficMeter.successfulPostsToday(c, before))
        assertEquals(0, NtfyTrafficMeter.successfulPostsToday(c, after))
        assertEquals(1, NtfyTrafficMeter.recordSuccessfulPost(c, after))
    }

    @Test
    fun commonSuccessHook_recordsTrafficAndClearsBackoff() {
        NtfyRateGate.record429(c, "120")
        assertEquals(0, NtfyTrafficMeter.successfulPostsToday(c))
        NtfyRateGate.clearAfterSuccess(c)
        assertEquals(1, NtfyTrafficMeter.successfulPostsToday(c))
        assertEquals(0L, NtfyRateGate.blockedUntil(c))
    }

    @Test
    fun softBudget_defersOnlyReplaceableStockTraffic() {
        val t = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
        repeat(NtfyTrafficBudget.NONCRITICAL_SOFT_LIMIT) {
            NtfyTrafficMeter.recordSuccessfulPost(c, t)
        }
        assertTrue(NtfyTrafficBudget.shouldDefer(c, "stock|owner|med|topic", t))
        assertTrue(NtfyTrafficBudget.shouldDefer(c, "stock|owner|med|topic|next", t))
        assertFalse(NtfyTrafficBudget.shouldDefer(c, "escalation|2026-09-10|08:00|peer|0", t))
        assertFalse(NtfyTrafficBudget.shouldDefer(c, "circle-revocation-1", t))
        assertFalse(NtfyTrafficBudget.shouldDefer(c, "ordinary-critical-alert", t))
    }

    @Test
    fun deferredStockRows_doNotStarveCriticalRowsBehindThem() {
        repeat(NtfyTrafficBudget.NONCRITICAL_SOFT_LIMIT) {
            NtfyTrafficMeter.recordSuccessfulPost(c)
        }
        val stockRows = (1..12).map { i ->
            PendingAlert("stock|owner|med$i|topic", "topic", "sync", "stock$i", i.toLong())
        }
        val critical = PendingAlert(
            "escalation|2026-09-10|08:00|peer|0",
            "peer",
            "alert",
            "critical",
            99L
        )
        // Persisted order is newest first. Put the critical row newest so it would
        // sit behind >10 oldest stock rows in the old fixed oldest-batch algorithm.
        val persisted = listOf(critical) + stockRows.reversed()
        val eligible = AlertOutbox.eligibleBatchForFlush(c, persisted)
        assertEquals(listOf(critical.id), eligible.map { it.id })
    }
}
