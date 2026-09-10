package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NtfyFreeTierTrafficTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("dosefolk_store", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dosefolk_events", Context.MODE_PRIVATE).edit().clear().commit()
        Store.saveTopic(context, "publisher-topic")
    }

    @Test
    fun publisherChannelModel_hasExactlyOnePublishTopicPerDevice() {
        assertEquals("publisher-topic", CircleTransport.publishTopic(context))
    }

    @Test
    fun alarmEvent_isLocalOnlyAndNeverEntersOutboundQuota() {
        val med = Medication("m1", "Test", "1", listOf("08:00"))
        val accepted = Ntfy.sendEvent(context, "alarm", "08:00", listOf(med), "2026-09-10", eventId = "alarm-local-only")
        assertTrue(accepted)
        val stored = EventStore.load(context).first { it.eventId == "alarm-local-only" }
        assertEquals("synced", stored.syncState)
        assertTrue(EventStore.pending(context).none { it.eventId == "alarm-local-only" })
    }
}
