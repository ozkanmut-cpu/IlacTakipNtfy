package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate

enum class DoseSessionStatus { UNKNOWN, PENDING, SNOOZED, TAKEN, MISSED, CONFLICT }

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

    fun stateForTime(c: Context, time: String, date: LocalDate = LocalDate.now()): DoseSessionState {
        val scheduledDate = date.toString()
        val events = EventStore.load(c).filter { it.time == time && eventDate(it) == scheduledDate }
        val scheduleMeds = Store.load(c).filter { time in it.times && ProgramRuleStore.isActiveOn(c, it.id, date) }
        return reduce(time, events, scheduleMeds, scheduledDate)
    }

    fun today(c: Context): List<DoseSessionState> {
        val date = LocalDate.now()
        val times = (Store.load(c).filter { ProgramRuleStore.isActiveOn(c, it.id, date) }.flatMap { it.times } +
            EventStore.load(c).filter { eventDate(it) == date.toString() }.map { it.time })
            .filter { it.isNotBlank() }.distinct().sorted()
        return times.map { stateForTime(c, it, date) }
    }

    fun unresolved(c: Context): List<DoseSessionState> = today(c).filter {
        it.status == DoseSessionStatus.PENDING || it.status == DoseSessionStatus.SNOOZED || it.status == DoseSessionStatus.CONFLICT
    }

    private fun reduce(time: String, events: List<DoseEvent>, scheduleMeds: List<Medication>, scheduledDate: String): DoseSessionState {
        if (events.isEmpty()) return DoseSessionState(time, DoseSessionStatus.UNKNOWN, null, scheduleMeds, scheduledDate = scheduledDate)
        val sessionEvents = events.filter { it.type in stateTypes }
        if (sessionEvents.isEmpty()) return DoseSessionState(time, DoseSessionStatus.UNKNOWN, events.firstOrNull(), scheduleMeds, scheduledDate = scheduledDate)

        val resolutions = sessionEvents.filter { it.type == "conflict_resolved_taken" || it.type == "conflict_resolved_missed" }
        if (resolutions.isNotEmpty()) {
            val latestPerActor = resolutions.groupBy { it.actorTopic }.values.mapNotNull(::newestForActor)
            val maxRevision = latestPerActor.maxOf { it.revision }
            val top = latestPerActor.filter { it.revision == maxRevision }
            val topTaken = top.filter { it.type == "conflict_resolved_taken" }
            val topMissed = top.filter { it.type == "conflict_resolved_missed" }

            if (topTaken.isNotEmpty() && topMissed.isNotEmpty()) {
                val conflicts = ordered(listOf(topTaken.maxBy { it.eventId }, topMissed.maxBy { it.eventId }))
                return DoseSessionState(time, DoseSessionStatus.CONFLICT, conflicts.last(), medsFrom(conflicts.last(), scheduleMeds), conflicts, scheduledDate)
            }

            val resolution = top.maxWithOrNull(compareBy<DoseEvent> { it.actorTopic }.thenBy { it.eventId })!!
            val laterUndo = sessionEvents.filter { (it.type == "undo_taken" || it.type == "undo_missed") && it.actorTopic == resolution.actorTopic }
                .maxWithOrNull(compareBy<DoseEvent> { it.revision }.thenBy { it.timestamp }.thenBy { it.eventId })
            if (laterUndo != null && laterUndo.revision > resolution.revision) {
                return DoseSessionState(time, DoseSessionStatus.PENDING, laterUndo, medsFrom(laterUndo, scheduleMeds), scheduledDate = scheduledDate)
            }
            val status = if (resolution.type == "conflict_resolved_taken") DoseSessionStatus.TAKEN else DoseSessionStatus.MISSED
            return DoseSessionState(time, status, resolution, medsFrom(resolution, scheduleMeds), scheduledDate = scheduledDate)
        }

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
            return DoseSessionState(time, DoseSessionStatus.CONFLICT, conflicts.last(), medsFrom(conflicts.last(), scheduleMeds), conflicts, scheduledDate)
        }

        val effectiveTerminal = lastTaken ?: lastMissed
        if (effectiveTerminal != null) {
            val status = if (effectiveTerminal.type == "taken") DoseSessionStatus.TAKEN else DoseSessionStatus.MISSED
            return DoseSessionState(time, status, effectiveTerminal, medsFrom(effectiveTerminal, scheduleMeds), scheduledDate = scheduledDate)
        }

        val latest = sessionEvents.maxWithOrNull(
            compareBy<DoseEvent> { it.revision }.thenBy { it.timestamp }.thenBy { it.actorTopic }.thenBy { it.eventId }
        )!!
        val status = when (latest.type) {
            "snoozed" -> DoseSessionStatus.SNOOZED
            "alarm" -> DoseSessionStatus.PENDING
            "undo_taken", "undo_missed" -> DoseSessionStatus.PENDING
            else -> DoseSessionStatus.UNKNOWN
        }
        return DoseSessionState(time, status, latest, medsFrom(latest, scheduleMeds), scheduledDate = scheduledDate)
    }

    private fun medsFrom(event: DoseEvent, fallback: List<Medication>): List<Medication> = if (event.medications.isNotEmpty()) event.medications else fallback
    private fun eventDate(event: DoseEvent): String = event.scheduledDate.ifBlank { LocalDate.now().toString() }

    /**
     * Logical revision is the causal clock. Wall-clock timestamps are only a final
     * display fallback so a device with a badly skewed clock cannot become the
     * apparent newest conflict event or supply the winning medication payload.
     */
    private fun ordered(events: List<DoseEvent>) = events.sortedWith(
        compareBy<DoseEvent> { it.revision }
            .thenBy { it.actorTopic }
            .thenBy { it.eventId }
            .thenBy { it.timestamp }
    )

    private fun newestForActor(events: List<DoseEvent>): DoseEvent? = events.maxWithOrNull(compareBy<DoseEvent> { it.revision }.thenBy { it.timestamp }.thenBy { it.eventId })
}
