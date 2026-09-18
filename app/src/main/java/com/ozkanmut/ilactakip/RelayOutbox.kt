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
    private val api: RelayApi
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var materializationFailed = false

    @Synchronized fun materializePending(): Int = try {
        materializationFailed = false
        val records = load().toMutableList()
        var created = 0
        val routes = api.routes()
        EventStore.pending(context).forEach { event ->
            val selected = if (event.targetTopic.isBlank()) routes else routes.filter { it.recipientInstallId == event.targetTopic }
            if (selected.isEmpty()) materializationFailed = true
            // A blank legacy target broadcasts; a nonblank relay target is an exact recipient binding.
            selected.forEach { route ->
                if (records.any { it.sourceEventId == event.eventId && it.outerContext.routeId == route.routeId }) return@forEach
                if (!peers.isTrusted(route.recipientInstallId, route.recipientIdentity)) {
                    materializationFailed = true
                    return@forEach
                }
                val context = RelayOuterContext(UUID.randomUUID().toString(), route.routeId, localInstallId,
                    route.recipientInstallId, crypto.localKeyVersion(), route.recipientIdentity.keyVersion)
                val payload = EventStore.payload(event).put("actorTopic", localInstallId).put("targetTopic", route.recipientInstallId)
                val sealed = crypto.seal(payload, route.recipientIdentity, context)
                records += RelayOutboxRecord(event.eventId, context, sealed)
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
            var allAccepted = true
            load().toList().forEach { record ->
                try {
                    api.enqueue(RelayOutboundEnvelope(record.outerContext, record.ciphertext))
                    val remaining = load().filterNot { it.outerContext.messageId == record.outerContext.messageId }
                    save(remaining)
                    if (remaining.none { it.sourceEventId == record.sourceEventId }) EventStore.markSynced(context, record.sourceEventId)
                } catch (_: Exception) { allAccepted = false }
            }
            allAccepted && !materializationFailed
        } catch (_: Exception) { false }
    }

    @Synchronized fun recordsFor(eventId: String): List<RelayOutboxRecord> = load().filter { it.sourceEventId == eventId }

    private fun load(): List<RelayOutboxRecord> = try {
        val raw = prefs.getString(RECORDS, "[]") ?: "[]"
        val values = JSONArray(raw)
        (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("sourceEventId", "messageId", "routeId", "senderInstallId", "recipientInstallId", "senderKeyVersion", "recipientKeyVersion", "ciphertext"))
            val source = item.getString("sourceEventId").also(RelayEnvelopeFormat::requireOpaqueId)
            val outer = RelayOuterContext(item.getString("messageId"), item.getString("routeId"), item.getString("senderInstallId"), item.getString("recipientInstallId"), item.getInt("senderKeyVersion"), item.getInt("recipientKeyVersion"))
            outer.json()
            val text = item.getString("ciphertext")
            val ciphertext = Base64.getDecoder().decode(text)
            require(ciphertext.size in 48..RelayEnvelopeFormat.MAX_BYTES && Base64.getEncoder().encodeToString(ciphertext) == text)
            RelayOutboxRecord(source, outer, ciphertext)
        }
    } catch (_: Exception) { throw GeneralSecurityException("Invalid relay outbox") }

    private fun save(records: List<RelayOutboxRecord>) {
        val encoded = JSONArray()
        records.forEach { record ->
            val c = record.outerContext
            encoded.put(JSONObject().put("sourceEventId", record.sourceEventId).put("messageId", c.messageId)
                .put("routeId", c.routeId).put("senderInstallId", c.senderInstallId).put("recipientInstallId", c.recipientInstallId)
                .put("senderKeyVersion", c.senderKeyVersion).put("recipientKeyVersion", c.recipientKeyVersion)
                .put("ciphertext", Base64.getEncoder().encodeToString(record.ciphertext)))
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
}

data class RelayOutboxRecord(val sourceEventId: String, val outerContext: RelayOuterContext, val ciphertext: ByteArray) {
    override fun toString(): String = "RelayOutboxRecord(redacted)"
}
