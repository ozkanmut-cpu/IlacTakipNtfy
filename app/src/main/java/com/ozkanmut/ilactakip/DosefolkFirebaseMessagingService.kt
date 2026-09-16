package com.ozkanmut.ilactakip

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

enum class FcmWakeAction {
    KICK_SYNC,
    FULL_RECONCILIATION,
    IGNORE
}

internal object FcmWakeRouter {
    private val WAKE_KEYS = setOf("wakeType", "protocolVersion")

    fun action(data: Map<String, String>, deletedMessages: Boolean): FcmWakeAction {
        if (deletedMessages) return FcmWakeAction.FULL_RECONCILIATION
        if (data.keys != WAKE_KEYS) return FcmWakeAction.IGNORE
        return if (data["wakeType"] == "sync" && data["protocolVersion"] == "1") {
            FcmWakeAction.KICK_SYNC
        } else {
            FcmWakeAction.IGNORE
        }
    }
}

class DosefolkFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        when (FcmWakeRouter.action(message.data, deletedMessages = false)) {
            FcmWakeAction.KICK_SYNC -> DosefolkSyncScheduler.kick(applicationContext)
            FcmWakeAction.FULL_RECONCILIATION -> DosefolkSyncScheduler.fullReconciliation(applicationContext)
            FcmWakeAction.IGNORE -> Unit
        }
    }

    override fun onDeletedMessages() {
        when (FcmWakeRouter.action(emptyMap(), deletedMessages = true)) {
            FcmWakeAction.FULL_RECONCILIATION -> DosefolkSyncScheduler.fullReconciliation(applicationContext)
            FcmWakeAction.KICK_SYNC -> DosefolkSyncScheduler.kick(applicationContext)
            FcmWakeAction.IGNORE -> Unit
        }
    }

    override fun onRegistered(installationId: String) {
        FcmRegistrationScheduler.refresh(applicationContext)
    }
}
