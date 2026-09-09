package com.ozkanmut.ilactakip

import android.content.Context

/**
 * Deterministic ordering gate for remote capability grant/revoke events.
 * Prevents an older grant from being replayed after a newer revoke and
 * accidentally restoring access. Revision is authoritative; legacy events
 * without revisions fall back to timestamp ordering.
 */
object CapabilityEventGate {
    private const val PREFS = "dosefolk_capability_order"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun permission(event: DoseEvent): String? = when {
        event.type.startsWith("capability_edit_program_") -> "EDIT_PROGRAM"
        event.type.startsWith("capability_edit_stock_") -> "EDIT_STOCK"
        else -> null
    }

    private fun owner(event: DoseEvent): String = event.ownerId.ifBlank { event.actorTopic }
    private fun base(owner: String, permission: String) = "$owner|$permission"

    @Synchronized
    fun accept(c: Context, event: DoseEvent): Boolean {
        val permission = permission(event) ?: return false
        val owner = owner(event)
        if (owner.isBlank()) return false

        val p = prefs(c)
        val base = base(owner, permission)
        val storedRevision = p.getLong("$base|rev", 0L)
        val storedActor = p.getString("$base|actor", "").orEmpty()
        val storedEvent = p.getString("$base|event", "").orEmpty()
        val storedTimestamp = p.getLong("$base|ts", 0L)

        val newer = if (event.revision > 0L || storedRevision > 0L) {
            when {
                event.revision != storedRevision -> event.revision > storedRevision
                event.actorTopic != storedActor -> event.actorTopic > storedActor
                else -> event.eventId > storedEvent
            }
        } else {
            when {
                event.timestamp != storedTimestamp -> event.timestamp >= storedTimestamp
                event.actorTopic != storedActor -> event.actorTopic > storedActor
                else -> event.eventId > storedEvent
            }
        }
        if (!newer) return false

        p.edit()
            .putLong("$base|rev", event.revision)
            .putLong("$base|ts", event.timestamp)
            .putString("$base|actor", event.actorTopic)
            .putString("$base|event", event.eventId)
            .commit()
        return true
    }

    @Synchronized
    fun clearPeer(c: Context, topic: String) {
        if (topic.isBlank()) return
        val p = prefs(c)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith("$topic|") }.forEach(edit::remove)
        edit.commit()
    }
}
