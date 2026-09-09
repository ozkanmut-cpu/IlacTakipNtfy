package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Separates the medication owner from the device/user performing an action.
 * Local medications keep living in Store; remote programs are cached here and never
 * enter the local alarm list.
 */
object OwnerScopeStore {
    private const val PREFS = "dosefolk_owner_scope"
    private const val KEY_REMOTE = "remote_programs"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun ownerKey(medicationId: String) = "owner|$medicationId"

    fun localOwnerId(c: Context): String = Store.topic(c)

    fun ownerOf(c: Context, medicationId: String): String =
        prefs(c).getString(ownerKey(medicationId), null) ?: localOwnerId(c)

    fun ownerFor(c: Context, medications: List<Medication>): String {
        val owners = medications.mapNotNull { med ->
            med.id.takeIf { it.isNotBlank() }?.let { ownerOf(c, it) }
        }.distinct()
        return owners.singleOrNull() ?: localOwnerId(c)
    }

    @Synchronized
    fun remember(c: Context, event: DoseEvent) {
        val owner = event.ownerId.ifBlank {
            if (event.type.startsWith("program_") || event.type == "alarm") event.actorTopic else ""
        }
        if (owner.isBlank()) return
        val edit = prefs(c).edit()
        event.medications.filter { it.id.isNotBlank() }.forEach { edit.putString(ownerKey(it.id), owner) }
        edit.commit()
    }

    fun remoteMedications(c: Context, ownerId: String): List<Medication> =
        loadRemote(c).filter { it.first == ownerId }.map { it.second }

    @Synchronized
    fun applyRemoteProgram(c: Context, ownerId: String, type: String, medication: Medication) {
        if (ownerId.isBlank() || medication.id.isBlank() || ownerId == localOwnerId(c)) return
        val all = loadRemote(c).toMutableList()
        when (type) {
            "program_deleted" -> all.removeAll { it.first == ownerId && it.second.id == medication.id }
            "program_added", "program_updated" -> {
                val index = all.indexOfFirst { it.first == ownerId && it.second.id == medication.id }
                val scoped = ownerId to medication
                if (index >= 0) all[index] = scoped else all += scoped
            }
        }
        saveRemote(c, all)
        prefs(c).edit().putString(ownerKey(medication.id), ownerId).commit()
    }

    private fun loadRemote(c: Context): List<Pair<String, Medication>> {
        val raw = prefs(c).getString(KEY_REMOTE, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val owner = o.optString("ownerId")
                val times = o.optJSONArray("times") ?: JSONArray()
                val med = Medication(
                    o.optString("id"), o.optString("name"), o.optString("dose"),
                    (0 until times.length()).map { times.optString(it) }.filter { it.isNotBlank() }
                )
                if (owner.isBlank() || med.id.isBlank()) null else owner to med
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRemote(c: Context, items: List<Pair<String, Medication>>) {
        val a = JSONArray()
        items.forEach { (owner, med) ->
            a.put(JSONObject()
                .put("ownerId", owner)
                .put("id", med.id)
                .put("name", med.name)
                .put("dose", med.dose)
                .put("times", JSONArray(med.times)))
        }
        prefs(c).edit().putString(KEY_REMOTE, a.toString()).commit()
    }
}
