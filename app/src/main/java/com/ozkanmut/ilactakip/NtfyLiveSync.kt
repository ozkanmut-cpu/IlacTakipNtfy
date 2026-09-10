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
        thread(name = "dosefolk-ntfy-live", isDaemon = true) {
            var retryMs = 1_000L
            while (started) {
                val topics = CircleTransport.subscriptionTopics(context)
                if (topics.isEmpty()) {
                    sleep(5_000L)
                    continue
                }
                try {
                    val connection = URL(NtfyEndpoint.streamUrl(topics, "10s"))
                        .openConnection() as HttpURLConnection
                    activeConnection = connection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    if (connection.responseCode !in 200..299) {
                        connection.errorStream?.close()
                        throw IllegalStateException("ntfy stream HTTP ${connection.responseCode}")
                    }
                    retryMs = 1_000L
                    connection.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!started) break
                            val envelope = runCatching { JSONObject(line) }.getOrNull()
                            if (envelope?.optString("event") == "message") {
                                SyncEngine.pullBlocking(context)
                            }
                            if (CircleTransport.subscriptionTopics(context) != topics) break
                        }
                    }
                } catch (_: Exception) {
                    if (CircleTransport.subscriptionTopics(context) == topics) {
                        sleep(retryMs)
                        retryMs = (retryMs * 2).coerceAtMost(30_000L)
                    } else {
                        retryMs = 1_000L
                    }
                } finally {
                    activeConnection?.disconnect()
                    activeConnection = null
                }
            }
        }
    }

    @Synchronized
    fun restart(c: Context) {
        activeConnection?.disconnect()
        ensure(c.applicationContext)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
