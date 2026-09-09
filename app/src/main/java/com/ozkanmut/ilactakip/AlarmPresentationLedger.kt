package com.ozkanmut.ilactakip

import android.content.Context

/**
 * Durable receipt written only after the alarm notification and escalation chain
 * have been presented. If the process dies after the alarm event is persisted but
 * before this receipt, AlarmReceiver may safely finish presentation on redelivery.
 */
object AlarmPresentationLedger {
    private const val PREFS = "dosefolk_alarm_presentation"
    private const val KEY = "presented_ids"
    private const val MAX_IDS = 512

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun isPresented(c: Context, deliveryId: String): Boolean =
        deliveryId.isNotBlank() && prefs(c).getStringSet(KEY, emptySet()).orEmpty().contains(deliveryId)

    @Synchronized
    fun markPresented(c: Context, deliveryId: String) {
        if (deliveryId.isBlank()) return
        val current = prefs(c).getStringSet(KEY, emptySet()).orEmpty().toMutableList()
        current.remove(deliveryId)
        current.add(deliveryId)
        val keep = current.takeLast(MAX_IDS).toSet()
        prefs(c).edit().putStringSet(KEY, keep).commit()
    }

    internal fun canonicalAlarmForRedelivery(c: Context, deliveryId: String, time: String, scheduledDate: String): DoseEvent? =
        EventStore.load(c).firstOrNull { event ->
            event.eventId == deliveryId &&
                event.type == "alarm" &&
                event.time == time &&
                event.scheduledDate == scheduledDate
        }
}
