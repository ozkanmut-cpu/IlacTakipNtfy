package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertOutboxBatchTest {
    @Test
    fun flushBatch_isBoundedAndStrictlyOldestFirst() {
        val all = (0 until 25).map { i ->
            // AlertOutbox stores newest first: id-24 is the oldest row here.
            PendingAlert("id-$i", "care", "title", "message-$i", createdAt = 25L - i)
        }

        val batch = AlertOutbox.batchForFlush(all)

        assertEquals(10, batch.size)
        assertEquals((24 downTo 15).map { "id-$it" }, batch.map { it.id })
    }

    @Test
    fun flushBatch_reversesEntireQueueWhenBelowLimit() {
        val all = (0 until 4).map { i -> PendingAlert("id-$i", "care", "title", "message-$i", 4L - i) }

        assertEquals(all.asReversed(), AlertOutbox.batchForFlush(all))
    }
}
