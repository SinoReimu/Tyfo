package ai.opencyvis.ui.fx

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Agent 的状态图标（扁平）：强调色实心圆 + 中间的星芒。
 * - 执行中：外圈一小段弧线绕着转（[showRing] 打开时），思考时转得更快；
 * - 聆听：星芒换成随音量起伏的声波条；
 * - 等用户：外圈整圈呼吸；
 * - 完成 / 失败：底色变绿 / 红，画出对勾 / 感叹号。
 */
class AgentIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, RUNNING, WAITING, SUCCESS, ERROR }

    private val palette = MonetPalette(context)
    private val density = resources.displayMetrics.density

    /** 外圈进度弧（胶囊里的小图标用；卡片里的大图标不需要）。 */
    var showRing: Boolean = false
        set(value) { field = value; invalidate() }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            onModeChanged()
        }

    private var fillFrom = palette.accent
    private var fillTo = palette.accent
    private var fillT = 1f
    private var glyphT = 0f   // 0 = 星芒 / 声波，1 = 对勾 / 感叹号
    private var fillAnimator: ValueAnimator? = null
    private var glyphAnimator: ValueAnimator? = null

    private var angle = 0f
    private var targetLevel = 0f
    private var level = 0f
    private var lastFrameMs = 0L

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = palette.accent
    }
    private val arcRect = RectF()
    private val path = Path()
    private val seg = Path()
    private val measure = PathMeasure()

    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    private fun fillFor(m: Mode) = when (m) {
        Mode.SUCCESS -> palette.success
        Mode.ERROR -> palette.error
        else -> palette.accent
    }

    private fun glyphColorFor(m: Mode) = when (m) {
        Mode.SUCCESS -> palette.onSuccess
        Mode.ERROR -> palette.onError
        else -> palette.onAccent
    }

    private fun onModeChanged() {
        val target = fillFor(mode)
        if (target != fillTo) {
            fillFrom = AuraPalette.lerpColor(fillFrom, fillTo, fillT)
            fillTo = target
            fillAnimator?.cancel()
            fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280
                interpolator = DecelerateInterpolator()
                addUpdateListener { fillT = it.animatedValue as Float; invalidate() }
                start()
            }
            fillT = 0f
        }
        val glyph = if (mode == Mode.SUCCESS || mode == Mode.ERROR) 1f else 0f
        if (glyph != glyphT) {
            glyphAnimator?.cancel()
            glyphAnimator = ValueAnimator.ofFloat(glyphT, glyph).apply {
                duration = if (glyph > 0f) 420 else 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { glyphT = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        fillAnimator?.cancel()
        glyphAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16f else min((now - lastFrameMs).toFloat(), 64f)
        lastFrameMs = now
        level += (targetLevel - level) * (1f - exp(-dt / 70f))

        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f
        if (outer <= 0f) return
        val ringGap = if (showRing) 3.5f * density else 0f
        val r = outer - ringGap

        fillPaint.color = AuraPalette.lerpColor(fillFrom, fillTo, fillT)
        canvas.drawCircle(cx, cy, r, fillPaint)

        var animating = false
        if (showRing && (mode == Mode.RUNNING || mode == Mode.THINKING || mode == Mode.WAITING)) {
            ringPaint.strokeWidth = 2f * density
            val rr = outer - ringPaint.strokeWidth / 2f
            arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            if (mode == Mode.WAITING) {
                val t = now / 1000f
                ringPaint.alpha = (255 * (0.35f + 0.45f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 1.6f)))).toInt()
                canvas.drawCircle(cx, cy, rr, ringPaint)
            } else {
                val speed = if (mode == Mode.THINKING) 360f / 800f else 360f / 1400f
                angle = (angle + speed * dt) % 360f
                ringPaint.alpha = 255
                canvas.drawArc(arcRect, angle, 100f, false, ringPaint)
            }
            animating = true
        }

        val glyphColor = glyphColorFor(mode)
        val baseAlpha = (1f - glyphT * 1.4f).coerceIn(0f, 1f)
        if (baseAlpha > 0f) {
            if (mode == Mode.LISTENING) {
                drawWave(canvas, cx, cy, r, glyphColor, baseAlpha, now)
                animating = true
            } else {
                drawSpark(canvas, cx, cy, r * 0.56f, glyphColor, baseAlpha)
            }
        }
        if (glyphT > 0.01f) drawResultGlyph(canvas, cx, cy, r, glyphColor)

        if (animating && isShown) postInvalidateOnAnimation()
    }

    /** 四角星芒：尖角之间是往里凹的弧。 */
    private fun drawSpark(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
        val c = radius * 0.16f
        path.reset()
        path.moveTo(cx, cy - radius)
        path.quadTo(cx + c, cy - c, cx + radius, cy)
        path.quadTo(cx + c, cy + c, cx, cy + radius)
        path.quadTo(cx - c, cy + c, cx - radius, cy)
        path.quadTo(cx - c, cy - c, cx, cy - radius)
        path.close()
        glyphPaint.color = color
        glyphPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(path, glyphPaint)
    }

    /** 四根声波条，高度随音量起伏，带一点错开的抖动显得是“活的”。 */
    private fun drawWave(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float, now: Long) {
        val barW = r * 0.16f
        val gap = r * 0.12f
        val maxH = r * 1.0f
        val shapes = floatArrayOf(0.42f, 0.78f, 1f, 0.6f)
        val t = now / 1000f
        val total = shapes.size * barW + (shapes.size - 1) * gap
        var x = cx - total / 2f
        glyphPaint.color = color
        glyphPaint.alpha = (255 * alpha).toInt()
        for (i in shapes.indices) {
            val wobble = 0.12f * sin(t * 7f + i * 1.3f)
            val h = maxH * shapes[i] * (0.34f + 0.66f * level + wobble).coerceIn(0.22f, 1f)
            canvas.drawRoundRect(x, cy - h / 2f, x + barW, cy + h / 2f, barW / 2f, barW / 2f, glyphPaint)
            x += barW + gap
        }
    }

    private fun drawResultGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val s = r * 0.46f
        strokePaint.strokeWidth = r * 0.16f
        strokePaint.color = color
        strokePaint.alpha = (255 * glyphT.coerceIn(0f, 1f)).toInt()
        path.reset()
        if (mode == Mode.SUCCESS || (fillTo == palette.success)) {
            path.moveTo(cx - s * 0.95f, cy + s * 0.02f)
            path.lineTo(cx - s * 0.28f, cy + s * 0.68f)
            path.lineTo(cx + s * 0.98f, cy - s * 0.6f)
            measure.setPath(path, false)
            seg.reset()
            measure.getSegment(0f, measure.length * glyphT.coerceIn(0f, 1f), seg, true)
            canvas.drawPath(seg, strokePaint)
        } else {
            path.moveTo(cx, cy - s * 0.9f)
            path.lineTo(cx, cy + s * 0.25f)
            canvas.drawPath(path, strokePaint)
            glyphPaint.color = color
            glyphPaint.alpha = strokePaint.alpha
            canvas.drawCircle(cx, cy + s * 0.8f, strokePaint.strokeWidth * 0.6f, glyphPaint)
        }
    }
}
