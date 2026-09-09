package com.ozkanmut.ilactakip

/**
 * Canonical ordering for dose events.
 *
 * v9+ events use Lamport revision first so wall-clock skew cannot change convergence.
 * Legacy events have revision=0; when comparing two legacy events we preserve the
 * historical timestamp-first semantics so old persisted data is not reordered by
 * actor/event IDs after an upgrade.
 */
object DoseEventOrder {
    val global: Comparator<DoseEvent> = Comparator { a, b ->
        if (a.revision == 0L && b.revision == 0L) {
            compareValuesBy(a, b, { it.timestamp }, { it.actorTopic }, { it.eventId })
        } else {
            compareValuesBy(a, b, { it.revision }, { it.actorTopic }, { it.eventId }, { it.timestamp })
        }
    }

    val withinActor: Comparator<DoseEvent> = Comparator { a, b ->
        if (a.revision == 0L && b.revision == 0L) {
            compareValuesBy(a, b, { it.timestamp }, { it.eventId })
        } else {
            compareValuesBy(a, b, { it.revision }, { it.eventId }, { it.timestamp })
        }
    }
}
