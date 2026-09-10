package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Keeps a lightweight ntfy JSON stream open while the app process is alive.
 * The stream is only a wake-up signal: canonical ingestion still goes through
 * SyncEngine.pullBlocking(), preserving replay, target and publisher-topic guards.
 * WorkManager remains the durable fallback if Android suspends/kills the process.
 */
object NtfyLiveSync {
    @Volatile private var started = false
    @Volatile private var activeConnection: HttpURLConnection? = null

    @Synchronized
    fun ensure(c: Context) {
        if (started) return
        started = true
        val context = c.applicationContext
        DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "live_stream_start")
        thread(name = "dosefolk-ntfy-live", isDaemon = true) {
            var retryMs = 1_000L
            while (started) {
                val topics = CircleTransport.subscriptionTopics(context)
                if (topics.isEmpty()) {
                    sleep(5_000L)
                    continue
                }
                try {
                    DosefolkQaLog.record(
                        context,
                        DosefolkQaLog.Category.SYNC,
                        "live_connect_attempt",
                        mapOf("topicCount" to topics.size, "topics" to topics.joinToString(","))
                    )
                    val connection = URL(NtfyEndpoint.streamUrl(topics, "10s"))
                        .openConnection() as HttpURLConnection
                    activeConnection = connection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        connection.errorStream?.close()
                        DosefolkQaLog.record(
                            context,
                            DosefolkQaLog.Category.ERROR,
                            "live_http_error",
                            mapOf("status" to status)
                        )
                        throw IllegalStateException("ntfy stream HTTP $status")
                    }
                    DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "live_connected")
                    retryMs = 1_000L
                    connection.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!started) break
                            val envelope = runCatching { JSONObject(line) }.getOrNull()
                            if (envelope?.optString("event") == "message") {
                                DosefolkQaLog.record(
                                    context,
                                    DosefolkQaLog.Category.NTFY_RX,
                                    "live_message_signal",
                                    mapOf(
                                        "ntfyId" to envelope.optString("id"),
                                        "topic" to envelope.optString("topic")
                                    )
                                )
                                SyncEngine.pullBlocking(context)
                            }
                            if (CircleTransport.subscriptionTopics(context) != topics) {
                                DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "live_topics_changed")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    DosefolkQaLog.record(
                        context,
                        DosefolkQaLog.Category.ERROR,
                        "live_stream_exception",
                        mapOf("type" to e.javaClass.simpleName, "message" to e.message)
                    )
                    if (CircleTransport.subscriptionTopics(context) == topics) {
                        sleep(retryMs)
                        retryMs = (retryMs * 2).coerceAtMost(30_000L)
                    } else {
                        retryMs = 1_000L
                    }
                } finally {
                    activeConnection?.disconnect()
                    activeConnection = null
                    DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "live_disconnected")
                }
            }
        }
    }

    @Synchronized
    fun restart(c: Context) {
        DosefolkQaLog.record(c, DosefolkQaLog.Category.SYNC, "live_restart_requested")
        activeConnection?.disconnect()
        ensure(c.applicationContext)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
