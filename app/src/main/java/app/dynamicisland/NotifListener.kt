package app.dynamicisland

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Gives the island access to (1) chat notifications from any messaging app
 * and (2) the active media session of any music/video app.
 */
class NotifListener : NotificationListenerService() {

    private var msm: MediaSessionManager? = null
    private val cn by lazy { ComponentName(this, NotifListener::class.java) }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { MediaWatcher.onSessions(it) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaWatcher.init(applicationContext)
        msm = getSystemService(MediaSessionManager::class.java)
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, cn)
            MediaWatcher.onSessions(msm?.getActiveSessions(cn))
        } catch (_: SecurityException) {
        }
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        MediaWatcher.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.enabled(this) || sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_MESSAGE) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val avatar = try {
            n.getLargeIcon()?.loadDrawable(this)?.let { Bitmaps.drawableToBitmap(it) } ?: senderAvatar(ex)
        } catch (_: Exception) {
            null
        }
        IslandBus.message(MessageInfo(sbn.key, title, text, avatar, n.contentIntent))
    }

    @Suppress("DEPRECATION")
    private fun senderAvatar(ex: Bundle) = try {
        val last = ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle
        val person = last?.getParcelable<Person>("sender_person")
        person?.icon?.loadDrawable(this)?.let { Bitmaps.drawableToBitmap(it) }
    } catch (_: Exception) {
        null
    }
}

/** Mirrors the currently active media session of other apps into the island. */
object MediaWatcher {
    private var appCtx: Context? = null
    private var ctrl: MediaController? = null

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
        override fun onSessionDestroyed() { clear() }
    }

    fun init(ctx: Context) { appCtx = ctx }

    fun onSessions(list: List<MediaController>?) {
        val l = list.orEmpty().filter { it.packageName != appCtx?.packageName }
        val pick = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
        if (pick?.sessionToken == ctrl?.sessionToken && pick != null) { publish(); return }
        ctrl?.unregisterCallback(cb)
        ctrl = pick
        pick?.registerCallback(cb)
        publish()
    }

    fun clear() {
        ctrl?.unregisterCallback(cb)
        ctrl = null
        IslandBus.setExt(null)
    }

    private fun publish() {
        val c = ctrl ?: run { IslandBus.setExt(null); return }
        val md = c.metadata
        if (md == null) { IslandBus.setExt(null); return }
        val ps = c.playbackState
        val st = ps?.state ?: PlaybackState.STATE_NONE
        val playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        IslandBus.setExt(
            MediaInfo(
                title, artist, art, playing,
                ps?.position ?: 0L,
                md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                Remote(c)
            )
        )
    }

    private class Remote(val c: MediaController) : Controls {
        override fun playPause() {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }
        override fun next() = c.transportControls.skipToNext()
        override fun prev() = c.transportControls.skipToPrevious()
        override fun seek(ms: Long) = c.transportControls.seekTo(ms)
        override fun openApp() { appCtx?.let { c.sessionActivity?.sendAllowed(it) } }
    }
}
