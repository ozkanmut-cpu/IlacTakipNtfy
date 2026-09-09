package com.ozkanmut.ilactakip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NtfyBatchCursorTest {
    @Test
    fun malformedEnvelope_doesNotAdvanceCursor() {
        val malformed = "{\"event\":\"message\",\"id\":\"abc"
        assertNull(NtfyBatchCursor.envelopeOrNull(malformed))
    }

    @Test
    fun nonMessageEnvelope_doesNotAdvanceCursor() {
        val envelope = JSONObject().put("event", "keepalive").put("id", "ignored")
        assertEquals("old-id", NtfyBatchCursor.advance("old-id", envelope))
    }

    @Test
    fun validMessageEnvelope_advancesEvenWhenInnerPayloadWouldBeMalformed() {
        val envelope = JSONObject()
            .put("event", "message")
            .put("id", "poison-id")
            .put("message", "not-json")

        assertEquals("poison-id", NtfyBatchCursor.advance("old-id", envelope))
    }

    @Test
    fun blankMessageId_keepsPreviousCursor() {
        val envelope = JSONObject().put("event", "message").put("id", "")
        assertEquals("old-id", NtfyBatchCursor.advance("old-id", envelope))
    }

    @Test
    fun laterValidMessageWinsAfterMalformedRowIsIgnored() {
        var cursor: String? = "start-id"
        val first = NtfyBatchCursor.envelopeOrNull("{broken")
        if (first != null) cursor = NtfyBatchCursor.advance(cursor, first)

        val second = NtfyBatchCursor.envelopeOrNull(
            JSONObject().put("event", "message").put("id", "next-id").put("message", "{}").toString()
        )
        if (second != null) cursor = NtfyBatchCursor.advance(cursor, second)

        assertEquals("next-id", cursor)
    }
}
