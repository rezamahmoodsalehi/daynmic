package app.dynamicisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * The island itself: one overlay window that holds a black "pill".
 *
 * - The pill is anchored to the real camera cutout. If the camera is on the left it grows to the right,
 *   if it is on the right it grows to the left, if it is centred it grows on both sides.
 * - Tap = expand, swipe up = collapse, tap on header = open the source app.
 * - The window is always cropped to what is visible, so the rest of the status bar stays touchable.
 */
class IslandWindow(private val ctx: Context) {

    private enum class Mode { HIDDEN, COMPACT, EXPANDED }
    private enum class Kind { MEDIA, MESSAGE, ONGOING }
    private class Palette(val bg: Int, val fg: Int, val sub: Int, val chip: Int, val accent: Int)

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val h = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val side = dp(38)

    // ---- state
    private var mode = Mode.HIDDEN
    private var kind = Kind.MEDIA
    private var media: MediaInfo? = null
    private var ongoing: OngoingInfo? = null
    private var message: MessageInfo? = null
    private var attached = false
    private var animating = false
    private var expansion = 0f
    private var animator: ValueAnimator? = null
    private var cut = CutoutInfo.NONE
    private val cRect = Rect()
    private val eRect = Rect()
    private val winRect = Rect()
    private lateinit var lp: WindowManager.LayoutParams
    private var pal = palette()
    private val argb = ArgbEvaluator()
    private val bg = GradientDrawable().apply { setColor(Color.BLACK) }

    // ---- views: window
    private val root = FrameLayout(ctx)
    private val pill = FrameLayout(ctx)

