package app.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings

class IslandService : Service() {

    companion object {
        const val ACTION_STOP = "app.dynamicisland.STOP"
        const val ACTION_CLEAR = "app.dynamicisland.CLEAR"
        const val ACTION_TEST_MESSAGE = "app.dynamicisland.TEST_MESSAGE"
        const val ACTION_TEST_MUSIC = "app.dynamicisland.TEST_MUSIC"
        const val ACTION_TEST_ONGOING = "app.dynamicisland.TEST_ONGOING"
        const val ACTION_PLAY_URIS = "app.dynamicisland.PLAY_URIS"
        const val ACTION_REFRESH = "app.dynamicisland.REFRESH"
        const val ACTION_MEDIA_PLAYPAUSE = "app.dynamicisland.MEDIA_PLAYPAUSE"
        const val ACTION_MEDIA_NEXT = "app.dynamicisland.MEDIA_NEXT"
        const val ACTION_MEDIA_PREV = "app.dynamicisland.MEDIA_PREV"
        const val EXTRA_URIS = "uris"
        private const val CHANNEL = "island"
    }

    private lateinit var window: IslandWindow
    private lateinit var player: PlayerEngine
    private var ready = false
    private var counter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        window = IslandWindow(this)
        player = PlayerEngine(this)
        IslandBus.listener = object : IslandBus.Listener {
            override fun onChanged() { window.update(IslandBus.media(), IslandBus.ongoing) }
            override fun onMessage(m: MessageInfo) { window.showMessage(m) }
        }
        ready = true
        window.update(IslandBus.media(), IslandBus.ongoing)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ready) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> { Prefs.setEnabled(this, false); stopSelf(); return START_NOT_STICKY }
            ACTION_CLEAR -> { player.release(); IslandBus.setOngoing(null) }
            ACTION_REFRESH -> window.onConfigChanged()
            ACTION_MEDIA_PLAYPAUSE -> player.playPause()
            ACTION_MEDIA_NEXT -> player.next()
            ACTION_MEDIA_PREV -> player.prev()
            ACTION_TEST_MESSAGE -> sendTestMessage()
            ACTION_TEST_MUSIC -> player.playDemo()
            ACTION_TEST_ONGOING ->
                IslandBus.setOngoing(OngoingInfo(getString(R.string.ongoing_label), SystemClock.elapsedRealtime()))
            ACTION_PLAY_URIS -> {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
                if (!list.isNullOrEmpty()) player.playQueue(list)
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ready) window.onConfigChanged()
    }

    override fun onDestroy() {
        if (ready) {
            window.destroy()
            player.release()
            IslandBus.listener = null
            IslandBus.clear()
        }
        super.onDestroy()
    }

    private fun sendTestMessage() {
        val people = arrayOf(
            "سارا" to "سلام! امشب میای دورهمی؟ 🎉",
            "Alex" to "Hey, did you see the new build?",
            "مامان" to "رسیدی خونه بهم خبر بده",
            "Nima" to "فایل‌ها رو فرستادم، ببین"
        )
        val (name, text) = people[counter++ % people.size]
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        IslandBus.message(MessageInfo("test$counter", name, text, Bitmaps.initials(name), open))
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(getString(R.string.fg_title))
            .setContentText(getString(R.string.fg_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close), getString(R.string.stop_label), stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
