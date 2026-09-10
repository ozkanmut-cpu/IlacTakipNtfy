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

@RunWith(RobolectricTestRunner::class)
class AlertOutboxCoalescingTest {
    private lateinit var c: Context
    private val prefsName = "dosefolk_alert_outbox"

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun rows(): JSONArray = JSONArray(
        c.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("alerts", "[]") ?: "[]"
    )

    @Test
    fun pendingLatest_replacesOlderSnapshotInsteadOfGrowingQueue() {
        repeat(10) { n ->
            AlertOutbox.enqueueLatest(
                c,
                topic = "publisher-topic",
                title = "Dosefolk sync",
                message = "stock=$n",
                stableId = "stock|owner|med-1|publisher-topic",
                kick = false
            )
        }

        val a = rows()
        assertEquals(1, a.length())
        val row = a.getJSONObject(0)
        assertEquals("stock|owner|med-1|publisher-topic", row.getString("id"))
        assertEquals("stock=9", row.getString("message"))
        assertFalse(row.getBoolean("inFlight"))
    }

    @Test
    fun inFlightSnapshot_isImmutableAndOnlyOneLatestFollowupIsKept() {
        val stableId = "stock|owner|med-1|publisher-topic"
        AlertOutbox.enqueueLatest(c, "publisher-topic", "Dosefolk sync", "stock=1", stableId, kick = false)

        val initial = rows()
        initial.getJSONObject(0).put("inFlight", true)
        c.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString("alerts", initial.toString()).commit()

        AlertOutbox.enqueueLatest(c, "publisher-topic", "Dosefolk sync", "stock=2", stableId, kick = false)
        AlertOutbox.enqueueLatest(c, "publisher-topic", "Dosefolk sync", "stock=3", stableId, kick = false)

        val a = rows()
        assertEquals(2, a.length())
        val base = (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == stableId }
        val next = (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "$stableId|next" }
        assertEquals("stock=1", base.getString("message"))
        assertTrue(base.getBoolean("inFlight"))
        assertEquals("stock=3", next.getString("message"))
        assertFalse(next.getBoolean("inFlight"))
    }
}
