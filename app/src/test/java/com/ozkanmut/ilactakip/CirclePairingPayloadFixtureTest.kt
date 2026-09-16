package com.ozkanmut.ilactakip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CirclePairingPayloadFixtureTest {
    @Test
    fun sharedPairingFixtureMatchesAndroidCodec() {
        val cases = JSONObject(loadFixture("circle-pairing-v1.json")).getJSONArray("cases")

        for (index in 0 until cases.length()) {
            val testCase = cases.getJSONObject(index)
            val id = testCase.getString("id")
            val raw = testCase.getString("raw")
            val parsed = CirclePairingPayload.parse(raw)

            if (!testCase.getBoolean("valid")) {
                assertNull("$id must be rejected", parsed)
                continue
            }

            assertNotNull("$id must be accepted", parsed)
            parsed!!
            assertEquals("$id topic", testCase.getString("topic"), parsed.topic)
            assertEquals("$id name", testCase.getString("name"), parsed.name)

            if (testCase.optBoolean("canonicalEncode", false)) {
                assertEquals(
                    "$id canonical encoding",
                    raw,
                    CirclePairingPayload.encode(parsed.topic, parsed.name)
                )
            }
        }
    }

    private fun loadFixture(name: String): String {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Shared pairing fixture $name was not packaged as a test resource"
        }
        return resource.bufferedReader().use { it.readText() }
    }
}
