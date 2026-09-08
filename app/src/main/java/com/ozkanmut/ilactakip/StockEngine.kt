package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MedicationStock(
    val medicationId: String,
    val medicationName: String,
    val remainingDoses: Int,
    val packSize: Int,
    val lowThreshold: Int = 5,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Dose-based inventory. It never guesses package size; users configure it once, then New Box can reuse it. */
object StockEngine {
    private const val PREFS = "dosefolk_stock"
    private const val KEY_STOCK = "stock"
    private const val KEY_PROCESSED = "processed_events"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<MedicationStock> = load(c)
    fun forMedication(c: Context, medicationId: String): MedicationStock? = load(c).firstOrNull { it.medicationId == medicationId }

    @Synchronized
    fun configure(c: Context, medication: Medication, packSize: Int, currentDoses: Int = packSize, lowThreshold: Int = 5) {
        if (packSize <= 0) return
        val stock = MedicationStock(medication.id, medication.name, currentDoses.coerceAtLeast(0), packSize, lowThreshold.coerceAtLeast(0))
        save(c, listOf(stock) + load(c).filterNot { it.medicationId == medication.id })
    }

    @Synchronized
    fun openNewBox(c: Context, medicationId: String): MedicationStock? {
        val current = forMedication(c, medicationId) ?: return null
        val updated = current.copy(remainingDoses = current.remainingDoses + current.packSize, updatedAt = System.currentTimeMillis())
        save(c, listOf(updated) + load(c).filterNot { it.medicationId == medicationId })
        return updated
    }

    fun lowStock(c: Context): List<MedicationStock> = load(c).filter { it.remainingDoses <= it.lowThreshold }

    /** Idempotent across devices: an event ID can reduce stock only once on this device. */
    @Synchronized
    fun applyEvent(c: Context, event: DoseEvent) {
        if (event.type !in setOf("taken", "prn_taken") || alreadyProcessed(c, event.eventId)) return
        val current = load(c).associateBy { it.medicationId }.toMutableMap()
        var changed = false
        event.medications.distinctBy { it.id }.forEach { med ->
            val stock = current[med.id] ?: return@forEach
            current[med.id] = stock.copy(
                remainingDoses = (stock.remainingDoses - 1).coerceAtLeast(0),
                updatedAt = event.timestamp
            )
            changed = true
        }
        if (changed) save(c, current.values.toList())
        markProcessed(c, event.eventId)
    }

    private fun alreadyProcessed(c: Context, eventId: String): Boolean = processed(c).contains(eventId)
    private fun processed(c: Context): Set<String> {
        val raw = prefs(c).getString(KEY_PROCESSED, "[]") ?: "[]"
        return runCatching { val a = JSONArray(raw); (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
    }
    private fun markProcessed(c: Context, eventId: String) {
        val ids = (listOf(eventId) + processed(c)).distinct().take(2000)
        prefs(c).edit().putString(KEY_PROCESSED, JSONArray(ids).toString()).apply()
    }
    private fun load(c: Context): List<MedicationStock> {
        val raw = prefs(c).getString(KEY_STOCK, "[]") ?: "[]"
        return runCatching { val a = JSONArray(raw); (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> MedicationStock(o.optString("medicationId"),o.optString("medicationName"),o.optInt("remainingDoses"),o.optInt("packSize"),o.optInt("lowThreshold",5),o.optLong("updatedAt")) } } }.getOrDefault(emptyList())
    }
    private fun save(c: Context, values: List<MedicationStock>) {
        val a = JSONArray(); values.forEach { s -> a.put(JSONObject().put("medicationId",s.medicationId).put("medicationName",s.medicationName).put("remainingDoses",s.remainingDoses).put("packSize",s.packSize).put("lowThreshold",s.lowThreshold).put("updatedAt",s.updatedAt)) }
        prefs(c).edit().putString(KEY_STOCK,a.toString()).apply()
    }
}
