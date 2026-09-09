package com.ozkanmut.ilactakip

import android.content.Context

enum class CirclePermission { VIEW, SET_STATUS, SNOOZE, REMIND, EDIT_PROGRAM, EDIT_STOCK }

/**
 * Owner-side permission gate for events arriving over ntfy.
 * Permissions are evaluated locally on the receiving device so a remote peer
 * cannot grant itself authority by changing its own app state.
 */
object PermissionPolicy {
    private const val PREFS = "dosefolk_permissions"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(topic: String, permission: CirclePermission) = "$topic|${permission.name}"

    fun allowed(c: Context, topic: String, permission: CirclePermission): Boolean {
        if (topic.isBlank()) return false
        if (topic == Store.topic(c)) return true
        val person = Store.people(c).firstOrNull { it.topic == topic } ?: return false
        val p = prefs(c)
        if (p.contains(key(topic, permission))) return p.getBoolean(key(topic, permission), false)
        return when (permission) {
            CirclePermission.VIEW,
            CirclePermission.SET_STATUS,
            CirclePermission.SNOOZE,
            CirclePermission.REMIND -> true
            CirclePermission.EDIT_PROGRAM -> person.canEdit
            CirclePermission.EDIT_STOCK -> person.canEdit
        }
    }

    fun set(c: Context, topic: String, permission: CirclePermission, allowed: Boolean) {
        if (topic.isBlank()) return
        prefs(c).edit().putBoolean(key(topic, permission), allowed).commit()
        if (permission == CirclePermission.EDIT_PROGRAM || permission == CirclePermission.EDIT_STOCK) {
            CapabilitySync.publish(c, topic, permission, allowed)
        }
    }

    fun acceptRemote(c: Context, event: DoseEvent): Boolean {
        if (event.actorTopic == Store.topic(c)) return true
        val permission = when (event.type) {
            "taken", "missed", "prn_taken", "conflict_resolved_taken", "conflict_resolved_missed",
            "care_claimed", "care_released" -> CirclePermission.SET_STATUS
            "snoozed" -> CirclePermission.SNOOZE
            "program_added", "program_updated", "program_deleted", "program_rule_updated" -> CirclePermission.EDIT_PROGRAM
            "stock_configured", "stock_adjusted", "stock_new_box" -> CirclePermission.EDIT_STOCK
            "capability_edit_program_granted", "capability_edit_program_revoked",
            "capability_edit_stock_granted", "capability_edit_stock_revoked" -> CirclePermission.VIEW
            else -> CirclePermission.VIEW
        }
        return allowed(c, event.actorTopic, permission)
    }
}
