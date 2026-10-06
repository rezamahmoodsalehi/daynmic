package app.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Built-in music player: plays a generated demo loop or a queue of files picked from the Files app. */
class PlayerEngine(private val ctx: Context) : Controls {
    private var mp: MediaPlayer? = null
    private var queue: List<Uri> = emptyList()
    private var index = 0
    private var demo = false
    private var title = ""
    private var artist = ""
    private var art: Bitmap? = null

    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var focusReq: AudioFocusRequest? = null

    /** MediaSession: lock screen, Bluetooth buttons, headset keys and the system media controls. */
    private val session = MediaSession(ctx, "DynamicIsland").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (!playingNow()) playPause() }
            override fun onPause() { if (playingNow()) playPause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { prev() }
            override fun onSeekTo(pos: Long) { seek(pos) }
            override fun onStop() { stopInternal() }
        })
    }

    private fun playingNow() = try { mp?.isPlaying == true } catch (_: Exception) { false }

    fun playDemo() {
        demo = true
        queue = emptyList()
        val f = File(ctx.cacheDir, "island_demo.wav")
        if (!f.exists()) WavSynth.write(f)
        title = ctx.getString(R.string.demo_title)
        artist = ctx.getString(R.string.demo_artist)
        art = Bitmaps.musicArt(title)
        begin { it.setDataSource(f.absolutePath); it.isLooping = true }
    }

    fun playQueue(list: List<Uri>) {
        demo = false
        queue = list
        index = 0
        playIndex()
    }

    private fun playIndex() {
        val uri = queue.getOrNull(index) ?: return
        readMeta(uri)
        begin { it.setDataSource(ctx, uri) }
    }

    private fun begin(setup: (MediaPlayer) -> Unit) {
        stopInternal()
        requestFocus()
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(attrs)
            setup(p)
            p.setOnPreparedListener { it.start(); publish() }
            p.setOnCompletionListener { onDone() }
            p.setOnErrorListener { _, _, _ -> stopInternal(); true }
            p.prepareAsync()
        } catch (e: Exception) {
            stopInternal()
        }
    }

    private fun onDone() {
        if (!demo && index + 1 < queue.size) { index++; playIndex() } else publish()
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    try { mp?.pause() } catch (_: Exception) {}
                    publish()
                }
            }.build()
        focusReq = req
        am.requestAudioFocus(req)
    }

    private fun readMeta(uri: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: displayName(uri)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            art = r.embeddedPicture?.let { bytes ->
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val s = 256f / max(bmp.width, bmp.height)
                if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
            }
        } catch (_: Exception) {
            title = displayName(uri); artist = ""; art = null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        if (art == null) art = Bitmaps.musicArt(title)
    }

    private fun displayName(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: ""
    }

    private fun publish() {
        val p = mp ?: run { IslandBus.setOwn(null); return }
        val playing = try { p.isPlaying } catch (_: Exception) { false }
        val pos = try { p.currentPosition.toLong() } catch (_: Exception) { 0L }
        val dur = try { p.duration.toLong() } catch (_: Exception) { 0L }
        IslandBus.setOwn(MediaInfo(title, artist, art, playing, pos, dur, SystemClock.elapsedRealtime(), this, demo))
        updateSession(playing, pos, dur)
    }

    private fun updateSession(playing: Boolean, pos: Long, dur: Long) {
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
        art?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(md.build())
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder().setActions(actions).setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                pos, if (playing) 1f else 0f, SystemClock.elapsedRealtime()
            ).build()
        )
        session.isActive = true
        postNotification(playing)
    }

    private fun postNotification(playing: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("media", ctx.getString(R.string.media_channel), NotificationManager.IMPORTANCE_LOW)
        )
        fun act(res: Int, label: Int, action: String, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                ctx, code, Intent(ctx, IslandService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Action.Builder(Icon.createWithResource(ctx, res), ctx.getString(label), pi).build()
        }
        val n = Notification.Builder(ctx, "media")
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(art)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(R.drawable.ic_prev, R.string.prev_label, IslandService.ACTION_MEDIA_PREV, 11))
            .addAction(
                if (playing) act(R.drawable.ic_pause, R.string.pause_label, IslandService.ACTION_MEDIA_PLAYPAUSE, 12)
                else act(R.drawable.ic_play, R.string.play_label, IslandService.ACTION_MEDIA_PLAYPAUSE, 12)
            )
            .addAction(act(R.drawable.ic_next, R.string.next_label, IslandService.ACTION_MEDIA_NEXT, 13))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        nm.notify(7, n)
    }

    private fun stopInternal() {
        mp?.let { try { it.release() } catch (_: Exception) {} }
        mp = null
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        focusReq = null
        IslandBus.setOwn(null)
        session.isActive = false
        ctx.getSystemService(NotificationManager::class.java).cancel(7)
    }

    fun release() { stopInternal(); session.release() }

    // ---- Controls
    override fun playPause() {
        val p = mp ?: return
        try {
            if (p.isPlaying) p.pause() else { requestFocus(); p.start() }
        } catch (_: Exception) {}
        publish()
    }

    override fun next() {
        if (demo) { mp?.seekTo(0); publish() }
        else if (index + 1 < queue.size) { index++; playIndex() }
    }

    override fun prev() {
        val p = mp
        if (p != null && (demo || index == 0 || p.currentPosition > 3000)) { p.seekTo(0); publish() }
        else if (index > 0) { index--; playIndex() }
    }

    override fun seek(ms: Long) { mp?.seekTo(ms.toInt()); publish() }

    override fun openApp() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Generates a soft 16-second ambient loop so the app can play music without bundling audio files. */
object WavSynth {
    fun write(out: File) {
        val sr = 22050
        val total = sr * 16
        val mix = DoubleArray(total)
        val seq = doubleArrayOf(261.63, 329.63, 392.00, 493.88, 440.00, 392.00, 329.63, 293.66)
        val step = sr / 2
        for (i in 0 until 32) {
            val f = seq[i % seq.size]
            val start = i * step
            for (j in 0 until step * 4) {
                val t = j.toDouble() / sr
                val env = exp(-2.2 * t) * min(1.0, t * 80.0)
                val s = (sin(2 * PI * f * t) + 0.35 * sin(4 * PI * f * t) + 0.12 * sin(6 * PI * f * t)) * env * 0.22
                mix[(start + j) % total] += s
            }
        }
        for (n in 0 until total) {
            val t = n.toDouble() / sr
            mix[n] += 0.05 * (sin(2 * PI * 130.8125 * t) + sin(2 * PI * 196.0 * t))
        }
        var peak = 0.0
        for (v in mix) peak = max(peak, kotlin.math.abs(v))
        val gain = if (peak > 0) 0.9 / peak else 1.0
        val buf = ByteBuffer.allocate(44 + total * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + total * 2).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(total * 2)
        for (v in mix) buf.putShort((v * gain * 32767).toInt().coerceIn(-32768, 32767).toShort())
        out.writeBytes(buf.array())
    }
}
