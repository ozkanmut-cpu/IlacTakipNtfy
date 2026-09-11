package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONObject

/** Persistent manual overrides for Dosefolk -> Orko Takip glucose timing anchors. */
object OrkoBridgeMappingStore {
    private const val PREFS = "orko_bridge_mapping"
    private const val KEY = "time_to_anchor"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, time: String): OrkoTakipBridge.Anchor? {
        val raw = prefs(context).getString(KEY, "{}") ?: "{}"
        return runCatching {
            val value = JSONObject(raw).optString(time)
            if (value.isBlank()) null else OrkoTakipBridge.Anchor.valueOf(value)
        }.getOrNull()
    }

    @Synchronized
    fun set(context: Context, time: String, anchor: OrkoTakipBridge.Anchor?) {
        if (time.isBlank()) return
        val current = runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        if (anchor == null) current.remove(time) else current.put(time, anchor.name)
        prefs(context).edit().putString(KEY, current.toString()).commit()
        DosefolkQaLog.record(
            context,
            DosefolkQaLog.Category.SYNC,
            "orko_bridge_mapping_changed",
            mapOf("time" to time, "anchor" to (anchor?.name ?: "AUTO"))
        )
    }

    fun all(context: Context): Map<String, OrkoTakipBridge.Anchor> {
        val raw = prefs(context).getString(KEY, "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { time ->
                    runCatching { OrkoTakipBridge.Anchor.valueOf(json.optString(time)) }
                        .getOrNull()
                        ?.let { put(time, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }
}
