package com.ozkanmut.ilactakip

import android.content.Context
import java.util.UUID

/** Sends the current local medication state to one newly paired Circle peer. */
object CircleInitialSync {
    fun publishToPeer(c: Context, targetTopic: String) {
        val context = c.applicationContext
        if (targetTopic.isBlank() || targetTopic == Store.topic(context)) return

        Store.load(context).forEach { med ->
            val meta = MedicationMetaStore.get(context, med.id)?.let(::listOf).orEmpty()
            enqueueEvent(
                context = context,
                targetTopic = targetTopic,
                type = "program_added",
                time = med.times.firstOrNull() ?: "program",
                medications = listOf(med),
                medicationMeta = meta,
                stableId = bootstrapId(context, targetTopic, "program", med.id)
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
                targetTopic = targetTopic,
                type = "program_rule_updated",
                time = "program",
                medications = listOf(carrier),
                medicationMeta = meta,
                stableId = bootstrapId(context, targetTopic, "rule", med.id)
            )
        }

        StockSync.publishAll(context, targetTopic)
        DosefolkSyncScheduler.kick(context)
    }

    private fun bootstrapId(context: Context, targetTopic: String, kind: String, medicationId: String): String =
        "bootstrap|${Store.topic(context)}|$targetTopic|$kind|$medicationId"

    private fun enqueueEvent(
        context: Context,
        targetTopic: String,
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
            targetTopic,
            "Dosefolk sync",
            EventStore.payload(event).toString(),
            stableId = stableId
        )
    }
}
