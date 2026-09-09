package com.ozkanmut.ilactakip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDeliveryProbeTest {
    @Test
    fun matchingSequence_isDetectedAmongCachedMessages() {
        val lines = sequenceOf(
            "{\"event\":\"message\",\"id\":\"a\",\"sequence_id\":\"other\"}",
            "{\"event\":\"message\",\"id\":\"b\",\"sequence_id\":\"alert-123\"}",
            "{\"event\":\"keepalive\",\"id\":\"c\"}"
        )

        assertTrue(AlertDeliveryProbe.containsSequence(lines, "alert-123"))
    }

    @Test
    fun malformedOrDifferentMessages_doNotMatch() {
        val lines = sequenceOf(
            "not-json",
            "{\"event\":\"message\",\"sequence_id\":\"other\"}",
            "{\"event\":\"open\",\"sequence_id\":\"alert-123\"}"
        )

        assertFalse(AlertDeliveryProbe.containsSequence(lines, "alert-123"))
    }
}
