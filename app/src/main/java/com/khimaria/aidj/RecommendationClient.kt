package com.khimaria.aidj

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object RecommendationClient {
    private const val USER_AGENT = "AI-DJ-Android/0.7.2 (https://github.com/Khimaria333/ai-dj-android)"
    private const val MUSICBRAINZ_MIN_INTERVAL_MS = 1150L
    private const val CACHE_TTL_MS = 30L * 60L * 1000L

    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val musicBrainzLock = Any()
    @Volatile private var lastMusicBrainzRequestAt = 0L

    data class DiscoveryResult(
        val candidates: List<Track>,
        val seedTags: Set<String>,
        val sourceStatus: List<String> = emptyList()
    )

    fun discover(current: Track): DiscoveryResult {
        if (current.artist.isBlank()) return DiscoveryResult(emptyList(), emptySet(), listOf("artist_missing"))

        val status = mutableListOf<String>()
        val collected = linkedMapOf<String, Track>()
        val seedTags = linkedSetOf<String>()
        val seeds = splitArtistSeeds(current.artist)
        status += "artist_seeds:${seeds.joinToString("+")}" 

        // Collaborative credits such as "Sufle ve Teoman", "A & B", "A feat. B" used to be
        // searched as one literal artist and could collapse the pool to zero. Resolve each real
        // artist independently and merge the results.
        seeds.take(3).forEachIndexed { index, seedName ->
            val artist = runCatching { findArtist(seedName) }.getOrNull()
            if (artist == null) {
                status += "seed_${index}_lookup_failed"
                runCatching { searchRecordingsByArtist(seedName, 35) }
                    .onSuccess { rows ->
                        rows.forEach { collected.putIfAbsent(it.key, it) }
                        status += "seed_${index}_catalog:${rows.size}"
                    }
                    .onFailure { status += "seed_${index}_catalog_failed" }
                return@forEachIndexed
            }

            val tags = runCatching { fetchArtistTags(artist.first) }.getOrDefault(emptySet())
            seedTags += tags

            runCatching { fetchArtistRadio(artist.first, "easy", if (index == 0) 18 else 10, if (index == 0) 5 else 3) }
                .onSuccess { rows ->
                    rows.forEach { collected.putIfAbsent(it.key, it) }
                    status += "seed_${index}_easy:${rows.size}"
                }
                .onFailure { status += "seed_${index}_easy_failed" }

            runCatching { fetchArtistRadio(artist.first, "medium", if (index == 0) 24 else 12, if (index == 0) 4 else 2) }
                .onSuccess { rows ->
                    rows.forEach { collected.putIfAbsent(it.key, it) }
                    status += "seed_${index}_medium:${rows.size}"
                }
                .onFailure { status += "seed_${index}_medium_failed" }

            if (collected.size < 45) {
                runCatching { searchRecordingsByArtist(artist.second, 40) }
                    .onSuccess { rows ->
                        rows.forEach { collected.putIfAbsent(it.key, it) }
                        status += "seed_${index}_artist_catalog:${rows.size}"
                    }
                    .onFailure { status += "seed_${index}_artist_catalog_failed" }
            }
        }

        seedTags.take(3).forEach { tag ->
            if (collected.size >= 90) return@forEach
            runCatching { fetchTagRadio(tag) }
                .onSuccess { rows ->
                    rows.forEach { collected.putIfAbsent(it.key, it.copy(tags = it.tags + tag.lowercase())) }
                    status += "tag_${tag}:${rows.size}"
                }
                .onFailure { status += "tag_${tag}_failed" }
        }

        // Reliable floor: if recommendation endpoints are sparse, use direct catalog searches for
        // each parsed artist. These are still based on the current song, never old-session history.
        if (collected.size < 35) {
            seeds.take(3).forEachIndexed { index, seedName ->
                runCatching { searchRecordingsLoose(seedName, 45) }
                    .onSuccess { rows ->
                        rows.forEach { collected.putIfAbsent(it.key, it) }
                        status += "seed_${index}_loose:${rows.size}"
                    }
                    .onFailure { status += "seed_${index}_loose_failed" }
            }
        }

        // Last non-empty fallback for unusual credits: query the full credit text and the track
        // title. It is intentionally lower quality than artist-radio results but prevents a total
        // dead end when public metadata services have incomplete Turkish artist mappings.
        if (collected.isEmpty()) {
            runCatching { searchRecordingsLoose(current.artist, 50) }
                .onSuccess { rows -> rows.forEach { collected.putIfAbsent(it.key, it) }; status += "full_credit_fallback:${rows.size}" }
                .onFailure { status += "full_credit_fallback_failed" }
        }
        if (collected.isEmpty() && current.title.isNotBlank()) {
            runCatching { searchRecordingsLoose(current.title, 35) }
                .onSuccess { rows -> rows.forEach { collected.putIfAbsent(it.key, it) }; status += "title_fallback:${rows.size}" }
                .onFailure { status += "title_fallback_failed" }
        }

        // Never recommend the exact song that is currently playing.
        collected.remove(current.key)
        return DiscoveryResult(collected.values.take(140), seedTags.take(8).toSet(), status)
    }

    private fun splitArtistSeeds(raw: String): List<String> {
        val cleaned = raw
            .replace(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+"), " | ")
            .replace(Regex("(?i)\\s+[xX]\\s+"), " | ")
            .replace(Regex("\\s+[&+]\\s+"), " | ")
            .replace(Regex("(?i)\\s+ve\\s+"), " | ")
            .replace(Regex("\\s*,\\s*"), " | ")
        val parts = cleaned.split("|").map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }
        return if (parts.isEmpty()) listOf(raw.trim()) else parts
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
        return parseRecordingSearch(getJson("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=$limit"), "musicbrainz:artist")
    }

    private fun searchRecordingsLoose(query: String, limit: Int): List<Track> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return parseRecordingSearch(getJson("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=$limit"), "musicbrainz:loose")
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
        val now = System.currentTimeMillis()
        cache[url]?.let { (savedAt, value) ->
            if (now - savedAt <= CACHE_TTL_MS) return JSONObject(value.toString())
            cache.remove(url)
        }

        val isMusicBrainz = url.contains("musicbrainz.org")
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                if (isMusicBrainz) throttleMusicBrainz()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 9000
                conn.readTimeout = 9000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code in 200..299) {
                    val json = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    cache[url] = System.currentTimeMillis() to JSONObject(json.toString())
                    return json
                }
                if (code == 429 || code == 502 || code == 503 || code == 504) {
                    val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000L)
                    Thread.sleep(retryAfter ?: (900L * (attempt + 1)))
                    lastError = IllegalStateException("HTTP $code")
                } else {
                    throw IllegalStateException("HTTP $code")
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 2) Thread.sleep(650L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Request failed")
    }

    private fun throttleMusicBrainz() {
        synchronized(musicBrainzLock) {
            val now = System.currentTimeMillis()
            val wait = MUSICBRAINZ_MIN_INTERVAL_MS - (now - lastMusicBrainzRequestAt)
            if (wait > 0) Thread.sleep(wait)
            lastMusicBrainzRequestAt = System.currentTimeMillis()
        }
    }
}
