package com.khimaria.aidj

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NowPlayingListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg != "com.google.android.apps.youtube.music" && pkg != "com.spotify.music") return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank()) return

        sendBroadcast(Intent(ACTION_NOW_PLAYING).apply {
            setPackage(packageName)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_ARTIST, text)
            putExtra(EXTRA_PACKAGE, pkg)
        })
    }

    companion object {
        const val ACTION_NOW_PLAYING = "com.khimaria.aidj.NOW_PLAYING"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_PACKAGE = "package"
    }
}
