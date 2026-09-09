package com.ozkanmut.ilactakip

import android.content.Context
import java.time.ZoneId

/**
 * Detects a timezone change and asks the user to verify the medication plan.
 * Dosefolk never guesses whether a regimen should follow local or home time.
 */
data class TravelNotice(
    val fromZone: String,
    val toZone: String,
    val detectedAt: Long
)

object TravelGuard {
    private const val PREFS = "dosefolk_travel_guard"
    private const val LAST_ZONE = "last_zone"
    private const val FROM_ZONE = "from_zone"
    private const val TO_ZONE = "to_zone"
    private const val DETECTED_AT = "detected_at"
    private const val ACKED = "acked"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun initialize(c: Context) {
        val p = prefs(c)
        if (!p.contains(LAST_ZONE)) p.edit().putString(LAST_ZONE, ZoneId.systemDefault().id).apply()
    }

    fun onTimezonePossiblyChanged(c: Context) {
        val p = prefs(c)
        val current = ZoneId.systemDefault().id
        val previous = p.getString(LAST_ZONE, null)
        if (previous == null) {
            p.edit().putString(LAST_ZONE, current).apply()
            return
        }
        if (previous != current) {
            p.edit()
                .putString(FROM_ZONE, previous)
                .putString(TO_ZONE, current)
                .putLong(DETECTED_AT, System.currentTimeMillis())
                .putBoolean(ACKED, false)
                .putString(LAST_ZONE, current)
                .apply()
        }
    }

    fun pendingNotice(c: Context): TravelNotice? {
        val p = prefs(c)
        if (p.getBoolean(ACKED, true)) return null
        val from = p.getString(FROM_ZONE, null) ?: return null
        val to = p.getString(TO_ZONE, null) ?: return null
        return TravelNotice(from, to, p.getLong(DETECTED_AT, 0L))
    }

    /**
     * Medication-day semantics remain anchored to the previous timezone until the
     * user explicitly acknowledges the timezone change. This keeps daily PRN
     * limits and other date-bounded safety logic from silently shifting.
     */
    fun effectiveMedicationZone(c: Context): ZoneId {
        val pending = pendingNotice(c)
        if (pending != null) {
            return runCatching { ZoneId.of(pending.fromZone) }.getOrElse { ZoneId.systemDefault() }
        }
        return ZoneId.systemDefault()
    }

    fun acknowledge(c: Context) {
        prefs(c).edit().putBoolean(ACKED, true).apply()
        AlarmScheduler.scheduleAll(c, Store.load(c))
    }
}
