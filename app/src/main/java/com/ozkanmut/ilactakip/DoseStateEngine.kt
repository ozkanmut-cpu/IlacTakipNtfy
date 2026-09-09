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

    private fun newestForActor(events: List<DoseEvent>): DoseEvent? = events.maxWithOrNull(
        compareBy<DoseEvent> { it.revision }.thenBy { it.timestamp }.thenBy { it.eventId }
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

        // Resolve explicit human decisions by per-device revision rather than wall clock.
        // If two different devices explicitly resolve the same conflict in opposite
        // directions, that disagreement is itself a conflict and must be surfaced.
        val resolutions = sessionEvents.filter {
            it.type == "conflict_resolved_taken" || it.type == "conflict_resolved_missed"
        }
        if (resolutions.isNotEmpty()) {
            val latestPerActor = resolutions.groupBy { it.actorTopic }.values.mapNotNull(::newestForActor)
            val takenResolution = latestPerActor.filter { it.type == "conflict_resolved_taken" }
            val missedResolution = latestPerActor.filter { it.type == "conflict_resolved_missed" }

            if (takenResolution.isNotEmpty() && missedResolution.isNotEmpty()) {
                val conflicts = ordered(listOf(takenResolution.maxBy { it.revision }, missedResolution.maxBy { it.revision }))
                return DoseSessionState(
                    time = time,
                    status = DoseSessionStatus.CONFLICT,
                    latestEvent = conflicts.last(),
                    medications = medsFrom(conflicts.last(), scheduleMeds),
                    conflictEvents = conflicts,
                    scheduledDate = scheduledDate
                )
            }

            val resolution = latestPerActor.maxWithOrNull(
                compareBy<DoseEvent> { it.revision }.thenBy { it.actorTopic }.thenBy { it.eventId }
            )!!
            val laterUndo = sessionEvents
                .filter { (it.type == "undo_taken" || it.type == "undo_missed") && it.actorTopic == resolution.actorTopic }
                .maxWithOrNull(compareBy<DoseEvent> { it.revision }.thenBy { it.timestamp }.thenBy { it.eventId })
            if (laterUndo != null && laterUndo.revision > resolution.revision) {
                return DoseSessionState(time, DoseSessionStatus.PENDING, laterUndo, medsFrom(laterUndo, scheduleMeds), scheduledDate = scheduledDate)
            }
            val status = if (resolution.type == "conflict_resolved_taken") DoseSessionStatus.TAKEN else DoseSessionStatus.MISSED
            return DoseSessionState(time, status, resolution, medsFrom(resolution, scheduleMeds), scheduledDate = scheduledDate)
        }

        // A same-device undo is causal only when its local revision is newer than
        // that device's latest terminal fact. Arrival order and wall clock do not matter.
        val latestByActor = sessionEvents.groupBy { it.actorTopic }.mapValues { (_, actorEvents) ->
            newestForActor(actorEvents.filter { it.type == "taken" || it.type == "missed" || it.type == "undo_taken" || it.type == "undo_missed" })
        }.values.filterNotNull()
        val effective = latestByActor.filterNot { it.type == "undo_taken" || it.type == "undo_missed" }
        if (effective.isEmpty() && latestByActor.any { it.type == "undo_taken" || it.type == "undo_missed" }) {
            val undo = latestByActor.maxWithOrNull(compareBy<DoseEvent> { it.revision }.thenBy { it.actorTopic }.thenBy { it.eventId })!!
            return DoseSessionState(time, DoseSessionStatus.PENDING, undo, medsFrom(undo, scheduleMeds), scheduledDate = scheduledDate)
        }

        val lastTaken = effective.filter { it.type == "taken" }.maxWithOrNull(compareBy<DoseEvent> { it.revision }.thenBy { it.actorTopic })
        val lastMissed = effective.filter { it.type == "missed" }.maxWithOrNull(compareBy<DoseEvent> { it.revision }.thenBy { it.actorTopic })
        if (lastTaken != null && lastMissed != null && lastTaken.actorTopic != lastMissed.actorTopic) {
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

        val effectiveTerminal = lastTaken ?: lastMissed
        if (effectiveTerminal != null) {
            val status = if (effectiveTerminal.type == "taken") DoseSessionStatus.TAKEN else DoseSessionStatus.MISSED
            return DoseSessionState(time, status, effectiveTerminal, medsFrom(effectiveTerminal, scheduleMeds), scheduledDate = scheduledDate)
        }

        val latest = sessionEvents.last()
        val status = when (latest.type) {
            "snoozed" -> DoseSessionStatus.SNOOZED
            "alarm" -> DoseSessionStatus.PENDING
            "undo_taken", "undo_missed" -> DoseSessionStatus.PENDING
            else -> DoseSessionStatus.UNKNOWN
        }
        return DoseSessionState(time, status, latest, medsFrom(latest, scheduleMeds), scheduledDate = scheduledDate)
    }

    private fun medsFrom(event: DoseEvent, fallback: List<Medication>): List<Medication> =
        if (event.medications.isNotEmpty()) event.medications else fallback
}
