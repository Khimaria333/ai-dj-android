package com.khimaria.aidj

data class Track(val title: String, val artist: String, val source: String) {
    val key: String get() = "${title.lowercase()}|${artist.lowercase()}"
    val display: String get() = if (artist.isBlank()) title else "$title — $artist"
}

object AutoDjEngine {
    fun recommendFromCandidates(
        current: Track?,
        candidates: List<Track>,
        history: List<Track>,
        liked: Set<String>,
        disliked: Set<String>,
        avoid: Set<String> = emptySet()
    ): Track? {
        val recent = history.take(5).map { it.key }.toSet() + avoid
        return candidates
            .distinctBy { it.key }
            .filter { it.key != current?.key && it.key !in recent && it.key !in disliked }
            .maxByOrNull { track ->
                var score = 0
                if (track.source == "listenbrainz") score += 35
                if (track.key in liked) score += 80
                if (current != null && track.artist.equals(current.artist, ignoreCase = true)) score -= 25
                val oldIndex = history.indexOfLast { it.key == track.key }
                if (oldIndex >= 0) score += oldIndex.coerceAtMost(30)
                score
            }
    }

    fun recommend(current: Track?, history: List<Track>, liked: Set<String>, disliked: Set<String>): Track? =
        recommendFromCandidates(current, history, history, liked, disliked)
}
