package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Temporary operational ownership for an unresolved dose session. */
data class CareBatonClaim(
    val doseKey: String,
    val time: String,
    val actor: String,
    val actorTopic: String,
    val claimedAt: Long,
    val expiresAt: Long
)

object CareBatonStore {
    private const val PREFS = "dosefolk_care_baton"
    private const val KEY = "claims"
    private const val DEFAULT_MINUTES = 30L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun doseKey(time: String): String {
        val day = java.time.LocalDate.now().toString()
        return "$day|$time"
    }

    @Synchronized
    fun active(c: Context, time: String): CareBatonClaim? {
        cleanup(c)
        return load(c).firstOrNull { it.doseKey == doseKey(time) }
    }

    @Synchronized
    fun claim(c: Context, time: String, minutes: Long = DEFAULT_MINUTES): CareBatonClaim {
        cleanup(c)
        val now = System.currentTimeMillis()
        val claim = CareBatonClaim(
            doseKey = doseKey(time),
            time = time,
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            claimedAt = now,
            expiresAt = now + minutes.coerceAtLeast(5) * 60_000L
        )
        val remaining = load(c).filterNot { it.doseKey == claim.doseKey }
        save(c, listOf(claim) + remaining)
        Ntfy.sendEvent(c, "care_claimed", time, emptyList())
        return claim
    }

    @Synchronized
    fun release(c: Context, time: String) {
        val key = doseKey(time)
        save(c, load(c).filterNot { it.doseKey == key })
        Ntfy.sendEvent(c, "care_released", time, emptyList())
    }

    @Synchronized
    fun resolve(c: Context, time: String) {
        val key = doseKey(time)
        save(c, load(c).filterNot { it.doseKey == key })
    }

    @Synchronized
    fun cleanup(c: Context) {
        val now = System.currentTimeMillis()
        val current = load(c)
        val valid = current.filter { it.expiresAt > now }
        if (valid.size != current.size) save(c, valid)
    }

    fun load(c: Context): List<CareBatonClaim> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                CareBatonClaim(
                    doseKey = o.optString("doseKey"),
                    time = o.optString("time"),
                    actor = o.optString("actor"),
                    actorTopic = o.optString("actorTopic"),
                    claimedAt = o.optLong("claimedAt"),
                    expiresAt = o.optLong("expiresAt")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, claims: List<CareBatonClaim>) {
        val a = JSONArray()
        claims.forEach { claim ->
            a.put(
                JSONObject()
                    .put("doseKey", claim.doseKey)
                    .put("time", claim.time)
                    .put("actor", claim.actor)
                    .put("actorTopic", claim.actorTopic)
                    .put("claimedAt", claim.claimedAt)
                    .put("expiresAt", claim.expiresAt)
            )
        }
        prefs(c).edit().putString(KEY, a.toString()).apply()
    }
}

object CircleState {
    /** A session is unresolved when its latest local event is alarm/snoozed. */
    fun unresolved(c: Context): List<DoseEvent> {
        val todayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        return EventStore.load(c)
            .filter { it.timestamp >= todayStart }
            .groupBy { it.time }
            .mapNotNull { (_, events) ->
                val latest = events.maxByOrNull { it.timestamp } ?: return@mapNotNull null
                if (latest.type == "alarm" || latest.type == "snoozed") latest else null
            }
            .sortedBy { it.time }
    }
}
