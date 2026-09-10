package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StockBootstrapRoutingTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "ilac_takip",
            "dosefolk_stock",
            "dosefolk_alert_outbox",
            "dosefolk_ntfy_rate",
            "dosefolk_ntfy_traffic"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        c.getSharedPreferences("ilac_takip", Context.MODE_PRIVATE)
            .edit()
            .putString("topic", "publisher-topic")
            .commit()
    }

    @Test
    fun targetedStockBootstrap_usesPublisherTopicAndExplicitTarget() {
        val med = Medication("med-1", "Test", "1", listOf("08:00"))
        StockEngine.configure(c, med, packSize = 20, currentDoses = 12)

        // Ignore the normal broadcast created by configure; inspect only bootstrap.
        c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE)
            .edit().putString("alerts", "[]").commit()

        StockSync.publishAll(c, "peer-topic")

        val raw = c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE)
            .getString("alerts", "[]") ?: "[]"
        val rows = JSONArray(raw)
        assertEquals(1, rows.length())

        val row = rows.getJSONObject(0)
        assertEquals("publisher-topic", row.getString("topic"))
        val payload = JSONObject(row.getString("message"))
        assertEquals("publisher-topic", payload.getString("actorTopic"))
        assertEquals("peer-topic", payload.getString("targetTopic"))
        assertEquals(StockSync.EVENT_TYPE, payload.getString("type"))
        assertTrue(NtfyEnvelopeBinding.matches(
            JSONObject().put("topic", row.getString("topic")),
            payload
        ))
    }
}
