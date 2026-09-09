package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteReceiptRetentionTest {
    @Test
    fun oldestReceiptIsEvictedDeterministically() {
        val next = RemoteReceiptRetention.nextOrder(
            existing = listOf("e1", "e2", "e3"),
            eventId = "e4",
            limit = 3
        )
        assertEquals(listOf("e2", "e3", "e4"), next)
    }

    @Test
    fun replayedReceiptMovesToNewestWithoutDuplication() {
        val next = RemoteReceiptRetention.nextOrder(
            existing = listOf("e1", "e2", "e3"),
            eventId = "e2",
            limit = 3
        )
        assertEquals(listOf("e1", "e3", "e2"), next)
    }

    @Test
    fun boundedRetentionKeepsExactlyNewestWindow() {
        var order = emptyList<String>()
        repeat(20) { index ->
            order = RemoteReceiptRetention.nextOrder(order, "e$index", 5)
        }
        assertEquals(listOf("e15", "e16", "e17", "e18", "e19"), order)
    }
}
