package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublisherTopicControlRoutingTest {
    private lateinit var c: Context
    private val publisher = "publisher-topic"
    private val peer = "peer-topic"

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf(
            "ilac_takip", "dosefolk_alert_outbox", "dosefolk_events", "dosefolk_event_clock",
            "dosefolk_remote_capabilities", "dosefolk_permissions", "dosefolk_revoked_peers",
            "dosefolk_owner_scope", "dosefolk_program_rules", "dosefolk_stock",
            "dosefolk_remote_medication_meta", "dosefolk_ntfy_rate", "dosefolk_ntfy_traffic"
        ).forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        c.getSharedPreferences("ilac_takip", Context.MODE_PRIVATE).edit()
            .putString("topic", publisher)
            .putString("my_name", "Owner")
            .putString("people", JSONArray().put(JSONObject()
                .put("id", "peer-id").put("name", "Peer").put("topic", peer).put("canEdit", false)).toString())
            .commit()
    }

    private fun clearOutbox() = c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE)
        .edit().putString("alerts", "[]").commit()

    private fun rows(): List<JSONObject> {
        val raw = c.getSharedPreferences("dosefolk_alert_outbox", Context.MODE_PRIVATE)
            .getString("alerts", "[]") ?: "[]"
        val a = JSONArray(raw)
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun assertPublisherTarget(row: JSONObject, expectedType: String) {
        assertEquals(publisher, row.getString("topic"))
        val payload = JSONObject(row.getString("message"))
        assertEquals(expectedType, payload.getString("type"))
        assertEquals(publisher, payload.getString("actorTopic"))
        assertEquals(peer, payload.getString("targetTopic"))
        assertTrue(NtfyEnvelopeBinding.matches(JSONObject().put("topic", row.getString("topic")), payload))
    }

    @Test fun receiverSubscribesToPeerPublisherTopic() {
        assertTrue(CircleTransport.subscriptionTopics(c).containsAll(listOf(publisher, peer)))
    }

    @Test fun capabilityGrantAndRevoke_publishOnActorTopic() {
        CapabilitySync.publish(c, peer, CirclePermission.EDIT_PROGRAM, true)
        CapabilitySync.publish(c, peer, CirclePermission.EDIT_PROGRAM, false)
        val payloads = rows().map { it to JSONObject(it.getString("message")) }
        assertEquals(2, payloads.size)
        assertTrue(payloads.all { (row, p) -> row.getString("topic") == publisher && p.getString("actorTopic") == publisher && p.getString("targetTopic") == peer })
        assertTrue(payloads.map { it.second.getString("type") }.containsAll(listOf("capability_edit_program_granted", "capability_edit_program_revoked")))
    }

    @Test fun remoteProgramAndRuleEdits_publishOnActorTopic() {
        val med = Medication("med-remote", "Remote Med", "1 tablet", listOf("08:00"))
        ScopedNtfy.sendProgramChange(c, peer, "program_updated", med)
        ScopedNtfy.sendRuleChange(c, peer, med, ProgramRule(medicationId = med.id))
        val byType = rows().associateBy { JSONObject(it.getString("message")).getString("type") }
        assertPublisherTarget(byType.getValue("program_updated"), "program_updated")
        assertPublisherTarget(byType.getValue("program_rule_updated"), "program_rule_updated")
    }

    @Test fun circleRevoke_publishOnActorTopic() {
        PairingLifecycle.revoke(c, Person("peer-id", "Peer", peer))
        val row = rows().single { JSONObject(it.getString("message")).optString("type") == "circle_revoked" }
        assertPublisherTarget(row, "circle_revoked")
    }

    @Test fun initialProgramAndRuleBootstrap_publishOnActorTopic() {
        Store.save(c, listOf(Medication("med-local", "Local Med", "1", listOf("09:00"))))
        clearOutbox()
        CircleInitialSync.publishToPeer(c, peer)
        val protocolRows = rows().mapNotNull { row ->
            runCatching { JSONObject(row.getString("message")) }.getOrNull()?.let { it to row }
        }
        val program = protocolRows.first { it.first.optString("type") == "program_added" }.second
        val rule = protocolRows.first { it.first.optString("type") == "program_rule_updated" }.second
        assertPublisherTarget(program, "program_added")
        assertPublisherTarget(rule, "program_rule_updated")
    }
}
