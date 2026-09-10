package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlin.concurrent.thread

object SyncCheckpointStore {
    private const val PREFS = "dosefolk_sync"
    private const val LAST_ID = "last_ntfy_id"
    private const val LAST_SUCCESS = "last_success_ms"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun since(c: Context): String = prefs(c).getString(LAST_ID, null) ?: "24h"
    fun lastSuccess(c: Context): Long = prefs(c).getLong(LAST_SUCCESS, 0L)

    @Synchronized
    fun commitSuccessfulBatch(c: Context, newestId: String?, successAt: Long = System.currentTimeMillis()) {
        val edit = prefs(c).edit().putLong(LAST_SUCCESS, successAt)
        newestId?.takeIf { it.isNotBlank() }?.let { edit.putString(LAST_ID, it) }
        edit.commit()
    }
}

object NtfyBatchCursor {
    fun envelopeOrNull(line: String): JSONObject? = runCatching { JSONObject(line) }.getOrNull()

    fun advance(current: String?, envelope: JSONObject): String? {
        if (envelope.optString("event") != "message") return current
        return envelope.optString("id").takeIf { it.isNotBlank() } ?: current
    }
}

internal object NtfyReplayGuard {
    fun isTruncated(header: String?): Boolean = header?.trim() == "1"
}

internal object NtfyEnvelopeBinding {
    fun matches(envelope: JSONObject, payload: JSONObject): Boolean {
        val envelopeTopic = envelope.optString("topic").trim()
        val actorTopic = payload.optString("actorTopic").trim()
        return envelopeTopic.isNotBlank() && actorTopic.isNotBlank() && envelopeTopic == actorTopic
    }
}

internal object NtfyTargetRouting {
    fun accepts(localTopic: String, targetTopic: String): Boolean =
        targetTopic.isBlank() || targetTopic == localTopic

    fun accepts(localTopic: String, event: DoseEvent): Boolean =
        accepts(localTopic, event.targetTopic)

    fun accepts(localTopic: String, payload: JSONObject): Boolean =
        accepts(localTopic, payload.optString("targetTopic"))
}

object CircleTransport {
    fun publishTopic(c: Context): String = Store.topic(c)

    fun subscriptionTopics(c: Context): List<String> = normalizeTopics(
        listOf(Store.topic(c)) + Store.people(c).map { it.topic }
    )

    internal fun revokedDrainTopics(topic: String): List<String> = normalizeTopics(listOf(topic))

    internal fun normalizeTopics(topics: List<String>): List<String> =
        topics.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

object SyncEngine {
    fun lastSuccess(c: Context): Long = SyncCheckpointStore.lastSuccess(c)

    fun pullOnce(c: Context) {
        val context = c.applicationContext
        SgkStockAutoImporter.start(context)
        SgkStockAutoImporter.reconcile(context)
        PrescriptionNotifier.evaluate(context)
        thread { DosefolkSyncScheduler.ensure(context); pullBlocking(context) }
    }

    @Synchronized
    fun pullBlocking(c: Context): Boolean = pullTopicsBlocking(
        c = c.applicationContext,
        topics = CircleTransport.subscriptionTopics(c.applicationContext),
        since = SyncCheckpointStore.since(c.applicationContext),
        commitCheckpoint = true
    )

    @Synchronized
    fun drainRevokedPeerBlocking(c: Context, topic: String): Boolean {
        val normalized = CircleTransport.revokedDrainTopics(topic)
        if (normalized.isEmpty()) return false
        return pullTopicsBlocking(
            c = c.applicationContext,
            topics = normalized,
            since = "24h",
            commitCheckpoint = false
        )
    }

    private fun pullTopicsBlocking(c: Context, topics: List<String>, since: String, commitCheckpoint: Boolean): Boolean {
        val context = c.applicationContext
        if (topics.isEmpty()) return false
        val url = URL(NtfyEndpoint.pollUrl(topics, since))
        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) {
                connection.errorStream?.close()
                connection.disconnect()
                false
            } else if (NtfyReplayGuard.isTruncated(connection.getHeaderField("X-Messages-Truncated"))) {
                connection.inputStream.close()
                connection.disconnect()
                false
            } else {
                var newestId: String? = null
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val envelope = NtfyBatchCursor.envelopeOrNull(line) ?: return@forEach
                        newestId = NtfyBatchCursor.advance(newestId, envelope)
                        if (envelope.optString("event") != "message") return@forEach

                        val payload = runCatching { JSONObject(envelope.optString("message")) }.getOrNull() ?: return@forEach
                        if (!NtfyEnvelopeBinding.matches(envelope, payload)) return@forEach
                        if (!NtfyTargetRouting.accepts(Store.topic(context), payload)) return@forEach
                        if (StockSync.applyIncoming(context, payload)) return@forEach
                        if (!IncomingEventGuard.supportedDosePayload(payload)) {
                            InboundProtocolHealth.recordUnsupported(context, IncomingEventGuard.protocolVersion(payload))
                            return@forEach
                        }
                        val incoming = parseDoseEvent(payload) ?: return@forEach
                        if (!IncomingEventGuard.shouldProcess(context, incoming)) return@forEach

                        val event = persistCanonicalIncoming(context, incoming)
                        OwnerScopeStore.remember(context, event)
                        val ownerId = event.ownerId.ifBlank { event.actorTopic }
                        if (ownerId.isNotBlank() && ownerId != OwnerScopeStore.localOwnerId(context)) {
                            event.medicationMeta.forEach { MedicationMetaStore.saveRemote(context, ownerId, it) }
                        }
                        if (ownerId.isBlank() || ownerId == OwnerScopeStore.localOwnerId(context)) {
                            PrnUsageLedger.observe(context, event)
                            StockEngine.applyEvent(context, event)
                        }
                        applyRemoteState(context, event)
                        RemoteEventReceiptStore.markProcessed(context, event.eventId)
                    }
                }

