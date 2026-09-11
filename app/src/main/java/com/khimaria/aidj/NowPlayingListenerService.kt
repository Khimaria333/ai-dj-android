package com.khimaria.aidj

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NowPlayingListenerService : NotificationListenerService() {
    private val controllers = mutableListOf<MediaController>()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private var trackedTitle = ""
    private var trackedArtist = ""
    private var trackedPackage = ""
    private var listenedMs = 0L
    private var lastClockAt = 0L
    private var wasPlaying = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        clearControllers()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (!isSupported(pkg)) return

        // MediaSession is the primary source. Notification parsing is only a fallback.
        refreshSessions()
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isNotBlank() && controllers.none { it.packageName == pkg }) {
            publish(title, artist, pkg, PlaybackState.STATE_PLAYING, 0L, 0L, "notification")
        }
    }

    private fun refreshSessions() {
        val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, NowPlayingListenerService::class.java)
        val active = runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
            .filter { isSupported(it.packageName) }

        clearControllers()
        controllers.addAll(active)

        active.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publishController(controller)
                override fun onPlaybackStateChanged(state: PlaybackState?) = publishController(controller)
                override fun onSessionDestroyed() = refreshSessions()
            }
            callbacks[controller] = callback
            controller.registerCallback(callback)
        }

        val preferred = active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: active.firstOrNull()
        preferred?.let { publishController(it) }
    }

    private fun publishController(controller: MediaController) {
        val metadata = controller.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isBlank()) return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
            .ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim().orEmpty() }
        val state = controller.playbackState
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val position = state?.position?.coerceAtLeast(0L) ?: 0L
        publish(title, artist, controller.packageName, state?.state ?: PlaybackState.STATE_NONE, position, duration, "media_session")
    }

    private fun publish(
        title: String,
        artist: String,
        pkg: String,
        playbackState: Int,
        positionMs: Long,
        durationMs: Long,
        source: String
    ) {
        val now = System.currentTimeMillis()
        if (lastClockAt == 0L) lastClockAt = now
        if (wasPlaying && trackedTitle.isNotBlank()) listenedMs += (now - lastClockAt).coerceAtLeast(0L)

        val changed = title.lowercase() != trackedTitle.lowercase() ||
            artist.lowercase() != trackedArtist.lowercase() || pkg != trackedPackage

        val previousTitle = if (changed) trackedTitle else ""
        val previousArtist = if (changed) trackedArtist else ""
        val previousPackage = if (changed) trackedPackage else ""
        val previousListenedMs = if (changed) listenedMs else 0L

        if (changed) {
            trackedTitle = title
            trackedArtist = artist
            trackedPackage = pkg
            listenedMs = 0L
        }

        lastClockAt = now
        wasPlaying = playbackState == PlaybackState.STATE_PLAYING

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("title", title)
            .putString("artist", artist)
            .putString("package", pkg)
            .putInt("playback_state", playbackState)
            .putLong("position_ms", positionMs)
            .putLong("duration_ms", durationMs)
            .putLong("updated_at", now)
            .putString("source", source)
            .apply()

        sendBroadcast(Intent(ACTION_NOW_PLAYING).apply {
            setPackage(packageName)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_ARTIST, artist)
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_PLAYBACK_STATE, playbackState)
            putExtra(EXTRA_POSITION_MS, positionMs)
            putExtra(EXTRA_DURATION_MS, durationMs)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_PREVIOUS_TITLE, previousTitle)
            putExtra(EXTRA_PREVIOUS_ARTIST, previousArtist)
            putExtra(EXTRA_PREVIOUS_PACKAGE, previousPackage)
            putExtra(EXTRA_PREVIOUS_LISTENED_MS, previousListenedMs)
        })
    }

    private fun clearControllers() {
        callbacks.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        callbacks.clear()
        controllers.clear()
    }

    private fun isSupported(pkg: String) = pkg == "com.google.android.apps.youtube.music" || pkg == "com.spotify.music"

    companion object {
        const val ACTION_NOW_PLAYING = "com.khimaria.aidj.NOW_PLAYING"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_PLAYBACK_STATE = "playback_state"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_PREVIOUS_TITLE = "previous_title"
        const val EXTRA_PREVIOUS_ARTIST = "previous_artist"
        const val EXTRA_PREVIOUS_PACKAGE = "previous_package"
        const val EXTRA_PREVIOUS_LISTENED_MS = "previous_listened_ms"
        const val PREFS = "now_playing_state"
    }
}
