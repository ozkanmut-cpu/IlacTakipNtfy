package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PrnMedication(
    val id: String,
    val medicationId: String,
    val name: String,
    val doseNote: String = "",
    val minimumIntervalMinutes: Int? = null,
    val maximumPerDay: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PrnCheck(
    val allowed: Boolean,
    val reason: String? = null,
    val lastTakenAt: Long? = null,
    val takenToday: Int = 0
)

data class PrnUsage(val eventId:String,val medicationId:String,val timestamp:Long)

object PrnUsageLedger {
    private const val PREFS="dosefolk_prn_usage"
    private const val KEY="usage"
    private const val KEY_BACKFILLED="backfilled_v1"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)

    @Synchronized fun observe(c:Context,event:DoseEvent){
        if(event.type!="prn_taken"||event.eventId.isBlank())return
        val ownerId=event.ownerId.ifBlank{event.actorTopic}
        if(ownerId.isNotBlank()&&ownerId!=OwnerScopeStore.localOwnerId(c))return
        val rows=loadRaw(c).toMutableList()
        event.medications.distinctBy{it.id}.filter{it.id.isNotBlank()}.forEach{med->
            if(rows.none{it.eventId==event.eventId&&it.medicationId==med.id}) rows+=PrnUsage(event.eventId,med.id,event.timestamp)
        }
        save(c,compact(rows))
    }

    @Synchronized fun ensureBackfilled(c:Context){
        val p=prefs(c)
        if(p.getBoolean(KEY_BACKFILLED,false))return
        EventStore.load(c).filter{it.type=="prn_taken"}.forEach{observe(c,it)}
        p.edit().putBoolean(KEY_BACKFILLED,true).commit()
    }

    fun usages(c:Context,medicationId:String):List<PrnUsage>{
        ensureBackfilled(c)
        return loadRaw(c).filter{it.medicationId==medicationId}
    }

    private fun compact(rows:List<PrnUsage>):List<PrnUsage>{
        val cutoff=LocalDate.now().minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val recent=rows.filter{it.timestamp>=cutoff}
        val latestOlder=rows.filter{it.timestamp<cutoff}.groupBy{it.medicationId}.values.mapNotNull{group->group.maxByOrNull{it.timestamp}}
        return (recent+latestOlder).distinctBy{"${it.eventId}|${it.medicationId}"}
    }

    private fun loadRaw(c:Context):List<PrnUsage>{
        val raw=prefs(c).getString(KEY,"[]")?:"[]"
        return runCatching{val a=JSONArray(raw);(0 until a.length()).mapNotNull{i->a.optJSONObject(i)?.let{o->
            val eventId=o.optString("eventId");val medId=o.optString("medicationId");if(eventId.isBlank()||medId.isBlank())null else PrnUsage(eventId,medId,o.optLong("timestamp"))
        }}}.getOrDefault(emptyList())
    }
    private fun save(c:Context,rows:List<PrnUsage>){val a=JSONArray();rows.forEach{u->a.put(JSONObject().put("eventId",u.eventId).put("medicationId",u.medicationId).put("timestamp",u.timestamp))};prefs(c).edit().putString(KEY,a.toString()).commit()}
}

/**
 * PRN rules are user-entered guardrails only. Dosefolk never invents clinical limits.
 * A missing limit means "not configured", not "safe without limit".
 */
object PrnEngine {
    private const val PREFS = "dosefolk_prn"
    private const val KEY = "items"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<PrnMedication> = load(c)

    @Synchronized
    fun upsert(c: Context, item: PrnMedication) {
        save(c, listOf(item) + load(c).filterNot { it.id == item.id })
    }

    @Synchronized
    fun create(
        c: Context,
        medication: Medication,
        minimumIntervalMinutes: Int? = null,
        maximumPerDay: Int? = null
    ): PrnMedication {
        val item = PrnMedication(
            id = UUID.randomUUID().toString(),
            medicationId = medication.id,
            name = medication.name,
            doseNote = medication.dose,
            minimumIntervalMinutes = minimumIntervalMinutes?.takeIf { it > 0 },
            maximumPerDay = maximumPerDay?.takeIf { it > 0 }
        )
        upsert(c, item)
        return item
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        save(c, load(c).filterNot { it.id == id })
    }

    fun check(c: Context, item: PrnMedication, now: Long = System.currentTimeMillis()): PrnCheck {
        val usages = PrnUsageLedger.usages(c,item.medicationId)
        val last = usages.maxByOrNull { it.timestamp }
        val nowDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val startOfDay = nowDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = nowDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayCount = usages.count { it.timestamp in startOfDay until endOfDay }

        item.minimumIntervalMinutes?.let { min ->
            if (last != null && now - last.timestamp < min * 60_000L) {
                return PrnCheck(false, "minimum_interval", last.timestamp, todayCount)
            }
        }
        item.maximumPerDay?.let { max ->
            if (todayCount >= max) return PrnCheck(false, "daily_maximum", last?.timestamp, todayCount)
        }
        return PrnCheck(true, null, last?.timestamp, todayCount)
    }

    /** Records an explicitly user-confirmed PRN use; it never auto-administers. */
    fun recordTaken(c: Context, item: PrnMedication): PrnCheck {
        val check = check(c, item)
        if (!check.allowed) return check
        val med = Medication(item.medicationId, item.name, item.doseNote, emptyList())
        Ntfy.sendEvent(c, "prn_taken", "PRN", listOf(med))
        return check(c, item)
    }

    private fun load(c: Context): List<PrnMedication> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    PrnMedication(
                        id = o.optString("id"),
                        medicationId = o.optString("medicationId"),
                        name = o.optString("name"),
                        doseNote = o.optString("doseNote"),
                        minimumIntervalMinutes = if (o.has("minimumIntervalMinutes")) o.optInt("minimumIntervalMinutes") else null,
                        maximumPerDay = if (o.has("maximumPerDay")) o.optInt("maximumPerDay") else null,
                        createdAt = o.optLong("createdAt")
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, items: List<PrnMedication>) {
        val a = JSONArray()
        items.forEach { p ->
            val o = JSONObject()
                .put("id", p.id)
                .put("medicationId", p.medicationId)
                .put("name", p.name)
                .put("doseNote", p.doseNote)
                .put("createdAt", p.createdAt)
            p.minimumIntervalMinutes?.let { o.put("minimumIntervalMinutes", it) }
            p.maximumPerDay?.let { o.put("maximumPerDay", it) }
            a.put(o)
        }
        prefs(c).edit().putString(KEY, a.toString()).apply()
    }
}
