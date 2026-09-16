package com.ozkanmut.ilactakip

import org.junit.Assert.assertSame
import org.junit.Test

class SyncTransportTest {
    @Test
    fun defaultRuntimeUsesNtfyAdapter() {
        assertSame(NtfySyncTransport, SyncTransportRuntime.current)
    }
}
