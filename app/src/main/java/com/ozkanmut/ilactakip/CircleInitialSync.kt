package com.ozkanmut.ilactakip

import android.content.Context
import java.util.UUID

/** Publishes the current local medication state after a new Circle pairing. */
object CircleInitialSync {
    fun publishToPeer(c: Context, targetTopic: String) {
        val context = c.applicationContext
        if (targetTopic.isBlank() || targetTopic == Store.topic(context)) return
        val publisherTopic = CircleTransport.publishTopic(context)

        Store.load(context).forEach { med ->
            val meta = MedicationMetaStore.get(context, med.id)?.let(::listOf).orEmpty()
            enqueueEvent(
                context = context,
                publisherTopic = publisherTopic,
                type = "program_added",
                time = med.times.firstOrNull() ?: "program",
                medications = listOf(med),
                medicationMeta = meta,
                stableId = bootstrapId(context, "program", med.id)
            )

            val rule = ProgramRuleStore.get(context, med.id)
            val carrier = Medication(
                id = med.id,
                name = med.name,
                dose = ProgramRuleStore.encode(rule).toString(),
                times = emptyList()
            )
            enqueueEvent(
                context = context,
                publisherTopic = publisherTopic,
                type = "program_rule_updated",
                time = "program",
                medications = listOf(carrier),
                medicationMeta = meta,
                stableId = bootstrapId(context, "rule", med.id)
            )
        }

        StockSync.publishAllToCircle(context)
        DosefolkSyncScheduler.kick(context)
    }

    private fun bootstrapId(context: Context, kind: String, medicationId: String): String =
        "bootstrap|${Store.topic(context)}|$kind|$medicationId"

    private fun enqueueEvent(
        context: Context,
        publisherTopic: String,
        type: String,
        time: String,
        medications: List<Medication>,
        medicationMeta: List<MedicationMeta>,
        stableId: String
    ) {
        val event = DoseEvent(
            eventId = UUID.randomUUID().toString(),
            type = type,
            time = time,
            actor = Store.myName(context),
            actorTopic = Store.topic(context),
            timestamp = System.currentTimeMillis(),
            medications = medications,
            syncState = "synced",
            revision = EventStore.nextRevision(context),
            ownerId = Store.topic(context),
            medicationMeta = medicationMeta
        )
        AlertOutbox.enqueueLatest(
            context,
            publisherTopic,
            "Dosefolk sync",
            EventStore.payload(event).toString(),
            stableId = stableId
        )
    }
}
