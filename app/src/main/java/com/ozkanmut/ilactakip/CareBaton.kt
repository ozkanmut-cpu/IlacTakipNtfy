package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class CareBatonClaim(
    val doseKey: String,
    val time: String,
    val actor: String,
    val actorTopic: String,
    val claimedAt: Long,
    val expiresAt: Long,
    val scheduledDate: String = LocalDate.now().toString()
)

object CareBatonStore {
    private const val PREFS = "dosefolk_care_baton"
    private const val KEY = "claims"
    private const val DEFAULT_MINUTES = 30L
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun doseKey(time: String, scheduledDate: String) = "$scheduledDate|$time"

    @Synchronized fun active(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()): CareBatonClaim? {
        cleanup(c)
        return load(c).firstOrNull { it.doseKey == doseKey(time, scheduledDate) }
    }

    fun claim(c: Context, time: String, minutes: Long = DEFAULT_MINUTES, scheduledDate: String = LocalDate.now().toString()): CareBatonClaim {
        val now = System.currentTimeMillis()
        val expiresAt = now + minutes.coerceAtLeast(5) * 60_000L
        val fallback = CareBatonClaim(doseKey(time, scheduledDate), time, Store.myName(c), Store.topic(c), now, expiresAt, scheduledDate)
        if (!Ntfy.sendEvent(c, "care_claimed", time, emptyList(), scheduledDate, snoozeUntil = expiresAt)) return fallback
        val event = EventStore.load(c).lastOrNull { it.type == "care_claimed" && it.time == time && it.scheduledDate == scheduledDate && it.actorTopic == Store.topic(c) }
        if (event != null) applyLocalClaim(c, event)
        return active(c, time, scheduledDate) ?: fallback
    }

    @Synchronized fun applyLocalClaim(c: Context, event: DoseEvent) = applyClaim(c, event)

    @Synchronized fun applyRemoteClaim(c: Context, event: DoseEvent) {
        cleanup(c)
        applyClaim(c, event)
    }

    private fun applyClaim(c: Context, event: DoseEvent) {
        val scheduledDate = event.scheduledDate.ifBlank { LocalDate.now().toString() }
        val expiresAt = event.snoozeUntil.takeIf { it > event.timestamp } ?: (event.timestamp + DEFAULT_MINUTES * 60_000L)
        val claim = CareBatonClaim(doseKey(event.time, scheduledDate), event.time, event.actor, event.actorTopic, event.timestamp, expiresAt, scheduledDate)
        if (claim.expiresAt <= System.currentTimeMillis()) return
        save(c, listOf(claim) + load(c).filterNot { it.doseKey == claim.doseKey })
        SmartEscalation.deferUntil(c, event.time, claim.expiresAt, scheduledDate)
    }

    fun release(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        if (!Ntfy.sendEvent(c, "care_released", time, emptyList(), scheduledDate)) return
        val event = EventStore.load(c).lastOrNull { it.type == "care_released" && it.time == time && it.scheduledDate == scheduledDate && it.actorTopic == Store.topic(c) }
        if (event != null) applyLocalRelease(c, event)
    }

    @Synchronized fun applyLocalRelease(c: Context, event: DoseEvent) = applyRelease(c, event)

    @Synchronized fun applyRemoteRelease(c: Context, event: DoseEvent) = applyRelease(c, event)

    private fun applyRelease(c: Context, event: DoseEvent) {
        val scheduledDate = event.scheduledDate.ifBlank { LocalDate.now().toString() }
        val key = doseKey(event.time, scheduledDate)
        val current = load(c)
        if (current.none { it.doseKey == key }) return
        resumeIfUnresolved(c, event.time, scheduledDate)
        save(c, current.filterNot { it.doseKey == key })
    }

    @Synchronized fun resolve(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        save(c, load(c).filterNot { it.doseKey == doseKey(time, scheduledDate) })
    }

