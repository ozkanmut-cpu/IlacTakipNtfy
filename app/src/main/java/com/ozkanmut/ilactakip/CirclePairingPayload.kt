package com.ozkanmut.ilactakip

import android.net.Uri

data class CirclePairingPayload(val topic: String, val name: String) {
    companion object {
        fun encode(topic: String, name: String): String = Uri.Builder()
            .scheme("dosefolk")
            .authority("pair")
            .appendQueryParameter("topic", topic)
            .appendQueryParameter("name", name)
            .build()
            .toString()

        fun parse(raw: String): CirclePairingPayload? {
            val value = raw.trim()
            if (value.startsWith("dosefolk://pair")) {
                val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
                val topic = uri.getQueryParameter("topic").orEmpty().trim()
                val name = uri.getQueryParameter("name").orEmpty().trim()
                if (topic.isBlank()) return null
                return CirclePairingPayload(topic, name)
            }
            if (value.startsWith("dosefolk-") && value.length >= 12) {
                return CirclePairingPayload(value, "")
            }
            return null
        }
    }
}
