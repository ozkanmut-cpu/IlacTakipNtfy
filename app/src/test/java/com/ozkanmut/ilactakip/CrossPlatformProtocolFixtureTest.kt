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
        val payload = loadFixture("dose-event-v9.json")
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

    @Test
    fun iosV9Fixture_decodesThroughAndroidDoseParser() {
        val event = SyncEngine.parseDoseEvent(loadFixture("ios-dose-event-v9.json"))

        assertNotNull(event)
        event!!
        assertEquals("ios-fixture-v9-001", event.eventId)
        assertEquals("snoozed", event.type)
        assertEquals("20:00", event.time)
        assertEquals("2026-09-11", event.scheduledDate)
        assertEquals(1789158600000L, event.snoozeUntil)
        assertEquals("ios-publisher-topic", event.ownerId)
        assertEquals("ios-publisher-topic", event.actorTopic)
        assertEquals("android-target-topic", event.targetTopic)
        assertEquals(77L, event.revision)
        assertEquals("ios-med-1", event.medications.single().id)
        assertEquals(MedicationForm.TABLET, event.medicationMeta.single().form)
    }

    private fun loadFixture(name: String): JSONObject {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Shared protocol fixture $name was not packaged as a test resource"
        }
        return resource.bufferedReader().use { JSONObject(it.readText()) }
    }
}