                SnoozeRecovery.reconcileToday(context)
                UndoRecovery.recoverCurrent(context)
                if (commitCheckpoint) {
                    SyncCheckpointStore.commitSuccessfulBatch(context, newestId)
                    RemoteEventReceiptStore.commitSuccessfulBatch(context)
                }
                connection.disconnect()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun persistCanonicalIncoming(c: Context, incoming: DoseEvent): DoseEvent {
        val stored = incoming.copy(syncState = "synced")
        if (EventStore.appendIfAbsent(c, stored)) return stored
        return EventStore.load(c).firstOrNull { it.eventId == incoming.eventId } ?: stored
    }

    private fun parseDoseEvent(o: JSONObject): DoseEvent? {
        val eventId = o.optString("eventId")
        val type = o.optString("type")
        val time = o.optString("time")
        if (eventId.isBlank() || type.isBlank() || time.isBlank()) return null
        val medsJson = o.optJSONArray("medications") ?: JSONArray()
        val meds = (0 until medsJson.length()).mapNotNull { index ->
            medsJson.optJSONObject(index)?.let { med ->
                val timesJson = med.optJSONArray("times") ?: JSONArray()
                Medication(
                    med.optString("id"), med.optString("name"), med.optString("dose"),
                    (0 until timesJson.length()).map { timesJson.optString(it) }.filter { it.isNotBlank() }
                )
            }
        }
        val metaJson = o.optJSONArray("medicationMeta") ?: JSONArray()
        val meta = (0 until metaJson.length()).mapNotNull { MedicationMetaStore.fromJson(metaJson.optJSONObject(it)) }
        val timestamp = o.optLong("timestamp")
        val fallbackDate = if (timestamp > 0L) Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString() else ""
        return DoseEvent(
            eventId = eventId,
            type = type,
            time = time,
            actor = o.optString("actor"),
            actorTopic = o.optString("actorTopic"),
            timestamp = timestamp,
            medications = meds,
            syncState = "synced",
            revision = o.optLong("revision", 0L),
            scheduledDate = o.optString("scheduledDate", fallbackDate).ifBlank { fallbackDate },
            snoozeUntil = o.optLong("snoozeUntil", 0L),
            ownerId = o.optString("ownerId"),
            medicationMeta = meta,
            targetTopic = o.optString("targetTopic")
        )
    }

    internal fun applyRemoteState(c: Context, event: DoseEvent) {
        val scheduledDate = event.scheduledDate
        when (event.type) {
            "care_claimed" -> CareBatonStore.applyRemoteClaim(c, event)
            "care_released" -> CareBatonStore.applyRemoteRelease(c, event)
            "taken", "missed", "conflict_resolved_taken", "conflict_resolved_missed" -> {
                CareBatonStore.resolve(c, event.time, scheduledDate)
                SmartEscalation.cancel(c, event.time, scheduledDate)
                AlarmScheduler.cancelSnooze(c, event.time, scheduledDate)
                DoseNotificationLifecycle.cancel(c, event.time, scheduledDate)
            }
            "snoozed" -> {
                SmartEscalation.cancel(c, event.time, scheduledDate)
                DoseNotificationLifecycle.cancel(c, event.time, scheduledDate)
                AlarmScheduler.scheduleSnoozeIfActive(c, event.time, event.medications, event.snoozeUntil, scheduledDate)
            }
            "program_added", "program_updated", "program_deleted" -> ProgramSync.applyRemote(c, event)
            "program_rule_updated" -> ProgramRuleStore.applyRemote(c, event)
            "capability_edit_program_granted", "capability_edit_program_revoked",
            "capability_edit_stock_granted", "capability_edit_stock_revoked" -> {
                if (CapabilityEventGate.accept(c, event)) RemoteCapabilityStore.applyEvent(c, event)
            }
            "circle_presence" -> CirclePresence.markSeen(c, event)
            "circle_revoked" -> PairingLifecycle.applyRemoteRevoke(c, event)
        }
    }
}
