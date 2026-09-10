package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class PendingAlert(
    val id: String,
    val topic: String,
    val title: String,
    val message: String,
    val createdAt: Long,
    val inFlight: Boolean = false
)

data class EscalationSessionKey(val scheduledDate: String, val time: String)

internal object AlertDeliveryProbe {
    fun containsSequence(lines: Sequence<String>, sequenceId: String): Boolean {
        if (sequenceId.isBlank()) return false
        return lines.any { line ->
            runCatching { JSONObject(line) }.getOrNull()?.let { o ->
                o.optString("event") == "message" && o.optString("sequence_id") == sequenceId
            } == true
        }
    }
}

object AlertOutbox {
    private const val PREFS = "dosefolk_alert_outbox"
    private const val KEY = "alerts"
    private const val FLUSH_BATCH = 10
    private const val STALE_ESCALATION_AMBIGUITY_MS = 10L * 60L * 60L * 1000L
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private enum class PostResult { DELIVERED, RATE_LIMITED, AMBIGUOUS_FAILURE }

    @Synchronized
    fun enqueue(
        c: Context,
        topic: String,
        title: String,
        message: String,
        id: String = UUID.randomUUID().toString(),
        kick: Boolean = true
    ): Boolean {
        if (topic.isBlank() || id.isBlank()) return false
        val current = load(c).toMutableList()
        if (current.any { it.id == id }) {
            if (kick) DosefolkSyncScheduler.kick(c)
            return false
        }
        current.add(0, PendingAlert(id, topic, title, message, System.currentTimeMillis()))
        save(c, current)
        if (kick) DosefolkSyncScheduler.kick(c)
        return true
    }

    /**
     * Latest-wins coalescing for replaceable snapshots such as stock state.
     * Only a not-yet-sent row is replaced. An in-flight row is delivery-ambiguous
     * and therefore must remain immutable until probe/ack resolution.
     */
    @Synchronized
    fun enqueueLatest(
        c: Context,
        topic: String,
        title: String,
        message: String,
        stableId: String,
        kick: Boolean = true
    ): Boolean {
        if (topic.isBlank() || stableId.isBlank()) return false
        val current = load(c).toMutableList()
        val index = current.indexOfFirst { it.id == stableId }
        val now = System.currentTimeMillis()
        when {
            index < 0 -> current.add(0, PendingAlert(stableId, topic, title, message, now))
            !current[index].inFlight -> current[index] = current[index].copy(
                topic = topic,
                title = title,
                message = message,
                createdAt = now
            )
            else -> {
                val followupId = "$stableId|next"
                val followup = current.indexOfFirst { it.id == followupId }
                if (followup < 0) current.add(0, PendingAlert(followupId, topic, title, message, now))
                else if (!current[followup].inFlight) current[followup] = current[followup].copy(
                    topic = topic,
                    title = title,
                    message = message,
                    createdAt = now
                )
            }
        }
        save(c, current)
        if (kick) DosefolkSyncScheduler.kick(c)
        return true
    }

    @Synchronized
    fun pendingCount(c: Context): Int = load(c).size

    @Synchronized
    fun actionablePendingCount(c: Context): Int =
        load(c).count { !NtfyTrafficBudget.shouldDefer(c, it.id) }

    @Synchronized
    fun dropTopic(c: Context, topic: String) {
        if (topic.isBlank()) return
        save(c, load(c).filterNot { it.topic == topic })
    }

    @Synchronized
    fun dropEscalationSession(c: Context, time: String, scheduledDate: String) {
        if (time.isBlank() || scheduledDate.isBlank()) return
        val prefix = "escalation|$scheduledDate|$time|"
        save(c, load(c).filterNot { it.id.startsWith(prefix) })
    }

    internal fun batchForFlush(all: List<PendingAlert>): List<PendingAlert> =
        all.takeLast(FLUSH_BATCH).asReversed()

    /** Oldest eligible rows first; soft-deferred snapshots never block critical rows behind them. */
    internal fun eligibleBatchForFlush(c: Context, all: List<PendingAlert>): List<PendingAlert> =
        all.asReversed()
            .filterNot { NtfyTrafficBudget.shouldDefer(c, it.id) }
            .take(FLUSH_BATCH)

    /**
     * True when nothing remains that should be sent in the current budget window.
     * Soft-deferred replaceable stock snapshots stay durable, but they do not force
     * a WorkManager retry loop. The 15-minute periodic worker will revisit them and
     * they become eligible automatically after the UTC traffic counter resets.
     */
    internal fun settledForCurrentBudget(c: Context, all: List<PendingAlert>): Boolean =
        all.isEmpty() || eligibleBatchForFlush(c, all).isEmpty()

    internal fun staleEscalationSession(alert: PendingAlert, now: Long = System.currentTimeMillis()): EscalationSessionKey? {
        if (!alert.inFlight || now - alert.createdAt < STALE_ESCALATION_AMBIGUITY_MS) return null
        val parts = alert.id.split('|')
        if (parts.size < 5 || parts[0] != "escalation") return null
        val scheduledDate = parts[1]
        val time = parts[2]
        if (scheduledDate.isBlank() || time.isBlank()) return null
        if (runCatching { LocalDate.parse(scheduledDate) }.isFailure) return null
        if (runCatching { LocalTime.parse(time) }.isFailure) return null
        return EscalationSessionKey(scheduledDate, time)
    }

