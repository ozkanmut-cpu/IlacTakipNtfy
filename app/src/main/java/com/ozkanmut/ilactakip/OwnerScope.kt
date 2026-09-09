package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object OwnerScopeStore {
    private const val PREFS = "dosefolk_owner_scope"
    private const val KEY_REMOTE = "remote_programs"
    private const val KEY_REMOTE_RULES = "remote_rules"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun ownerKey(medicationId: String) = "owner|$medicationId"
    private fun ruleStampKey(ownerId: String, medicationId: String) = "rule_stamp|$ownerId|$medicationId"
    private fun ruleRevisionKey(ownerId: String, medicationId: String) = "rule_rev|$ownerId|$medicationId"
    private fun ruleActorKey(ownerId: String, medicationId: String) = "rule_actor|$ownerId|$medicationId"
    private fun ruleEventKey(ownerId: String, medicationId: String) = "rule_event|$ownerId|$medicationId"

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

    fun remoteRule(c: Context, ownerId: String, medicationId: String): ProgramRule =
        loadRemoteRules(c).firstOrNull { it.ownerId == ownerId && it.rule.medicationId == medicationId }?.rule
            ?: ProgramRule(medicationId)

    @Synchronized
    fun applyRemoteProgram(c: Context, ownerId: String, type: String, medication: Medication) {
        if (ownerId.isBlank() || medication.id.isBlank() || ownerId == localOwnerId(c)) return
        val all = loadRemote(c).toMutableList()
        when (type) {
            "program_deleted" -> {
                all.removeAll { it.first == ownerId && it.second.id == medication.id }
                val rules = loadRemoteRules(c).filterNot { it.ownerId == ownerId && it.rule.medicationId == medication.id }
                saveRemoteRules(c, rules)
            }
            "program_added", "program_updated" -> {
                val index = all.indexOfFirst { it.first == ownerId && it.second.id == medication.id }
                val scoped = ownerId to medication
                if (index >= 0) all[index] = scoped else all += scoped
            }
        }
        saveRemote(c, all)
        prefs(c).edit().putString(ownerKey(medication.id), ownerId).commit()
    }

    @Synchronized
    fun applyRemoteRule(c: Context, ownerId: String, rule: ProgramRule, event: DoseEvent): Boolean {
        if (ownerId.isBlank() || rule.medicationId.isBlank() || ownerId == localOwnerId(c)) return false
        val p = prefs(c)
        val medId = rule.medicationId
        val storedRevision = p.getLong(ruleRevisionKey(ownerId, medId), 0L)
        val storedActor = p.getString(ruleActorKey(ownerId, medId), "").orEmpty()
        val storedEvent = p.getString(ruleEventKey(ownerId, medId), "").orEmpty()
        val legacyStamp = p.getLong(ruleStampKey(ownerId, medId), 0L)
        val accept = if (event.revision > 0L || storedRevision > 0L) {
            when {
                event.revision != storedRevision -> event.revision > storedRevision
                event.actorTopic != storedActor -> event.actorTopic > storedActor
                else -> event.eventId > storedEvent
            }
        } else event.timestamp >= legacyStamp
        if (!accept) return false

        val normalized = rule.copy(
            weekdays = rule.weekdays.filter { it in 1..7 }.toSet(),
            everyNDays = rule.everyNDays.coerceAtLeast(1)
        )
        val current = loadRemoteRules(c).toMutableList()
        val index = current.indexOfFirst { it.ownerId == ownerId && it.rule.medicationId == normalized.medicationId }
        val scoped = ScopedRule(ownerId, normalized)
        if (index >= 0) current[index] = scoped else current += scoped
        saveRemoteRules(c, current)
        p.edit()
            .putLong(ruleStampKey(ownerId, medId), event.timestamp)
            .putLong(ruleRevisionKey(ownerId, medId), event.revision)
            .putString(ruleActorKey(ownerId, medId), event.actorTopic)
            .putString(ruleEventKey(ownerId, medId), event.eventId)
            .putString(ownerKey(normalized.medicationId), ownerId)
            .commit()
        return true
    }

    @Synchronized
    fun applyRemoteRule(c: Context, ownerId: String, rule: ProgramRule, timestamp: Long): Boolean {
        val event = EventStore.load(c)
            .filter {
                it.type == "program_rule_updated" &&
                    it.ownerId == ownerId &&
                    it.timestamp == timestamp &&
                    it.medications.firstOrNull()?.id == rule.medicationId
            }
            .maxWithOrNull(DoseEventOrder.global)
            ?: DoseEvent(
                eventId = "legacy-rule-$ownerId-${rule.medicationId}-$timestamp",
                type = "program_rule_updated",
                time = "program",
                actor = "",
                actorTopic = "",
                timestamp = timestamp,
                medications = emptyList(),
                syncState = "synced",
                revision = 0L,
                ownerId = ownerId
            )
        return applyRemoteRule(c, ownerId, rule, event)
    }

    private data class ScopedRule(val ownerId: String, val rule: ProgramRule)

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

    private fun loadRemoteRules(c: Context): List<ScopedRule> {
        val raw = prefs(c).getString(KEY_REMOTE_RULES, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val owner = o.optString("ownerId")
                val medicationId = o.optString("medicationId")
                val days = o.optJSONArray("weekdays") ?: JSONArray()
                if (owner.isBlank() || medicationId.isBlank()) return@mapNotNull null
                ScopedRule(
                    owner,
                    ProgramRule(
                        medicationId = medicationId,
                        weekdays = (0 until days.length()).map { days.optInt(it) }.filter { it in 1..7 }.toSet(),
                        startDate = o.optString("startDate").takeIf { it.isNotBlank() },
                        endDate = o.optString("endDate").takeIf { it.isNotBlank() },
                        everyNDays = o.optInt("everyNDays", 1).coerceAtLeast(1),
                        anchorDate = o.optString("anchorDate").takeIf { it.isNotBlank() },
                        routineLabel = o.optString("routineLabel")
                    )
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRemoteRules(c: Context, items: List<ScopedRule>) {
        val a = JSONArray()
        items.forEach { item ->
            val r = item.rule
            a.put(JSONObject()
                .put("ownerId", item.ownerId)
                .put("medicationId", r.medicationId)
                .put("weekdays", JSONArray(r.weekdays.sorted()))
                .put("startDate", r.startDate ?: "")
                .put("endDate", r.endDate ?: "")
                .put("everyNDays", r.everyNDays)
                .put("anchorDate", r.anchorDate ?: "")
                .put("routineLabel", r.routineLabel))
        }
        prefs(c).edit().putString(KEY_REMOTE_RULES, a.toString()).commit()
    }
}
