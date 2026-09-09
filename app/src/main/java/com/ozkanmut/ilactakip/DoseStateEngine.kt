package com.ozkanmut.ilactakip

import android.content.Context
import java.time.Instant
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
    val conflictEvents: List<DoseEvent> = emptyList(),
    val scheduledDate: String = LocalDate.now().toString()
)

object DoseStateEngine {
    private val stateTypes = setOf(
        "alarm", "snoozed", "taken", "missed", "undo_taken", "undo_missed",
        "conflict_resolved_taken", "conflict_resolved_missed"
    )

    private fun eventDate(event: DoseEvent): String {
        if (event.scheduledDate.isNotBlank()) return event.scheduledDate
        return if (event.timestamp > 0L) {
            Instant.ofEpochMilli(event.timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } else ""
    }

    private fun ordered(events: List<DoseEvent>) = events.sortedWith(
        compareBy<DoseEvent> { it.timestamp }.thenBy { it.actorTopic }.thenBy { it.revision }.thenBy { it.eventId }
    )

    fun stateForTime(c: Context, time: String, date: LocalDate = LocalDate.now()): DoseSessionState {
        val dateKey = date.toString()
        val scheduleMeds = Store.load(c).filter { time in it.times && ProgramRuleStore.isActiveOn(c, it.id, date) }
        val events = ordered(EventStore.load(c).filter { eventDate(it) == dateKey && it.time == time })
        return reduce(time, events, scheduleMeds, dateKey)
    }

    fun today(c: Context): List<DoseSessionState> {
        val today = LocalDate.now()
        val dateKey = today.toString()
        val events = EventStore.load(c).filter { eventDate(it) == dateKey }
        val schedule = Store.load(c).filter { ProgramRuleStore.isActiveOn(c, it.id, today) }
        val eventTimes = events.map { it.time }.filter { it.isNotBlank() }
        val scheduleTimes = schedule.flatMap { it.times }
        return (eventTimes + scheduleTimes).distinct().sorted().map { time ->
            reduce(
                time = time,
                events = ordered(events.filter { it.time == time }),
                scheduleMeds = schedule.filter { time in it.times },
                scheduledDate = dateKey
            )
        }
    }

    fun unresolved(c: Context): List<DoseSessionState> = today(c).filter {
        it.status == DoseSessionStatus.PENDING ||
            it.status == DoseSessionStatus.SNOOZED ||
            it.status == DoseSessionStatus.CONFLICT
    }

    private fun reduce(time: String, events: List<DoseEvent>, scheduleMeds: List<Medication>, scheduledDate: String): DoseSessionState {
        if (events.isEmpty()) return DoseSessionState(time, DoseSessionStatus.UNKNOWN, null, scheduleMeds, scheduledDate = scheduledDate)

        val relevant = events.filter { it.type in stateTypes }
        if (relevant.isEmpty()) return DoseSessionState(time, DoseSessionStatus.UNKNOWN, events.lastOrNull(), scheduleMeds, scheduledDate = scheduledDate)

        val latestAlarmIndex = relevant.indexOfLast { it.type == "alarm" }
        val sessionEvents = if (latestAlarmIndex >= 0) relevant.drop(latestAlarmIndex) else relevant
        val latestExplicitResolution = sessionEvents.lastOrNull {
            it.type == "conflict_resolved_taken" || it.type == "conflict_resolved_missed"
        }
        if (latestExplicitResolution != null && sessionEvents.last().timestamp <= latestExplicitResolution.timestamp) {
            val status = if (latestExplicitResolution.type == "conflict_resolved_taken") DoseSessionStatus.TAKEN else DoseSessionStatus.MISSED
            return DoseSessionState(time, status, latestExplicitResolution, medsFrom(latestExplicitResolution, scheduleMeds), scheduledDate = scheduledDate)
        }

        val latest = sessionEvents.last()
        if (latest.type == "undo_taken" || latest.type == "undo_missed") {
            return DoseSessionState(time, DoseSessionStatus.PENDING, latest, medsFrom(latest, scheduleMeds), scheduledDate = scheduledDate)
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
                    conflictEvents = conflicts,
                    scheduledDate = scheduledDate
                )
            }
        }

        val status = when (latest.type) {
            "taken" -> DoseSessionStatus.TAKEN
            "missed" -> DoseSessionStatus.MISSED
            "snoozed" -> DoseSessionStatus.SNOOZED
            "alarm" -> DoseSessionStatus.PENDING
            else -> DoseSessionStatus.UNKNOWN
        }
        return DoseSessionState(time, status, latest, medsFrom(latest, scheduleMeds), scheduledDate = scheduledDate)
    }

    private fun medsFrom(event: DoseEvent, fallback: List<Medication>): List<Medication> =
        if (event.medications.isNotEmpty()) event.medications else fallback
}
