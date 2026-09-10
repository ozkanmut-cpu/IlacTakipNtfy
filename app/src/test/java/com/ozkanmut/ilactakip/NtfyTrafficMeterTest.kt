package com.ozkanmut.ilactakip

import android.content.Context
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NtfyTrafficMeterTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_ntfy_traffic", "dosefolk_ntfy_rate", "dosefolk_alert_outbox", "ilac_takip")
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
    fun repeatedBootstrap_coalescesProgramAndRuleSnapshots() {
        val med = JSONObject()
            .put("id", "med-1")
            .put("name", "Test")
            .put("dose", "1")
            .put("times", JSONArray().put("08:00"))
        c.getSharedPreferences("ilac_takip", Context.MODE_PRIVATE)
            .edit()
            .putString("topic", "publisher-topic")
            .putString("meds", JSONArray().put(med).toString())
            .commit()

        CircleInitialSync.publishToPeer(c, "peer-topic")
        CircleInitialSync.publishToPeer(c, "peer-topic")

        val raw = c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE)
            .getString("alerts", "[]") ?: "[]"
        val rows = JSONArray(raw)
        val bootstrapRows = (0 until rows.length())
            .map { rows.getJSONObject(it).getString("id") }
            .filter { it.startsWith("bootstrap|") }
        assertEquals(2, bootstrapRows.size)
        assertEquals(2, bootstrapRows.distinct().size)
    }

    private fun fillSoftBudget() {
        repeat(NtfyTrafficBudget.NONCRITICAL_SOFT_LIMIT) {
            NtfyTrafficMeter.recordSuccessfulPost(c)
        }
    }

    @Test
    fun onlySoftDeferredStock_isSettledForCurrentBudget() {
        fillSoftBudget()
        val rows = listOf(
            PendingAlert("stock|me|med-1|peer", "me", "sync", "{}", 1L)
        )

        assertTrue(AlertOutbox.settledForCurrentBudget(c, rows))
    }

    @Test
    fun criticalBehindDeferredStock_isNotSettledForCurrentBudget() {
        fillSoftBudget()
        val rows = listOf(
            PendingAlert("critical-event", "me", "sync", "{}", 2L),
            PendingAlert("stock|me|med-1|peer", "me", "sync", "{}", 1L)
        )

        assertFalse(AlertOutbox.settledForCurrentBudget(c, rows))
        assertEquals(listOf("critical-event"), AlertOutbox.eligibleBatchForFlush(c, rows).map { it.id })
    }

    @Test
    fun actionablePendingCount_hidesOnlySoftDeferredStock() {
        fillSoftBudget()
        AlertOutbox.enqueueLatest(c, "me", "sync", "{}", "stock|me|med-1|peer", kick = false)
        AlertOutbox.enqueue(c, "me", "urgent", "dose", id = "critical-event", kick = false)

        assertEquals(2, AlertOutbox.pendingCount(c))
        assertEquals(1, AlertOutbox.actionablePendingCount(c))
    }
}
