package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteReceiptBatchRecoveryTest {
    private lateinit var c: Context
    private val prefsName = "dosefolk_remote_event_receipts"

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        c.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun processedReceiptRemainsInflightUntilCheckpointCommit() {
        RemoteEventReceiptStore.markProcessed(c, "e1")
        RemoteEventReceiptStore.markProcessed(c, "e2")

        val p = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        assertTrue(RemoteEventReceiptStore.processed(c, "e1"))
        assertTrue(RemoteEventReceiptStore.processed(c, "e2"))
        assertTrue(p.getStringSet("processed_event_ids", emptySet()).orEmpty().isEmpty())
        assertEquals(setOf("e1", "e2"), p.getStringSet("processed_event_ids_inflight", emptySet()).orEmpty())

        RemoteEventReceiptStore.commitSuccessfulBatch(c)

        assertTrue(RemoteEventReceiptStore.processed(c, "e1"))
        assertTrue(RemoteEventReceiptStore.processed(c, "e2"))
        assertTrue(p.getStringSet("processed_event_ids_inflight", emptySet()).orEmpty().isEmpty())
        assertEquals(setOf("e1", "e2"), p.getStringSet("processed_event_ids", emptySet()).orEmpty())
    }

    @Test
    fun crashBeforeCheckpointLeavesInflightReceiptReplaySafe() {
        RemoteEventReceiptStore.markProcessed(c, "crash-window")

        // Simulated process death: no batch commit happens. Durable in-flight
        // membership must still reject the event when catch-up replays it.
        assertTrue(RemoteEventReceiptStore.processed(c, "crash-window"))
    }

    @Test
    fun successfulBatchCompactionKeepsNewestFiveThousandReceipts() {
        val p = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val history = (0 until 4999).map { "h$it" }
        val inflight = (0 until 10).map { "n$it" }
        p.edit()
            .putStringSet("processed_event_ids", history.toSet())
            .putString("processed_event_order_v2", JSONArray(history).toString())
            .putStringSet("processed_event_ids_inflight", inflight.toSet())
            .putString("processed_event_order_inflight_v1", JSONArray(inflight).toString())
            .commit()

        RemoteEventReceiptStore.commitSuccessfulBatch(c)

        val kept = p.getStringSet("processed_event_ids", emptySet()).orEmpty()
        assertEquals(5000, kept.size)
        assertFalse("h0" in kept)
        assertFalse("h8" in kept)
        assertTrue("h9" in kept)
        assertTrue("n0" in kept)
        assertTrue("n9" in kept)
        assertTrue(p.getStringSet("processed_event_ids_inflight", emptySet()).orEmpty().isEmpty())
    }
}
