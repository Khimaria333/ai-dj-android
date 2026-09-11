package com.khimaria.aidj

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

object RecommendationClient {
    private const val USER_AGENT = "AI-DJ-Android/0.5"

    fun discover(current: Track): List<Track> {
        if (current.artist.isBlank()) return emptyList()
        val artistMbid = findArtistMbid(current.artist) ?: return emptyList()
        val radioUrl = "https://api.listenbrainz.org/1/lb-radio/artist/$artistMbid?mode=easy&max_similar_artists=8&max_recordings_per_artist=3"
        val radioJson = getJson(radioUrl)
        val payload = radioJson.optJSONArray("payload") ?: radioJson.optJSONArray("recordings") ?: return emptyList()
        val mbids = mutableListOf<String>()
        for (i in 0 until payload.length()) {
            val id = payload.optJSONObject(i)?.optString("recording_mbid").orEmpty()
            if (id.isNotBlank()) mbids.add(id)
            if (mbids.size >= 20) break
        }
        if (mbids.isEmpty()) return emptyList()

        val ids = mbids.joinToString(",")
        val meta = getJson("https://api.listenbrainz.org/1/metadata/recording/?recording_mbids=$ids&inc=artist")
        val out = mutableListOf<Track>()
        val keys = meta.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = meta.optJSONObject(key) ?: continue
            val recording = obj.optJSONObject("recording") ?: obj
            val title = recording.optString("name").ifBlank { recording.optString("recording_name") }
            val artistObj = obj.optJSONObject("artist")
            val artist = artistObj?.optString("name").orEmpty().ifBlank {
                artistObj?.optString("artist_credit_name").orEmpty()
            }
            if (title.isNotBlank()) out.add(Track(title, artist, "listenbrainz"))
        }
        return out
    }

    private fun findArtistMbid(artist: String): String? {
        val q = URLEncoder.encode("artist:$artist", StandardCharsets.UTF_8.toString())
        val json = getJson("https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=5")
        val arr = json.optJSONArray("artists") ?: return null
        var bestId: String? = null
        var bestScore = -1
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val score = a.optInt("score", 0)
            val name = a.optString("name")
            val exactBonus = if (name.equals(artist, ignoreCase = true)) 1000 else 0
            if (score + exactBonus > bestScore) {
                bestScore = score + exactBonus
                bestId = a.optString("id")
            }
        }
        return bestId
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }
}
