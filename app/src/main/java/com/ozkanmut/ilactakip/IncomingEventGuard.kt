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
            // A legitimate self-originated event must already exist locally,
            // because local actions are persisted before they are sent to ntfy.
            // Unknown self events are therefore treated as spoofed/replayed data.
            if (EventStore.contains(c, event.eventId)) {
                RemoteEventReceiptStore.markProcessed(c, event.eventId)
            }
            return false
        }

        // A revoked relationship is a durable trust boundary. While the topic is
        // tombstoned, consume old catch-up IDs as rejected receipts. This lets a
        // later prepareRePair() drain the old relationship backlog without ever
        // persisting or applying its side effects.
        if (RevokedPeerFence.isRevoked(c, event.actorTopic)) {
            RemoteEventReceiptStore.markProcessed(c, event.eventId)
            return false
        }

        return PermissionPolicy.acceptRemote(c, event)
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

    private fun orderedIds(c: Context, membership: Set<String>): MutableList<String> {
        val raw = prefs(c).getString(KEY_ORDER, null)
        if (raw == null) return membership.toMutableList() // one-time legacy migration; old order was unknowable
        return runCatching {
            val a = JSONArray(raw)
            val ordered = (0 until a.length()).map { a.optString(it) }
                .filter { it.isNotBlank() && it in membership }
                .distinct()
                .toMutableList()
            membership.filterNot { it in ordered }.forEach { ordered += it }
            ordered
        }.getOrElse { membership.toMutableList() }
    }

    @Synchronized
    fun markProcessed(c: Context, eventId: String) {
        if (eventId.isBlank()) return
        val p = prefs(c)
        val membership = p.getStringSet(KEY_SET, emptySet()).orEmpty().toMutableSet()
        val ordered = orderedIds(c, membership)

        ordered.remove(eventId)
        ordered += eventId
        membership += eventId

        while (ordered.size > MAX_IDS) {
            val evicted = ordered.removeAt(0)
            membership.remove(evicted)
        }

        p.edit()
            .putStringSet(KEY_SET, membership)
            .putString(KEY_ORDER, JSONArray(ordered).toString())
            .commit()
    }
}
