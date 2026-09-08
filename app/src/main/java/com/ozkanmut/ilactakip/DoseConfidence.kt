package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate

data class DoseConfidencePoint(
    val date: LocalDate,
    val time: String,
    val score: Int,
    val status: DoseSessionStatus
)

data class DoseConfidenceSummary(
    val average: Int,
    val points: List<DoseConfidencePoint>,
    val uncertainCount: Int
)

/**
 * Confidence is operational evidence quality, not a medical judgement.
 * It only describes how certain Dosefolk is about the recorded dose state.
 */
object DoseConfidenceEngine {
    fun score(state: DoseSessionState): Int = when (state.status) {
        DoseSessionStatus.TAKEN, DoseSessionStatus.MISSED -> {
            val explicit = state.latestEvent?.type?.startsWith("conflict_resolved_") == true
            if (explicit) 100 else 95
        }
        DoseSessionStatus.SNOOZED -> 70
        DoseSessionStatus.PENDING -> 45
        DoseSessionStatus.CONFLICT -> 15
        DoseSessionStatus.UNKNOWN -> 30
    }

    fun recent(c: Context, days: Int = 7): DoseConfidenceSummary {
        val safeDays = days.coerceIn(1, 30)
        val today = LocalDate.now()
        val meds = Store.load(c)
        val points = mutableListOf<DoseConfidencePoint>()

        for (offset in 0 until safeDays) {
            val date = today.minusDays(offset.toLong())
            val active = meds.filter { ProgramRuleStore.isActiveOn(c, it.id, date) }
            val times = active.flatMap { it.times }.distinct().sorted()
            times.forEach { time ->
                val state = DoseStateEngine.stateForTime(c, time, date)
                points += DoseConfidencePoint(date, time, score(state), state.status)
            }
        }

        val average = if (points.isEmpty()) 100 else points.sumOf { it.score } / points.size
        val uncertain = points.count { it.score < 60 }
        return DoseConfidenceSummary(average, points.sortedWith(compareBy({ it.date }, { it.time })), uncertain)
    }
}
