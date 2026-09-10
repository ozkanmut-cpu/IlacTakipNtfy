package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.util.UUID

object StockSync {
    const val EVENT_TYPE = "stock_updated"

    private fun outboxId(c: Context, targetTopic: String, stock: MedicationStock): String =
        "stock|${Store.topic(c)}|${stock.medicationId}|$targetTopic"

    private fun enqueue(
        c: Context,
        destinationTopic: String,
        stock: MedicationStock,
        targetTopic: String = ""
    ) {
        if (destinationTopic.isBlank()) return
        val payload = JSONObject()
            .put("protocolVersion", 2)
            .put("eventId", UUID.randomUUID().toString())
            .put("type", EVENT_TYPE)
            .put("ownerId", Store.topic(c))
            .put("actor", Store.myName(c))
            .put("actorTopic", Store.topic(c))
            .put("timestamp", System.currentTimeMillis())
            .put("revision", EventStore.nextRevision(c))
            .put("stock", StockEngine.toJson(stock))
        if (targetTopic.isNotBlank()) payload.put("targetTopic", targetTopic)
        AlertOutbox.enqueueLatest(
            c.applicationContext,
            destinationTopic,
            "Dosefolk sync",
            payload.toString(),
            stableId = outboxId(c, targetTopic.ifBlank { destinationTopic }, stock)
        )
    }

    /** Legacy/direct destination helper retained for existing callers. */
    fun publish(c: Context, targetTopic: String, stock: MedicationStock) {
        enqueue(c, targetTopic, stock)
    }

    /** Normal Circle fan-out is pub/sub: publish once to this device's publisher topic. */
    fun publishToCircle(c: Context, stock: MedicationStock) {
        enqueue(c, CircleTransport.publishTopic(c), stock)
    }

    /** Targeted bootstrap also uses the sender's publisher topic plus explicit routing. */
    fun publishAll(c: Context, targetTopic: String) {
        if (targetTopic.isBlank() || targetTopic == Store.topic(c)) return
        StockEngine.all(c).forEach {
            enqueue(
                c = c,
                destinationTopic = CircleTransport.publishTopic(c),
                stock = it,
                targetTopic = targetTopic
            )
        }
    }

    fun publishAllToCircle(c: Context) {
        StockEngine.all(c).forEach { publishToCircle(c, it) }
    }

    fun applyIncoming(c: Context, payload: JSONObject): Boolean {
        if (payload.optString("type") != EVENT_TYPE) return false
        val eventId = payload.optString("eventId")
        val ownerId = payload.optString("ownerId").ifBlank { payload.optString("actorTopic") }
        val actorTopic = payload.optString("actorTopic")
        if (ownerId.isBlank() || actorTopic.isBlank()) return true
        if (ownerId != actorTopic) return true
        if (eventId.isNotBlank() && RemoteEventReceiptStore.processed(c, eventId)) return true
        if (RevokedPeerFence.isRevoked(c, ownerId)) {
            if (eventId.isNotBlank()) RemoteEventReceiptStore.markProcessed(c, eventId)
            return true
        }
        val known = Store.people(c).any { it.topic == ownerId }
        if (!known || ownerId == Store.topic(c)) return true
        val stock = StockEngine.fromJson(payload.optJSONObject("stock")) ?: return true
        val revision = payload.optLong("revision", 0L)
        EventStore.observeRevision(c, revision)
        StockEngine.applyRemoteSnapshot(
            c = c,
            ownerId = ownerId,
            stock = stock,
            revision = revision,
            actorTopic = actorTopic,
            eventId = eventId
        )
        if (eventId.isNotBlank()) RemoteEventReceiptStore.markProcessed(c, eventId)
        return true
    }
}
