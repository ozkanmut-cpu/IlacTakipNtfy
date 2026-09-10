package com.ozkanmut.ilactakip

import java.net.URLEncoder

object NtfyEndpoint {
    const val BASE_URL = "https://ntfy.field-maintenance-prod.com"

    fun topicUrl(topic: String): String = "$BASE_URL/${encode(topic)}"

    fun pollUrl(topics: List<String>, since: String): String {
        val topicPath = topics.joinToString(",") { encode(it) }
        return "$BASE_URL/$topicPath/json?poll=1&since=${encode(since)}"
    }

    fun streamUrl(topics: List<String>, since: String): String {
        val topicPath = topics.joinToString(",") { encode(it) }
        return "$BASE_URL/$topicPath/json?since=${encode(since)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
