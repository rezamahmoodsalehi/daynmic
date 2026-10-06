package app.dynamicisland

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display

/** Transport controls, implemented either by our own player or by another app's MediaSession. */
interface Controls {
    fun playPause()
    fun next()
    fun prev()
    fun seek(ms: Long)
    fun openApp()
}

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val controls: Controls?,
    val loops: Boolean = false
) {
    fun positionNow(): Long {
        var p = if (playing) position + (SystemClock.elapsedRealtime() - updatedAt) else position
        if (duration > 0) p = if (loops) p % duration else p.coerceAtMost(duration)
        return p.coerceAtLeast(0)
    }
}

data class MessageInfo(
    val id: String,
    val sender: String,
    val text: String,
    val avatar: Bitmap?,
    val open: PendingIntent?,
    /** false for a generic app notification (like, voice note, ...): shown compact only, no auto-expand. */
    val expand: Boolean = true
)

data class OngoingInfo(val label: String, val startElapsed: Long)

/** Single in-process hub between the notification listener, the player and the overlay service. */
object IslandBus {
    interface Listener {
        fun onChanged()
        fun onMessage(m: MessageInfo)
    }

    private val main = Handler(Looper.getMainLooper())
    var listener: Listener? = null
    private var own: MediaInfo? = null
    private var ext: MediaInfo? = null
    var ongoing: OngoingInfo? = null
        private set

    fun setOwn(m: MediaInfo?) { main.post { own = m; listener?.onChanged() } }
    fun setExt(m: MediaInfo?) { main.post { ext = m; listener?.onChanged() } }
    fun setOngoing(o: OngoingInfo?) { main.post { ongoing = o; listener?.onChanged() } }
    fun message(m: MessageInfo) { main.post { listener?.onMessage(m) } }

    fun media(): MediaInfo? {
        val l = listOfNotNull(own, ext)
        return l.firstOrNull { it.playing } ?: l.maxByOrNull { it.updatedAt }
    }

    fun clear() { own = null; ext = null; ongoing = null }
}

enum class CamPos { LEFT, CENTER, RIGHT, NONE }

data class CutoutInfo(
    val rect: Rect?,
    val pos: CamPos,
    val screenW: Int,
    val screenH: Int,
    val landscape: Boolean
) {
    companion object {
        val NONE = CutoutInfo(null, CamPos.NONE, 0, 0, false)
    }
}

/** Finds the front-camera cutout and tells whether it sits left, centre or right. */
object CutoutDetector {
    @Suppress("DEPRECATION")
    fun detect(ctx: Context): CutoutInfo {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        display.getRealSize(size)
        val w = size.x
        val h = size.y
        val landscape = w > h
        var rect = display.cutout?.boundingRects?.firstOrNull {
            it.centerY() < h * 0.10f && it.width() < w * 0.5f
        }
        // Test override: simulate the camera on another side of the screen.
        val ov = Prefs.camOverride(ctx)
        if (ov != 0 && !landscape) {
            val d = ctx.resources.displayMetrics.density
            val cw = rect?.width() ?: (30 * d).toInt()
            val ch = rect?.height() ?: (30 * d).toInt()
            val top = rect?.top ?: (12 * d).toInt()
            val cx = when (ov) { 1 -> (w * 0.12f).toInt(); 3 -> (w * 0.88f).toInt(); else -> w / 2 }
            rect = if (ov == 4) null else Rect(cx - cw / 2, top, cx + cw / 2, top + ch)
        }
        val pos = when {
            rect == null -> CamPos.NONE
            Math.abs(rect.centerX() / w.toFloat() - 0.5f) < 0.12f -> CamPos.CENTER
            rect.centerX() < w / 2 -> CamPos.LEFT
            else -> CamPos.RIGHT
        }
        return CutoutInfo(rect?.let { Rect(it) }, pos, w, h, landscape)
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
    fun styleAuto(c: Context) = sp(c).getBoolean("style_auto", true)
    fun setStyleAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("style_auto", v).apply()
    /** 0 = auto, 1 = left, 2 = centre, 3 = right, 4 = no cutout (test only). */
    fun camOverride(c: Context) = sp(c).getInt("cam_override", 0)
    fun setCamOverride(c: Context, v: Int) = sp(c).edit().putInt("cam_override", v).apply()
}

/** Sends a PendingIntent in a way that is allowed to start an activity from the overlay on Android 14+. */
fun PendingIntent.sendAllowed(ctx: Context) {
    try {
        val opts = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            opts.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        send(ctx, 0, null, null, null, null, opts.toBundle())
    } catch (_: Exception) {
    }
}
