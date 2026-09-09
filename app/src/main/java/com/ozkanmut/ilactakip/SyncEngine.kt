package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlin.concurrent.thread

object SyncEngine {
    private const val PREFS = "dosefolk_sync"
    private const val LAST_ID = "last_ntfy_id"
    private const val LAST_SUCCESS = "last_success_ms"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastSuccess(c: Context): Long = prefs(c).getLong(LAST_SUCCESS, 0L)

    fun pullOnce(c: Context) = thread {
        DosefolkSyncScheduler.ensure(c.applicationContext)
        pullBlocking(c.applicationContext)
    }

    fun pullBlocking(c: Context): Boolean {
        val context = c.applicationContext
        val topic = Store.topic(context)
        val since = prefs(context).getString(LAST_ID, null) ?: "24h"
        val encodedSince = URLEncoder.encode(since, "UTF-8")
        val url = URL("https://ntfy.sh/$topic/json?poll=1&since=$encodedSince")
        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"; connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) {
                connection.errorStream?.close(); connection.disconnect(); false
            } else {
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
                val edit = prefs(context).edit().putLong(LAST_SUCCESS, System.currentTimeMillis())
                newestId?.let { edit.putString(LAST_ID, it) }
                edit.apply()
                connection.disconnect()
                true
            }
        } catch (_: Exception) { false }
    }

    private fun parseDoseEvent(o: JSONObject): DoseEvent? {
        val eventId=o.optString("eventId"); val type=o.optString("type"); val time=o.optString("time")
        if(eventId.isBlank()||type.isBlank()||time.isBlank()) return null
        val medsJson=o.optJSONArray("medications")?:JSONArray()
        val meds=(0 until medsJson.length()).mapNotNull{index->medsJson.optJSONObject(index)?.let{med->Medication(med.optString("id"),med.optString("name"),med.optString("dose"),emptyList())}}
        val timestamp = o.optLong("timestamp")
        val fallbackDate = if (timestamp > 0L) Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString() else ""
        return DoseEvent(
            eventId, type, time, o.optString("actor"), o.optString("actorTopic"), timestamp,
            meds, "synced", o.optLong("revision",0L),
            o.optString("scheduledDate", fallbackDate).ifBlank { fallbackDate },
            o.optLong("snoozeUntil", 0L)
        )
    }

    private fun applyRemoteState(c: Context, event: DoseEvent) {
        val scheduledDate = event.scheduledDate
        when(event.type){
            "care_claimed" -> CareBatonStore.applyRemoteClaim(c,event)
            "care_released" -> CareBatonStore.resolve(c,event.time,scheduledDate)
            "taken","missed","conflict_resolved_taken","conflict_resolved_missed" -> {
                CareBatonStore.resolve(c,event.time,scheduledDate)
                SmartEscalation.cancel(c,event.time,scheduledDate)
                AlarmScheduler.cancelSnooze(c,event.time)
            }
            "snoozed" -> {
                SmartEscalation.cancel(c,event.time,scheduledDate)
                if (event.snoozeUntil > System.currentTimeMillis() && event.medications.isNotEmpty()) {
                    AlarmScheduler.scheduleSnoozeUntil(c,event.time,event.medications,event.snoozeUntil,scheduledDate)
                }
            }
        }
    }
}