    // ---- views: compact
    private val compactRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val cLeft = FrameLayout(ctx)
    private val cSpacer = View(ctx)
    private val cRight = FrameLayout(ctx)
    private val cArt = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val cEq = EqView(ctx)
    private val cBadge = ImageView(ctx).apply { setImageResource(R.drawable.ic_chat); setColorFilter(Color.WHITE) }
    private val cText = tv(11f, true).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE) }

    // ---- views: expanded
    private val expandedBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val eArt = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val eTexts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val eTitle = tv(17f, true)
    private val eSub = tv(14f)

    private val mediaBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val progress = ThinProgress(ctx)
    private val tPos = tv(11f)
    private val tDur = tv(11f)
    private val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val bPrev = iconButton(R.drawable.ic_prev)
    private val bPlay = iconButton(R.drawable.ic_pause)
    private val bNext = iconButton(R.drawable.ic_next)

    private val messageBlock = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val bOpen = textButton(R.string.open_label)
    private val bDismiss = textButton(R.string.dismiss_label)

    private val ongoingBlock = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val eTimer = tv(30f, true)
    private val bStop = textButton(R.string.stop_label)

    // ---- runnables / gestures
    private val autoCollapse = Runnable { collapse() }
    private val refreshRunnable = Runnable { if (mode == Mode.EXPANDED) scheduleRefresh() else refresh() }
    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            updateTimes()
            h.postDelayed(this, 500)
        }
    }
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode == Mode.COMPACT) expand(9000)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (vy < -500 && mode == Mode.EXPANDED) { collapse(); return true }
            return false
        }
        override fun onLongPress(e: MotionEvent) {
            if (mode == Mode.COMPACT) openSource()
        }
    })

    init { build() }

    // =====================================================================================
    // Public API
    // =====================================================================================

    fun update(m: MediaInfo?, o: OngoingInfo?) {
        media = m
        ongoing = o
        scheduleRefresh()
        refresh()
    }

    fun showMessage(m: MessageInfo) {
        message = m
        refresh()
        if (m.expand) {
            h.postDelayed({ if (message === m) expand(5500) }, 150)
        } else {
            h.removeCallbacks(autoCollapse)
            h.postDelayed({ if (message === m) dismissCompactMessage() }, 3200)
        }
    }

    private fun dismissCompactMessage() {
        message = null
        refresh()
    }

    fun onConfigChanged() {
        pal = palette()
        if (attached || media != null || ongoing != null) refresh()
    }

    fun destroy() {
        h.removeCallbacksAndMessages(null)
        animator?.cancel()
        detach()
    }

    // =====================================================================================
    // Build
    // =====================================================================================

    private fun tv(size: Float, bold: Boolean = false) = TextView(ctx).apply {
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun iconButton(res: Int) = ImageView(ctx).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true
    }

    private fun textButton(res: Int) = tv(14f, true).apply {
        text = ctx.getString(res)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
    }

    private fun roundify(v: View, fraction: Float) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height * fraction)
            }
        }
        v.invalidateOutline()
    }

    private fun chip(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(22).toFloat() }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun build() {
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        root.addView(pill, FrameLayout.LayoutParams(0, 0))
        pill.background = bg
        pill.clipToOutline = true
        pill.outlineProvider = ViewOutlineProvider.BACKGROUND
        pill.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        pill.addView(compactRow, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pill.addView(expandedBox, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // compact
        cLeft.addView(cArt, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        cRight.addView(cEq, FrameLayout.LayoutParams(dp(20), dp(16), Gravity.CENTER))
        cRight.addView(cBadge, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        cRight.addView(cText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // expanded: header
        roundify(eArt, 0.22f)
        header.addView(eArt, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(eTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14); marginEnd = dp(14)
        })
        eTexts.addView(eTitle)
        eTexts.addView(eSub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        header.setOnClickListener { openSource() }
        expandedBox.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // media block
        mediaBlock.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        val timeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        timeRow.addView(tPos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(tDur)
        mediaBlock.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        controls.addView(bPrev, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(bPlay, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(12); marginEnd = dp(12) })
        controls.addView(bNext, LinearLayout.LayoutParams(dp(56), dp(56)))
        mediaBlock.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        expandedBox.addView(mediaBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        bPlay.setOnClickListener { media?.controls?.playPause() }
        bNext.setOnClickListener { media?.controls?.next() }
        bPrev.setOnClickListener { media?.controls?.prev() }
        progress.onSeek = { f -> media?.let { m -> if (m.duration > 0) m.controls?.seek((f * m.duration).toLong()) } }

        // message block
        messageBlock.addView(bOpen, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        messageBlock.addView(bDismiss, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        expandedBox.addView(messageBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        bOpen.setOnClickListener { openSource() }
        bDismiss.setOnClickListener { collapse() }

        // ongoing block
        ongoingBlock.addView(eTimer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ongoingBlock.addView(bStop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        expandedBox.addView(ongoingBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        bStop.setOnClickListener {
            IslandBus.setOngoing(null)
            collapse()
        }
    }

    // =====================================================================================
    // Theme
    // =====================================================================================

    private fun isDark() =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun accent(onDark: Boolean): Int =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getColor(if (onDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        } else {
            if (onDark) 0xFF64D2C8.toInt() else 0xFF0A7F76.toInt()
        }

    private fun palette(): Palette {
        val light = Prefs.styleAuto(ctx) && !isDark()
        return if (light) {
            Palette(0xFFF2F2F7.toInt(), 0xFF111114.toInt(), 0x99111114.toInt(), 0x14000000, accent(false))
        } else {
            Palette(Color.BLACK, Color.WHITE, 0xB3FFFFFF.toInt(), 0x26FFFFFF, accent(true))
        }
    }

    private fun applyPalette() {
        eTitle.setTextColor(pal.fg)
        eSub.setTextColor(pal.sub)
        tPos.setTextColor(pal.sub)
        tDur.setTextColor(pal.sub)
        eTimer.setTextColor(pal.fg)
        progress.track = pal.chip
        progress.fill = pal.accent
        cEq.color = accent(true)
        for (b in listOf(bPrev, bPlay, bNext)) b.setColorFilter(pal.fg)
        bPlay.background = oval(pal.chip)
        for (b in listOf(bOpen, bDismiss, bStop)) {
            b.setTextColor(pal.fg)
            b.background = chip(pal.chip)
        }
    }

    // =====================================================================================
    // Content / state
    // =====================================================================================

    private fun scheduleRefresh() {
        h.removeCallbacks(refreshRunnable)
        val m = media
        if (m != null && !m.playing) h.postDelayed(refreshRunnable, 8200)
    }

    private fun refresh() {
        val m = media?.takeIf { it.playing || SystemClock.elapsedRealtime() - it.updatedAt < 8000 }
        val k = when {
            message != null -> Kind.MESSAGE
            m != null -> Kind.MEDIA
            ongoing != null -> Kind.ONGOING
            else -> null
        }
        cut = CutoutDetector.detect(ctx)
        if (k == null || cut.landscape || cut.screenW == 0) { hide(); return }
        kind = k
        bind(m)
        computeRects()
        if (!attached) {
            if (!attach()) return
        } else {
            pill.animate().cancel()
            pill.alpha = 1f
        }
        if (mode == Mode.HIDDEN) mode = Mode.COMPACT
        layoutPill()
        h.removeCallbacks(tick)
        h.post(tick)
    }

    private fun isRtl(s: String): Boolean {
        for (ch in s) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                else -> {}
            }
        }
        return false
    }

    private fun bind(m: MediaInfo?) {
        pal = palette()
        var art: android.graphics.Bitmap? = null
        var title = ""
        var sub = ""
        when (kind) {
            Kind.MEDIA -> {
                val mi = m ?: return
                art = mi.art ?: Bitmaps.musicArt(mi.title)
                title = mi.title.ifBlank { ctx.getString(R.string.unknown_track) }
                sub = mi.artist
                cEq.visibility = View.VISIBLE
                cEq.playing = mi.playing
                cBadge.visibility = View.GONE
                cText.visibility = View.GONE
                mediaBlock.visibility = View.VISIBLE
                messageBlock.visibility = View.GONE
                ongoingBlock.visibility = View.GONE
                bPlay.setImageResource(if (mi.playing) R.drawable.ic_pause else R.drawable.ic_play)
                roundify(cArt, 0.22f)
            }
            Kind.MESSAGE -> {
                val msg = message ?: return
                art = msg.avatar ?: Bitmaps.initials(msg.sender)
                title = msg.sender
                sub = msg.text
                cEq.visibility = View.GONE
                cEq.playing = false
                cBadge.visibility = View.VISIBLE
                cText.visibility = View.GONE
                mediaBlock.visibility = View.GONE
                messageBlock.visibility = View.VISIBLE
                ongoingBlock.visibility = View.GONE
                bOpen.visibility = if (msg.open != null) View.VISIBLE else View.GONE
                roundify(cArt, 0.5f)
            }
            Kind.ONGOING -> {
                val og = ongoing ?: return
                title = og.label
                sub = ""
                cEq.visibility = View.GONE
                cEq.playing = false
                cBadge.visibility = View.GONE
                cText.visibility = View.VISIBLE
                mediaBlock.visibility = View.GONE
                messageBlock.visibility = View.GONE
                ongoingBlock.visibility = View.VISIBLE
                roundify(cArt, 0.5f)
            }
        }
        if (art != null) {
            cArt.setImageBitmap(art)
            eArt.setImageBitmap(art)
        } else {
            val d = ctx.getDrawable(R.drawable.ic_timer)?.mutate()
            d?.setTint(Color.WHITE)
            cArt.setImageDrawable(d)
            cArt.setPadding(dp(5), dp(5), dp(5), dp(5))
            cArt.background = oval(0xFFFF453A.toInt())
            eArt.setImageDrawable(d)
            eArt.setPadding(dp(14), dp(14), dp(14), dp(14))
            eArt.background = oval(0xFFFF453A.toInt())
        }
        if (art != null) {
            cArt.setPadding(0, 0, 0, 0); cArt.background = null
            eArt.setPadding(0, 0, 0, 0); eArt.background = null
        }
        eTitle.text = title
        eSub.text = sub
        eSub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        header.layoutDirection =
            if (isRtl(title.ifBlank { sub })) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        applyPalette()
        updateTimes()
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private fun updateTimes() {
        when (kind) {
            Kind.MEDIA -> {
                val m = media ?: return
                val pos = m.positionNow()
                progress.value = if (m.duration > 0) pos.toFloat() / m.duration else 0f
                tPos.text = fmt(pos)
                tDur.text = if (m.duration > 0) fmt(m.duration) else ""
            }
            Kind.ONGOING -> {
                val og = ongoing ?: return
                val t = fmt(SystemClock.elapsedRealtime() - og.startElapsed)
                cText.text = t
                eTimer.text = t
            }
            else -> {}
        }
    }

    private fun openSource() {
        when (kind) {
            Kind.MEDIA -> media?.controls?.openApp()
            Kind.MESSAGE -> message?.open?.sendAllowed(ctx)
            Kind.ONGOING -> ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        collapse()
    }

    // =====================================================================================
    // Geometry
    // =====================================================================================

    private fun arrangeCompact(spacerW: Int, art: Int) {
        compactRow.removeAllViews()
        fun slot() = LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.MATCH_PARENT)
        val sp = LinearLayout.LayoutParams(spacerW, ViewGroup.LayoutParams.MATCH_PARENT)
        when (cut.pos) {
            CamPos.LEFT -> { compactRow.addView(cSpacer, sp); compactRow.addView(cLeft, slot()); compactRow.addView(cRight, slot()) }
            CamPos.RIGHT -> { compactRow.addView(cLeft, slot()); compactRow.addView(cRight, slot()); compactRow.addView(cSpacer, sp) }
            else -> { compactRow.addView(cLeft, slot()); compactRow.addView(cSpacer, sp); compactRow.addView(cRight, slot()) }
        }
        val alp = cArt.layoutParams as FrameLayout.LayoutParams
        alp.width = art; alp.height = art
        cArt.layoutParams = alp
    }

    private fun computeRects() {
        val w = cut.screenW
        val c = cut.rect
        val pad = dp(6)
        val top: Int
        val ch: Int
        val spacer: Int
        if (c != null) {
            top = max(0, c.top - dp(3)); ch = c.bottom + dp(3) - top; spacer = c.width()
        } else {
            top = dp(8); ch = dp(34); spacer = dp(56)
        }
        val cw = spacer + 2 * side + 2 * pad
        var left = when (cut.pos) {
            CamPos.LEFT -> c!!.left - pad
            CamPos.RIGHT -> c!!.right + pad - cw
            else -> (c?.centerX() ?: (w / 2)) - cw / 2
        }
        left = left.coerceIn(dp(2), max(dp(2), w - cw - dp(2)))
        cRect.set(left, top, left + cw, top + ch)
        arrangeCompact(spacer, (ch - dp(10)).coerceIn(dp(18), dp(28)))
        compactRow.layoutParams = FrameLayout.LayoutParams(cw, ch, Gravity.TOP or Gravity.CENTER_HORIZONTAL)

        val ew = min(w - dp(16), dp(390))
        val el = when (cut.pos) {
            CamPos.LEFT -> dp(8)
            CamPos.RIGHT -> w - dp(8) - ew
            else -> (w - ew) / 2
        }
        expandedBox.setPadding(dp(20), ch + dp(4), dp(20), dp(18))
        expandedBox.layoutParams = FrameLayout.LayoutParams(ew, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        expandedBox.measure(
            View.MeasureSpec.makeMeasureSpec(ew, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        eRect.set(el, top, el + ew, top + expandedBox.measuredHeight)
    }

    private fun layoutPill() {
        val e = expansion
        val ec = e.coerceIn(0f, 1f)
        fun lerp(a: Int, b: Int) = (a + (b - a) * e).toInt()
        val l = lerp(cRect.left, eRect.left)
        val t = lerp(cRect.top, eRect.top)
        val r = lerp(cRect.right, eRect.right)
        val b = lerp(cRect.bottom, eRect.bottom)

        val win = when {
            animating -> Rect(
                min(cRect.left, eRect.left) - dp(14), min(cRect.top, eRect.top),
                max(cRect.right, eRect.right) + dp(14), max(cRect.bottom, eRect.bottom) + dp(24)
            )
            mode == Mode.EXPANDED -> Rect(eRect)
            else -> Rect(cRect)
        }
        if (win != winRect) {
            winRect.set(win)
            lp.x = win.left; lp.y = win.top; lp.width = win.width(); lp.height = win.height()
            try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
        val plp = (pill.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
        plp.width = r - l; plp.height = b - t
        plp.leftMargin = l - win.left; plp.topMargin = t - win.top
        pill.layoutParams = plp

        bg.setColor(argb.evaluate(ec, Color.BLACK, pal.bg) as Int)
        bg.cornerRadius = cRect.height() / 2f + (dp(34) - cRect.height() / 2f) * ec
        if (pal.bg != Color.BLACK) bg.setStroke(dp(1), Color.argb((0x26 * ec).toInt(), 0, 0, 0)) else bg.setStroke(0, 0)
        pill.invalidateOutline()

        compactRow.alpha = 1f - (ec * 2.2f).coerceAtMost(1f)
        compactRow.visibility = if (ec > 0.9f) View.INVISIBLE else View.VISIBLE
        expandedBox.alpha = ((ec - 0.45f) / 0.55f).coerceIn(0f, 1f)
        expandedBox.visibility = if (ec < 0.1f) View.INVISIBLE else View.VISIBLE
    }

    // =====================================================================================
    // Animation
    // =====================================================================================

    private fun animateTo(target: Float) {
        animator?.cancel()
        val from = expansion
        animating = true
        mode = if (target > 0.5f) Mode.EXPANDED else Mode.COMPACT
        val a = ValueAnimator.ofFloat(from, target)
        a.duration = if (target > from) 460 else 320
        a.interpolator = if (target > from) OvershootInterpolator(0.85f) else DecelerateInterpolator(1.6f)
        a.addUpdateListener { expansion = it.animatedValue as Float; layoutPill() }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === a) {
                    animating = false
                    expansion = target
                    layoutPill()
                }
            }
        })
        animator = a
        a.start()
    }

    private fun expand(autoMs: Long = 0) {
        if (!attached || mode == Mode.HIDDEN) return
        h.removeCallbacks(autoCollapse)
        if (mode != Mode.EXPANDED) animateTo(1f)
        if (autoMs > 0) h.postDelayed(autoCollapse, autoMs)
    }

    private fun collapse() {
        h.removeCallbacks(autoCollapse)
        if (mode == Mode.EXPANDED) animateTo(0f)
        if (message != null) {
            message = null
            h.postDelayed({ refresh() }, 360)
        }
    }

    // =====================================================================================
    // Window
    // =====================================================================================

    private fun attach(): Boolean {
        lp = WindowManager.LayoutParams(
            cRect.width(), cRect.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cRect.left
            y = cRect.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "DynamicIsland"
        }
        winRect.set(cRect)
        expansion = 0f
        animating = false
        mode = Mode.COMPACT
        pill.alpha = 0f
        return try {
            wm.addView(root, lp)
            attached = true
            pill.animate().alpha(1f).setDuration(220).start()
            true
        } catch (e: Exception) {
            attached = false
            mode = Mode.HIDDEN
            false
        }
    }

    private fun hide() {
        h.removeCallbacks(autoCollapse)
        h.removeCallbacks(tick)
        if (!attached) { mode = Mode.HIDDEN; return }
        animator?.cancel()
        animating = false
        mode = Mode.HIDDEN
        pill.animate().alpha(0f).setDuration(180).withEndAction { if (mode == Mode.HIDDEN) detach() }.start()
    }

    private fun detach() {
        if (!attached) return
        try { wm.removeView(root) } catch (_: Exception) {}
        attached = false
        expansion = 0f
        animating = false
        mode = Mode.HIDDEN
    }
}
