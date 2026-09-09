package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertOutboxBatchTest {
    @Test
    fun flushBatch_isBoundedAndStartsWithOldestPendingAlerts() {
        val all = (0 until 25).map { i ->
            // AlertOutbox stores newest first.
            PendingAlert("id-$i", "care", "title", "message-$i", createdAt = 25L - i)
        }

        val batch = AlertOutbox.batchForFlush(all)

        assertEquals(10, batch.size)
        assertEquals((15 until 25).map { "id-$it" }, batch.map { it.id })
    }

    @Test
    fun flushBatch_usesEntireQueueWhenBelowLimit() {
        val all = (0 until 4).map { i -> PendingAlert("id-$i", "care", "title", "message-$i", i.toLong()) }

        assertEquals(all, AlertOutbox.batchForFlush(all))
    }
}
