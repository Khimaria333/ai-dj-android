package com.khimaria.aidj

data class Track(
    val title: String,
    val artist: String,
    val source: String,
    val recordingMbid: String = "",
    val artistMbid: String = "",
    val tags: Set<String> = emptySet(),
    val popularity: Int? = null
) {
    val key: String get() = "${title.lowercase()}|${artist.lowercase()}"
    val display: String get() = if (artist.isBlank()) title else "$title — $artist"
}

enum class FlowMode { SAFE, BALANCED, DISCOVERY }

data class RankedRecommendation(
    val track: Track,
    val score: Int,
    val reasons: List<String>
)

object AutoDjEngine {
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
        val recentTracks = history.take(8).map { it.key }.toSet() + avoid
        val recentArtists = history.take(5).map { it.artist.lowercase() }.filter { it.isNotBlank() }
        val likedArtists = history.filter { it.key in liked }.map { it.artist.lowercase() }.toSet()
        val dislikedArtists = history.filter { it.key in disliked }.map { it.artist.lowercase() }.toSet()
        val seed = seedTags.map { it.lowercase() }.toSet()

        return candidates
            .distinctBy { it.key }
            .filter { it.title.isNotBlank() && it.key != current?.key && it.key !in recentTracks && it.key !in disliked }
            .map { track ->
                var score = 50
                val reasons = mutableListOf<String>()
                val artistKey = track.artist.lowercase()
                val seenIndex = history.indexOfFirst { it.key == track.key }

                when {
                    track.source.startsWith("artist:easy") -> { score += 34; reasons += "yakın sanatçı" }
                    track.source.startsWith("artist:medium") -> { score += 27; reasons += "benzer sanatçı" }
                    track.source.startsWith("tag:") -> { score += 24; reasons += "tür / mood" }
                    track.source.startsWith("musicbrainz:") -> { score += 14; reasons += "katalog keşfi" }
                    track.source == "history" -> score -= 8
                }

                if (seenIndex < 0) {
                    score += when (mode) {
                        FlowMode.SAFE -> 5
                        FlowMode.BALANCED -> 16
                        FlowMode.DISCOVERY -> 28
                    }
                    reasons += "yeni keşif"
                } else {
                    score -= 10 + (8 - seenIndex.coerceAtMost(8))
                    reasons += "daha önce dinlendi"
                }

                if (track.key in liked) { score += 40; reasons += "beğendin" }
                if (artistKey in likedArtists) { score += 14; reasons += "sevdiğin sanatçı çizgisi" }
                if (artistKey in dislikedArtists) score -= 28

                val skips = skipCounts[track.key] ?: 0
                if (skips > 0) {
                    score -= (skips * 22).coerceAtMost(66)
                    reasons += "skip cezası"
                }
                val listens = listenedCounts[track.key] ?: 0
                if (listens > 0) score += (listens * 5).coerceAtMost(15)

                if (artistKey.isNotBlank()) {
                    val repeats = recentArtists.count { it == artistKey }
                    score -= repeats * 26
                    if (current != null && artistKey == current.artist.lowercase()) score -= 20
                }

                val overlap = track.tags.map { it.lowercase() }.toSet().intersect(seed)
                if (overlap.isNotEmpty()) {
                    score += (overlap.size * 8).coerceAtMost(24)
                    reasons += "${overlap.take(2).joinToString("/")} uyumu"
                }

                track.popularity?.let { pop ->
                    when {
                        pop in 28..88 -> { score += 10; reasons += "güçlü dinleyici sinyali" }
                        pop > 96 -> score -= if (mode == FlowMode.DISCOVERY) 8 else 0
                        pop < 6 -> score -= if (mode == FlowMode.SAFE) 12 else 3
                    }
                }

                // Small deterministic tiebreaker only; it is not presented as musical compatibility.
                score += ((track.key.hashCode() xor (current?.key?.hashCode() ?: 0)) and 3)
                RankedRecommendation(track, score, reasons.distinct().take(4))
            }
            .sortedByDescending { it.score }
    }

    fun recommendFromCandidates(
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
    ): Track? = rank(current, candidates, history, liked, disliked, avoid, seedTags, mode, skipCounts, listenedCounts).firstOrNull()?.track
}
