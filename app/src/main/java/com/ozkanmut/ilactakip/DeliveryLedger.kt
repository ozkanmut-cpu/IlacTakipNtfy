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
        val editor = p.edit().putBoolean(k, true)
        val keys = p.all.keys
        if (keys.size >= MAX_KEYS) {
            keys.take(keys.size - MAX_KEYS + 500).forEach { editor.remove(it) }
        }
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
}
