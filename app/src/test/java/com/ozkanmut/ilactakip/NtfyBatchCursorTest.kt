package com.ozkanmut.ilactakip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun replayTruncation_headerIsRejected() {
        assertTrue(NtfyReplayGuard.isTruncated("1"))
        assertTrue(NtfyReplayGuard.isTruncated(" 1 "))
        assertFalse(NtfyReplayGuard.isTruncated(null))
        assertFalse(NtfyReplayGuard.isTruncated("0"))
        assertFalse(NtfyReplayGuard.isTruncated("true"))
    }

    @Test
    fun envelopeBinding_acceptsRealPublisherTopic() {
        val envelope = JSONObject().put("topic", "peer-a")
        val payload = JSONObject().put("actorTopic", "peer-a")
        assertTrue(NtfyEnvelopeBinding.matches(envelope, payload))
    }

    @Test
    fun envelopeBinding_rejectsSpoofedActorTopic() {
        val envelope = JSONObject().put("topic", "peer-a")
        val payload = JSONObject().put("actorTopic", "peer-b")
        assertFalse(NtfyEnvelopeBinding.matches(envelope, payload))
    }

    @Test
    fun envelopeBinding_rejectsMissingTopicIdentity() {
        assertFalse(NtfyEnvelopeBinding.matches(JSONObject(), JSONObject().put("actorTopic", "peer-a")))
        assertFalse(NtfyEnvelopeBinding.matches(JSONObject().put("topic", "peer-a"), JSONObject()))
    }

    private fun event(target: String = "") = DoseEvent(
        eventId = "event-1",
        type = "program_updated",
        time = "program",
        actor = "Peer",
        actorTopic = "peer-a",
        timestamp = 1L,
        medications = emptyList(),
        syncState = "synced",
        revision = 1L,
        ownerId = "owner-a",
        targetTopic = target
    )

    @Test
    fun targetRouting_broadcastEventIsAcceptedByAnySubscriber() {
        assertTrue(NtfyTargetRouting.accepts("phone-a", event()))
        assertTrue(NtfyTargetRouting.accepts("phone-b", event()))
    }

    @Test
    fun targetRouting_targetedEventOnlyAcceptedByTarget() {
        assertTrue(NtfyTargetRouting.accepts("phone-a", event("phone-a")))
        assertFalse(NtfyTargetRouting.accepts("phone-b", event("phone-a")))
    }

    @Test
    fun payload_preservesExplicitTargetTopic() {
        assertEquals("phone-a", EventStore.payload(event("phone-a")).optString("targetTopic"))
    }
}
