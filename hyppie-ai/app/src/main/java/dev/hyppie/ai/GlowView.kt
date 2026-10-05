package dev.hyppie.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Animated light running around the rounded screen edge (the HyperOS / Apple Intelligence style glow).
 * mode: IDLE (soft, slow), LISTENING (brighter), THINKING (fast), ACTING (agent working).
 */
class GlowView(ctx: Context) : View(ctx) {

    enum class Mode(val speed: Long, val width: Float, val alpha: Int) {
        IDLE(5200, 14f, 170), LISTENING(2600, 22f, 235), THINKING(1500, 18f, 220), ACTING(2000, 16f, 210)
    }

    private val colors = intArrayOf(
        Color.parseColor("#4F46E5"), Color.parseColor("#06B6D4"), Color.parseColor("#22C55E"),
        Color.parseColor("#F59E0B"), Color.parseColor("#EC4899"), Color.parseColor("#8B5CF6"),
        Color.parseColor("#4F46E5"))
    private val d = resources.displayMetrics.density
    private val corner = 96f   // the A13's screen corner radius in px (rounded_corner_radius)
    private val rect = RectF()
    private val m = Matrix()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var angle = 0f
    private var pulse = 1f
    private var gradient: SweepGradient? = null

    var mode: Mode = Mode.IDLE
        set(v) { field = v; restart() }

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }
    private val breathe = ValueAnimator.ofFloat(0.75f, 1.15f).apply {
        duration = 1100
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { pulse = it.animatedValue as Float }
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // BlurMaskFilter needs a software layer

    private fun restart() {
        spin.duration = mode.speed
        if (!spin.isStarted) spin.start()
        if (!breathe.isStarted) breathe.start()
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
    override fun onDetachedFromWindow() { spin.cancel(); breathe.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        gradient = SweepGradient(w / 2f, h / 2f, colors, null)
    }

    override fun onDraw(c: Canvas) {
        val g = gradient ?: return
        m.setRotate(angle, width / 2f, height / 2f)
        g.setLocalMatrix(m)
        val wGlow = mode.width * d * pulse
        glow.shader = g
        glow.strokeWidth = wGlow
        glow.alpha = mode.alpha
        glow.maskFilter = BlurMaskFilter(wGlow * 0.9f, BlurMaskFilter.Blur.NORMAL)
        core.shader = g
        core.strokeWidth = 2.2f * d
        core.alpha = 255
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, corner, corner, glow)
        rect.inset(core.strokeWidth / 2, core.strokeWidth / 2)
        c.drawRoundRect(rect, corner, corner, core)
    }
}
