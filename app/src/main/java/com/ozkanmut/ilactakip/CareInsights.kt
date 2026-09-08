package com.ozkanmut.ilactakip

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Operational summary for a caregiver handover. No clinical interpretation. */
data class HandoverDigest(
    val windowHours: Int,
    val taken: Int,
    val missed: Int,
    val snoozed: Int,
    val conflicts: Int,
    val unresolvedNow: Int,
    val recentLines: List<String>
)

data class RegimenDrift(
    val time: String,
    val snoozedDays: Int,
    val missedDays: Int,
    val observedDays: Int
)

object CareInsights {
    private val terminal = setOf(
        "taken", "missed", "conflict_resolved_taken", "conflict_resolved_missed"
    )
    private val stateTypes = terminal + setOf("snoozed", "alarm")

    /**
     * Summarizes the latest state of each dose session in the chosen window so
     * conflict resolution events do not double-count the same session.
     */
    fun handover(c: Context, hours: Int = 12): HandoverDigest {
        val safeHours = hours.coerceIn(1, 48)
        val cutoff = System.currentTimeMillis() - safeHours * 60L * 60L * 1000L
        val zone = ZoneId.systemDefault()
        val events = EventStore.load(c).filter { it.timestamp >= cutoff && it.type in stateTypes }

        val sessions = events.groupBy { event ->
            val day = Instant.ofEpochMilli(event.timestamp).atZone(zone).toLocalDate()
            "$day|${event.time}"
        }
        val latest = sessions.values.mapNotNull { group ->
            group.maxWithOrNull(compareBy<DoseEvent> { it.timestamp }.thenBy { it.revision }.thenBy { it.eventId })
        }

        val recentLines = latest
            .sortedByDescending { it.timestamp }
            .take(3)
            .map { event ->
                val names = event.medications.joinToString(", ") { it.name }
                val subject = if (names.isBlank()) event.time else "${event.time} • $names"
                val actor = event.actor.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                "$subject • ${label(event.type)}$actor"
            }

        return HandoverDigest(
            windowHours = safeHours,
            taken = latest.count { it.type == "taken" || it.type == "conflict_resolved_taken" },
            missed = latest.count { it.type == "missed" || it.type == "conflict_resolved_missed" },
            snoozed = latest.count { it.type == "snoozed" },
            conflicts = DoseStateEngine.today(c).count { it.status == DoseSessionStatus.CONFLICT },
            unresolvedNow = DoseStateEngine.unresolved(c).size,
            recentLines = recentLines
        )
    }

    /**
     * Detects repeated operational friction only. It never recommends a dose or
     * schedule change and never edits the medication program automatically.
     */
    fun regimenDrift(c: Context, days: Int = 7): List<RegimenDrift> {
        val safeDays = days.coerceIn(3, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val cutoffDay = today.minusDays((safeDays - 1).toLong())
        val events = EventStore.load(c).filter { event ->
            val day = Instant.ofEpochMilli(event.timestamp).atZone(zone).toLocalDate()
            !day.isBefore(cutoffDay) && !day.isAfter(today)
        }

        val byTime = events.groupBy { it.time }.filterKeys { it.isNotBlank() }
        return byTime.mapNotNull { (time, group) ->
            val snoozedDays = group.filter { it.type == "snoozed" }
                .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.distinct().size
            val missedDays = group.filter { it.type == "missed" || it.type == "conflict_resolved_missed" }
                .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.distinct().size
            val observedDays = group.filter { it.type == "alarm" }
                .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.distinct().size
                .coerceAtLeast(maxOf(snoozedDays, missedDays))

            if (snoozedDays >= 3 || missedDays >= 2) {
                RegimenDrift(time, snoozedDays, missedDays, observedDays)
            } else null
        }.sortedWith(compareByDescending<RegimenDrift> { it.missedDays }.thenByDescending { it.snoozedDays }.thenBy { it.time })
    }

    private fun label(type: String): String = if (I18n.language() == "tr") {
        when (type) {
            "taken", "conflict_resolved_taken" -> "içildi"
            "missed", "conflict_resolved_missed" -> "içilmedi"
            "snoozed" -> "ertelendi"
            else -> "hatırlatıldı"
        }
    } else {
        when (type) {
            "taken", "conflict_resolved_taken" -> "taken"
            "missed", "conflict_resolved_missed" -> "missed"
            "snoozed" -> "snoozed"
            else -> "reminded"
        }
    }
}
