package com.ozkanmut.ilactakip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrossPlatformProtocolFixtureTest {
    @Test
    fun sharedV9Fixture_decodesThroughAndroidDoseParser() {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("dose-event-v9.json")) {
            "Shared protocol fixture dose-event-v9.json was not packaged as a test resource"
        }
        val payload = resource.bufferedReader().use { JSONObject(it.readText()) }

        val event = SyncEngine.parseDoseEvent(payload)

        assertNotNull(event)
        event!!
        assertEquals("fixture-v9-001", event.eventId)
        assertEquals("taken", event.type)
        assertEquals("08:00", event.time)
        assertEquals("publisher-fixture-topic", event.actorTopic)
        assertEquals("target-fixture-topic", event.targetTopic)
        assertEquals(42L, event.revision)
        assertEquals("med-fixture-1", event.medications.single().id)
        assertEquals(listOf("08:00", "20:00"), event.medications.single().times)
        assertEquals(MedicationForm.TABLET, event.medicationMeta.single().form)
    }
}
