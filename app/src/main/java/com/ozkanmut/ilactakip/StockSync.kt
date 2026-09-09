package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.util.UUID

object StockSync {
    const val EVENT_TYPE = "stock_updated"

    fun publish(c: Context, targetTopic: String, stock: MedicationStock) {
        if (targetTopic.isBlank() || targetTopic == Store.topic(c)) return
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
        AlertOutbox.enqueue(c.applicationContext, targetTopic, "Dosefolk sync", payload.toString())
    }

    fun publishToCircle(c: Context, stock: MedicationStock) {
        Store.people(c).map { it.topic }.filter { it.isNotBlank() }.distinct().forEach { publish(c, it, stock) }
    }

    fun publishAll(c: Context, targetTopic: String) {
        StockEngine.all(c).forEach { publish(c, targetTopic, it) }
    }

    fun publishAllToCircle(c: Context) {
        StockEngine.all(c).forEach { publishToCircle(c, it) }
    }

    fun applyIncoming(c: Context, payload: JSONObject): Boolean {
        if (payload.optString("type") != EVENT_TYPE) return false
        val ownerId = payload.optString("ownerId").ifBlank { payload.optString("actorTopic") }
        val actorTopic = payload.optString("actorTopic")
        if (ownerId.isBlank() || actorTopic.isBlank()) return true
        if (ownerId != actorTopic) return true
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
            eventId = payload.optString("eventId")
        )
        return true
    }
}
