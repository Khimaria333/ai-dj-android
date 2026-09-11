package com.khimaria.aidj

import kotlin.math.abs

data class Track(
    val title: String,
    val artist: String,
    val source: String,
    val recordingMbid: String = "",
    val artistMbid: String = "",
    val tags: Set<String> = emptySet(),
    val popularity: Int? = null,
    val observedAtMs: Long = System.currentTimeMillis(),
    val releaseYear: Int? = null,
    val durationMs: Long? = null,
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val energy: Double? = null
) {
    val key: String get() = "${title.lowercase()}|${artist.lowercase()}"
    val display: String get() = if (artist.isBlank()) title else "$title — $artist"
}

enum class FlowMode { SAFE, BALANCED, DISCOVERY }

data class RankedRecommendation(
    val track: Track,
    val score: Int,
    val confidence: Int,
    val reasons: List<String>
)

object AutoDjEngine {
    const val SESSION_GAP_MS = 60L * 60L * 1000L

    fun rank(
        current: Track?,
        candidates: List<Track>,
        history: List<Track>,
        liked: Set<String>,
        disliked: Set<String>,
        avoid: Set<String> = emptySet(),
        seedTags: Set<String> = emptySet(),
        mode: FlowMode = FlowMode.BALANCED,
        skipCounts: Map<String, Int> = emptyMap(),
        listenedCounts: Map<String, Int> = emptyMap()
    ): List<RankedRecommendation> {
        val now = System.currentTimeMillis()
        val recentByClock = history.filter { it.observedAtMs > 0L && now - it.observedAtMs <= SESSION_GAP_MS }
        val sessionHistory = removeRestoreBurst(recentByClock)
        val sessionKeys = sessionHistory.take(8).map { it.key }.toSet()
        val recentArtists = sessionHistory.take(5).map { it.artist.lowercase() }.filter { it.isNotBlank() }
        val likedArtists = history.filter { it.key in liked }.map { it.artist.lowercase() }.toSet()
        val dislikedArtists = history.filter { it.key in disliked }.map { it.artist.lowercase() }.toSet()
        val seed = seedTags.map { normalizeTag(it) }.filter { it.isNotBlank() }.toSet()
        val explicitAvoid = avoid - (history.map { it.key }.toSet() - sessionKeys)

        return candidates.distinctBy { it.key }
            .filter { it.title.isNotBlank() && it.key != current?.key && it.key !in sessionKeys && it.key !in explicitAvoid && it.key !in disliked }
            .map { track -> scoreTrack(track, current, sessionHistory, recentArtists, liked, likedArtists, dislikedArtists, seed, mode, skipCounts, listenedCounts, history) }
            .sortedWith(compareByDescending<RankedRecommendation> { it.score }.thenByDescending { it.confidence }.thenBy { it.track.key })
    }

    private fun removeRestoreBurst(rows: List<Track>): List<Track> {
        if (rows.size < 3) return rows
        val timestamps = rows.map { it.observedAtMs }.sorted()
        val burst = timestamps.windowed(3).any { it.last() - it.first() < 2_000L }
        if (!burst) return rows
        val burstCenter = timestamps.groupBy { it / 2_000L }.maxByOrNull { it.value.size }?.key ?: return rows
        return rows.filter { it.observedAtMs / 2_000L != burstCenter }
    }

