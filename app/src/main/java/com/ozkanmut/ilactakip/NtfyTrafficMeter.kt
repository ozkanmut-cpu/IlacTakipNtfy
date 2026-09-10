package com.ozkanmut.ilactakip

import android.content.Context
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Counts successful ntfy POSTs made by this app for diagnostics. */
object NtfyTrafficMeter {
    private const val PREFS = "dosefolk_ntfy_traffic"
    private const val KEY_DAY = "utc_day"
    private const val KEY_SUCCESSFUL_POSTS = "successful_posts"
    private val DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun utcDay(nowMs: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().format(DAY_FORMAT)

    @Synchronized
    fun successfulPostsToday(c: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val p = prefs(c)
        val day = utcDay(nowMs)
        if (p.getString(KEY_DAY, null) != day) {
            p.edit().putString(KEY_DAY, day).putInt(KEY_SUCCESSFUL_POSTS, 0).commit()
            return 0
        }
        return p.getInt(KEY_SUCCESSFUL_POSTS, 0).coerceAtLeast(0)
    }

    @Synchronized
    fun recordSuccessfulPost(c: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val p = prefs(c)
        val day = utcDay(nowMs)
        val current = if (p.getString(KEY_DAY, null) == day) p.getInt(KEY_SUCCESSFUL_POSTS, 0).coerceAtLeast(0) else 0
        val next = if (current == Int.MAX_VALUE) Int.MAX_VALUE else current + 1
        p.edit().putString(KEY_DAY, day).putInt(KEY_SUCCESSFUL_POSTS, next).commit()
        return next
    }
}

/**
 * Public ntfy.sh needed a conservative daily soft quota. Dosefolk now uses its
 * own ntfy server, so replaceable stock snapshots no longer need local deferral.
 * HTTP 429 handling remains active in NtfyRateGate as a server safety backoff.
 */
object NtfyTrafficBudget {
    internal const val NONCRITICAL_SOFT_LIMIT = Int.MAX_VALUE

    fun shouldDeferNoncritical(c: Context, nowMs: Long = System.currentTimeMillis()): Boolean = false

    internal fun isReplaceableNoncritical(alertId: String): Boolean =
        alertId.startsWith("stock|")

    fun shouldDefer(c: Context, alertId: String, nowMs: Long = System.currentTimeMillis()): Boolean = false
}
