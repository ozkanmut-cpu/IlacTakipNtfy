package com.ozkanmut.ilactakip

/**
 * Canonical ordering for v9 dose events.
 * Logical revision is the causal clock; stable IDs break ties deterministically.
 * Wall-clock time is only the final fallback so device clock skew cannot change convergence.
 */
object DoseEventOrder {
    val global: Comparator<DoseEvent> = compareBy<DoseEvent> { it.revision }
        .thenBy { it.actorTopic }
        .thenBy { it.eventId }
        .thenBy { it.timestamp }

    val withinActor: Comparator<DoseEvent> = compareBy<DoseEvent> { it.revision }
        .thenBy { it.eventId }
        .thenBy { it.timestamp }
}
