package com.khimaria.aidj

data class Track(val title: String, val artist: String, val source: String) {
    val key: String get() = "${title.lowercase()}|${artist.lowercase()}"
    val display: String get() = if (artist.isBlank()) title else "$title — $artist"
}

object AutoDjEngine {
    fun recommend(current: Track?, history: List<Track>, liked: Set<String>, disliked: Set<String>): Track? {
        val recent = history.take(3).map { it.key }.toSet()
        val candidates = history
            .distinctBy { it.key }
            .filter { it.key != current?.key && it.key !in recent && it.key !in disliked }
        return candidates.maxByOrNull { track ->
            var score = 0
            if (track.key in liked) score += 100
            if (current != null && track.artist.equals(current.artist, ignoreCase = true)) score -= 15
            score + history.indexOfLast { it.key == track.key }.coerceAtLeast(0)
        }
    }
}
