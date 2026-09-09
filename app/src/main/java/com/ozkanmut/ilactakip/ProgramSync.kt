package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ProgramSync {
    private const val PREFS = "dosefolk_program_sync"
    private const val KEY_BASELINE = "baseline"
    private const val KEY_INITIALIZED = "initialized"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun stampKey(ownerId: String, id: String) = "stamp|$ownerId|$id"
    private fun revisionKey(ownerId: String, id: String) = "order_rev|$ownerId|$id"
    private fun actorKey(ownerId: String, id: String) = "order_actor|$ownerId|$id"
    private fun eventKey(ownerId: String, id: String) = "order_event|$ownerId|$id"

    @Synchronized
    fun observeLocal(c: Context, meds: List<Medication>) {
        val p = prefs(c)
        val ownerId = OwnerScopeStore.localOwnerId(c)
        if (!p.getBoolean(KEY_INITIALIZED, false)) {
            saveBaseline(c, meds)
            meds.forEach { OwnerScopeStore.remember(c, localProgramEvent(c, it, ownerId)) }
            p.edit().putBoolean(KEY_INITIALIZED, true).commit()
            return
        }
        val old = loadBaseline(c).associateBy { it.id }
        val current = meds.associateBy { it.id }
        val now = System.currentTimeMillis()

        (current.keys - old.keys).forEach { id ->
            val med = current.getValue(id)
            p.edit().putLong(stampKey(ownerId, id), now).commit()
            Ntfy.sendEvent(c, "program_added", med.times.firstOrNull() ?: "program", listOf(med))
        }
        (old.keys - current.keys).forEach { id ->
            val med = old.getValue(id)
            p.edit().putLong(stampKey(ownerId, id), now).commit()
            Ntfy.sendEvent(c, "program_deleted", med.times.firstOrNull() ?: "program", listOf(med))
        }
        (current.keys intersect old.keys).forEach { id ->
            val before = old.getValue(id)
            val after = current.getValue(id)
            if (before != after) {
                p.edit().putLong(stampKey(ownerId, id), now).commit()
                Ntfy.sendEvent(c, "program_updated", after.times.firstOrNull() ?: before.times.firstOrNull() ?: "program", listOf(after))
            }
        }
        saveBaseline(c, meds)
    }

    /**
     * Applies an authorized remote program event without touching the local medication list.
     * Revision is the primary order. Equal concurrent revisions converge by actorTopic/eventId.
     * Legacy revision=0 events retain timestamp ordering for backwards compatibility.
     */
    @Synchronized
    fun applyRemote(c: Context, event: DoseEvent) {
        if (event.type !in setOf("program_added", "program_updated", "program_deleted")) return
        val med = event.medications.firstOrNull() ?: return
        if (med.id.isBlank()) return
        val ownerId = event.ownerId.ifBlank { event.actorTopic }
        if (ownerId.isBlank() || ownerId == OwnerScopeStore.localOwnerId(c)) return
        val p = prefs(c)

        val storedRevision = p.getLong(revisionKey(ownerId, med.id), 0L)
        val storedActor = p.getString(actorKey(ownerId, med.id), "").orEmpty()
        val storedEvent = p.getString(eventKey(ownerId, med.id), "").orEmpty()
        val legacyStamp = p.getLong(stampKey(ownerId, med.id), 0L)

        val accept = if (event.revision > 0L || storedRevision > 0L) {
            when {
                event.revision != storedRevision -> event.revision > storedRevision
                event.actorTopic != storedActor -> event.actorTopic > storedActor
                else -> event.eventId > storedEvent
            }
        } else {
            event.timestamp >= legacyStamp
        }
        if (!accept) return

        OwnerScopeStore.applyRemoteProgram(c, ownerId, event.type, med)
        p.edit()
            .putLong(stampKey(ownerId, med.id), event.timestamp)
            .putLong(revisionKey(ownerId, med.id), event.revision)
            .putString(actorKey(ownerId, med.id), event.actorTopic)
            .putString(eventKey(ownerId, med.id), event.eventId)
            .commit()
    }

    private fun localProgramEvent(c: Context, med: Medication, ownerId: String) = DoseEvent(
        eventId = "baseline-${med.id}",
        type = "program_baseline",
        time = med.times.firstOrNull() ?: "program",
        actor = Store.myName(c),
        actorTopic = Store.topic(c),
        timestamp = System.currentTimeMillis(),
        medications = listOf(med),
        syncState = "synced",
        ownerId = ownerId
    )

    private fun loadBaseline(c: Context): List<Medication> {
        val raw = prefs(c).getString(KEY_BASELINE, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    val times = o.optJSONArray("times") ?: JSONArray()
                    Medication(o.optString("id"), o.optString("name"), o.optString("dose"), (0 until times.length()).map { times.optString(it) })
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveBaseline(c: Context, meds: List<Medication>) {
        val a = JSONArray()
        meds.forEach { m -> a.put(JSONObject().put("id", m.id).put("name", m.name).put("dose", m.dose).put("times", JSONArray(m.times))) }
        prefs(c).edit().putString(KEY_BASELINE, a.toString()).commit()
    }
}
