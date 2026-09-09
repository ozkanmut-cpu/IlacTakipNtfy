package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray

/**
 * Durable receipt written only after the alarm notification and escalation chain
 * have been presented. If the process dies after the alarm event is persisted but
 * before this receipt, AlarmReceiver may safely finish presentation on redelivery.
 *
 * Receipts are kept in insertion order so bounded retention always evicts the
 * oldest completed presentation first. Legacy StringSet receipts are migrated
 * lazily and remain readable during the transition.
 */
object AlarmPresentationLedger {
    private const val PREFS = "dosefolk_alarm_presentation"
    private const val LEGACY_KEY = "presented_ids"
    private const val ORDERED_KEY = "presented_ids_ordered"
    internal const val MAX_IDS = 512

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun isPresented(c: Context, deliveryId: String): Boolean {
        if (deliveryId.isBlank()) return false
        return loadOrdered(c).contains(deliveryId) ||
            prefs(c).getStringSet(LEGACY_KEY, emptySet()).orEmpty().contains(deliveryId)
    }

    @Synchronized
    fun markPresented(c: Context, deliveryId: String) {
        if (deliveryId.isBlank()) return
        val current = loadMerged(c)
        current.remove(deliveryId)
        current.add(deliveryId)
        val keep = current.takeLast(MAX_IDS)
        prefs(c).edit()
            .putString(ORDERED_KEY, JSONArray(keep).toString())
            .remove(LEGACY_KEY)
            .commit()
    }

    internal fun orderedIds(c: Context): List<String> = loadMerged(c)

    private fun loadMerged(c: Context): MutableList<String> {
        val ordered = loadOrdered(c).toMutableList()
        val seen = ordered.toMutableSet()
        prefs(c).getStringSet(LEGACY_KEY, emptySet()).orEmpty().forEach { id ->
            if (id.isNotBlank() && seen.add(id)) ordered.add(id)
        }
        return ordered
    }

    private fun loadOrdered(c: Context): List<String> {
        val raw = prefs(c).getString(ORDERED_KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { index -> a.optString(index).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    internal fun canonicalAlarmForRedelivery(c: Context, deliveryId: String, time: String, scheduledDate: String): DoseEvent? =
        EventStore.load(c).firstOrNull { event ->
            event.eventId == deliveryId &&
                event.type == "alarm" &&
                event.time == time &&
                event.scheduledDate == scheduledDate
        }
}
