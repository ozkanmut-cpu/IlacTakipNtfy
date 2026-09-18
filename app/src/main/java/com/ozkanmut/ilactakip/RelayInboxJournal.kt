package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable relay-only progress/ACK ledger; it never stores plaintext payloads or ciphertext. */
internal interface RelayInboxJournalStore {
    fun get(messageId: String): RelayInboxJournal.Entry?
    fun put(entry: RelayInboxJournal.Entry)
    fun pendingAcks(): List<RelayInboxJournal.Entry>
    fun remove(ids: Set<String>)
}

internal class RelayInboxJournal(context: Context) : RelayInboxJournalStore {
    private val prefs = context.applicationContext.getSharedPreferences("dosefolk_relay_inbox_journal", Context.MODE_PRIVATE)
    data class Entry(val messageId: String, val eventId: String, val eventHash: String, val phase: String, val outcome: String?)

    @Synchronized override fun get(messageId: String): Entry? = all().firstOrNull { it.messageId == messageId }
    @Synchronized override fun put(entry: Entry) { save(all().filterNot { it.messageId == entry.messageId } + entry) }
    @Synchronized override fun pendingAcks(): List<Entry> = all().filter { it.phase == TERMINAL }
    @Synchronized override fun remove(ids: Set<String>) { save(all().filterNot { it.messageId in ids }) }

    private fun all(): List<Entry> = try {
        val array = JSONArray(prefs.getString("entries", "[]") ?: "[]")
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            require(o.keys().asSequence().toSet() == setOf("messageId", "eventId", "eventHash", "phase", "outcome"))
            val entry = Entry(o.getString("messageId"), o.getString("eventId"), o.getString("eventHash"), o.getString("phase"), o.optString("outcome").ifBlank { null })
            RelayEnvelopeFormat.requireOpaqueId(entry.messageId); RelayEnvelopeFormat.requireOpaqueId(entry.eventId)
            require(entry.eventHash.matches(Regex("[0-9a-f]{64}")))
            require(entry.phase in setOf(STARTED, APPENDED, EFFECTS, TERMINAL))
            require((entry.phase == TERMINAL) == (entry.outcome != null))
            require(entry.outcome == null || entry.outcome in setOf("processed", "duplicate", "rejected"))
            entry
        }
    } catch (_: Exception) { throw java.security.GeneralSecurityException("Invalid relay journal") }

    private fun save(entries: List<Entry>) {
        val before = prefs.getString("entries", null)
        val encoded = JSONArray(entries.map { JSONObject().put("messageId", it.messageId).put("eventId", it.eventId).put("eventHash", it.eventHash).put("phase", it.phase).put("outcome", it.outcome ?: JSONObject.NULL) })
        if (!prefs.edit().putString("entries", encoded.toString()).commit()) {
            val rollback = prefs.edit(); if (before == null) rollback.remove("entries") else rollback.putString("entries", before); rollback.commit()
            throw java.io.IOException("Relay journal persistence failed")
        }
    }
    companion object { const val STARTED="started"; const val APPENDED="appended"; const val EFFECTS="effects"; const val TERMINAL="terminal" }
}
