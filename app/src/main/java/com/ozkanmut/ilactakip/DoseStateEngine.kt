package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

enum class DoseSessionStatus {
    PENDING,
    SNOOZED,
    TAKEN,
    MISSED,
    CONFLICT,
    UNKNOWN
}

data class DoseSessionState(
    val time: String,
    val status: DoseSessionStatus,
    val latestEvent: DoseEvent?,
    val medications: List<Medication>,
    val conflictEvents: List<DoseEvent> = emptyList()
)

object DoseStateEngine {
    private val stateTypes = setOf(
        "alarm", "snoozed", "taken", "missed",
        "conflict_resolved_taken", "conflict_resolved_missed"
    )

    private fun todayStartMillis(): Long = LocalDate.now()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun ordered(events: List<DoseEvent>) = events.sortedWith(
        compareBy<DoseEvent> { it.timestamp }.thenBy { it.actorTopic }.thenBy { it.revision }.thenBy { it.eventId }
    )

    fun stateForTime(c: Context, time: String): DoseSessionState {
        val scheduleMeds = Store.load(c).filter { time in it.times && ProgramRuleStore.isActiveOn(c, it.id, LocalDate.now()) }
        val events = ordered(EventStore.load(c)
            .filter { it.timestamp >= todayStartMillis() && it.time == time })
        return reduce(time, events, scheduleMeds)
    }

    fun today(c: Context): List<DoseSessionState> {
        val events = EventStore.load(c).filter { it.timestamp >= todayStartMillis() }
        val today = LocalDate.now()
        val schedule = Store.load(c).filter { ProgramRuleStore.isActiveOn(c, it.id, today) }
        val eventTimes = events.map { it.time }.filter { it.isNotBlank() }
        val scheduleTimes = schedule.flatMap { it.times }
        return (eventTimes + scheduleTimes).distinct().sorted().map { time ->
            reduce(
                time = time,
                events = ordered(events.filter { it.time == time }),
                scheduleMeds = schedule.filter { time in it.times }
            )
        }
    }

    fun unresolved(c: Context): List<DoseSessionState> = today(c).filter {
        it.status == DoseSessionStatus.PENDING ||
            it.status == DoseSessionStatus.SNOOZED ||
            it.status == DoseSessionStatus.CONFLICT
    }

    private fun reduce(time: String, events: List<DoseEvent>, scheduleMeds: List<Medication>): DoseSessionState {
        if (events.isEmpty()) return DoseSessionState(time, DoseSessionStatus.UNKNOWN, null, scheduleMeds)

        val relevant = events.filter { it.type in stateTypes }
        if (relevant.isEmpty()) return DoseSessionState(time, DoseSessionStatus.UNKNOWN, events.lastOrNull(), scheduleMeds)

        val latestAlarmIndex = relevant.indexOfLast { it.type == "alarm" }
        val sessionEvents = if (latestAlarmIndex >= 0) relevant.drop(latestAlarmIndex) else relevant
        val latestExplicitResolution = sessionEvents.lastOrNull {
            it.type == "conflict_resolved_taken" || it.type == "conflict_resolved_missed"
        }
        if (latestExplicitResolution != null) {
            val status = if (latestExplicitResolution.type == "conflict_resolved_taken") DoseSessionStatus.TAKEN else DoseSessionStatus.MISSED
            return DoseSessionState(time, status, latestExplicitResolution, medsFrom(latestExplicitResolution, scheduleMeds))
        }

        val terminal = sessionEvents.filter { it.type == "taken" || it.type == "missed" }
        val lastTaken = terminal.lastOrNull { it.type == "taken" }
        val lastMissed = terminal.lastOrNull { it.type == "missed" }

        if (lastTaken != null && lastMissed != null && lastTaken.actorTopic != lastMissed.actorTopic) {
            val delta = kotlin.math.abs(lastTaken.timestamp - lastMissed.timestamp)
            if (delta <= 120_000L) {
                val conflicts = ordered(listOf(lastTaken, lastMissed))
                return DoseSessionState(
                    time = time,
                    status = DoseSessionStatus.CONFLICT,
                    latestEvent = conflicts.last(),
                    medications = medsFrom(conflicts.last(), scheduleMeds),
                    conflictEvents = conflicts
                )
            }
        }

        val latest = sessionEvents.last()
        val status = when (latest.type) {
            "taken" -> DoseSessionStatus.TAKEN
            "missed" -> DoseSessionStatus.MISSED
            "snoozed" -> DoseSessionStatus.SNOOZED
            "alarm" -> DoseSessionStatus.PENDING
            else -> DoseSessionStatus.UNKNOWN
        }
        return DoseSessionState(time, status, latest, medsFrom(latest, scheduleMeds))
    }

    private fun medsFrom(event: DoseEvent, fallback: List<Medication>): List<Medication> =
        if (event.medications.isNotEmpty()) event.medications else fallback
}
