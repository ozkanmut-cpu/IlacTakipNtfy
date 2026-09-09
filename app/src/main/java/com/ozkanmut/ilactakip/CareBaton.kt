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

    @Synchronized fun claim(c: Context, time: String, minutes: Long = DEFAULT_MINUTES, scheduledDate: String = LocalDate.now().toString()): CareBatonClaim {
        cleanup(c)
        val now = System.currentTimeMillis()
        val claim = CareBatonClaim(
            doseKey(time, scheduledDate), time, Store.myName(c), Store.topic(c),
            now, now + minutes.coerceAtLeast(5) * 60_000L, scheduledDate
        )
        save(c, listOf(claim) + load(c).filterNot { it.doseKey == claim.doseKey })
        SmartEscalation.deferUntil(c, time, claim.expiresAt, scheduledDate)
        Ntfy.sendEvent(c, "care_claimed", time, emptyList(), scheduledDate)
        return claim
    }

    @Synchronized fun applyRemoteClaim(c: Context, event: DoseEvent) {
        cleanup(c)
        val scheduledDate = event.scheduledDate.ifBlank { LocalDate.now().toString() }
        val claim = CareBatonClaim(
            doseKey(event.time, scheduledDate), event.time, event.actor, event.actorTopic,
            event.timestamp, event.timestamp + DEFAULT_MINUTES * 60_000L, scheduledDate
        )
        if (claim.expiresAt <= System.currentTimeMillis()) return
        save(c, listOf(claim) + load(c).filterNot { it.doseKey == claim.doseKey })
        SmartEscalation.deferUntil(c, event.time, claim.expiresAt, scheduledDate)
    }

    @Synchronized fun release(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        save(c, load(c).filterNot { it.doseKey == doseKey(time, scheduledDate) })
        Ntfy.sendEvent(c, "care_released", time, emptyList(), scheduledDate)
        resumeIfUnresolved(c, time, scheduledDate)
    }

    @Synchronized fun resolve(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        save(c, load(c).filterNot { it.doseKey == doseKey(time, scheduledDate) })
    }

    @Synchronized fun cleanup(c: Context) {
        val current = load(c)
        val now = System.currentTimeMillis()
        if (current.any { it.expiresAt <= now }) save(c, current.filter { it.expiresAt > now })
    }

    private fun resumeIfUnresolved(c: Context, time: String, scheduledDate: String) {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val status = DoseStateEngine.stateForTime(c, time, date).status
        if (status == DoseSessionStatus.PENDING || status == DoseSessionStatus.SNOOZED || status == DoseSessionStatus.CONFLICT) {
            SmartEscalation.schedule(c, time, scheduledDate)
        }
    }

    fun load(c: Context): List<CareBatonClaim> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    val storedDate = o.optString("scheduledDate")
                    val fallbackDate = o.optString("doseKey").substringBefore('|').takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                        ?: LocalDate.now().toString()
                    CareBatonClaim(
                        o.optString("doseKey"), o.optString("time"), o.optString("actor"), o.optString("actorTopic"),
                        o.optLong("claimedAt"), o.optLong("expiresAt"), storedDate.ifBlank { fallbackDate }
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, claims: List<CareBatonClaim>) {
        val a = JSONArray()
        claims.forEach {
            a.put(
                JSONObject()
                    .put("doseKey", it.doseKey)
                    .put("time", it.time)
                    .put("actor", it.actor)
                    .put("actorTopic", it.actorTopic)
                    .put("claimedAt", it.claimedAt)
                    .put("expiresAt", it.expiresAt)
                    .put("scheduledDate", it.scheduledDate)
            )
        }
        prefs(c).edit().putString(KEY, a.toString()).apply()
    }
}

object CircleState {
    fun unresolved(c: Context): List<DoseEvent> = DoseStateEngine.unresolved(c)
        .mapNotNull { state -> state.latestEvent ?: state.medications.firstOrNull()?.let { med ->
            DoseEvent(
                "synthetic-${state.scheduledDate}-${state.time}", "alarm", state.time, "", "", 0L,
                listOf(med), "synced", scheduledDate = state.scheduledDate
            )
        } }
        .sortedBy { it.time }
}