    @Synchronized
    fun flushBlocking(c: Context): Boolean {
        var all = load(c)
        if (all.isEmpty()) return true
        if (NtfyRateGate.isBlocked(c)) return false

        val batchIds = eligibleBatchForFlush(c, all).map { it.id }
        for (id in batchIds) {
            if (NtfyRateGate.isBlocked(c)) return false
            var alert = all.firstOrNull { it.id == id } ?: continue

            val staleSession = staleEscalationSession(alert)
            if (staleSession != null) {
                all = all.filterNot { it.id == alert.id }
                save(c, all)
                recoverEscalationFromCurrentState(c, staleSession)
                all = load(c)
                continue
            }

            if (alert.inFlight) {
                when (probeDelivered(alert)) {
                    ProbeResult.DELIVERED -> {
                        all = all.filterNot { it.id == alert.id }
                        save(c, all)
                        continue
                    }
                    ProbeResult.NOT_FOUND -> {
                        alert = alert.copy(inFlight = false)
                        all = all.map { if (it.id == alert.id) alert else it }
                        save(c, all)
                    }
                    ProbeResult.UNKNOWN -> return false
                }
            }

            alert = alert.copy(inFlight = true)
            all = all.map { if (it.id == alert.id) alert else it }
            save(c, all)

            when (post(c, alert)) {
                PostResult.DELIVERED -> {
                    all = all.filterNot { it.id == alert.id }
                    save(c, all)
                }
                PostResult.RATE_LIMITED -> {
                    alert = alert.copy(inFlight = false)
                    all = all.map { if (it.id == alert.id) alert else it }
                    save(c, all)
                    return false
                }
                PostResult.AMBIGUOUS_FAILURE -> return false
            }
        }
        return settledForCurrentBudget(c, all)
    }

    private fun recoverEscalationFromCurrentState(c: Context, session: EscalationSessionKey) {
        val date = runCatching { LocalDate.parse(session.scheduledDate) }.getOrNull() ?: return
        val state = DoseStateEngine.stateForTime(c, session.time, date)
        val unresolved = state.status == DoseSessionStatus.PENDING ||
            state.status == DoseSessionStatus.SNOOZED ||
            state.status == DoseSessionStatus.CONFLICT
        if (unresolved) SmartEscalation.schedule(c, session.time, session.scheduledDate)
        else dropEscalationSession(c, session.time, session.scheduledDate)
    }

    private enum class ProbeResult { DELIVERED, NOT_FOUND, UNKNOWN }

    private fun probeDelivered(alert: PendingAlert): ProbeResult = try {
        val sinceSeconds = ((alert.createdAt - 5_000L).coerceAtLeast(0L) / 1000L).toString()
        val encodedSince = URLEncoder.encode(sinceSeconds, "UTF-8")
        val connection = URL("https://ntfy.sh/${alert.topic}/json?poll=1&since=$encodedSince").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        if (connection.responseCode !in 200..299) {
            connection.errorStream?.close()
            connection.disconnect()
            ProbeResult.UNKNOWN
        } else {
            val truncated = connection.getHeaderField("X-Messages-Truncated") == "1"
            val found = connection.inputStream.bufferedReader().useLines { lines ->
                AlertDeliveryProbe.containsSequence(lines, alert.id)
            }
            connection.disconnect()
            when {
                found -> ProbeResult.DELIVERED
                truncated -> ProbeResult.UNKNOWN
                else -> ProbeResult.NOT_FOUND
            }
        }
    } catch (_: Exception) { ProbeResult.UNKNOWN }

    private fun post(c: Context, alert: PendingAlert): PostResult {
        if (NtfyRateGate.isBlocked(c)) return PostResult.RATE_LIMITED
        return try {
            val connection = URL("https://ntfy.sh/${alert.topic}").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Title", alert.title)
            connection.setRequestProperty("Priority", "high")
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.setRequestProperty("X-Sequence-ID", alert.id)
            connection.outputStream.use { it.write(alert.message.toByteArray()) }
            val code = connection.responseCode
            val result = when {
                code in 200..299 -> {
                    NtfyRateGate.clearAfterSuccess(c)
                    PostResult.DELIVERED
                }
                code == 429 -> {
                    NtfyRateGate.record429(c, connection.getHeaderField("Retry-After"))
                    PostResult.RATE_LIMITED
                }
                else -> PostResult.AMBIGUOUS_FAILURE
            }
            if (code in 200..299) connection.inputStream.close() else connection.errorStream?.close()
            connection.disconnect()
            result
        } catch (_: Exception) { PostResult.AMBIGUOUS_FAILURE }
    }

    private fun load(c: Context): List<PendingAlert> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            PendingAlert(
                o.optString("id"),
                o.optString("topic"),
                o.optString("title"),
                o.optString("message"),
                o.optLong("createdAt"),
                o.optBoolean("inFlight", false)
            )
        } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, alerts: List<PendingAlert>) {
        val a = JSONArray()
        alerts.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("topic", it.topic)
                    .put("title", it.title)
                    .put("message", it.message)
                    .put("createdAt", it.createdAt)
                    .put("inFlight", it.inFlight)
            )
        }
        prefs(c).edit().putString(KEY, a.toString()).commit()
    }
}
