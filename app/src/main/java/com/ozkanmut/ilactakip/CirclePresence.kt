package com.ozkanmut.ilactakip

import android.content.Context
import java.util.UUID

/** Lightweight mutual-pairing handshake carried on the sender publisher topic. */
object CirclePresence {
    private const val PREFS = "dosefolk_circle_presence"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun confirmed(c: Context, peerTopic: String): Boolean =
        peerTopic.isNotBlank() && prefs(c).getLong(peerTopic, 0L) > 0L

    fun lastSeen(c: Context, peerTopic: String): Long = prefs(c).getLong(peerTopic, 0L)

    fun markSeen(c: Context, event: DoseEvent) {
        if (event.type != "circle_presence") return
        if (event.targetTopic != Store.topic(c)) return
        if (event.actorTopic.isBlank() || event.actorTopic == Store.topic(c)) return
        prefs(c).edit().putLong(event.actorTopic, event.timestamp.coerceAtLeast(System.currentTimeMillis())).commit()
    }

    fun clear(c: Context, peerTopic: String) {
        if (peerTopic.isBlank()) return
        prefs(c).edit().remove(peerTopic).commit()
    }

    fun publish(c: Context, targetTopic: String) {
        if (targetTopic.isBlank() || targetTopic == Store.topic(c)) return
        val event = DoseEvent(
            eventId = UUID.randomUUID().toString(),
            type = "circle_presence",
            time = "circle",
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = emptyList(),
            syncState = "synced",
            revision = EventStore.nextRevision(c),
            ownerId = Store.topic(c),
            targetTopic = targetTopic
        )
        EventStore.append(c, event)
        AlertOutbox.enqueueLatest(
            c.applicationContext, CircleTransport.publishTopic(c), "Dosefolk sync",
            EventStore.payload(event).toString(),
            stableId = "presence|${Store.topic(c)}|$targetTopic"
        )
    }
}
