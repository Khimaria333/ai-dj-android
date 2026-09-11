package com.khimaria.aidj

import kotlin.math.abs

data class Track(val title: String, val artist: String, val source: String) {
    val key: String get() = "${title.lowercase()}|${artist.lowercase()}"
    val display: String get() = if (artist.isBlank()) title else "$title — $artist"
}

object AutoDjEngine {
    fun recommend(current: Track?, history: List<Track>, liked: Set<String>, disliked: Set<String>): Track? {
        if (current == null) return null

        // Son dinlenenleri tekrar önermeyerek aynı parçaya takılmayı engelle.
        val recentKeys = history.take(4).map { it.key }.toSet()
        val uniqueHistory = history.distinctBy { it.key }
        val candidates = uniqueHistory.filter {
            it.key != current.key && it.key !in recentKeys && it.key !in disliked
        }

        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { track ->
            var score = 0

            // Kullanıcı geri bildirimi en güçlü sinyal.
            if (track.key in liked) score += 60

            // Aynı sanatçıyı art arda getirmeyi azalt.
            if (track.artist.isNotBlank() && track.artist.equals(current.artist, ignoreCase = true)) {
                score -= 25
            }

            // Biraz daha uzun süredir çalınmayan parçaya küçük avantaj ver.
            val historyIndex = uniqueHistory.indexOfFirst { it.key == track.key }.coerceAtLeast(0)
            score += historyIndex * 3

            // Geçerli parçaya göre aday sıralamasını değiştir.
            // Böylece tek bir eski şarkı sürekli öneride sabit kalmaz.
            val transitionScore = abs((current.key + "->" + track.key).hashCode()) % 50
            score += transitionScore

            score
        }
    }
}