    private fun scoreTrack(
        track: Track, current: Track?, sessionHistory: List<Track>, recentArtists: List<String>, liked: Set<String>,
        likedArtists: Set<String>, dislikedArtists: Set<String>, seedTags: Set<String>, mode: FlowMode,
        skipCounts: Map<String, Int>, listenedCounts: Map<String, Int>, history: List<Track>
    ): RankedRecommendation {
        val reasons = mutableListOf<String>()
        val artistKey = track.artist.lowercase()
        val seenBefore = history.any { it.key == track.key }
        val relation = when {
            track.source.startsWith("artist:easy") -> 94.0
            track.source.startsWith("artist:medium") -> 84.0
            track.source.startsWith("tag:") -> 78.0
            track.source.startsWith("itunes:artist") -> 68.0
            track.source.startsWith("itunes:genre") -> 60.0
            track.source.startsWith("musicbrainz:") -> 58.0
            track.source == "history" -> 42.0
            else -> 55.0
        }
        if (relation >= 84) reasons += "güçlü katalog benzerliği"

        val trackTags = track.tags.map { normalizeTag(it) }.filter { it.isNotBlank() }.toSet()
        val overlap = trackTags.intersect(seedTags)
        val genreFit = when {
            seedTags.isEmpty() || trackTags.isEmpty() -> 55.0
            overlap.isNotEmpty() -> (76 + overlap.size * 8).coerceAtMost(100).toDouble()
            else -> 34.0
        }
        if (overlap.isNotEmpty()) reasons += "${overlap.take(2).joinToString("/")} çizgisi"

        var tasteFit = 52.0
        if (track.key in liked) { tasteFit += 38; reasons += "beğendiğin parça" }
        if (artistKey in likedArtists) { tasteFit += 20; reasons += "sevdiğin sanatçı çizgisi" }
        if (artistKey in dislikedArtists) tasteFit -= 32
        val listens = listenedCounts[track.key] ?: 0
        if (listens > 0) tasteFit += (listens * 4).coerceAtMost(12)
        val skips = skipCounts[track.key] ?: 0
        if (skips > 0) { tasteFit -= (skips * 28).coerceAtMost(70); reasons += "skip cezası" }
        tasteFit = tasteFit.coerceIn(0.0, 100.0)

        var sessionFit = 78.0
        val repeats = recentArtists.count { it == artistKey && artistKey.isNotBlank() }
        if (repeats > 0) sessionFit -= (repeats * 7).coerceAtMost(21)
        if (current != null && artistKey.isNotBlank() && artistKey == current.artist.lowercase()) sessionFit -= 5
        if (repeats > 0) reasons += "çeşitlilik dengesi"
        if (sessionHistory.isEmpty()) sessionFit = 72.0

        val noveltyFit = when (mode) {
            FlowMode.SAFE -> if (seenBefore) 78.0 else 60.0
            FlowMode.BALANCED -> if (seenBefore) 58.0 else 82.0
            FlowMode.DISCOVERY -> if (seenBefore) 35.0 else 96.0
        }
        if (!seenBefore) reasons += "yeni keşif"

        val metadataSignals = mutableListOf<Double>()
        current?.releaseYear?.let { cy -> track.releaseYear?.let { y ->
            val d = abs(cy - y); metadataSignals += when { d <= 2 -> 95.0; d <= 5 -> 85.0; d <= 10 -> 72.0; d <= 20 -> 56.0; else -> 42.0 }
            if (d <= 5) reasons += "dönem uyumu"
        } }
        current?.durationMs?.let { cd -> track.durationMs?.let { d -> if (cd > 0 && d > 0) metadataSignals += (100.0 - abs(cd - d).toDouble() / cd * 80.0).coerceIn(35.0, 100.0) } }
        current?.bpm?.let { cb -> track.bpm?.let { b -> val d = bpmDistance(cb, b); metadataSignals += (100.0 - d * 3.2).coerceIn(20.0, 100.0); if (d <= 5.0) reasons += "BPM uyumu" } }
        current?.energy?.let { ce -> track.energy?.let { e -> val d = abs(ce - e); metadataSignals += (100.0 - d * 100.0).coerceIn(20.0, 100.0); if (d <= .12) reasons += "enerji uyumu" } }
        current?.musicalKey?.takeIf { it.isNotBlank() }?.let { ck -> track.musicalKey?.takeIf { it.isNotBlank() }?.let { k -> metadataSignals += if (ck.equals(k, true)) 100.0 else 58.0; if (ck.equals(k, true)) reasons += "tonal uyum" } }
        val metadataFit = if (metadataSignals.isEmpty()) 55.0 else metadataSignals.average()
        val popularityFit = track.popularity?.let { p -> when { p in 25..90 -> 78.0; p > 90 -> if (mode == FlowMode.DISCOVERY) 58.0 else 72.0; else -> if (mode == FlowMode.SAFE) 45.0 else 62.0 } } ?: 55.0

        val weighted = relation * .25 + genreFit * .19 + tasteFit * .16 + sessionFit * .15 + noveltyFit * .10 + metadataFit * .10 + popularityFit * .05
        val score = weighted.toInt().coerceIn(0, 100)
        var confidence = 38 + when { relation >= 84 -> 18; relation >= 68 -> 12; else -> 6 }
        if (seedTags.isNotEmpty() && trackTags.isNotEmpty()) confidence += 12
        if (metadataSignals.isNotEmpty()) confidence += (metadataSignals.size * 8).coerceAtMost(24)
        if (track.popularity != null) confidence += 6
        if (track.key in liked || artistKey in likedArtists || skips > 0 || listens > 0) confidence += 8
        confidence = confidence.coerceIn(25, 98)
        if (metadataSignals.isEmpty()) reasons += "BPM/key verisi yok"
        return RankedRecommendation(track, score, confidence, reasons.distinct().take(4))
    }

    private fun normalizeTag(raw: String): String = raw.lowercase().trim()
        .replace("alternative rock", "rock").replace("turkish rock", "rock").replace("indie rock", "rock").replace("pop/rock", "rock")

    private fun bpmDistance(a: Double, b: Double): Double = listOf(abs(a - b), abs(a - b * 2.0), abs(a * 2.0 - b)).minOrNull() ?: abs(a - b)

    fun recommendFromCandidates(
        current: Track?, candidates: List<Track>, history: List<Track>, liked: Set<String>, disliked: Set<String>,
        avoid: Set<String> = emptySet(), seedTags: Set<String> = emptySet(), mode: FlowMode = FlowMode.BALANCED,
        skipCounts: Map<String, Int> = emptyMap(), listenedCounts: Map<String, Int> = emptyMap()
    ): Track? = rank(current, candidates, history, liked, disliked, avoid, seedTags, mode, skipCounts, listenedCounts).firstOrNull()?.track
}
