package com.khimaria.aidj

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle

object ExternalMediaController {
    data class Result(
        val sent: Boolean,
        val method: String,
        val supportedActions: Long,
        val message: String
    )

    private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    /**
     * Tries to ask YouTube Music's own active MediaSession to play an exact search query.
     * This uses Android's public MediaSession transport API and the notification-listener
     * permission that AI DJ already requires; it does not automate/tap YouTube Music's UI.
     */
    fun playYoutubeMusicSearch(context: Context, title: String, artist: String): Result {
        val controller = findYoutubeMusicController(context)
            ?: return Result(false, "none", 0L, "YouTube Music aktif MediaSession bulunamadı")

        val query = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return Result(false, "none", controller.playbackState?.actions ?: 0L, "Arama metni boş")

        val actions = controller.playbackState?.actions ?: 0L
        return try {
            // The platform method exists for all MediaSessions. Individual apps may choose
            // whether to implement the callback, so the APK exposes this as a capability test.
            controller.transportControls.playFromSearch(query, Bundle.EMPTY)
            Result(true, "playFromSearch", actions, "YouTube Music'e playFromSearch gönderildi")
        } catch (t: Throwable) {
            Result(false, "playFromSearch", actions, t.message ?: t.javaClass.simpleName)
        }
    }

    fun skipYoutubeMusicNext(context: Context): Result {
        val controller = findYoutubeMusicController(context)
            ?: return Result(false, "none", 0L, "YouTube Music aktif MediaSession bulunamadı")
        val actions = controller.playbackState?.actions ?: 0L
        return try {
            controller.transportControls.skipToNext()
            Result(true, "skipToNext", actions, "YouTube Music'e sonraki komutu gönderildi")
        } catch (t: Throwable) {
            Result(false, "skipToNext", actions, t.message ?: t.javaClass.simpleName)
        }
    }

    fun supportsPlayFromSearch(context: Context): Boolean {
        val actions = findYoutubeMusicController(context)?.playbackState?.actions ?: return false
        return actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L
    }

    private fun findYoutubeMusicController(context: Context): MediaController? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = ComponentName(context, NowPlayingListenerService::class.java)
        return runCatching { manager.getActiveSessions(listener) }
            .getOrDefault(emptyList())
            .firstOrNull { it.packageName == YOUTUBE_MUSIC }
    }
}
