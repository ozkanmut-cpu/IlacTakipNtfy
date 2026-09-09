package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.util.UUID

object StockSync {
    const val EVENT_TYPE = "stock_updated"

    /** Publish an authoritative local stock snapshot to one Circle peer over the existing ntfy outbox. */
    fun publish(c: Context, targetTopic: String, stock: MedicationStock) {
        if (targetTopic.isBlank() || targetTopic == Store.topic(c)) return
        val payload = JSONObject()
            .put("protocolVersion", 1)
            .put("eventId", UUID.randomUUID().toString())
            .put("type", EVENT_TYPE)
            .put("ownerId", Store.topic(c))
            .put("actor", Store.myName(c))
            .put("actorTopic", Store.topic(c))
            .put("timestamp", System.currentTimeMillis())
            .put("stock", StockEngine.toJson(stock))
        AlertOutbox.enqueue(c.applicationContext, targetTopic, "Dosefolk sync", payload.toString())
    }

    /** Publish all configured local stocks; used for pairing/catch-up and explicit resync. */
    fun publishAll(c: Context, targetTopic: String) {
        StockEngine.all(c).forEach { publish(c, targetTopic, it) }
    }

    /** Returns true when payload is a stock protocol message (valid or intentionally ignored). */
    fun applyIncoming(c: Context, payload: JSONObject): Boolean {
        if (payload.optString("type") != EVENT_TYPE) return false
        val ownerId = payload.optString("ownerId").ifBlank { payload.optString("actorTopic") }
        val actorTopic = payload.optString("actorTopic")
        if (ownerId.isBlank() || actorTopic.isBlank()) return true
        // Only accept a stock snapshot published by its owner. This prevents a follower from forging
        // another person's stock simply by writing that person's ownerId into an ntfy message.
        if (ownerId != actorTopic) return true
        val known = Store.people(c).any { it.topic == ownerId }
        if (!known || ownerId == Store.topic(c)) return true
        val stock = StockEngine.fromJson(payload.optJSONObject("stock")) ?: return true
        StockEngine.applyRemoteSnapshot(c, ownerId, stock)
        return true
    }
}
