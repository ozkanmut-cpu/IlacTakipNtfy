package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    private fun doseKey(time: String) = "${java.time.LocalDate.now()}|$time"

    @Synchronized fun active(c: Context, time: String): CareBatonClaim? { cleanup(c); return load(c).firstOrNull { it.doseKey == doseKey(time) } }
    @Synchronized fun claim(c: Context, time: String, minutes: Long = DEFAULT_MINUTES): CareBatonClaim {
        cleanup(c); val now = System.currentTimeMillis(); val claim = CareBatonClaim(doseKey(time), time, Store.myName(c), Store.topic(c), now, now + minutes.coerceAtLeast(5) * 60_000L)
        save(c, listOf(claim) + load(c).filterNot { it.doseKey == claim.doseKey }); Ntfy.sendEvent(c, "care_claimed", time, emptyList()); return claim
    }
    @Synchronized fun applyRemoteClaim(c: Context, event: DoseEvent) {
        cleanup(c); val claim = CareBatonClaim(doseKey(event.time), event.time, event.actor, event.actorTopic, event.timestamp, event.timestamp + DEFAULT_MINUTES * 60_000L)
        if (claim.expiresAt <= System.currentTimeMillis()) return
        save(c, listOf(claim) + load(c).filterNot { it.doseKey == claim.doseKey })
    }
    @Synchronized fun release(c: Context, time: String) { save(c, load(c).filterNot { it.doseKey == doseKey(time) }); Ntfy.sendEvent(c, "care_released", time, emptyList()) }
    @Synchronized fun resolve(c: Context, time: String) { save(c, load(c).filterNot { it.doseKey == doseKey(time) }) }
    @Synchronized fun cleanup(c: Context) { val current = load(c); val valid = current.filter { it.expiresAt > System.currentTimeMillis() }; if (valid.size != current.size) save(c, valid) }
    fun load(c: Context): List<CareBatonClaim> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching { val a = JSONArray(raw); (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> CareBatonClaim(o.optString("doseKey"),o.optString("time"),o.optString("actor"),o.optString("actorTopic"),o.optLong("claimedAt"),o.optLong("expiresAt")) } } }.getOrDefault(emptyList())
    }
    private fun save(c: Context, claims: List<CareBatonClaim>) { val a = JSONArray(); claims.forEach { a.put(JSONObject().put("doseKey",it.doseKey).put("time",it.time).put("actor",it.actor).put("actorTopic",it.actorTopic).put("claimedAt",it.claimedAt).put("expiresAt",it.expiresAt)) }; prefs(c).edit().putString(KEY,a.toString()).apply() }
}

object CircleState {
    /** Includes pending, snoozed and explicit conflict sessions. */
    fun unresolved(c: Context): List<DoseEvent> = DoseStateEngine.unresolved(c)
        .mapNotNull { it.latestEvent ?: it.medications.firstOrNull()?.let { med ->
            DoseEvent("synthetic-${it.time}", "alarm", it.time, "", "", 0L, listOf(med), "synced")
        } }
        .sortedBy { it.time }
}
