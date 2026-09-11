package com.khimaria.aidj

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

object RecommendationClient {
    private const val USER_AGENT = "AI-DJ-Android/0.7 (https://github.com/Khimaria333/ai-dj-android)"

    data class DiscoveryResult(
        val candidates: List<Track>,
        val seedTags: Set<String>,
        val sourceStatus: List<String> = emptyList()
    )

    fun discover(current: Track): DiscoveryResult {
        if (current.artist.isBlank()) return DiscoveryResult(emptyList(), emptySet(), listOf("artist_missing"))

        val status = mutableListOf<String>()
        val artist = runCatching { findArtist(current.artist) }.getOrNull()
        if (artist == null) {
            val fallback = runCatching { searchRecordingsByArtist(current.artist, 30) }.getOrDefault(emptyList())
            return DiscoveryResult(fallback, emptySet(), listOf("artist_lookup_failed", "catalog_fallback"))
        }

        val seedTags = runCatching { fetchArtistTags(artist.first) }.getOrDefault(emptySet())
        val collected = linkedMapOf<String, Track>()

        runCatching { fetchArtistRadio(artist.first, "easy", 16, 5) }
            .onSuccess { rows -> rows.forEach { collected.putIfAbsent(it.key, it) }; status += "artist_easy:${rows.size}" }
            .onFailure { status += "artist_easy_failed" }

        runCatching { fetchArtistRadio(artist.first, "medium", 24, 4) }
            .onSuccess { rows -> rows.forEach { collected.putIfAbsent(it.key, it) }; status += "artist_medium:${rows.size}" }
            .onFailure { status += "artist_medium_failed" }

        seedTags.take(4).forEach { tag ->
            runCatching { fetchTagRadio(tag) }
                .onSuccess { rows ->
                    rows.forEach { collected.putIfAbsent(it.key, it.copy(tags = it.tags + tag.lowercase())) }
                    status += "tag_${tag}:${rows.size}"
                }
                .onFailure { status += "tag_${tag}_failed" }
        }

        if (collected.size < 35) {
            val rows = runCatching { searchRecordingsByArtist(artist.second, 35) }.getOrDefault(emptyList())
            rows.forEach { collected.putIfAbsent(it.key, it) }
            status += "artist_catalog:${rows.size}"
        }

        if (collected.size < 45) {
            seedTags.take(2).forEach { tag ->
                val rows = runCatching { searchRecordingsByTag(tag, 25) }.getOrDefault(emptyList())
                rows.forEach { collected.putIfAbsent(it.key, it.copy(tags = it.tags + tag.lowercase())) }
                status += "tag_catalog_${tag}:${rows.size}"
            }
        }

        return DiscoveryResult(collected.values.take(140), seedTags, status)
    }

    private fun fetchArtistRadio(artistMbid: String, mode: String, similarArtists: Int, recordingsPerArtist: Int): List<Track> {
        val url = "https://api.listenbrainz.org/1/lb-radio/artist/$artistMbid?mode=$mode&max_similar_artists=$similarArtists&max_recordings_per_artist=$recordingsPerArtist&pop_begin=3&pop_end=100"
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
            if (ids.size >= 90) break
        }
        return metadataFor(ids.distinct()).map { t ->
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
        val root = getJson("https://api.listenbrainz.org/1/lb-radio/tags?tag=$encoded&operator=OR&pop_begin=5&pop_end=98&count=50")
        val rows = extractArray(root)
        val ids = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val id = rows.optJSONObject(i)?.optString("recording_mbid").orEmpty()
            if (id.isNotBlank()) ids += id
        }
        return metadataFor(ids.distinct().take(50)).map { it.copy(source = "tag:${tag.lowercase()}", tags = setOf(tag.lowercase())) }
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

    private fun searchRecordingsByArtist(artist: String, limit: Int): List<Track> {
        val q = URLEncoder.encode("artist:\"$artist\"", StandardCharsets.UTF_8.toString())
        val root = getJson("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=$limit")
        return parseRecordingSearch(root, "musicbrainz:artist")
    }

    private fun searchRecordingsByTag(tag: String, limit: Int): List<Track> {
        val q = URLEncoder.encode("tag:\"$tag\"", StandardCharsets.UTF_8.toString())
        val root = getJson("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=$limit")
        return parseRecordingSearch(root, "musicbrainz:tag:$tag").map { it.copy(tags = setOf(tag.lowercase())) }
    }

    private fun parseRecordingSearch(root: JSONObject, source: String): List<Track> {
        val arr = root.optJSONArray("recordings") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            if (title.isBlank()) continue
            val credits = item.optJSONArray("artist-credit")
            var artist = ""
            var artistMbid = ""
            if (credits != null && credits.length() > 0) {
                val credit = credits.optJSONObject(0)
                artist = credit?.optString("name").orEmpty()
                val artistObj = credit?.optJSONObject("artist")
                if (artist.isBlank()) artist = artistObj?.optString("name").orEmpty()
                artistMbid = artistObj?.optString("id").orEmpty()
            }
            out += Track(title, artist, source, item.optString("id"), artistMbid)
        }
        return out.distinctBy { it.key }
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
        val json = getJson("https://musicbrainz.org/ws/2/artist/$mbid?inc=tags&fmt=json")
        val arr = json.optJSONArray("tags") ?: return emptySet()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim().lowercase()
            val count = o.optInt("count", 0)
            if (name.isNotBlank() && count >= 0) name to count else null
        }.sortedByDescending { it.second }
            .map { it.first }
            .filterNot { it in setOf("seen live", "favorites", "albums i own") }
            .take(6)
            .toSet()
    }

    private fun extractArray(root: JSONObject): JSONArray =
        root.optJSONArray("payload") ?: root.optJSONArray("recordings") ?: root.optJSONArray("results") ?: JSONArray()

    private fun popularityScore(listens: Long): Int {
        val log = kotlin.math.log10(listens.coerceAtLeast(1).toDouble())
        return (log * 18.0).toInt().coerceIn(0, 100)
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8500
        conn.readTimeout = 8500
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }
}
