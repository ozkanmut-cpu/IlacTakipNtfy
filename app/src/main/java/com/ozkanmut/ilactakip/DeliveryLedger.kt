package com.ozkanmut.ilactakip

import android.content.Context

/**
 * Keeps retry delivery idempotent per event/topic. If one Circle member received
 * an event and another delivery failed, retries only target the missing topics.
 */
object DeliveryLedger {
    private const val PREFS = "dosefolk_delivery_ledger"
    private const val MAX_KEYS = 4000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(eventId: String, topic: String) = "$eventId|$topic"

    fun delivered(c: Context, eventId: String, topic: String): Boolean =
        prefs(c).getBoolean(key(eventId, topic), false)

    private fun belongsToEvent(ledgerKey: String, eventId: String): Boolean =
        ledgerKey.startsWith("$eventId|")

    private fun belongsToAnyEvent(ledgerKey: String, eventIds: Set<String>): Boolean =
        eventIds.any { belongsToEvent(ledgerKey, it) }

    @Synchronized
    fun markDelivered(c: Context, eventId: String, topic: String) {
        val p = prefs(c)
        val k = key(eventId, topic)
        if (p.getBoolean(k, false)) return

        val existingKeys = p.all.keys.toList()
        val pendingIds = EventStore.pending(c).mapTo(mutableSetOf()) { it.eventId }
        // eventId may itself contain '|', e.g. deterministic alarm delivery IDs.
        // Never parse with substringBefore('|'); match the full pending eventId prefix.
        val protectedKeys = existingKeys.filter { belongsToAnyEvent(it, pendingIds) }.toSet()
        val removable = existingKeys.filterNot { it in protectedKeys }
        val projectedSize = existingKeys.size + 1
        val removeCount = (projectedSize - MAX_KEYS + 500).coerceAtLeast(0)

        val editor = p.edit().putBoolean(k, true)
        removable.take(removeCount).forEach { editor.remove(it) }
        editor.commit()
    }

    /**
     * Self-heals the crash window between EventStore.markSynced() and clearEvent().
     * Receipts for still-pending events are preserved; receipts whose event is no
     * longer pending are residue only and can be removed without causing a resend.
     */
    @Synchronized
    fun pruneCompleted(c: Context) {
        val p = prefs(c)
        if (p.all.isEmpty()) return
        val pendingIds = EventStore.pending(c).mapTo(mutableSetOf()) { it.eventId }
        val stale = p.all.keys.filterNot { belongsToAnyEvent(it, pendingIds) }
        if (stale.isEmpty()) return
        val editor = p.edit()
        stale.forEach(editor::remove)
        editor.commit()
    }

    @Synchronized
    fun clearEvent(c: Context, eventId: String) {
        val prefix = "$eventId|"
        val p = prefs(c)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.commit()
    }

    @Synchronized
    fun dropTopic(c: Context, topic: String) {
        if (topic.isBlank()) return
        val suffix = "|$topic"
        val p = prefs(c)
        val matching = p.all.keys.filter { it.endsWith(suffix) }
        if (matching.isEmpty()) return
        val editor = p.edit()
        matching.forEach(editor::remove)
        editor.commit()
    }
}
