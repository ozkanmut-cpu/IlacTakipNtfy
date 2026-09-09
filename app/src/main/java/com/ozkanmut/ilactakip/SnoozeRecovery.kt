package com.ozkanmut.ilactakip

import android.content.Context

/**
 * Reconciles the durable dose-state reducer with Android alarms after sync/restart.
 * A snooze that arrives late must not disappear silently: if its due time has already
 * passed and it is still the latest unresolved state, re-fire it immediately.
 */
object SnoozeRecovery {
    internal fun recoveryTrigger(snoozeUntil: Long, now: Long): Long =
        if (snoozeUntil > now) snoozeUntil else now + 1_000L

    fun reconcileToday(c: Context, now: Long = System.currentTimeMillis()): Int {
        var scheduled = 0
        DoseStateEngine.unresolved(c).forEach { state ->
            if (state.status != DoseSessionStatus.SNOOZED) return@forEach
            val latest = state.latestEvent ?: return@forEach
            if (latest.type != "snoozed") return@forEach
            val meds = latest.medications.ifEmpty { state.medications }
            if (meds.isEmpty()) return@forEach

            if (AlarmScheduler.scheduleSnoozeIfActive(
                    c = c,
                    time = state.time,
                    meds = meds,
                    triggerAtMillis = recoveryTrigger(latest.snoozeUntil, now),
                    scheduledDate = state.scheduledDate
                )
            ) scheduled++
        }
        return scheduled
    }
}
