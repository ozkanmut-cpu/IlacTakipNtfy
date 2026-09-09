package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps medication program changes in the same durable ntfy event stream as dose actions.
 * The first observation only establishes a baseline so upgrades do not broadcast every
 * existing medication as newly added.
 */
object ProgramSync {
    private const val PREFS = "dosefolk_program_sync"
    private const val KEY_BASELINE = "baseline"
    private const val KEY_INITIALIZED = "initialized"
    private const val STORE_PREFS = "ilac_takip"
    private const val STORE_MEDS = "meds"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun stampKey(id: String) = "stamp|$id"

    @Synchronized
    fun observeLocal(c: Context, meds: List<Medication>) {
        val p = prefs(c)
        if (!p.getBoolean(KEY_INITIALIZED, false)) {
            saveBaseline(c, meds)
            p.edit().putBoolean(KEY_INITIALIZED, true).commit()
            return
        }
        val old = loadBaseline(c).associateBy { it.id }
        val current = meds.associateBy { it.id }
        val now = System.currentTimeMillis()

        (current.keys - old.keys).forEach { id ->
            val med = current.getValue(id)
            p.edit().putLong(stampKey(id), now).commit()
            Ntfy.sendEvent(c, "program_added", med.times.firstOrNull() ?: "program", listOf(med))
        }
        (old.keys - current.keys).forEach { id ->
            val med = old.getValue(id)
            p.edit().putLong(stampKey(id), now).commit()
            Ntfy.sendEvent(c, "program_deleted", med.times.firstOrNull() ?: "program", listOf(med))
        }
        (current.keys intersect old.keys).forEach { id ->
            val before = old.getValue(id)
            val after = current.getValue(id)
            if (before != after) {
                p.edit().putLong(stampKey(id), now).commit()
                Ntfy.sendEvent(c, "program_updated", after.times.firstOrNull() ?: before.times.firstOrNull() ?: "program", listOf(after))
            }
        }
        saveBaseline(c, meds)
    }

    /** Applies an authorized remote program event without creating a send-back loop. */
    @Synchronized
    fun applyRemote(c: Context, event: DoseEvent) {
        if (event.type !in setOf("program_added", "program_updated", "program_deleted")) return
        val med = event.medications.firstOrNull() ?: return
        if (med.id.isBlank()) return
        val p = prefs(c)
        val lastStamp = p.getLong(stampKey(med.id), 0L)
        if (event.timestamp in 1 until lastStamp) return

        val current = Store.load(c).toMutableList()
        when (event.type) {
            "program_deleted" -> current.removeAll { it.id == med.id }
            "program_added", "program_updated" -> {
                val index = current.indexOfFirst { it.id == med.id }
                if (index >= 0) current[index] = med else current.add(med)
            }
        }
        writeStore(c, current)
        p.edit().putLong(stampKey(med.id), event.timestamp).putBoolean(KEY_INITIALIZED, true).commit()
        saveBaseline(c, current)
        AlarmScheduler.scheduleAll(c, current, observeProgramChanges = false)
    }

    private fun writeStore(c: Context, meds: List<Medication>) {
        val a = JSONArray()
        meds.forEach { m ->
            a.put(JSONObject().put("id", m.id).put("name", m.name).put("dose", m.dose).put("times", JSONArray(m.times)))
        }
        c.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(STORE_MEDS, a.toString()).commit()
    }

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
