package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerBacklogTest {
    @Test
    fun successfulBoundedBatch_retriesWhenMoreOutboundEventsRemain() {
        assertTrue(
            SyncWorkDecision.shouldRetry(
                outboundOk = true,
                alertsOk = true,
                inboundOk = true,
                hasMoreOutbound = true
            )
        )
    }

    @Test
    fun completelyDrainedHealthySync_canFinishSuccessfully() {
        assertFalse(
            SyncWorkDecision.shouldRetry(
                outboundOk = true,
                alertsOk = true,
                inboundOk = true,
                hasMoreOutbound = false
            )
        )
    }

    @Test
    fun anyTransportFailure_retriesEvenWhenOutboundQueueIsEmpty() {
        assertTrue(SyncWorkDecision.shouldRetry(false, true, true, false))
        assertTrue(SyncWorkDecision.shouldRetry(true, false, true, false))
        assertTrue(SyncWorkDecision.shouldRetry(true, true, false, false))
    }

    @Test
    fun ntfyRetryAfter_secondsAreRespected() {
        val now = 1_000_000L
        assertEquals(now + 120_000L, Ntfy.retryAfterMillis("120", now))
    }

    @Test
    fun ntfyRetryAfter_missingOrMalformed_usesSafeDefault() {
        val now = 1_000_000L
        assertEquals(now + 60_000L, Ntfy.retryAfterMillis(null, now))
        assertEquals(now + 60_000L, Ntfy.retryAfterMillis("not-a-number", now))
    }

    @Test
    fun ntfyRetryAfter_isBoundedAgainstHammeringOrExtremeServerValues() {
        val now = 1_000_000L
        assertEquals(now + 30_000L, Ntfy.retryAfterMillis("1", now))
        assertEquals(now + 6L * 60L * 60L * 1_000L, Ntfy.retryAfterMillis("999999", now))
    }
}
