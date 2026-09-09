package com.ozkanmut.ilactakip

import android.content.Context
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
    private const val KEY = "processed_event_ids"
    private const val MAX_IDS = 5000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun processed(c: Context, eventId: String): Boolean =
        eventId.isNotBlank() && prefs(c).getStringSet(KEY, emptySet()).orEmpty().contains(eventId)

    @Synchronized
    fun markProcessed(c: Context, eventId: String) {
        if (eventId.isBlank()) return
        val existing = prefs(c).getStringSet(KEY, emptySet()).orEmpty().toMutableList()
        existing.remove(eventId)
        existing.add(eventId)
        val kept = if (existing.size > MAX_IDS) existing.takeLast(MAX_IDS) else existing
        prefs(c).edit().putStringSet(KEY, kept.toSet()).commit()
    }
}
