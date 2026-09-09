package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate

/** Re-opens today's local dose session after an undo without creating reminder loops. */
object UndoRecovery {
    private const val PREFS = "dosefolk_undo_recovery"
    private const val KEY = "rearmed_event_ids"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recoverEvent(c: Context, event: DoseEvent): Boolean {
        if (event.type !in setOf("undo_taken", "undo_missed")) return false
        val date = runCatching { LocalDate.parse(event.scheduledDate) }.getOrNull() ?: return false
        if (date != LocalDate.now()) return false
        val ownerId = event.ownerId.ifBlank { event.actorTopic }
        if (ownerId.isNotBlank() && ownerId != OwnerScopeStore.localOwnerId(c)) return false
        if (alreadyRearmed(c, event.eventId)) return false

        val state = DoseStateEngine.stateForTime(c, event.time, date)
        if (state.status != DoseSessionStatus.PENDING || state.latestEvent?.eventId != event.eventId) return false
        val meds = event.medications.ifEmpty { state.medications }
        if (meds.isEmpty()) return false

        AlarmScheduler.cancelSnooze(c, event.time)
        AlarmScheduler.scheduleSnoozeUntil(c, event.time, meds, System.currentTimeMillis() + 1_000L, event.scheduledDate)
        SmartEscalation.schedule(c, event.time, event.scheduledDate)
        markRearmed(c, event.eventId)
        return true
    }

    fun recoverCurrent(c: Context): Int {
        var count = 0
        DoseStateEngine.today().forEach { state ->
            val event = state.latestEvent ?: return@forEach
            if (recoverEvent(c, event)) count++
        }
        return count
    }

    private fun alreadyRearmed(c: Context, eventId: String): Boolean =
        prefs(c).getStringSet(KEY, emptySet()).orEmpty().contains(eventId)

    private fun markRearmed(c: Context, eventId: String) {
        val ids = (listOf(eventId) + prefs(c).getStringSet(KEY, emptySet()).orEmpty()).distinct().take(1000).toSet()
        prefs(c).edit().putStringSet(KEY, ids).commit()
    }
}
