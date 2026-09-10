package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class NtfyRateGateTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("dosefolk_ntfy_rate", "dosefolk_alert_outbox")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun numericRetryAfter_isRespectedAndClamped() {
        val now = 1_000_000L
        assertEquals(now + 120_000L, NtfyRateGate.retryAfterMillis("120", now))
        assertEquals(now + 30_000L, NtfyRateGate.retryAfterMillis("1", now))
        assertEquals(now + 6L * 60L * 60L * 1000L, NtfyRateGate.retryAfterMillis("999999", now))
        assertEquals(now + 60_000L, NtfyRateGate.retryAfterMillis("bad", now))
        assertEquals(now + 60_000L, NtfyRateGate.retryAfterMillis(null, now))
    }

    @Test
    fun httpDateRetryAfter_isSupported() {
        val now = Instant.parse("2026-09-10T01:00:00Z").toEpochMilli()
        val target = Instant.ofEpochMilli(now + 120_000L)
            .atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(now + 120_000L, NtfyRateGate.retryAfterMillis(target, now))
    }

    @Test
    fun persistedGate_isSharedAndClearsAfterSuccess() {
        val now = System.currentTimeMillis()
        val until = NtfyRateGate.record429(c, "120", now)
        assertEquals(until, NtfyRateGate.blockedUntil(c))
        assertTrue(NtfyRateGate.isBlocked(c, now))
        NtfyRateGate.clearAfterSuccess(c)
        assertEquals(0L, NtfyRateGate.blockedUntil(c))
        assertFalse(NtfyRateGate.isBlocked(c, now))
    }

    @Test
    fun alertBackoff_isVisibleToPublisherPath() {
        val now = System.currentTimeMillis()
        val until = NtfyRateGate.record429(c, "120", now)
        assertEquals(until, Ntfy.rateBlockedUntil(c))
    }

    @Test
    fun blockedAlertOutbox_keepsRowPendingWithoutMarkingInFlight() {
        AlertOutbox.enqueue(c, "care-topic", "Alert", "Message", id = "alert-1", kick = false)
        NtfyRateGate.record429(c, "120")

        assertFalse(AlertOutbox.flushBlocking(c))
        assertEquals(1, AlertOutbox.pendingCount(c))

        val raw = c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE)
            .getString("alerts", "[]") ?: "[]"
        val row = JSONArray(raw).getJSONObject(0)
        assertEquals("alert-1", row.getString("id"))
        assertFalse(row.getBoolean("inFlight"))
    }
}