    @Synchronized fun clearPeer(c: Context, topic: String) {
        if (topic.isBlank()) return
        val current = load(c)
        val removed = current.filter { it.actorTopic == topic }
        if (removed.isEmpty()) return
        removed.forEach { resumeIfUnresolved(c, it.time, it.scheduledDate) }
        save(c, current.filterNot { it.actorTopic == topic })
    }

    @Synchronized fun cleanup(c: Context) {
        reconcileLocalDurableEvents(c)
        val current = load(c)
        val now = System.currentTimeMillis()
        if (current.any { it.expiresAt <= now }) save(c, current.filter { it.expiresAt > now })
    }

    /** Rebuild local baton side effects after a crash that happened after EventStore append. */
    private fun reconcileLocalDurableEvents(c: Context) {
        val localTopic = Store.topic(c)
        val latest = EventStore.load(c)
            .filter { it.actorTopic == localTopic && it.type in setOf("care_claimed", "care_released") }
            .groupBy { doseKey(it.time, it.scheduledDate.ifBlank { LocalDate.now().toString() }) }
            .mapValues { (_, events) -> events.maxWithOrNull(DoseEventOrder.withinActor)!! }
        if (latest.isEmpty()) return
        var claims = load(c)
        var changed = false
        val now = System.currentTimeMillis()
        latest.forEach { (key, event) ->
            val scheduledDate = event.scheduledDate.ifBlank { LocalDate.now().toString() }
            if (event.type == "care_released") {
                if (claims.any { it.doseKey == key }) {
                    claims = claims.filterNot { it.doseKey == key }
                    changed = true
                }
            } else {
                val expiresAt = event.snoozeUntil.takeIf { it > event.timestamp } ?: (event.timestamp + DEFAULT_MINUTES * 60_000L)
                if (expiresAt > now) {
                    val claim = CareBatonClaim(key, event.time, event.actor, event.actorTopic, event.timestamp, expiresAt, scheduledDate)
                    val existing = claims.firstOrNull { it.doseKey == key }
                    if (existing != claim) {
                        claims = listOf(claim) + claims.filterNot { it.doseKey == key }
                        changed = true
                    }
                }
            }
        }
        if (changed) save(c, claims)
    }

    private fun resumeIfUnresolved(c: Context, time: String, scheduledDate: String) {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val status = DoseStateEngine.stateForTime(c, time, date).status
        if (status == DoseSessionStatus.PENDING || status == DoseSessionStatus.SNOOZED || status == DoseSessionStatus.CONFLICT) SmartEscalation.schedule(c, time, scheduledDate)
    }

    fun load(c: Context): List<CareBatonClaim> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    val storedDate = o.optString("scheduledDate")
                    val fallbackDate = o.optString("doseKey").substringBefore('|').takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: LocalDate.now().toString()
                    CareBatonClaim(o.optString("doseKey"), o.optString("time"), o.optString("actor"), o.optString("actorTopic"), o.optLong("claimedAt"), o.optLong("expiresAt"), storedDate.ifBlank { fallbackDate })
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, claims: List<CareBatonClaim>) {
        val a = JSONArray()
        claims.forEach { a.put(JSONObject().put("doseKey", it.doseKey).put("time", it.time).put("actor", it.actor).put("actorTopic", it.actorTopic).put("claimedAt", it.claimedAt).put("expiresAt", it.expiresAt).put("scheduledDate", it.scheduledDate)) }
        prefs(c).edit().putString(KEY, a.toString()).commit()
    }
}

object CircleState {
    fun unresolved(c: Context): List<DoseEvent> = DoseStateEngine.unresolved(c)
        .mapNotNull { state -> state.latestEvent ?: state.medications.firstOrNull()?.let { med -> DoseEvent("synthetic-${state.scheduledDate}-${state.time}", "alarm", state.time, "", "", 0L, listOf(med), "synced", scheduledDate = state.scheduledDate) } }
        .sortedBy { it.time }
}
