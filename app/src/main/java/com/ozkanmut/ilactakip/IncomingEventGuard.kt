package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guards ntfy catch-up processing against duplicate replays and forged unseen
 * events claiming to originate from this device.
 *
 * We intentionally mark a remote event as processed only after all side effects
 * complete. If the app crashes mid-processing, the event can be replayed safely
 * on the next pull instead of being lost.
 */
object IncomingEventGuard {
    private const val MAX_PROTOCOL_VERSION = 9

    fun supportedDosePayload(payload: JSONObject): Boolean {
        val version = if (payload.has("v")) payload.optInt("v", -1) else 1
        return version in 1..MAX_PROTOCOL_VERSION
    }

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

internal object RemoteReceiptRetention {
    fun nextOrder(existing: List<String>, eventId: String, limit: Int): List<String> {
        if (eventId.isBlank() || limit <= 0) return emptyList()
        val ordered = existing.filter { it.isNotBlank() && it != eventId }.toMutableList()
        ordered += eventId
        return if (ordered.size > limit) ordered.takeLast(limit) else ordered
    }
}

object RemoteEventReceiptStore {
    private const val PREFS = "dosefolk_remote_event_receipts"
    private const val KEY_SET = "processed_event_ids"
    private const val KEY_ORDER = "processed_event_order_v2"
    private const val MAX_IDS = 5000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun processed(c: Context, eventId: String): Boolean =
        eventId.isNotBlank() && prefs(c).getStringSet(KEY_SET, emptySet()).orEmpty().contains(eventId)

    private fun orderedIds(c: Context, membership: Set<String>): List<String> {
        val raw = prefs(c).getString(KEY_ORDER, null)
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

    @Synchronized
    fun markProcessed(c: Context, eventId: String) {
        if (eventId.isBlank()) return
        val p = prefs(c)
        val membership = p.getStringSet(KEY_SET, emptySet()).orEmpty().toMutableSet()
        val ordered = RemoteReceiptRetention.nextOrder(orderedIds(c, membership), eventId, MAX_IDS)
        val kept = ordered.toSet()

        p.edit()
            .putStringSet(KEY_SET, kept)
            .putString(KEY_ORDER, JSONArray(ordered).toString())
            .commit()
    }
}
