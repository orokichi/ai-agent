package com.orokichi.agent

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.delay

/** 仮想マウスカーソル＋AIの思考バブル（タッチは素通り） */
class CursorOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: CursorView? = null

    fun show() {
        if (view != null || !Prefs.cursor) return
        val canOverlay = Settings.canDrawOverlays(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (canOverlay) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Android 12+ ではタッチを素通りさせるため不透明度0.8以下にする
            if (canOverlay) alpha = 0.8f
        }
        val v = CursorView(ctx)
        runCatching { wm.addView(v, lp); view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun think(title: String, body: String) { view?.post { view?.setBubble(title, body) } }

    /** カーソルを移動して到着まで待つ */
    suspend fun moveTo(x: Float, y: Float, label: String) {
        val v = view ?: return
        v.post { v.moveTo(x, y, label) }
        delay(520)
    }

    fun pulse() { view?.post { view?.pulse() } }
}

private class CursorView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val accent = ctx.getColor(android.R.color.system_accent1_500)
    private val bubbleBg = ctx.getColor(if (dark) android.R.color.system_accent1_800 else android.R.color.system_accent1_100)
    private val bubbleFg = ctx.getColor(if (dark) android.R.color.system_accent1_50 else android.R.color.system_accent1_900)
    private val chipBg = ctx.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
    private val chipFg = ctx.getColor(if (dark) android.R.color.system_accent1_900 else android.R.color.system_accent1_0)

    private var cx = -1f
    private var cy = -1f
    private var label = ""
    private var title = "考え中…"
    private var body = ""
    private var ripple = 0f
    private val loc = IntArray(2)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; setShadowLayer(6 * d, 0f, 2 * d, 0x66000000) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bubbleBg; setShadowLayer(10 * d, 0f, 3 * d, 0x55000000) }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = chipBg }
    private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = bubbleFg; textSize = 13 * d; isFakeBoldText = true }
    private val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = bubbleFg; textSize = 14 * d }
    private val chipP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = chipFg; textSize = 12.5f * d; isFakeBoldText = true }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setBubble(t: String, b: String) { title = t; body = b; invalidate() }

    fun moveTo(x: Float, y: Float, l: String) {
        getLocationOnScreen(loc)
        val tx = x - loc[0]; val ty = y - loc[1]
        label = l
        if (cx < 0) { cx = width / 2f; cy = height * 0.6f }
        val sx = cx; val sy = cy
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { cx = sx + (tx - sx) * it.animatedFraction; cy = sy + (ty - sy) * it.animatedFraction; invalidate() }
            start()
        }
    }

    fun pulse() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550
            addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { ripple = 0f; invalidate() }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        drawBubble(c)
        if (cx < 0) return
        if (ripple > 0f) {
            ring.alpha = ((1f - ripple) * 255).toInt()
            c.drawCircle(cx, cy, (10 + 34 * ripple) * d, ring)
        }
        // 矢印カーソル（先端が操作点）
        val s = d * 1.15f
        val p = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy + 22 * s)
            lineTo(cx + 6 * s, cy + 17 * s)
            lineTo(cx + 10 * s, cy + 26 * s)
            lineTo(cx + 14 * s, cy + 24 * s)
            lineTo(cx + 10 * s, cy + 15.5f * s)
            lineTo(cx + 17 * s, cy + 15.5f * s)
            close()
        }
        c.drawPath(p, fill); c.drawPath(p, stroke)
        if (label.isNotEmpty()) {
            val t = TextUtils.ellipsize(label, chipP, width * 0.6f, TextUtils.TruncateAt.END).toString()
            val w = chipP.measureText(t) + 20 * d
            var lx = cx + 20 * s; val ly = cy + 26 * s
            if (lx + w > width - 8 * d) lx = width - 8 * d - w
            val r = RectF(lx, ly, lx + w, ly + 26 * d)
            c.drawRoundRect(r, 13 * d, 13 * d, chip)
            c.drawText(t, lx + 10 * d, ly + 18 * d, chipP)
        }
    }

    private fun drawBubble(c: Canvas) {
        if (title.isEmpty() && body.isEmpty()) return
        val m = 14 * d
        val w = (width - 2 * m).toInt()
        val inner = w - (32 * d).toInt()
        val lay = StaticLayout.Builder.obtain(body, 0, body.length, bodyP, inner)
            .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val h = 16 * d + 18 * d + 4 * d + lay.height + 14 * d
        // カーソルが上半分なら下に、下半分なら上に表示
        getLocationOnScreen(loc)
        val top = if (cy in 0f..(height * 0.45f)) height - h - 72 * d else 44 * d - loc[1].coerceAtMost(0)
        val r = RectF(m, top, m + w, top + h)
        c.drawRoundRect(r, 24 * d, 24 * d, bg)
        c.drawText("✦ " + title, m + 16 * d, top + 16 * d + 13 * d, titleP)
        c.save()
        c.translate(m + 16 * d, top + 16 * d + 22 * d)
        lay.draw(c)
        c.restore()
    }
}
