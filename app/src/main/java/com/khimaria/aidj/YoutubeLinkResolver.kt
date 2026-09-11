package com.khimaria.aidj

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object YoutubeLinkResolver {
    fun resolveVideoId(recordingMbid: String): String? {
        if (recordingMbid.isBlank()) return null
        val endpoint = "https://musicbrainz.org/ws/2/recording/$recordingMbid?inc=url-rels&fmt=json"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AI-DJ/0.10 (android prototype)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val relations = root.optJSONArray("relations") ?: return null
            for (i in 0 until relations.length()) {
                val rel = relations.optJSONObject(i) ?: continue
                val target = rel.optJSONObject("url")?.optString("resource").orEmpty()
                parseVideoId(target)?.let { return it }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVideoId(url: String): String? {
        val watch = Regex("(?:youtube\\.com|music\\.youtube\\.com)/watch\\?[^#]*v=([A-Za-z0-9_-]{6,})", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
        if (!watch.isNullOrBlank()) return watch
        return Regex("youtu\\.be/([A-Za-z0-9_-]{6,})", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
    }
}
