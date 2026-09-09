package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

object NaturalActionRouter {
    private data class PendingProgramChange(
        val medicationId: String,
        val medicationName: String,
        val time: String,
        val weekdays: Set<Int>
    )

    @Volatile private var pending: PendingProgramChange? = null

    fun handle(c: Context, raw: String): String? {
        val q = raw.trim()
        if (q.isBlank()) return null
        val lower = q.lowercase(Locale.getDefault())

        if (pending != null && lower in setOf("evet", "onayla", "tamam", "yes", "confirm", "ok")) {
            val p = pending ?: return null
            pending = null
            return applyProgramChange(c, p)
        }
        if (pending != null && lower in setOf("hayır", "hayir", "iptal", "no", "cancel")) {
            pending = null
            return tr("Değişiklik iptal edildi.", "Change cancelled.")
        }

        parseDoseCorrection(c, lower)?.let { return it }

        parseProgramChange(c, q)?.let { change ->
            pending = change
            val days = if (change.weekdays.isEmpty()) tr("her gün", "every day") else weekdayLabel(change.weekdays)
            return tr(
                "${change.medicationName} programını $days ${change.time} olarak değiştireceğim. Onaylıyor musun?",
                "I'll change ${change.medicationName} to $days at ${change.time}. Confirm?"
            )
        }

        parseNewBox(c, lower)?.let { return it }
        return null
    }

    private fun parseDoseCorrection(c: Context, lower: String): String? {
        val timeMatch = Regex("(?<!\\d)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\d)").find(lower)
        val time = timeMatch?.let { String.format("%02d:%02d", it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?: DoseStateEngine.today(c).filter { it.status == DoseSessionStatus.TAKEN || it.status == DoseSessionStatus.MISSED }.maxByOrNull { it.latestEvent?.timestamp ?: 0L }?.time
            ?: return null
        val date = LocalDate.now().toString()
        return when {
            listOf("geri al", "gerial", "undo").any { lower.contains(it) } -> {
                if (DoseCorrectionEngine.undo(c, time, date)) tr("$time kaydı geri alındı.", "$time record was undone.") else tr("$time için geri alınabilir bir kayıt yok.", "There is no undoable record for $time.")
            }
            listOf("içildi olarak düzelt", "icildi olarak duzelt", "içtim olarak düzelt", "taken olarak düzelt", "correct to taken").any { lower.contains(it) } -> {
                if (DoseCorrectionEngine.correctToTaken(c, time, date)) tr("$time kaydı İçildi olarak düzeltildi.", "$time was corrected to Taken.") else tr("$time kaydı düzeltilemedi.", "$time could not be corrected.")
            }
            listOf("içilmedi olarak düzelt", "icilmedi olarak duzelt", "missed olarak düzelt", "correct to missed").any { lower.contains(it) } -> {
                if (DoseCorrectionEngine.correctToMissed(c, time, date)) tr("$time kaydı İçilmedi olarak düzeltildi.", "$time was corrected to Missed.") else tr("$time kaydı düzeltilemedi.", "$time could not be corrected.")
            }
            else -> null
        }
    }

    private fun parseProgramChange(c: Context, raw: String): PendingProgramChange? {
        val lower = raw.lowercase(Locale.getDefault())
        val actionWords = listOf("ayarla", "değiştir", "degistir", "taşı", "tasi", "al", "set ", "change", "move")
        if (actionWords.none { lower.contains(it) }) return null

        val med = Store.load(c).sortedByDescending { it.name.length }.firstOrNull { lower.contains(it.name.lowercase(Locale.getDefault())) } ?: return null
        val timeMatch = Regex("(?<!\\d)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\d)").find(lower) ?: return null
        val h = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val m = timeMatch.groupValues[2].toIntOrNull() ?: return null
        val time = LocalTime.of(h, m).toString()

        val map = linkedMapOf(
            1 to listOf("pazartesi", "monday", "mon"),
            2 to listOf("salı", "sali", "tuesday", "tue"),
            3 to listOf("çarşamba", "carsamba", "wednesday", "wed"),
            4 to listOf("perşembe", "persembe", "thursday", "thu"),
            5 to listOf("cuma", "friday", "fri"),
            6 to listOf("cumartesi", "saturday", "sat"),
            7 to listOf("pazar", "sunday", "sun")
        )
        val weekdays = map.filterValues { names -> names.any { lower.contains(it) } }.keys.toSet()
        return PendingProgramChange(med.id, med.name, time, weekdays)
    }

    private fun applyProgramChange(c: Context, change: PendingProgramChange): String {
        val meds = Store.load(c)
        val med = meds.firstOrNull { it.id == change.medicationId } ?: return tr("İlaç artık bulunamıyor.", "Medication is no longer available.")
        Store.save(c, meds.map { if (it.id == med.id) it.copy(times = listOf(change.time)) else it })
        val oldRule = ProgramRuleStore.get(c, med.id)
        ProgramRuleStore.save(c, oldRule.copy(weekdays = change.weekdays))
        return tr("${med.name} güncellendi: ${weekdayLabel(change.weekdays)} ${change.time}.", "${med.name} updated: ${weekdayLabel(change.weekdays)} ${change.time}.")
    }

    private fun parseNewBox(c: Context, lower: String): String? {
        listOf("yeni kutu", "new box").firstOrNull { lower.contains(it) } ?: return null
        val med = Store.load(c).sortedByDescending { it.name.length }.firstOrNull { lower.contains(it.name.lowercase(Locale.getDefault())) } ?: return null
        val updated = StockEngine.openNewBox(c, med.id)
            ?: return tr("${med.name} için önce paket boyutunu stok ekranında bir kez tanımla.", "Set the pack size for ${med.name} once in Stock first.")
        return tr("${med.name}: yeni kutu eklendi. Kalan ${updated.remainingDoses} doz.", "${med.name}: new box added. ${updated.remainingDoses} doses remaining.")
    }

    private fun weekdayLabel(days: Set<Int>): String {
        if (days.isEmpty()) return tr("her gün", "every day")
        val trNames = mapOf(1 to "Pzt",2 to "Sal",3 to "Çar",4 to "Per",5 to "Cum",6 to "Cmt",7 to "Paz")
        val enNames = mapOf(1 to "Mon",2 to "Tue",3 to "Wed",4 to "Thu",5 to "Fri",6 to "Sat",7 to "Sun")
        val names = if (I18n.language() == "tr") trNames else enNames
        return days.sorted().joinToString(" · ") { names[it].orEmpty() }
    }

    private fun tr(tr: String, en: String) = if (I18n.language() == "tr") tr else en
}
