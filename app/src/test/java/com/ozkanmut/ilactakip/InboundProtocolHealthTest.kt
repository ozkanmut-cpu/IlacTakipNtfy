package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InboundProtocolHealthTest {
    private lateinit var c: Context

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "dosefolk_inbound_protocol_health",
            "ilac_takip",
            "dosefolk_sync",
            "dosefolk_events",
            "dosefolk_alert_outbox",
            "dosefolk_alarm_scheduler"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun newerProtocol_isRememberedAndSurfaced() {
        InboundProtocolHealth.recordUnsupported(c, IncomingEventGuard.MAX_PROTOCOL_VERSION + 1, 1234L)

        assertEquals(IncomingEventGuard.MAX_PROTOCOL_VERSION + 1, InboundProtocolHealth.unsupportedVersion(c))
        assertEquals(1234L, InboundProtocolHealth.lastSeenAt(c))
        assertTrue(DosefolkCheck.issues(c).any { it.id == "protocol_update_required" })
    }

    @Test
    fun supportedProtocol_doesNotCreateUpdateIssue() {
        InboundProtocolHealth.recordUnsupported(c, IncomingEventGuard.MAX_PROTOCOL_VERSION)

        assertEquals(0, InboundProtocolHealth.unsupportedVersion(c))
        assertFalse(DosefolkCheck.issues(c).any { it.id == "protocol_update_required" })
    }

    @Test
    fun highestUnsupportedVersion_isRetained() {
        InboundProtocolHealth.recordUnsupported(c, 12, 100L)
        InboundProtocolHealth.recordUnsupported(c, 10, 200L)

        assertEquals(12, InboundProtocolHealth.unsupportedVersion(c))
        assertEquals(200L, InboundProtocolHealth.lastSeenAt(c))
    }
}
