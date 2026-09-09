package com.ozkanmut.ilactakip

import android.content.Context
import java.util.UUID

/**
 * Pairing lifecycle for removing a Circle peer. The receiving device remains authoritative:
 * once a peer is removed from Store.people, future events from that topic are rejected by
 * PermissionPolicy even if the old device still knows this device's ntfy topic.
 */
object PairingLifecycle {
    fun revoke(c: Context, person: Person) {
        val context = c.applicationContext
        if (person.topic.isBlank() || person.topic == Store.topic(context)) return

        AlertOutbox.dropTopic(context, person.topic)

        val event = DoseEvent(
            eventId = UUID.randomUUID().toString(),
            type = "circle_revoked",
            time = "circle",
            actor = Store.myName(context),
            actorTopic = Store.topic(context),
            timestamp = System.currentTimeMillis(),
            medications = emptyList(),
            syncState = "synced",
            revision = EventStore.nextRevision(context),
            ownerId = Store.topic(context)
        )
        EventStore.append(context, event)
        AlertOutbox.enqueue(context, person.topic, "Dosefolk sync", EventStore.payload(event).toString())

        cleanupPeer(context, person.topic, dropOutbox = false)
    }

    fun applyRemoteRevoke(c: Context, event: DoseEvent) {
        if (event.type != "circle_revoked") return
        val peerTopic = event.actorTopic
        if (peerTopic.isBlank() || peerTopic == Store.topic(c)) return
        cleanupPeer(c.applicationContext, peerTopic, dropOutbox = true)
    }

    private fun cleanupPeer(c: Context, topic: String, dropOutbox: Boolean) {
        Store.savePeople(c, Store.people(c).filterNot { it.topic == topic })
        PermissionPolicy.clearPeer(c, topic)
        RevocationCleanup.clearPeer(c, topic)
        MedicationMetaStore.clearRemoteOwner(c, topic)
        StockEngine.clearRemoteOwner(c, topic)
        if (dropOutbox) AlertOutbox.dropTopic(c, topic)
    }
}
