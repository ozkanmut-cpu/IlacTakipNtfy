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
    private fun todayStartMillis(): Long = LocalDate.now()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    fun stateForTime(c: Context, time: String): DoseSessionState {
        val events = EventStore.load(c)
            .filter { it.timestamp >= todayStartMillis() && it.time == time }
            .sortedBy { it.timestamp }
        return reduce(time, events)
    }

    fun today(c: Context): List<DoseSessionState> {
        val events = EventStore.load(c).filter { it.timestamp >= todayStartMillis() }
        val eventTimes = events.map { it.time }.filter { it.isNotBlank() }
        val scheduleTimes = Store.load(c).flatMap { it.times }
        return (eventTimes + scheduleTimes).distinct().sorted().map { time ->
            reduce(time, events.filter { it.time == time }.sortedBy { it.timestamp })
        }
    }

    fun unresolved(c: Context): List<DoseSessionState> = today(c).filter {
        it.status == DoseSessionStatus.PENDING ||
            it.status == DoseSessionStatus.SNOOZED ||
            it.status == DoseSessionStatus.CONFLICT
    }

    private fun reduce(time: String, events: List<DoseEvent>): DoseSessionState {
        val scheduleMeds = Store.loadPlaceholderForState(time)
        if (events.isEmpty()) {
            return DoseSessionState(time, DoseSessionStatus.UNKNOWN, null, scheduleMeds)
        }

        val relevant = events.filter { it.type in setOf("alarm", "snoozed", "taken", "missed") }
        if (relevant.isEmpty()) {
            return DoseSessionState(time, DoseSessionStatus.UNKNOWN, events.lastOrNull(), scheduleMeds)
        }

        val latestAlarmIndex = relevant.indexOfLast { it.type == "alarm" }
        val sessionEvents = if (latestAlarmIndex >= 0) relevant.drop(latestAlarmIndex) else relevant
        val terminal = sessionEvents.filter { it.type == "taken" || it.type == "missed" }
        val lastTaken = terminal.lastOrNull { it.type == "taken" }
        val lastMissed = terminal.lastOrNull { it.type == "missed" }

        if (lastTaken != null && lastMissed != null && lastTaken.actorTopic != lastMissed.actorTopic) {
            val delta = kotlin.math.abs(lastTaken.timestamp - lastMissed.timestamp)
            if (delta <= 120_000L) {
                val conflicts = listOf(lastTaken, lastMissed).sortedBy { it.timestamp }
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

private fun Store.loadPlaceholderForState(time: String): List<Medication> =
    runCatching { Store.load(StateContextHolder.context!!).filter { time in it.times } }.getOrDefault(emptyList())

/** Small bridge so the reducer stays deterministic without threading Context through every helper. */
private object StateContextHolder {
    var context: Context? = null
}
