package com.ozkanmut.ilactakip

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ScheduleKind { FIXED, WINDOW, PRN, ROUTINE, TEMPORARY }
enum class StockUnit { TABLET, CAPSULE, ML, PUFF, DOSE, OTHER }
enum class EventKind { TAKEN, MISSED, SNOOZED, REMINDER, CLAIM, SEEN, PROGRAM_CHANGE, STOCK_CHANGE, IMPORT, UNDO, PING, PONG }
enum class Permission { VIEW, SET_STATUS, REMIND, SNOOZE, EDIT_PROGRAM, EDIT_STOCK, EXPORT }

data class AdvancedMedication(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dose: String = "",
    val scheduleKind: ScheduleKind = ScheduleKind.FIXED,
    val times: List<String> = emptyList(),
    val windowMinutes: Int = 0,
    val weekdays: Set<Int> = (1..7).toSet(),
    val startDate: String? = null,
    val endDate: String? = null,
    val stock: Double? = null,
    val stockUnit: StockUnit = StockUnit.TABLET,
    val consumePerDose: Double = 1.0,
    val lowStockThreshold: Double? = null,
    val expiryDate: String? = null,
    val critical: Boolean = false,
    val archived: Boolean = false,
    val note: String = "",
    val routineId: String? = null,
    val revision: Long = 0
)

data class CarePermission(
    val personId: String,
    val permissions: Set<Permission> = setOf(Permission.VIEW, Permission.SET_STATUS, Permission.REMIND),
    val expiresAt: Long? = null,
    val notifyMode: String = "late",
    val quietStart: String? = null,
    val quietEnd: String? = null
)

data class SyncEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val kind: EventKind,
    val ownerId: String,
    val actorId: String,
    val actorName: String,
    val medicationIds: List<String> = emptyList(),
    val scheduledTime: String = "",
    val status: String = "",
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val revision: Long = timestamp,
    val undoneEventId: String? = null
) {
    fun json(): JSONObject = JSONObject()
        .put("v", 3)
        .put("eventId", eventId)
        .put("kind", kind.name)
        .put("ownerId", ownerId)
        .put("actorId", actorId)
        .put("actorName", actorName)
        .put("medicationIds", JSONArray(medicationIds))
        .put("scheduledTime", scheduledTime)
        .put("status", status)
        .put("detail", detail)
        .put("timestamp", timestamp)
        .put("revision", revision)
        .also { if (undoneEventId != null) it.put("undoneEventId", undoneEventId) }
}

data class ImportBatch(
    val id: String = UUID.randomUUID().toString(),
    val sourceName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val addedIds: List<String>,
    val updatedIds: List<String> = emptyList()
)

data class OutboxItem(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0
)

data class CareShift(
    val id: String = UUID.randomUUID().toString(),
    val personId: String,
    val startAt: Long,
    val endAt: Long
)

data class DoseOverride(
    val id: String = UUID.randomUUID().toString(),
    val medicationId: String,
    val date: String,
    val originalTime: String,
    val overrideTime: String
)
