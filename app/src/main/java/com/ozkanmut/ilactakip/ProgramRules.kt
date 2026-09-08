package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Optional operational scheduling constraints. Prescription details are never inferred. */
data class ProgramRule(
    val medicationId: String,
    val weekdays: Set<Int> = emptySet(), // java.time DayOfWeek values 1..7; empty = every day
    val startDate: String? = null,
    val endDate: String? = null,
    val everyNDays: Int = 1,
    val anchorDate: String? = null,
    val routineLabel: String = ""
)

object ProgramRuleStore {
    private const val PREFS = "dosefolk_program_rules"
    private const val KEY = "rules"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, medicationId: String): ProgramRule =
        load(c).firstOrNull { it.medicationId == medicationId } ?: ProgramRule(medicationId)

    @Synchronized
    fun save(c: Context, rule: ProgramRule) {
        val normalized = rule.copy(
            weekdays = rule.weekdays.filter { it in 1..7 }.toSet(),
            everyNDays = rule.everyNDays.coerceAtLeast(1)
        )
        persist(c, listOf(normalized) + load(c).filterNot { it.medicationId == rule.medicationId })
        AlarmScheduler.scheduleAll(c, Store.load(c))
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

    fun describe(c: Context, medicationId: String): String {
        val r = get(c, medicationId)
        val parts = mutableListOf<String>()
        if (r.everyNDays > 1) parts += if (I18n.language() == "tr") "${r.everyNDays} günde bir" else "Every ${r.everyNDays} days"
        if (r.weekdays.isNotEmpty()) {
            val names = r.weekdays.sorted().map { DayOfWeek.of(it).name.take(3) }
            parts += names.joinToString(" · ")
        }
        if (!r.routineLabel.isNullOrBlank()) parts += r.routineLabel
        return parts.joinToString(" • ")
    }

    private fun load(c: Context): List<ProgramRule> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val days = o.optJSONArray("weekdays") ?: JSONArray()
                ProgramRule(
                    medicationId = o.optString("medicationId"),
                    weekdays = (0 until days.length()).map { days.optInt(it) }.filter { it in 1..7 }.toSet(),
                    startDate = o.optString("startDate").takeIf { it.isNotBlank() },
                    endDate = o.optString("endDate").takeIf { it.isNotBlank() },
                    everyNDays = o.optInt("everyNDays", 1).coerceAtLeast(1),
                    anchorDate = o.optString("anchorDate").takeIf { it.isNotBlank() },
                    routineLabel = o.optString("routineLabel")
                )
            }.filter { it.medicationId.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun persist(c: Context, rules: List<ProgramRule>) {
        val a = JSONArray()
        rules.forEach { r ->
            a.put(JSONObject()
                .put("medicationId", r.medicationId)
                .put("weekdays", JSONArray(r.weekdays.sorted()))
                .put("startDate", r.startDate ?: "")
                .put("endDate", r.endDate ?: "")
                .put("everyNDays", r.everyNDays)
                .put("anchorDate", r.anchorDate ?: "")
                .put("routineLabel", r.routineLabel))
        }
        prefs(c).edit().putString(KEY, a.toString()).apply()
    }
}
