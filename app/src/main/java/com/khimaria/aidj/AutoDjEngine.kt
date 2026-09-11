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
        mode: FlowMode = FlowMode.BALANCED
    ): List<RankedRecommendation> {
        val recentTracks = history.take(6).map { it.key }.toSet() + avoid
        val recentArtists = history.take(4).map { it.artist.lowercase() }.filter { it.isNotBlank() }
        val likedArtists = history.filter { it.key in liked }.map { it.artist.lowercase() }.toSet()
        val dislikedArtists = history.filter { it.key in disliked }.map { it.artist.lowercase() }.toSet()

        return candidates
            .distinctBy { it.key }
            .filter { it.title.isNotBlank() && it.key != current?.key && it.key !in recentTracks && it.key !in disliked }
            .map { track ->
                var score = 50
                val reasons = mutableListOf<String>()
                val artistKey = track.artist.lowercase()
                val seenIndex = history.indexOfFirst { it.key == track.key }

                when {
                    track.source.startsWith("artist:easy") -> { score += 34; reasons += "yakın sanatçı eşleşmesi" }
                    track.source.startsWith("artist:medium") -> { score += 27; reasons += "benzer sanatçı keşfi" }
                    track.source.startsWith("tag:") -> { score += 22; reasons += "tür/mood eşleşmesi" }
                    track.source == "history" -> score += 5
                }

                if (seenIndex < 0) {
                    score += when (mode) {
                        FlowMode.SAFE -> 7
                        FlowMode.BALANCED -> 17
                        FlowMode.DISCOVERY -> 30
                    }
                    reasons += "yeni keşif"
                } else {
                    score += (12 - seenIndex.coerceAtMost(12)) / 2
                }

                if (track.key in liked) { score += 38; reasons += "beğeni geçmişin" }
                if (artistKey in likedArtists) { score += 14; reasons += "sevdiğin sanatçı çizgisi" }
                if (artistKey in dislikedArtists) score -= 25

                if (artistKey.isNotBlank()) {
                    val repeats = recentArtists.count { it == artistKey }
                    score -= repeats * 24
                    if (current != null && artistKey == current.artist.lowercase()) score -= 18
                }

                val overlap = track.tags.map { it.lowercase() }.toSet().intersect(seedTags.map { it.lowercase() }.toSet())
                if (overlap.isNotEmpty()) {
                    score += (overlap.size * 7).coerceAtMost(21)
                    reasons += "${overlap.take(2).joinToString("/")} uyumu"
                }

                track.popularity?.let { pop ->
                    when {
                        pop in 35..85 -> { score += 10; reasons += "güçlü dinleyici sinyali" }
                        pop > 95 -> score -= if (mode == FlowMode.DISCOVERY) 8 else 0
                        pop < 8 -> score -= if (mode == FlowMode.SAFE) 10 else 2
                    }
                }

                val deterministicVariety = ((track.key.hashCode() xor (current?.key?.hashCode() ?: 0)) and 7)
                score += deterministicVariety

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
        mode: FlowMode = FlowMode.BALANCED
    ): Track? = rank(current, candidates, history, liked, disliked, avoid, seedTags, mode).firstOrNull()?.track
}
