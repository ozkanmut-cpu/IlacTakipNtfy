package com.ozkanmut.ilactakip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrkoBridgeSelfTestTest {
    @Test fun pendingTestTimesOutAfterSixSeconds() {
        val sentAt = 1_000L
        val status = OrkoBridgeSelfTest.Status(
            token = "token",
            sentAtMs = sentAt,
            ackAtMs = 0L
        )

        assertFalse(status.timedOut(sentAt + 5_999L))
        assertTrue(status.timedOut(sentAt + 6_000L))
    }

    @Test fun acknowledgedTestNeverTimesOut() {
        val sentAt = 1_000L
        val status = OrkoBridgeSelfTest.Status(
            token = "token",
            sentAtMs = sentAt,
            ackAtMs = sentAt + 100L
        )

        assertTrue(status.acknowledged)
        assertFalse(status.timedOut(sentAt + 60_000L))
    }
}
