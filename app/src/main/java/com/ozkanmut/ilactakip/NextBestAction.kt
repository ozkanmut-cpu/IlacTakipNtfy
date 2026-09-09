package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

enum class NextActionKind { REPAIR, RESOLVE_CONFLICT, HANDLE_DOSE, UPCOMING, ALL_GOOD }

data class NextBestAction(
    val kind: NextActionKind,
    val title: String,
    val detail: String,
    val time: String? = null,
    val medications: List<Medication> = emptyList(),
    val scheduledDate: String = LocalDate.now().toString()
)

/**
 * Dosefolk's low-attention home policy: show one useful thing, not a dashboard.
 * Clinical decisions are never inferred here; this only prioritizes deterministic app state.
 */
object NextBestActionEngine {
    fun calculate(c: Context, meds: List<Medication>): NextBestAction {
        val issue = DosefolkCheck.issues(c).firstOrNull()
        if (issue != null) return NextBestAction(
            NextActionKind.REPAIR,
            issue.title,
            issue.detail
        )

        val states = DoseStateEngine.today(c)
        states.firstOrNull { it.status == DoseSessionStatus.CONFLICT }?.let { s ->
            return NextBestAction(
                NextActionKind.RESOLVE_CONFLICT,
                uiText("Çelişkili kayıt var", "Conflicting records"),
                uiText("${s.time} dozu için iki farklı durum kaydedildi.", "Two different states were recorded for the ${s.time} dose."),
                s.time,
                s.medications,
                s.scheduledDate
            )
        }

        states.firstOrNull {
            it.status == DoseSessionStatus.PENDING || it.status == DoseSessionStatus.SNOOZED
        }?.let { s ->
            return NextBestAction(
                NextActionKind.HANDLE_DOSE,
                uiText("Şimdi ilgilen", "Needs attention now"),
                s.medications.joinToString(", ") { it.name }.ifBlank { uiText("İlaç zamanı", "Medication time") },
                s.time,
                s.medications,
                s.scheduledDate
            )
        }

        nextScheduled(meds)?.let { (time, dueMeds, minutes) ->
            val detail = if (minutes <= 60) {
                uiText("$minutes dakika sonra", "In $minutes minutes")
            } else {
                dueMeds.joinToString(", ") { it.name }
            }
            return NextBestAction(NextActionKind.UPCOMING, time, detail, time, dueMeds)
        }

        return NextBestAction(
            NextActionKind.ALL_GOOD,
            uiText("Her şey yolunda", "Everything is on track"),
            uiText("Şu anda senden gereken bir şey yok.", "Nothing needs your attention right now.")
        )
    }

    private fun nextScheduled(meds: List<Medication>): Triple<String, List<Medication>, Long>? {
        val now = LocalTime.now()
        return meds.flatMap { med -> med.times.mapNotNull { raw ->
            runCatching { LocalTime.parse(raw) }.getOrNull()?.let { t -> Triple(raw, med, ChronoUnit.MINUTES.between(now, t)) }
        } }
            .filter { it.third >= 0 }
            .groupBy { it.first }
            .map { (time, rows) -> Triple(time, rows.map { it.second }, rows.minOf { it.third }) }
            .minByOrNull { it.third }
    }

    private fun uiText(tr: String, en: String) = if (I18n.language() == "tr") tr else en
}
