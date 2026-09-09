package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray

/** Removes local data that is meaningful only while a Circle relationship exists. */
object RevocationCleanup {
    fun clearPeer(c: Context, topic: String) {
        if (topic.isBlank()) return
        clearRemoteCapabilities(c, topic)
        clearOwnerScope(c, topic)
    }

    private fun clearRemoteCapabilities(c: Context, topic: String) {
        val p = c.getSharedPreferences("dosefolk_remote_capabilities", Context.MODE_PRIVATE)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith("$topic|") }.forEach(edit::remove)
        edit.commit()
    }

    private fun clearOwnerScope(c: Context, topic: String) {
        val p = c.getSharedPreferences("dosefolk_owner_scope", Context.MODE_PRIVATE)
        fun filteredArray(key: String): String {
            val raw = p.getString(key, "[]") ?: "[]"
            val out = JSONArray()
            runCatching {
                val input = JSONArray(raw)
                for (i in 0 until input.length()) {
                    val o = input.optJSONObject(i) ?: continue
                    if (o.optString("ownerId") != topic) out.put(o)
                }
            }
            return out.toString()
        }
        val edit = p.edit()
            .putString("remote_programs", filteredArray("remote_programs"))
            .putString("remote_rules", filteredArray("remote_rules"))
        p.all.forEach { (key, value) ->
            if (key.startsWith("rule_stamp|$topic|")) edit.remove(key)
            if (key.startsWith("owner|") && value == topic) edit.remove(key)
        }
        edit.commit()
    }
}
