package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object IncomingEventGuard {
    internal const val MAX_PROTOCOL_VERSION = 9

    fun protocolVersion(payload: JSONObject): Int =
        if (payload.has("v")) payload.optInt("v", -1) else 1

    fun supportedDosePayload(payload: JSONObject): Boolean =
        protocolVersion(payload) in 1..MAX_PROTOCOL_VERSION

    fun shouldProcess(c: Context, event: DoseEvent): Boolean {
        if (event.eventId.isBlank() || event.actorTopic.isBlank()) return false
        if (RemoteEventReceiptStore.processed(c, event.eventId)) return false

        val localTopic = Store.topic(c)
        if (event.actorTopic == localTopic) {
            if (EventStore.contains(c, event.eventId)) {
                RemoteEventReceiptStore.markProcessed(c, event.eventId)
            }
            return false
        }

        if (RevokedPeerFence.isRevoked(c, event.actorTopic)) {
            RemoteEventReceiptStore.markProcessed(c, event.eventId)
            return false
        }

        return PermissionPolicy.acceptRemote(c, event)
    }
}

object InboundProtocolHealth {
    private const val PREFS = "dosefolk_inbound_protocol_health"
    private const val KEY_VERSION = "unsupported_version"
    private const val KEY_SEEN_AT = "unsupported_seen_at"
    private const val KEY_STOCK_VERSION = "unsupported_stock_version"
    private const val KEY_STOCK_SEEN_AT = "unsupported_stock_seen_at"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun recordUnsupported(c: Context, version: Int, seenAt: Long = System.currentTimeMillis()) {
        if (version <= IncomingEventGuard.MAX_PROTOCOL_VERSION) return
        val p = prefs(c)
        val current = p.getInt(KEY_VERSION, 0)
        p.edit()
            .putInt(KEY_VERSION, maxOf(current, version))
            .putLong(KEY_SEEN_AT, seenAt)
            .commit()
    }

    @Synchronized
    fun recordUnsupportedStock(c: Context, version: Int, seenAt: Long = System.currentTimeMillis()) {
        if (version <= StockSync.MAX_PROTOCOL_VERSION) return
        val p = prefs(c)
        val current = p.getInt(KEY_STOCK_VERSION, 0)
        p.edit()
            .putInt(KEY_STOCK_VERSION, maxOf(current, version))
            .putLong(KEY_STOCK_SEEN_AT, seenAt)
            .commit()
    }

    fun unsupportedVersion(c: Context): Int = prefs(c).getInt(KEY_VERSION, 0)
    fun lastSeenAt(c: Context): Long = prefs(c).getLong(KEY_SEEN_AT, 0L)
    fun unsupportedStockVersion(c: Context): Int = prefs(c).getInt(KEY_STOCK_VERSION, 0)
    fun lastStockSeenAt(c: Context): Long = prefs(c).getLong(KEY_STOCK_SEEN_AT, 0L)
    fun hasUnsupportedProtocol(c: Context): Boolean = unsupportedVersion(c) > 0 || unsupportedStockVersion(c) > 0

    @Synchronized
    fun clear(c: Context) {
        prefs(c).edit().clear().commit()
    }
}

internal object RemoteReceiptRetention {
    fun merge(existing: List<String>, additions: List<String>, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val ordered = existing.filter { it.isNotBlank() }.distinct().toMutableList()
        additions.filter { it.isNotBlank() }.forEach { id ->
            ordered.remove(id)
            ordered += id
        }
        return if (ordered.size > limit) ordered.takeLast(limit) else ordered
    }

    fun nextOrder(existing: List<String>, eventId: String, limit: Int): List<String> =
        merge(existing, listOf(eventId), limit)
}

object RemoteEventReceiptStore {
    private const val PREFS = "dosefolk_remote_event_receipts"
    private const val KEY_SET = "processed_event_ids"
    private const val KEY_ORDER = "processed_event_order_v2"
    private const val KEY_INFLIGHT_SET = "processed_event_ids_inflight"
    private const val KEY_INFLIGHT_ORDER = "processed_event_order_inflight_v1"
    private const val MAX_IDS = 5000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun processed(c: Context, eventId: String): Boolean {
        if (eventId.isBlank()) return false
        val p = prefs(c)
        return p.getStringSet(KEY_SET, emptySet()).orEmpty().contains(eventId) ||
            p.getStringSet(KEY_INFLIGHT_SET, emptySet()).orEmpty().contains(eventId)
    }

    private fun orderedIds(c: Context, orderKey: String, membership: Set<String>): List<String> {
        val raw = prefs(c).getString(orderKey, null)
        if (raw == null) return membership.toList()
        return runCatching {
            val a = JSONArray(raw)
            val ordered = (0 until a.length()).map { a.optString(it) }
                .filter { it.isNotBlank() && it in membership }
                .distinct()
                .toMutableList()
            membership.filterNot { it in ordered }.forEach { ordered += it }
            ordered
        }.getOrElse { membership.toList() }
    }

    /**
     * Processed IDs stay in an unbounded in-flight ledger until the ntfy cursor
     * checkpoint for the whole catch-up batch is durably committed. This avoids
     * a >5000-message batch evicting its own early receipts before crash recovery.
     */
    @Synchronized
    fun markProcessed(c: Context, eventId: String) {
        if (eventId.isBlank()) return
        val p = prefs(c)
        val membership = p.getStringSet(KEY_INFLIGHT_SET, emptySet()).orEmpty().toMutableSet()
        val ordered = RemoteReceiptRetention.nextOrder(
            orderedIds(c, KEY_INFLIGHT_ORDER, membership),
            eventId,
            Int.MAX_VALUE
        )
        p.edit()
            .putStringSet(KEY_INFLIGHT_SET, ordered.toSet())
            .putString(KEY_INFLIGHT_ORDER, JSONArray(ordered).toString())
            .commit()
    }

    /** Call only after SyncCheckpointStore.commitSuccessfulBatch(). */
    @Synchronized
    fun commitSuccessfulBatch(c: Context) {
        val p = prefs(c)
        val inflight = p.getStringSet(KEY_INFLIGHT_SET, emptySet()).orEmpty()
        if (inflight.isEmpty()) return

        val history = p.getStringSet(KEY_SET, emptySet()).orEmpty()
        val historyOrder = orderedIds(c, KEY_ORDER, history)
        val inflightOrder = orderedIds(c, KEY_INFLIGHT_ORDER, inflight)
        val merged = RemoteReceiptRetention.merge(historyOrder, inflightOrder, MAX_IDS)

        p.edit()
            .putStringSet(KEY_SET, merged.toSet())
            .putString(KEY_ORDER, JSONArray(merged).toString())
            .remove(KEY_INFLIGHT_SET)
            .remove(KEY_INFLIGHT_ORDER)
            .commit()
    }
}
