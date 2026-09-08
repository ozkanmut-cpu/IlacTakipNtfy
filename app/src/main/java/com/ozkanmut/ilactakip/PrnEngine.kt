package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PrnMedication(
    val id: String,
    val medicationId: String,
    val name: String,
    val doseNote: String = "",
    val minimumIntervalMinutes: Int? = null,
    val maximumPerDay: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PrnCheck(
    val allowed: Boolean,
    val reason: String? = null,
    val lastTakenAt: Long? = null,
    val takenToday: Int = 0
)

/**
 * PRN rules are user-entered guardrails only. Dosefolk never invents clinical limits.
 * A missing limit means "not configured", not "safe without limit".
 */
object PrnEngine {
    private const val PREFS = "dosefolk_prn"
    private const val KEY = "items"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<PrnMedication> = load(c)

    @Synchronized
    fun upsert(c: Context, item: PrnMedication) {
        save(c, listOf(item) + load(c).filterNot { it.id == item.id })
    }

    @Synchronized
    fun create(
        c: Context,
        medication: Medication,
        minimumIntervalMinutes: Int? = null,
        maximumPerDay: Int? = null
    ): PrnMedication {
        val item = PrnMedication(
            id = UUID.randomUUID().toString(),
            medicationId = medication.id,
            name = medication.name,
            doseNote = medication.dose,
            minimumIntervalMinutes = minimumIntervalMinutes?.takeIf { it > 0 },
            maximumPerDay = maximumPerDay?.takeIf { it > 0 }
        )
        upsert(c, item)
        return item
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        save(c, load(c).filterNot { it.id == id })
    }

    fun check(c: Context, item: PrnMedication, now: Long = System.currentTimeMillis()): PrnCheck {
        val events = EventStore.load(c).filter { e ->
            e.type == "prn_taken" && e.medications.any { it.id == item.medicationId }
        }
        val last = events.maxByOrNull { it.timestamp }
        val startOfDay = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val todayCount = events.count { it.timestamp >= startOfDay }

        item.minimumIntervalMinutes?.let { min ->
            if (last != null && now - last.timestamp < min * 60_000L) {
                return PrnCheck(false, "minimum_interval", last.timestamp, todayCount)
            }
        }
        item.maximumPerDay?.let { max ->
            if (todayCount >= max) return PrnCheck(false, "daily_maximum", last?.timestamp, todayCount)
        }
        return PrnCheck(true, null, last?.timestamp, todayCount)
    }

    /** Records an explicitly user-confirmed PRN use; it never auto-administers. */
    fun recordTaken(c: Context, item: PrnMedication): PrnCheck {
        val check = check(c, item)
        if (!check.allowed) return check
        val med = Medication(item.medicationId, item.name, item.doseNote, emptyList())
        Ntfy.sendEvent(c, "prn_taken", "PRN", listOf(med))
        return check(c, item)
    }

    private fun load(c: Context): List<PrnMedication> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    PrnMedication(
                        id = o.optString("id"),
                        medicationId = o.optString("medicationId"),
                        name = o.optString("name"),
                        doseNote = o.optString("doseNote"),
                        minimumIntervalMinutes = if (o.has("minimumIntervalMinutes")) o.optInt("minimumIntervalMinutes") else null,
                        maximumPerDay = if (o.has("maximumPerDay")) o.optInt("maximumPerDay") else null,
                        createdAt = o.optLong("createdAt")
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, items: List<PrnMedication>) {
        val a = JSONArray()
        items.forEach { p ->
            val o = JSONObject()
                .put("id", p.id)
                .put("medicationId", p.medicationId)
                .put("name", p.name)
                .put("doseNote", p.doseNote)
                .put("createdAt", p.createdAt)
            p.minimumIntervalMinutes?.let { o.put("minimumIntervalMinutes", it) }
            p.maximumPerDay?.let { o.put("maximumPerDay", it) }
            a.put(o)
        }
        prefs(c).edit().putString(KEY, a.toString()).apply()
    }
}
