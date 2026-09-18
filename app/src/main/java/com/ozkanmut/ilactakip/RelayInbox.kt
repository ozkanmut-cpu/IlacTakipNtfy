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
        val pending = linkedMapOf<String, RelayInboxJournal.Entry>()
        journal.pendingAcks().forEach { entry -> pending.putIfAbsent(entry.messageId, entry) }
        messages.forEach { message ->
            process(message)
            journal.get(message.outerContext.messageId)?.let { entry ->
                if (entry.phase == RelayInboxJournal.TERMINAL && entry.outcome != null) {
                    pending.putIfAbsent(entry.messageId, entry)
                }
            }
        }
        pending.values.chunked(MAX_ACKS_PER_REQUEST).forEach { chunk ->
            try {
                api.acknowledge(chunk.map { RelayTerminalAck(it.messageId, checkNotNull(it.outcome)) })
                RemoteEventReceiptStore.commitRelayTerminalBatch(context, chunk.asSequence()
                    .filter { it.outcome == "processed" }
                    .map { it.eventId }
                    .toSet())
                journal.remove(chunk.map { it.messageId }.toSet())
            } catch (_: Exception) { return false }
        }
        return true
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
            RelayDomainEffects.apply(context, stored)
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

    private companion object { const val MAX_ACKS_PER_REQUEST = 100 }
}
