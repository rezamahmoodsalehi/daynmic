package app.dynamicisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Tiny animated equaliser shown on the right of the compact island while music plays. */
class EqView(ctx: Context) : View(ctx) {
    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var playing: Boolean = false
        set(v) {
            field = v
            if (isAttachedToWindow) { if (v) anim.start() else anim.cancel() }
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val n = 4
        val bw = width / (n * 2f - 1f)
        for (i in 0 until n) {
            val f = if (playing) 0.3f + 0.7f * abs(sin(phase + i * 1.4f)) else 0.22f
            val bh = height * f
            val l = i * bw * 2
            rect.set(l, (height - bh) / 2f, l + bw, (height + bh) / 2f)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }
}

/** Thin seek bar. Tap anywhere to seek. */
class ThinProgress(ctx: Context) : View(ctx) {
    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var track: Int = 0x33FFFFFF
    var fill: Int = Color.WHITE
    var onSeek: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val bh = 4f * resources.displayMetrics.density
        val top = (height - bh) / 2f
        paint.color = track
        rect.set(0f, top, width.toFloat(), top + bh)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, paint)
        paint.color = fill
        rect.set(0f, top, width * value, top + bh)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onSeek != null
            MotionEvent.ACTION_UP -> {
                if (width > 0) onSeek?.invoke((e.x / width).coerceIn(0f, 1f))
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
