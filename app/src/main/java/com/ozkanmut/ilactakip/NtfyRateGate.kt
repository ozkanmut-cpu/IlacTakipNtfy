package com.ozkanmut.ilactakip

import android.content.Context
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Process-independent ntfy publish backoff shared by every outbound path.
 * ntfy may return Retry-After either as delta-seconds or an HTTP/RFC1123 date.
 */
object NtfyRateGate {
    private const val PREFS = "dosefolk_ntfy_rate"
    private const val KEY_BLOCK_UNTIL = "block_until_ms"
    private const val MIN_BACKOFF_MS = 30_000L
    private const val DEFAULT_BACKOFF_MS = 60_000L
    private const val MAX_BACKOFF_MS = 6L * 60L * 60L * 1000L

    fun blockedUntil(c: Context): Long =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_BLOCK_UNTIL, 0L)

    fun isBlocked(c: Context, now: Long = System.currentTimeMillis()): Boolean =
        blockedUntil(c) > now

    internal fun retryAfterMillis(header: String?, now: Long = System.currentTimeMillis()): Long {
        val rawDelay = header?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            value.toLongOrNull()?.let { seconds -> seconds * 1_000L }
                ?: runCatching {
                    val target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli()
                    target - now
                }.getOrNull()
        } ?: DEFAULT_BACKOFF_MS
        return now + rawDelay.coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
    }

    fun record429(c: Context, retryAfter: String?, now: Long = System.currentTimeMillis()): Long {
        val until = retryAfterMillis(retryAfter, now)
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_BLOCK_UNTIL, until).commit()
        return until
    }

    fun clearAfterSuccess(c: Context) {
        val prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_BLOCK_UNTIL, 0L) != 0L) {
            prefs.edit().remove(KEY_BLOCK_UNTIL).commit()
        }
    }
}
