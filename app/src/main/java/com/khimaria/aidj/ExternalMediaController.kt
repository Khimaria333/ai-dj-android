package com.khimaria.aidj

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle

object ExternalMediaController {
    data class Result(
        val sent: Boolean,
        val method: String,
        val supportedActions: Long,
        val message: String
    )

    private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    fun playYoutubeMusicSearch(context: Context, title: String, artist: String): Result {
        val controller = findYoutubeMusicController(context)
            ?: return Result(false, "none", 0L, "YouTube Music aktif MediaSession bulunamadı")

        val queueId = findQueueItemId(controller, title, artist)
        if (queueId != null) {
            try {
                controller.transportControls.skipToQueueItem(queueId)
                return Result(true, "skipToQueueItem", controller.playbackState?.actions ?: 0L, "Hedef parça YouTube Music kuyruğunda bulundu")
            } catch (_: Throwable) {
                // Queue handoff failed; continue with search fallback.
            }
        }

        val query = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return Result(false, "none", controller.playbackState?.actions ?: 0L, "Arama metni boş")

        val actions = controller.playbackState?.actions ?: 0L
        return try {
            controller.transportControls.playFromSearch(query, Bundle.EMPTY)
            Result(true, "playFromSearch", actions, "YouTube Music'e playFromSearch gönderildi")
        } catch (t: Throwable) {
            Result(false, "playFromSearch", actions, t.message ?: t.javaClass.simpleName)
        }
    }

    fun playYoutubeMusicVideo(context: Context, videoId: String): Result {
        if (videoId.isBlank()) return Result(false, "none", 0L, "YouTube video kimliği boş")
        val controller = findYoutubeMusicController(context)
        val actions = controller?.playbackState?.actions ?: 0L

        if (controller != null && actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L) {
            try {
                controller.transportControls.playFromMediaId(videoId, Bundle.EMPTY)
                return Result(true, "playFromMediaId", actions, "YouTube video kimliği MediaSession'a gönderildi")
            } catch (_: Throwable) {
            }
        }

        val uri = Uri.parse("https://music.youtube.com/watch?v=$videoId&t=0")
        if (controller != null && actions and PlaybackState.ACTION_PLAY_FROM_URI != 0L) {
            return try {
                controller.transportControls.playFromUri(uri, Bundle.EMPTY)
                Result(true, "playFromUri", actions, "YouTube Music'e doğrudan parça URI'si gönderildi")
            } catch (_: Throwable) {
                openYoutubeMusicUri(context, uri, actions)
            }
        }
        return openYoutubeMusicUri(context, uri, actions)
    }

    private fun openYoutubeMusicUri(context: Context, uri: Uri, actions: Long): Result {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(YOUTUBE_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            Result(true, "watchUri", actions, "YouTube Music doğrudan watch URI ile açıldı")
        } catch (t: Throwable) {
            Result(false, "watchUri", actions, t.message ?: t.javaClass.simpleName)
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

    private fun findQueueItemId(controller: MediaController, title: String, artist: String): Long? {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        if (wantedTitle.isBlank()) return null
        return controller.queue.orEmpty().firstOrNull { item ->
            val qTitle = normalize(item.description.title?.toString().orEmpty())
            val qArtist = normalize(item.description.subtitle?.toString().orEmpty())
            qTitle == wantedTitle && (wantedArtist.isBlank() || qArtist.contains(wantedArtist) || wantedArtist.contains(qArtist))
        }?.queueId
    }

    private fun normalize(raw: String): String = raw.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun findYoutubeMusicController(context: Context): MediaController? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = ComponentName(context, NowPlayingListenerService::class.java)
        return runCatching { manager.getActiveSessions(listener) }
            .getOrDefault(emptyList())
            .firstOrNull { it.packageName == YOUTUBE_MUSIC }
    }
}
