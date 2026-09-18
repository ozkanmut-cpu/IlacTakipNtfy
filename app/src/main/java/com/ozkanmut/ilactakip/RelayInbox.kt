package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/** Non-destructive inbox processor: terminal ACKs are emitted only after durable local outcomes. */
interface RelayInboxFaults {
    fun afterDurableApplyBeforeAck(messageId: String)
    companion object { val NONE = object : RelayInboxFaults { override fun afterDurableApplyBeforeAck(messageId: String) = Unit } }
}

class RelayInbox(
    private val context: Context,
    private val localInstallId: String,
    private val crypto: RelayCrypto,
    private val peers: RelayPeerStore,
    private val api: RelayApi,
    private val faults: RelayInboxFaults = RelayInboxFaults.NONE
) {
    fun reconcile(): Boolean {
        val messages = try { api.inbox() } catch (_: Exception) { return false }
        val acks = mutableListOf<RelayTerminalAck>()
        messages.forEach { message ->
            val outcome = process(message)
            acks += RelayTerminalAck(message.outerContext.messageId, outcome)
        }
        if (acks.isEmpty()) return true
        return try {
            api.acknowledge(acks)
            // Receipts were committed before ACK. Moving the in-flight ledger after ACK is safe:
            // a crash here still finds either EventStore or the in-flight receipt on replay.
            RemoteEventReceiptStore.commitRelayTerminalBatch(context)
            true
        } catch (_: Exception) { false }
    }

    private fun process(message: RelayInboxEnvelope): String {
        val outer = message.outerContext
        if (outer.recipientInstallId != localInstallId) return "rejected"
        val sender = peers.pinnedIdentity(outer.senderInstallId) ?: return "rejected"
        if (!peers.isTrusted(outer.senderInstallId, sender)) return "rejected"
        val payload = try { crypto.open(message.ciphertext, sender, outer).domainPayload } catch (_: Exception) { return "rejected" }
        if (payload.optString("actorTopic") != outer.senderInstallId || payload.optString("targetTopic").isBlank() ||
            payload.optString("targetTopic") != localInstallId) return "rejected"
        val event = try {
            if (!IncomingEventGuard.supportedDosePayload(payload)) return "rejected"
            SyncEngine.parseDoseEvent(payload) ?: return "rejected"
        } catch (_: Exception) { return "rejected" }
        if (EventStore.contains(context, event.eventId) || RemoteEventReceiptStore.processed(context, event.eventId)) return "duplicate"
        if (!IncomingEventGuard.shouldProcess(context, event)) return "rejected"
        val stored = try { SyncEngine.persistCanonicalIncoming(context, event) } catch (_: Exception) { throw IOException("Relay local persistence failed") }
        try {
            OwnerScopeStore.remember(context, stored)
            val ownerId = stored.ownerId.ifBlank { stored.actorTopic }
            if (ownerId.isNotBlank() && ownerId != OwnerScopeStore.localOwnerId(context)) {
                stored.medicationMeta.forEach { MedicationMetaStore.saveRemote(context, ownerId, it) }
            }
            if (ownerId.isBlank() || ownerId == OwnerScopeStore.localOwnerId(context)) {
                PrnUsageLedger.observe(context, stored); StockEngine.applyEvent(context, stored)
            }
            SyncEngine.applyRemoteState(context, stored)
            RemoteEventReceiptStore.markProcessed(context, stored.eventId)
        } catch (_: Exception) { throw IOException("Relay local persistence failed") }
        // A test-only process-death seam. Nothing ACKs if this throws.
        faults.afterDurableApplyBeforeAck(outer.messageId)
        return "processed"
    }
}
