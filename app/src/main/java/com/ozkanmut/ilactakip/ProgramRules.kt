package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Optional operational scheduling constraints. Prescription details are never inferred. */
data class ProgramRule(
    val medicationId: String,
    val weekdays: Set<Int> = emptySet(),
    val startDate: String? = null,
    val endDate: String? = null,
    val everyNDays: Int = 1,
    val anchorDate: String? = null,
    val routineLabel: String = ""
)

object ProgramRuleStore {
    private const val PREFS = "dosefolk_program_rules"
    private const val KEY = "rules"
    private const val STAMP_PREFIX = "stamp|"
    private const val REV_PREFIX = "rev|"
    private const val ACTOR_PREFIX = "actor|"
    private const val EVENT_PREFIX = "event|"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, medicationId: String): ProgramRule =
        load(c).firstOrNull { it.medicationId == medicationId } ?: ProgramRule(medicationId)

    @Synchronized
    fun save(c: Context, rule: ProgramRule) {
        val normalized = normalize(rule)
        val before = get(c, normalized.medicationId)
        persist(c, listOf(normalized) + load(c).filterNot { it.medicationId == normalized.medicationId })
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
        if (before != normalized) {
            val now = System.currentTimeMillis()
            prefs(c).edit().putLong(STAMP_PREFIX + normalized.medicationId, now).commit()
            val medName = Store.load(c).firstOrNull { it.id == normalized.medicationId }?.name ?: "Program"
            val carrier = Medication(normalized.medicationId, medName, encode(normalized).toString(), emptyList())
            Ntfy.sendEvent(c, "program_rule_updated", "program", listOf(carrier))
        }
    }

    @Synchronized
    fun applyRemote(c: Context, event: DoseEvent) {
        if (event.type != "program_rule_updated") return
        val carrier = event.medications.firstOrNull() ?: return
        if (carrier.id.isBlank() || carrier.dose.isBlank()) return
        val rule = runCatching { decode(JSONObject(carrier.dose)) }.getOrNull() ?: return
        if (rule.medicationId != carrier.id) return
        val ownerId = event.ownerId.ifBlank { event.actorTopic }
        if (ownerId.isNotBlank() && ownerId != OwnerScopeStore.localOwnerId(c)) {
            OwnerScopeStore.applyRemoteRule(c, ownerId, rule, event)
            return
        }

        val p = prefs(c)
        val id = rule.medicationId
        val storedRevision = p.getLong(REV_PREFIX + id, 0L)
        val storedActor = p.getString(ACTOR_PREFIX + id, "").orEmpty()
        val storedEvent = p.getString(EVENT_PREFIX + id, "").orEmpty()
        val lastStamp = p.getLong(STAMP_PREFIX + id, 0L)
        val accept = if (event.revision > 0L || storedRevision > 0L) {
            when {
                event.revision != storedRevision -> event.revision > storedRevision
                event.actorTopic != storedActor -> event.actorTopic > storedActor
                else -> event.eventId > storedEvent
            }
        } else event.timestamp >= lastStamp
        if (!accept) return

        val normalized = normalize(rule)
        persist(c, listOf(normalized) + load(c).filterNot { it.medicationId == normalized.medicationId })
        p.edit()
            .putLong(STAMP_PREFIX + normalized.medicationId, event.timestamp)
            .putLong(REV_PREFIX + normalized.medicationId, event.revision)
            .putString(ACTOR_PREFIX + normalized.medicationId, event.actorTopic)
            .putString(EVENT_PREFIX + normalized.medicationId, event.eventId)
            .commit()
        AlarmScheduler.scheduleAll(c, Store.load(c), observeProgramChanges = false)
    }

    fun isActiveOn(c: Context, medicationId: String, date: LocalDate): Boolean {
        val r = get(c, medicationId)
        val start = r.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val end = r.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (start != null && date.isBefore(start)) return false
        if (end != null && date.isAfter(end)) return false
        if (r.weekdays.isNotEmpty() && date.dayOfWeek.value !in r.weekdays) return false
        if (r.everyNDays > 1) {
            val anchor = r.anchorDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: start ?: LocalDate.now()
            val delta = java.time.temporal.ChronoUnit.DAYS.between(anchor, date)
            if (delta < 0 || delta % r.everyNDays != 0L) return false
        }
        return true
    }

    fun nextActiveDate(c: Context, medication: Medication, from: LocalDate, maxDays: Int = 370): LocalDate? {
        repeat(maxDays.coerceAtLeast(1)) { offset ->
            val d = from.plusDays(offset.toLong())
            if (isActiveOn(c, medication.id, d)) return d
        }
        return null
    }

    fun describe(c: Context, medicationId: String): String = describeRule(get(c, medicationId))

    fun describeRule(r: ProgramRule): String {
        val parts = mutableListOf<String>()
        if (r.everyNDays > 1) parts += if (I18n.language() == "tr") "${r.everyNDays} günde bir" else "Every ${r.everyNDays} days"
        if (r.weekdays.isNotEmpty()) {
            val names = r.weekdays.sorted().map { DayOfWeek.of(it).name.take(3) }
            parts += names.joinToString(" · ")
        }
        r.startDate?.let { parts += if (I18n.language() == "tr") "Başlangıç $it" else "Starts $it" }
        r.endDate?.let { parts += if (I18n.language() == "tr") "Bitiş $it" else "Ends $it" }
        if (r.routineLabel.isNotBlank()) parts += r.routineLabel
        return parts.joinToString(" • ")
    }

    fun normalizeForSync(rule: ProgramRule): ProgramRule = normalize(rule)
    fun encode(rule: ProgramRule): JSONObject = toJson(normalize(rule))
    fun decode(o: JSONObject): ProgramRule = normalize(fromJson(o))

    private fun normalize(rule: ProgramRule) = rule.copy(
        weekdays = rule.weekdays.filter { it in 1..7 }.toSet(),
        everyNDays = rule.everyNDays.coerceAtLeast(1)
    )

    private fun toJson(r: ProgramRule) = JSONObject()
        .put("medicationId", r.medicationId)
        .put("weekdays", JSONArray(r.weekdays.sorted()))
        .put("startDate", r.startDate ?: "")
        .put("endDate", r.endDate ?: "")
        .put("everyNDays", r.everyNDays)
        .put("anchorDate", r.anchorDate ?: "")
        .put("routineLabel", r.routineLabel)

    private fun fromJson(o: JSONObject): ProgramRule {
        val days = o.optJSONArray("weekdays") ?: JSONArray()
        return ProgramRule(
            medicationId = o.optString("medicationId"),
            weekdays = (0 until days.length()).map { days.optInt(it) }.filter { it in 1..7 }.toSet(),
            startDate = o.optString("startDate").takeIf { it.isNotBlank() },
            endDate = o.optString("endDate").takeIf { it.isNotBlank() },
            everyNDays = o.optInt("everyNDays", 1).coerceAtLeast(1),
            anchorDate = o.optString("anchorDate").takeIf { it.isNotBlank() },
            routineLabel = o.optString("routineLabel")
        )
    }

    private fun load(c: Context): List<ProgramRule> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
                .filter { it.medicationId.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun persist(c: Context, rules: List<ProgramRule>) {
        val a = JSONArray()
        rules.forEach { a.put(toJson(it)) }
        prefs(c).edit().putString(KEY, a.toString()).commit()
    }
}
