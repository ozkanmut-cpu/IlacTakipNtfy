package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.concurrent.thread

object SyncEngine {
    private const val PREFS = "dosefolk_sync"
    private const val LAST_ID = "last_ntfy_id"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pullOnce(c: Context) = thread {
        val context = c.applicationContext
        val topic = Store.topic(context)
        val since = prefs(context).getString(LAST_ID, null) ?: "24h"
        val encodedSince = URLEncoder.encode(since, "UTF-8")
        val url = URL("https://ntfy.sh/$topic/json?poll=1&since=$encodedSince")
        try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"; connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) { connection.errorStream?.close(); connection.disconnect(); return@thread }
            var newestId: String? = null
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val envelope = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    if (envelope.optString("event") != "message") return@forEach
                    val ntfyId = envelope.optString("id"); if (ntfyId.isNotBlank()) newestId = ntfyId
                    val payload = runCatching { JSONObject(envelope.optString("message")) }.getOrNull() ?: return@forEach
                    val event = parseDoseEvent(payload) ?: return@forEach
                    EventStore.append(context, event.copy(syncState = "synced"))
                    StockEngine.applyEvent(context, event)
                    applyRemoteState(context, event)
                }
            }
            newestId?.let { prefs(context).edit().putString(LAST_ID, it).apply() }
            connection.disconnect()
        } catch (_: Exception) { }
    }

    private fun parseDoseEvent(o: JSONObject): DoseEvent? {
        val eventId=o.optString("eventId"); val type=o.optString("type"); val time=o.optString("time")
        if(eventId.isBlank()||type.isBlank()||time.isBlank()) return null
        val medsJson=o.optJSONArray("medications")?:JSONArray()
        val meds=(0 until medsJson.length()).mapNotNull{index->medsJson.optJSONObject(index)?.let{med->Medication(med.optString("id"),med.optString("name"),med.optString("dose"),emptyList())}}
        return DoseEvent(eventId,type,time,o.optString("actor"),o.optString("actorTopic"),o.optLong("timestamp"),meds,"synced")
    }

    private fun applyRemoteState(c: Context, event: DoseEvent) {
        when(event.type){
            "care_claimed" -> CareBatonStore.applyRemoteClaim(c,event)
            "care_released" -> CareBatonStore.resolve(c,event.time)
            "taken","missed" -> { CareBatonStore.resolve(c,event.time); SmartEscalation.cancel(c,event.time) }
            "snoozed" -> SmartEscalation.schedule(c,event.time)
        }
    }
}
