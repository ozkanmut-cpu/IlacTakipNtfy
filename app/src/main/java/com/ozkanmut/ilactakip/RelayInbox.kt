package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** Non-destructive inbox processor: terminal ACKs are emitted only after durable local outcomes. */
interface RelayInboxFaults {
    fun afterAppend(messageId: String) = Unit
    fun beforeEffects(messageId: String) = Unit
    fun afterEffects(messageId: String) = Unit
    fun afterDurableApplyBeforeAck(messageId: String)
    companion object { val NONE = object : RelayInboxFaults { override fun afterDurableApplyBeforeAck(messageId: String) = Unit } }
}

class RelayInbox internal constructor(
    private val context: Context,
    private val localInstallId: String,
    private val crypto: RelayCrypto,
    private val peers: RelayPeerStore,
    private val api: RelayApi,
    private val faults: RelayInboxFaults = RelayInboxFaults.NONE,
    private val journal: RelayInboxJournalStore = RelayInboxJournal(context)
) {
    fun reconcile(): Boolean {
        val messages = try { api.inbox() } catch (_: Exception) { return false }
        val acks = journal.pendingAcks().mapNotNull { it.outcome?.let { outcome -> RelayTerminalAck(it.messageId, outcome) } }.toMutableList()
        messages.forEach { message ->
            val outcome = process(message)
            if (acks.none { it.messageId == message.outerContext.messageId }) acks += RelayTerminalAck(message.outerContext.messageId, outcome)
        }
        if (acks.isEmpty()) return true
        return try {
            api.acknowledge(acks)
            // Receipts were committed before ACK. Moving the in-flight ledger after ACK is safe:
            // a crash here still finds either EventStore or the in-flight receipt on replay.
            RemoteEventReceiptStore.commitRelayTerminalBatch(context)
            journal.remove(acks.map { it.messageId }.toSet())
            true
        } catch (_: Exception) { false }
    }

    private fun process(message: RelayInboxEnvelope): String {
        val outer = message.outerContext
        val alreadyTerminal = journal.get(outer.messageId)
        if (alreadyTerminal?.phase == RelayInboxJournal.TERMINAL) return alreadyTerminal.outcome ?: "rejected"
        if (outer.recipientInstallId != localInstallId) return rejected(outer.messageId)
        val sender = peers.pinnedIdentity(outer.senderInstallId) ?: return rejected(outer.messageId)
        if (!peers.isTrusted(outer.senderInstallId, sender)) return rejected(outer.messageId)
        val payload = try { crypto.open(message.ciphertext, sender, outer).domainPayload } catch (_: Exception) { return rejected(outer.messageId) }
        if (payload.optString("actorTopic") != outer.senderInstallId || payload.optString("targetTopic").isBlank() ||
            payload.optString("targetTopic") != localInstallId) return rejected(outer.messageId)
        val event = try {
            if (!IncomingEventGuard.supportedDosePayload(payload)) return rejected(outer.messageId)
            SyncEngine.parseDoseEvent(payload) ?: return rejected(outer.messageId)
        } catch (_: Exception) { return rejected(outer.messageId) }
        val prior = alreadyTerminal
        if (prior?.phase == RelayInboxJournal.TERMINAL) return prior.outcome ?: "rejected"
        val hash = MessageDigest.getInstance("SHA-256").digest(RelayEnvelopeFormat.canonical(payload).toByteArray()).joinToString("") { "%02x".format(it) }
        if (prior != null && (prior.eventId != event.eventId || prior.eventHash != hash)) return terminal(outer.messageId, event.eventId, hash, "rejected")
        if (prior == null && (EventStore.contains(context, event.eventId) || RemoteEventReceiptStore.processed(context, event.eventId))) return terminal(outer.messageId, event.eventId, hash, "duplicate")
        if (!IncomingEventGuard.shouldProcess(context, event) && prior == null) return terminal(outer.messageId, event.eventId, hash, "rejected")
        if (prior == null) journal.put(RelayInboxJournal.Entry(outer.messageId, event.eventId, hash, RelayInboxJournal.STARTED, null))
        val stored = if (prior == null || prior.phase == RelayInboxJournal.STARTED) {
            val appended = try { SyncEngine.persistCanonicalIncoming(context, event) } catch (_: Exception) { throw IOException("Relay local persistence failed") }
            journal.put(RelayInboxJournal.Entry(outer.messageId, event.eventId, hash, RelayInboxJournal.APPENDED, null))
            faults.afterAppend(outer.messageId)
            appended
        } else EventStore.load(context).firstOrNull { it.eventId == event.eventId } ?: throw IOException("Relay local persistence failed")
        if (prior?.phase != RelayInboxJournal.EFFECTS) {
        try {
            faults.beforeEffects(outer.messageId)
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
        journal.put(RelayInboxJournal.Entry(outer.messageId, event.eventId, hash, RelayInboxJournal.EFFECTS, null))
        faults.afterEffects(outer.messageId)
        }
        // A test-only process-death seam. Nothing ACKs if this throws.
        faults.afterDurableApplyBeforeAck(outer.messageId)
        return terminal(outer.messageId, event.eventId, hash, "processed")
    }

    private fun terminal(messageId: String, eventId: String, hash: String, outcome: String): String {
        journal.put(RelayInboxJournal.Entry(messageId, eventId, hash, RelayInboxJournal.TERMINAL, outcome))
        return outcome
    }

    /** Rejections are also journaled: a terminal ACK is never backed only by transient memory. */
    private fun rejected(messageId: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(messageId.toByteArray()).joinToString("") { "%02x".format(it) }
        return terminal(messageId, messageId, hash, "rejected")
    }
}
