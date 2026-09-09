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

    @Synchronized
    fun markDelivered(c: Context, eventId: String, topic: String) {
        val p = prefs(c)
        val k = key(eventId, topic)
        if (p.getBoolean(k, false)) return

        val existingKeys = p.all.keys.toList()
        val pendingIds = EventStore.pending(c).mapTo(mutableSetOf()) { it.eventId }
        val protectedKeys = existingKeys.filter { ledgerEventId(it) in pendingIds }.toSet()
        val removable = existingKeys.filterNot { it in protectedKeys }
        val projectedSize = existingKeys.size + 1
        val removeCount = (projectedSize - MAX_KEYS + 500).coerceAtLeast(0)

        val editor = p.edit().putBoolean(k, true)
        removable.take(removeCount).forEach { editor.remove(it) }
        editor.commit()
    }

    private fun ledgerEventId(key: String): String = key.substringBefore('|')

    @Synchronized
    fun clearEvent(c: Context, eventId: String) {
        val prefix = "$eventId|"
        val p = prefs(c)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.commit()
    }
}
