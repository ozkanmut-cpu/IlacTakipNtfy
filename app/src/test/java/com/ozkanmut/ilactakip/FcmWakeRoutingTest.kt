package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Test

class FcmWakeRoutingTest {
    @Test
    fun validMinimalWakeKicksSync() {
        assertEquals(
            FcmWakeAction.KICK_SYNC,
            FcmWakeRouter.action(
                mapOf("wakeType" to "sync", "protocolVersion" to "1"),
                deletedMessages = false
            )
        )
    }

    @Test
    fun wrongProtocolIsIgnored() {
        assertEquals(
            FcmWakeAction.IGNORE,
            FcmWakeRouter.action(
                mapOf("wakeType" to "sync", "protocolVersion" to "2"),
                deletedMessages = false
            )
        )
    }

    @Test
    fun unrelatedDataIsIgnored() {
        assertEquals(
            FcmWakeAction.IGNORE,
            FcmWakeRouter.action(mapOf("kind" to "dose-event"), deletedMessages = false)
        )
    }

    @Test
    fun healthOrEventFieldsCannotPiggybackOnWake() {
        assertEquals(
            FcmWakeAction.IGNORE,
            FcmWakeRouter.action(
                mapOf(
                    "wakeType" to "sync",
                    "protocolVersion" to "1",
                    "medication" to "secret",
                    "dose" to "secret",
                    "event" to "secret"
                ),
                deletedMessages = false
            )
        )
    }

    @Test
    fun deletedMessagesTriggerFullReconciliation() {
        assertEquals(
            FcmWakeAction.FULL_RECONCILIATION,
            FcmWakeRouter.action(emptyMap(), deletedMessages = true)
        )
    }
}
