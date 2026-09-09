package com.ozkanmut.ilactakip

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Reconciles newly imported SGK cycles with medication inventory.
 * Each SGK cycle is applied at most once. Existing historical cycles are never re-added.
 */
object SgkStockAutoImporter {
    private const val PREFS = "dosefolk_sgk_stock_import"
    private const val KEY_APPLIED = "applied_cycles"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_REVIEW = "review_cycles"
    private const val PRESCRIPTION_PREFS = "dosefolk_prescription_tracker"
    private val dotDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var reconciling = false

    fun start(c: Context) {
        if (listener != null) return
        synchronized(this) {
            if (listener != null) return
            val context = c.applicationContext
            val ownPrefs = prefs(context)
            if (!ownPrefs.getBoolean(KEY_INITIALIZED, false)) {
                val baseline = PrescriptionRecordStore.all(context).map(::cycleKey).toSet()
                ownPrefs.edit().putStringSet(KEY_APPLIED, baseline).putBoolean(KEY_INITIALIZED, true).commit()
            }
            val p = context.getSharedPreferences(PRESCRIPTION_PREFS, Context.MODE_PRIVATE)
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "records" && !reconciling) {
                    reconcile(context)
                    PrescriptionNotifier.evaluate(context)
                }
            }
            p.registerOnSharedPreferenceChangeListener(l)
            listener = l
        }
    }

    /** Number of new SGK cycles whose stock amount could not be derived safely. */
    fun reviewCount(c: Context): Int = prefs(c).getStringSet(KEY_REVIEW, emptySet()).orEmpty().size

    @Synchronized
    fun reconcile(c: Context): Int {
        if (reconciling) return 0
        reconciling = true
        try {
            val context = c.applicationContext
            val records = PrescriptionRecordStore.all(context)
            if (records.isEmpty()) return 0
            val applied = prefs(context).getStringSet(KEY_APPLIED, emptySet()).orEmpty().toMutableSet()
            val review = prefs(context).getStringSet(KEY_REVIEW, emptySet()).orEmpty().toMutableSet()
            var changed = 0

            records.sortedBy { parse(it.fillDate) ?: parse(it.prescriptionDate) ?: it.endDate() ?: LocalDate.MIN }.forEach { r ->
                val cycle = cycleKey(r)
                if (cycle in applied) return@forEach

                // Mark before nested preference writes so callbacks cannot add the same cycle twice.
                applied += cycle
                prefs(context).edit().putStringSet(KEY_APPLIED, keepRecent(applied)).commit()

                val meds = Store.load(context)
                var med = r.medicationId.takeIf { it.isNotBlank() }?.let { id -> meds.firstOrNull { it.id == id } }
                    ?: meds.firstOrNull { MedicationIdentity.same(it.name, r.medicationName) }

                if (med == null) {
                    med = Medication(UUID.randomUUID().toString(), r.medicationName.trim(), r.dosePattern, emptyList())
                    Store.save(context, meds + med)
                    val suggestion = MedicationSuggestionEngine.suggest(r.medicationName)
                    MedicationMetaStore.save(context, MedicationMeta(
                        medicationId = med.id,
                        form = suggestion.form,
                        quantity = doseQuantity(r.dosePattern),
                        packageCount = suggestion.packageCount,
                        packageUnit = suggestion.packageUnit,
                        source = "sgk_pdf",
                        doseUnitOverride = suggestion.doseUnit
                    ))
                    PrescriptionRecordStore.update(context, r.copy(medicationId = med.id))
                } else if (r.medicationId != med.id) {
                    PrescriptionRecordStore.update(context, r.copy(medicationId = med.id))
                }

                val units = estimateSupplyUnits(context, med, r)
                if (units != null && units > 0) {
                    StockEngine.addSupply(context, med, units)
                    review.remove(cycle)
                    changed++
                } else {
                    // Keep the prescription import, but never invent an inventory amount.
                    review += cycle
                }
            }

            prefs(context).edit()
                .putStringSet(KEY_APPLIED, keepRecent(applied))
                .putStringSet(KEY_REVIEW, keepRecent(review))
                .commit()
            return changed
        } finally {
            reconciling = false
        }
    }

    private fun keepRecent(values: Set<String>): Set<String> {
        val list = values.toList()
        return if (list.size > 4000) list.subList(list.size - 4000, list.size).toSet() else values
    }

    private fun estimateSupplyUnits(c: Context, med: Medication, r: PrescriptionRecord): Int? {
        val meta = MedicationMetaStore.get(c, med.id)
        val boxes = r.boxCount?.takeIf { it > 0 }

        if (meta?.form == MedicationForm.INSULIN) {
            val daily = dailyDoseUnits(r.dosePattern, r.period) ?: return null
            val start = parse(r.fillDate) ?: parse(r.prescriptionDate) ?: return null
            val end = r.endDate() ?: return null
            val days = (ChronoUnit.DAYS.between(start, end) + 1).coerceAtLeast(1)
            return (daily * days).roundToInt().coerceAtLeast(1)
        }

        // For SGK-created metadata, only auto-add stock when package contents are explicitly
        // present in the medication name (e.g. "28 FILM TABLET"). A suggestion alone is not
        // strong enough evidence. User-entered/confirmed metadata remains authoritative.
        val explicitPack = explicitPackageCount(r.medicationName)
        val confirmedPack = meta?.packageCount?.takeIf { it > 0 && meta.source != "sgk_pdf" }
        val pack = explicitPack ?: confirmedPack
        return if (boxes != null && pack != null) boxes * pack else null
    }

    internal fun explicitPackageCount(name: String): Int? {
        val normalized = name.uppercase()
            .replace('İ', 'I')
            .replace('Ş', 'S')
            .replace('Ü', 'U')
            .replace('Ö', 'O')
            .replace('Ç', 'C')
            .replace('Ğ', 'G')
        val form = "(?:FILM\\s*)?(?:TABLET|TB|KAPSUL|KAPSULE|CAPSULE|AMPUL|FLAKON|NEBUL|NEBULE|PATCH|YAMA)"
        val matches = Regex("\\b(\\d{1,4})\\s*(?:ADET\\s*)?$form\\b").findAll(normalized).toList()
        if (matches.size != 1) return null
        return matches.single().groupValues[1].toIntOrNull()?.takeIf { it in 1..1000 }
    }

    private fun dailyDoseUnits(pattern: String, period: String): Double? {
        val m = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*x\\s*(\\d+(?:[.,]\\d+)?)").find(pattern) ?: return null
        val times = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val qty = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        val every = Regex("(?i)(\\d+)\\s*(günde|gunde|day)").find(period)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return times * qty / every.coerceAtLeast(1.0)
    }

    private fun doseQuantity(pattern: String): Double? = Regex("(?i)\\d+(?:[.,]\\d+)?\\s*x\\s*(\\d+(?:[.,]\\d+)?)")
        .find(pattern)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()

    private fun cycleKey(r: PrescriptionRecord) = listOf(MedicationIdentity.canonical(r.medicationName), r.prescriptionNo, r.fillDate, r.doseEndDate).joinToString("|")
    private fun parse(v: String): LocalDate? = runCatching { LocalDate.parse(v) }.getOrNull() ?: runCatching { LocalDate.parse(v, dotDate) }.getOrNull()
}
