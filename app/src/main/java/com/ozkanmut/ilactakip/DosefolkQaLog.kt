package com.ozkanmut.ilactakip

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Lightweight local QA trace. Never sends logs off-device automatically. */
object DosefolkQaLog {
    private const val DIR_NAME = "qa"
    private const val FILE_NAME = "dosefolk-qa.jsonl"
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private const val KEEP_BYTES = 1L * 1024L * 1024L
    private val sessionId: String = UUID.randomUUID().toString().replace("-", "").take(12)

    enum class Category {
        APP, SYNC, NTFY_RX, NTFY_TX, PAIR, REVOKE, ALARM, ACTION, WORKER, ERROR, SECURITY_REJECT
    }

    private fun file(c: Context): File {
        val dir = File(c.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun record(
        c: Context,
        category: Category,
        event: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        runCatching {
            val context = c.applicationContext
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val f = file(context)
            rotateIfNeeded(f)
            val safe = JSONObject()
                .put("ts", Instant.now().toString())
                .put("session", sessionId)
                .put("appVersion", packageInfo.versionName.orEmpty())
                .put("appCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong())
                .put("android", Build.VERSION.RELEASE ?: "")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("model", Build.MODEL ?: "")
                .put("category", category.name)
                .put("event", event)
            details.forEach { (key, value) -> safe.put(key, sanitize(key, value)) }
            f.appendText(safe.toString() + "\n")
        }
    }

    fun exportFile(c: Context): File = file(c)

    @Synchronized
    fun clear(c: Context) {
        runCatching { file(c).delete() }
    }

    internal fun currentSessionId(): String = sessionId

    private fun sanitize(key: String, value: Any?): Any? {
        if (value == null) return JSONObject.NULL
        val text = value.toString()
        val lower = key.lowercase()
        return when {
            lower.contains("topic") || lower.contains("token") || lower.contains("secret") -> mask(text)
            text.length > 500 -> text.take(500) + "…"
            else -> value
        }
    }

    internal fun mask(value: String): String {
        if (value.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val tag = digest.take(4).joinToString("") { "%02x".format(it) }
        return "#${tag}"
    }

    private fun rotateIfNeeded(f: File) {
        if (!f.exists() || f.length() < MAX_BYTES) return
        val bytes = f.readBytes()
        val start = (bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0)
        var slice = bytes.copyOfRange(start, bytes.size)
        val firstNl = slice.indexOf('\n'.code.toByte())
        if (firstNl >= 0 && firstNl + 1 < slice.size) slice = slice.copyOfRange(firstNl + 1, slice.size)
        f.writeBytes(slice)
    }
}
