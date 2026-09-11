package com.khimaria.aidj

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

object RecommendationClient {
    private const val USER_AGENT = "AI-DJ-Android/0.6 (https://github.com/Khimaria333/ai-dj-android)"

    data class DiscoveryResult(
        val candidates: List<Track>,
        val seedTags: Set<String>
    )

    fun discover(current: Track): DiscoveryResult {
        if (current.artist.isBlank()) return DiscoveryResult(emptyList(), emptySet())
        val artist = findArtist(current.artist) ?: return DiscoveryResult(emptyList(), emptySet())
        val seedTags = fetchArtistTags(artist.first)

        val collected = linkedMapOf<String, Track>()
        fetchArtistRadio(artist.first, "easy", 10, 4).forEach { collected.putIfAbsent(it.key, it) }
        fetchArtistRadio(artist.first, "medium", 14, 3).forEach { collected.putIfAbsent(it.key, it) }

        seedTags.take(3).forEach { tag ->
            fetchTagRadio(tag).forEach { collected.putIfAbsent(it.key, it.copy(tags = it.tags + tag.lowercase())) }
        }

        return DiscoveryResult(collected.values.take(90), seedTags)
    }

    private fun fetchArtistRadio(artistMbid: String, mode: String, similarArtists: Int, recordingsPerArtist: Int): List<Track> {
        val url = "https://api.listenbrainz.org/1/lb-radio/artist/$artistMbid?mode=$mode&max_similar_artists=$similarArtists&max_recordings_per_artist=$recordingsPerArtist&pop_begin=5&pop_end=100"
        val root = getJson(url)
        val rows = extractArray(root)
        val ids = mutableListOf<String>()
        val artistNames = mutableMapOf<String, String>()
        val popularity = mutableMapOf<String, Int>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val id = item.optString("recording_mbid")
            if (id.isBlank()) continue
            ids += id
            artistNames[id] = item.optString("similar_artist_name")
            val listens = item.optLong("total_listen_count", 0L)
            if (listens > 0) popularity[id] = popularityScore(listens)
            if (ids.size >= 60) break
        }
        return metadataFor(ids).map { t ->
            val id = t.recordingMbid
            t.copy(
                artist = t.artist.ifBlank { artistNames[id].orEmpty() },
                source = "artist:$mode",
                popularity = popularity[id]
            )
        }
    }

    private fun fetchTagRadio(tag: String): List<Track> {
        val encoded = URLEncoder.encode(tag, StandardCharsets.UTF_8.toString())
        val root = getJson("https://api.listenbrainz.org/1/lb-radio/tags?tag=$encoded&operator=OR&pop_begin=8&pop_end=96&count=35")
        val rows = extractArray(root)
        val ids = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val id = item.optString("recording_mbid")
            if (id.isNotBlank()) ids += id
        }
        return metadataFor(ids.distinct().take(35)).map { it.copy(source = "tag:${tag.lowercase()}", tags = setOf(tag.lowercase())) }
    }

    private fun metadataFor(ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Track>()
        ids.chunked(25).forEach { chunk ->
            val joined = chunk.joinToString(",")
            val root = getJson("https://api.listenbrainz.org/1/metadata/recording/?recording_mbids=$joined&inc=artist")
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = root.optJSONObject(id) ?: continue
                val recording = obj.optJSONObject("recording") ?: obj
                val title = recording.optString("name").ifBlank { recording.optString("recording_name") }
                val artistObj = obj.optJSONObject("artist")
                val artist = artistObj?.optString("name").orEmpty().ifBlank { artistObj?.optString("artist_credit_name").orEmpty() }
                val artistMbid = artistObj?.optString("artist_mbid").orEmpty().ifBlank { artistObj?.optString("mbid").orEmpty() }
                if (title.isNotBlank()) out += Track(title, artist, "listenbrainz", id, artistMbid)
            }
        }
        return out
    }

    private fun findArtist(name: String): Pair<String, String>? {
        val q = URLEncoder.encode("artist:\"$name\"", StandardCharsets.UTF_8.toString())
        val json = getJson("https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=6")
        val arr = json.optJSONArray("artists") ?: return null
        var best: Pair<String, String>? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val candidateName = a.optString("name")
            val score = a.optInt("score", 0) + if (candidateName.equals(name, true)) 1000 else 0
            if (score > bestScore) {
                bestScore = score
                best = a.optString("id") to candidateName
            }
        }
        return best?.takeIf { it.first.isNotBlank() }
    }

    private fun fetchArtistTags(mbid: String): Set<String> {
        return runCatching {
            val json = getJson("https://musicbrainz.org/ws/2/artist/$mbid?inc=tags&fmt=json")
            val arr = json.optJSONArray("tags") ?: return@runCatching emptySet<String>()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim().lowercase()
                val count = o.optInt("count", 0)
                if (name.isNotBlank() && count >= 0) name to count else null
            }.sortedByDescending { it.second }.map { it.first }.filterNot { it in setOf("seen live", "favorites", "albums i own") }.take(5).toSet()
        }.getOrDefault(emptySet())
    }

    private fun extractArray(root: JSONObject): JSONArray {
        return root.optJSONArray("payload")
            ?: root.optJSONArray("recordings")
            ?: root.optJSONArray("results")
            ?: JSONArray()
    }

    private fun popularityScore(listens: Long): Int {
        val log = kotlin.math.log10(listens.coerceAtLeast(1).toDouble())
        return (log * 18.0).toInt().coerceIn(0, 100)
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 9000
        conn.readTimeout = 9000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }
}
