package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class PendingAlert(
    val id: String,
    val topic: String,
    val title: String,
    val message: String,
    val createdAt: Long
)

object AlertOutbox {
    private const val PREFS = "dosefolk_alert_outbox"
    private const val KEY = "alerts"
    private const val FLUSH_BATCH = 10
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(c: Context, topic: String, title: String, message: String) {
        if (topic.isBlank()) return
        val current = load(c).toMutableList()
        current.add(0, PendingAlert(UUID.randomUUID().toString(), topic, title, message, System.currentTimeMillis()))
        // Pending caregiver alerts are reliability data. Never discard them merely
        // because the device stayed offline for a long time.
        save(c, current)
        DosefolkSyncScheduler.kick(c)
    }

    @Synchronized
    fun pendingCount(c: Context): Int = load(c).size

    @Synchronized
    fun dropTopic(c: Context, topic: String) {
        if (topic.isBlank()) return
        save(c, load(c).filterNot { it.topic == topic })
    }

    internal fun batchForFlush(all: List<PendingAlert>): List<PendingAlert> = all.takeLast(FLUSH_BATCH)

    @Synchronized
    fun flushBlocking(c: Context): Boolean {
        val all = load(c)
        if (all.isEmpty()) return true

        // Lists are newest-first. Drain the oldest alerts first so long-offline
        // caregiver notifications preserve chronology. Keep each worker pass small:
        // every HTTP call can consume its full timeout on a bad network.
        val batch = batchForFlush(all)
        val deliveredIds = mutableSetOf<String>()
        for (alert in batch) {
            if (!post(alert)) break
            deliveredIds += alert.id
        }
        val remaining = all.filterNot { it.id in deliveredIds }
        save(c, remaining)
        return remaining.isEmpty()
    }

    private fun post(alert: PendingAlert): Boolean = try {
        val connection = URL("https://ntfy.sh/${alert.topic}").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Title", alert.title)
        connection.setRequestProperty("Priority", "high")
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.outputStream.use { it.write(alert.message.toByteArray()) }
        val ok = connection.responseCode in 200..299
        if (ok) connection.inputStream.close() else connection.errorStream?.close()
        connection.disconnect()
        ok
    } catch (_: Exception) { false }

    private fun load(c: Context): List<PendingAlert> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            PendingAlert(o.optString("id"), o.optString("topic"), o.optString("title"), o.optString("message"), o.optLong("createdAt"))
        } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, alerts: List<PendingAlert>) {
        val a = JSONArray()
        alerts.forEach { a.put(JSONObject().put("id", it.id).put("topic", it.topic).put("title", it.title).put("message", it.message).put("createdAt", it.createdAt)) }
        prefs(c).edit().putString(KEY, a.toString()).commit()
    }
}
