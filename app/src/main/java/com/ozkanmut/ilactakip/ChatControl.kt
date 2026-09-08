package com.ozkanmut.ilactakip

import org.json.JSONObject
import java.util.UUID

enum class ChatAction {
    GET_TODAY,
    GET_NEXT_DOSE,
    GET_MEDICATIONS,
    GET_HISTORY,
    GET_STOCK,
    GET_PEOPLE,
    GET_DEVICE_HEALTH,
    MARK_TAKEN,
    MARK_MISSED,
    SNOOZE,
    SEND_REMINDER,
    CLAIM_CARE,
    RELEASE_CARE,
    ADD_MEDICATION,
    UPDATE_MEDICATION,
    ARCHIVE_MEDICATION,
    DELETE_MEDICATION,
    SET_STOCK,
    ADD_STOCK,
    SET_TEMPORARY_OVERRIDE,
    ADD_PRN_USE,
    ADD_PERSON,
    REMOVE_PERSON,
    SET_PERMISSIONS,
    SET_NOTIFICATION_POLICY,
    SET_CARE_SHIFT,
    IMPORT_PREVIEW,
    IMPORT_COMMIT,
    IMPORT_UNDO,
    EXPORT_DATA,
    BACKUP_CREATE,
    BACKUP_RESTORE,
    TEST_CONNECTION,
    RUN_SELF_TEST,
    UNDO_LAST_ACTION
}

data class ChatCommand(
    val commandId: String = UUID.randomUUID().toString(),
    val action: ChatAction,
    val actorId: String,
    val actorName: String,
    val ownerId: String,
    val payload: JSONObject = JSONObject(),
    val createdAt: Long = System.currentTimeMillis(),
    val requiresConfirmation: Boolean = false
) {
    fun json(): JSONObject = JSONObject()
        .put("v", 1)
        .put("commandId", commandId)
        .put("action", action.name)
        .put("actorId", actorId)
        .put("actorName", actorName)
        .put("ownerId", ownerId)
        .put("payload", payload)
        .put("createdAt", createdAt)
        .put("requiresConfirmation", requiresConfirmation)
}

data class ChatCommandResult(
    val commandId: String,
    val ok: Boolean,
    val message: String,
    val data: JSONObject = JSONObject(),
    val completedAt: Long = System.currentTimeMillis()
) {
    fun json(): JSONObject = JSONObject()
        .put("commandId", commandId)
        .put("ok", ok)
        .put("message", message)
        .put("data", data)
        .put("completedAt", completedAt)
}

object ChatControlPolicy {
    val destructiveActions = setOf(
        ChatAction.DELETE_MEDICATION,
        ChatAction.REMOVE_PERSON,
        ChatAction.BACKUP_RESTORE,
        ChatAction.IMPORT_COMMIT
    )

    fun needsExplicitConfirmation(action: ChatAction): Boolean = action in destructiveActions
}
