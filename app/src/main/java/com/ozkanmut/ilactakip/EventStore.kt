package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class DoseEvent(
    val eventId: String,
    val type: String,
    val time: String,
    val actor: String,
    val actorTopic: String,
    val timestamp: Long,
    val medications: List<Medication>,
    val syncState: String = "pending",
    val revision: Long = 0L,
    val scheduledDate: String = "",
    val snoozeUntil: Long = 0L
)

object EventStore {
    private const val PREFS = "dosefolk_events"
    private const val KEY_EVENTS = "events"
    private const val KEY_REVISION = "local_revision"
    private const val MAX_EVENTS = 1000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun nextRevision(c: Context): Long {
        val p = prefs(c)
        val next = p.getLong(KEY_REVISION, 0L) + 1L
        p.edit().putLong(KEY_REVISION, next).commit()
        return next
    }

    @Synchronized
    fun append(c: Context, event: DoseEvent) {
        val current = load(c).toMutableList()
        if (current.none { it.eventId == event.eventId }) current.add(0, event)
        save(c, current.take(MAX_EVENTS))
    }

    @Synchronized
    fun markSynced(c: Context, eventId: String) {
        save(c, load(c).map { if (it.eventId == eventId) it.copy(syncState = "synced") else it })
    }

    fun pending(c: Context): List<DoseEvent> = load(c).filter { it.syncState != "synced" }

    fun load(c: Context): List<DoseEvent> {
        val raw = prefs(c).getString(KEY_EVENTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> fromJson(array.optJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, events: List<DoseEvent>) {
        val array = JSONArray()
        events.forEach { array.put(toJson(it)) }
        prefs(c).edit().putString(KEY_EVENTS, array.toString()).commit()
    }

    fun payload(event: DoseEvent): JSONObject = JSONObject()
        .put("v", 6)
        .put("eventId", event.eventId)
        .put("type", event.type)
        .put("time", event.time)
        .put("scheduledDate", event.scheduledDate)
        .put("snoozeUntil", event.snoozeUntil)
        .put("actor", event.actor)
        .put("actorTopic", event.actorTopic)
        .put("timestamp", event.timestamp)
        .put("revision", event.revision)
        .put("syncState", event.syncState)
        .put("medications", JSONArray(event.medications.map { med ->
            JSONObject().put("id", med.id).put("name", med.name).put("dose", med.dose)
        }))

    private fun toJson(event: DoseEvent): JSONObject = payload(event)

    private fun fromJson(o: JSONObject?): DoseEvent? {
        if (o == null) return null
        val medsJson = o.optJSONArray("medications") ?: JSONArray()
        val meds = (0 until medsJson.length()).mapNotNull { index ->
            medsJson.optJSONObject(index)?.let { med ->
                Medication(med.optString("id"), med.optString("name"), med.optString("dose"), emptyList())
            }
        }
        val timestamp = o.optLong("timestamp")
        val fallbackDate = if (timestamp > 0L) {
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } else ""
        return DoseEvent(
            eventId = o.optString("eventId"),
            type = o.optString("type"),
            time = o.optString("time"),
            actor = o.optString("actor"),
            actorTopic = o.optString("actorTopic"),
            timestamp = timestamp,
            medications = meds,
            syncState = o.optString("syncState", "pending"),
            revision = o.optLong("revision", 0L),
            scheduledDate = o.optString("scheduledDate", fallbackDate).ifBlank { fallbackDate },
            snoozeUntil = o.optLong("snoozeUntil", 0L)
        )
    }
}
