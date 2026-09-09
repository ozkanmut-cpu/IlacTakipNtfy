package com.ozkanmut.ilactakip

import android.content.Context
import java.util.UUID

/**
 * Durable relationship tombstone. While a peer is revoked, catch-up events from
 * that topic are consumed as rejected receipts so they cannot become live again
 * if the same topic is paired later.
 */
object RevokedPeerFence {
    private const val PREFS = "dosefolk_revoked_peers"
    private const val KEY = "topics"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRevoked(c: Context, topic: String): Boolean =
        topic.isNotBlank() && prefs(c).getStringSet(KEY, emptySet()).orEmpty().contains(topic)

    @Synchronized
    fun markRevoked(c: Context, topic: String) {
        if (topic.isBlank()) return
        val topics = prefs(c).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        topics += topic
        prefs(c).edit().putStringSet(KEY, topics).commit()
    }

    @Synchronized
    fun clear(c: Context, topic: String) {
        if (topic.isBlank()) return
        val topics = prefs(c).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (!topics.remove(topic)) return
        prefs(c).edit().putStringSet(KEY, topics).commit()
    }
}

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

    /**
     * Before trusting a previously revoked topic again, drain the current ntfy
     * catch-up while that topic is still unauthorized. Rejected old messages are
     * receipted by IncomingEventGuard. Only a successful drain clears the tombstone.
     */
    fun prepareRePair(c: Context, topic: String): Boolean {
        val context = c.applicationContext
        if (!RevokedPeerFence.isRevoked(context, topic)) return true
        if (!SyncEngine.pullBlocking(context)) return false
        RevokedPeerFence.clear(context, topic)
        return true
    }

    private fun cleanupPeer(c: Context, topic: String, dropOutbox: Boolean) {
        RevokedPeerFence.markRevoked(c, topic)
        Store.savePeople(c, Store.people(c).filterNot { it.topic == topic })
        PermissionPolicy.clearPeer(c, topic)
        RevocationCleanup.clearPeer(c, topic)
        MedicationMetaStore.clearRemoteOwner(c, topic)
        StockEngine.clearRemoteOwner(c, topic)
        if (dropOutbox) AlertOutbox.dropTopic(c, topic)
    }
}
