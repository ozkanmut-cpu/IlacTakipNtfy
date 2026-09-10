package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyEndpointTest {
    @Test
    fun productionBaseUrl_isSelfHosted() {
        assertEquals("https://ntfy.field-maintenance-prod.com", NtfyEndpoint.BASE_URL)
        assertFalse(NtfyEndpoint.BASE_URL.contains("ntfy.sh"))
    }

    @Test
    fun pollUrl_usesSelfHostedServerAndPollMode() {
        val url = NtfyEndpoint.pollUrl(listOf("dosefolk-a", "dosefolk-b"), "24h")
        assertTrue(url.startsWith("https://ntfy.field-maintenance-prod.com/"))
        assertTrue(url.contains("dosefolk-a,dosefolk-b/json"))
        assertTrue(url.contains("poll=1"))
        assertTrue(url.contains("since=24h"))
    }

    @Test
    fun streamUrl_usesSelfHostedServerWithoutPollFlag() {
        val url = NtfyEndpoint.streamUrl(listOf("dosefolk-a"), "10s")
        assertTrue(url.startsWith("https://ntfy.field-maintenance-prod.com/dosefolk-a/json"))
        assertTrue(url.contains("since=10s"))
        assertFalse(url.contains("poll=1"))
    }
}
