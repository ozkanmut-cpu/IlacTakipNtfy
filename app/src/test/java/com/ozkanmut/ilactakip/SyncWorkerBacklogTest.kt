package com.ozkanmut.ilactakip

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
}
