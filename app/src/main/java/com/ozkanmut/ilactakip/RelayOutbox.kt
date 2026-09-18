package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID

/** Durable recipient-specific relay materialization. Ciphertext is retained until confirmed enqueue. */
class RelayOutbox(
    private val context: Context,
    private val localInstallId: String,
    private val crypto: RelayCrypto,
    private val peers: RelayPeerStore,
    private val api: RelayApi,
    private val faults: RelayOutboxFaults = RelayOutboxFaults.NONE
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var materializationFailed = false

    @Synchronized fun materializePending(): Int = try {
        materializationFailed = false
        val records = load().toMutableList()
        var created = 0
        val routes = normalizedRoutes(api.routes())
        EventStore.pending(context).forEach { event ->
            val selected = if (event.targetTopic.isBlank()) routes else routes.filter { it.recipientInstallId == event.targetTopic }
            if (selected.isEmpty()) materializationFailed = true
            // A blank legacy target broadcasts; a nonblank relay target is an exact recipient binding.
            selected.forEach { route ->
                if (records.any { it.sourceEventId == event.eventId && it.outerContext.recipientInstallId == route.recipientInstallId }) return@forEach
                if (!peers.isTrusted(route.recipientInstallId, route.recipientIdentity)) {
                    materializationFailed = true
                    return@forEach
                }
                val context = RelayOuterContext(UUID.randomUUID().toString(), route.routeId, localInstallId,
                    route.recipientInstallId, crypto.localKeyVersion(), route.recipientIdentity.keyVersion)
                val payload = EventStore.payload(event).put("actorTopic", localInstallId).put("targetTopic", route.recipientInstallId)
                val sealed = crypto.seal(payload, route.recipientIdentity, context)
                records += RelayOutboxRecord(event.eventId, context, sealed, accepted = false)
                created++
            }
        }
        if (created > 0) save(records)
        created
    } catch (_: Exception) { materializationFailed = true; 0 }

    @Synchronized fun flush(): Boolean {
        return try {
            materializePending()
            if (materializationFailed) return false
            finalizeAccepted()
            var allAccepted = true
            load().filterNot { it.accepted }.forEach { record ->
                try {
                    api.enqueue(RelayOutboundEnvelope(record.outerContext, record.ciphertext))
                    val accepted = load().map { if (it.outerContext.messageId == record.outerContext.messageId) it.copy(accepted = true) else it }
                    save(accepted)
                    finalizeAccepted()
                } catch (_: Exception) { allAccepted = false }
            }
            allAccepted && !materializationFailed
        } catch (_: Exception) { false }
    }

    @Synchronized fun recordsFor(eventId: String): List<RelayOutboxRecord> = load().filter { it.sourceEventId == eventId }

    /** Recovered on every flush: source sync is durable before accepted ciphertext cleanup. */
    private fun finalizeAccepted() {
        val all = load()
        all.groupBy { it.sourceEventId }.forEach { (source, records) ->
            if (records.isNotEmpty() && records.all { it.accepted }) {
                faults.beforeSourceSynced(source)
                EventStore.markSynced(context, source)
                faults.afterSourceSyncedBeforeCleanup(source)
                save(load().filterNot { it.sourceEventId == source })
            }
        }
    }

    private fun load(): List<RelayOutboxRecord> = try {
        val raw = prefs.getString(RECORDS, "[]") ?: "[]"
        val values = JSONArray(raw)
        (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("sourceEventId", "messageId", "routeId", "senderInstallId", "recipientInstallId", "senderKeyVersion", "recipientKeyVersion", "ciphertext", "accepted"))
            val source = item.getString("sourceEventId").also(RelayEnvelopeFormat::requireOpaqueId)
            val outer = RelayOuterContext(item.getString("messageId"), item.getString("routeId"), item.getString("senderInstallId"), item.getString("recipientInstallId"), item.getInt("senderKeyVersion"), item.getInt("recipientKeyVersion"))
            outer.json()
            val text = item.getString("ciphertext")
            val ciphertext = Base64.getDecoder().decode(text)
            require(ciphertext.size in 48..RelayEnvelopeFormat.MAX_BYTES && Base64.getEncoder().encodeToString(ciphertext) == text)
            RelayOutboxRecord(source, outer, ciphertext, item.get("accepted") as? Boolean ?: throw IllegalArgumentException())
        }
    } catch (_: Exception) { throw GeneralSecurityException("Invalid relay outbox") }

    private fun save(records: List<RelayOutboxRecord>) {
        val encoded = JSONArray()
        records.forEach { record ->
            val c = record.outerContext
            encoded.put(JSONObject().put("sourceEventId", record.sourceEventId).put("messageId", c.messageId)
                .put("routeId", c.routeId).put("senderInstallId", c.senderInstallId).put("recipientInstallId", c.recipientInstallId)
                .put("senderKeyVersion", c.senderKeyVersion).put("recipientKeyVersion", c.recipientKeyVersion)
                .put("ciphertext", Base64.getEncoder().encodeToString(record.ciphertext)).put("accepted", record.accepted))
        }
        val previous = prefs.getString(RECORDS, null)
        if (!prefs.edit().putString(RECORDS, encoded.toString()).commit()) {
            val rollback = prefs.edit()
            if (previous == null) rollback.remove(RECORDS) else rollback.putString(RECORDS, previous)
            rollback.commit()
            throw GeneralSecurityException("Could not persist relay outbox")
        }
    }

    private companion object { const val PREFS = "dosefolk_relay_outbox"; const val RECORDS = "records" }

    private fun normalizedRoutes(routes: List<RelayRoute>): List<RelayRoute> {
        val selected = mutableListOf<RelayRoute>()
        routes.groupBy { it.recipientInstallId }.forEach { (_, candidates) ->
            if (candidates.map { it.recipientIdentity }.distinct().size != 1) throw GeneralSecurityException("Ambiguous relay route")
            selected += candidates.minBy { it.routeId }
        }
        return selected
    }
}

data class RelayOutboxRecord(val sourceEventId: String, val outerContext: RelayOuterContext, val ciphertext: ByteArray, val accepted: Boolean) {
    override fun toString(): String = "RelayOutboxRecord(redacted)"
}

interface RelayOutboxFaults {
    fun beforeSourceSynced(sourceEventId: String) = Unit
    fun afterSourceSyncedBeforeCleanup(sourceEventId: String)
    companion object { val NONE = object : RelayOutboxFaults { override fun afterSourceSyncedBeforeCleanup(sourceEventId: String) = Unit } }
}
