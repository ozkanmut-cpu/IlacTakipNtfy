package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Keeps SGK history append-only while exposing only the newest cycle of each medication
 * as the active refill record. Older fills remain available in PrescriptionRecordStore.all().
 */
object PrescriptionLifecycle {
    private val dotDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun current(c: Context): List<PrescriptionRecord> = current(PrescriptionRecordStore.all(c))

    fun current(records: List<PrescriptionRecord>): List<PrescriptionRecord> =
        records.groupBy(::medicationKey)
            .values
            .mapNotNull { rows -> rows.maxWithOrNull(compareBy<PrescriptionRecord>({ cycleDate(it) }, { it.id })) }
            .sortedBy { it.medicationName.lowercase(Locale.getDefault()) }

    fun due(c: Context, today: LocalDate = LocalDate.now()): List<PrescriptionRecord> =
        current(c).filter { r ->
            r.continuous && r.eligibleDate()?.let { !it.isAfter(today) } == true
        }.sortedBy { it.eligibleDate() }

    fun upcoming(c: Context, today: LocalDate = LocalDate.now(), days: Long = 45): List<PrescriptionRecord> =
        current(c).filter { r ->
            if (!r.continuous) false else r.eligibleDate()?.let { d ->
                d.isAfter(today) && !d.isAfter(today.plusDays(days))
            } == true
        }.sortedBy { it.eligibleDate() }

    private fun medicationKey(r: PrescriptionRecord): String {
        if (r.medicationId.isNotBlank()) return "id:${r.medicationId}"
        return "name:${MedicationIdentity.canonical(r.medicationName)}"
    }

    private fun cycleDate(r: PrescriptionRecord): LocalDate =
        parse(r.fillDate) ?: parse(r.prescriptionDate) ?: r.endDate() ?: LocalDate.MIN

    private fun parse(value: String): LocalDate? {
        if (value.isBlank()) return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
            ?: runCatching { LocalDate.parse(value, dotDate) }.getOrNull()
    }
}
