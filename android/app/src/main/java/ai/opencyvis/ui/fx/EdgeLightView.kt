package ai.opencyvis.ui.fx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.RoundedCorner
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 屏幕边缘流光（走马灯）。一圈贴着屏幕四边的彩色柔光：最亮处紧贴边缘、往里高斯衰减，
 * 沿着屏幕真实圆角走；上面再叠一颗带拖尾的高光珠子，按周长匀速绕屏幕跑。
 *
 * 渲染方式：光带形状（透明度）只在尺寸变化时离屏算一次，存成两张 ALPHA_8 蒙版——宽而软的光晕
 * （1/4 分辨率）和贴边的细亮线（1/2 分辨率）。每帧只画一层旋转的 SweepGradient 彩虹和几颗径向渐变
 * 的高光珠，再用蒙版 DST_IN 抠出来，不需要每帧模糊，全程走硬件加速。
 *
 * 状态：执行中彩虹缓慢流动 + 高光绕圈；思考中流动加快；等用户时琥珀色呼吸；
 * 完成 / 失败时收成绿 / 红色闪一下再淡出。
 */
class EdgeLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { OFF, RUNNING, THINKING, WAITING, PAUSED, SUCCESS, ERROR }

    /** 一层光带：四边各往里多厚（dp）、模糊半径（dp）、不透明度。 */
    private class Band(val top: Float, val side: Float, val bottom: Float, val blur: Float, val alpha: Int)

    private companion object {
        const val GLOW_SCALE = 0.25f
        const val CORE_SCALE = 0.5f
        const val CORE_WIDTH_DP = 3.2f
        const val TAIL_BEADS = 7
    }

    private val density = resources.displayMetrics.density

    var mode: Mode = Mode.OFF
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            applyMode(previous)
        }

    // ── 动画量 ──
    private var intensity = 0f
    private var reveal = 1f
    private var fromPalette = AuraPalette.RAINBOW
    private var toPalette = AuraPalette.RAINBOW
    private var paletteT = 1f
    private var angle = 0f
    private var speed = 0f          // 彩虹旋转，度 / 毫秒
    private var targetSpeed = 0f
    private var cometPos = 0f       // 高光珠在周长上的位置，px
    private var cometSpeed = 0f     // px / 毫秒
    private var cometLapMs = 4200f  // 高光珠绕一圈的时长；换算成速度要等量出周长
    private var cometAlpha = 0f
    private var targetCometAlpha = 0f
    private var cometCount = 1
    private var lastFrameMs = 0L
    private var modeStartMs = 0L

    private var intensityAnimator: ValueAnimator? = null
    private var paletteAnimator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null

    // ── 绘制资源 ──
    private val bounds = RectF()
    private var glowMask: Bitmap? = null
    private var coreMask: Bitmap? = null
    private var cornerRadius = 0f
    private val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val revealPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val layerPaint = Paint()
    private val rotation = Matrix()
    private val sweepColors = IntArray(AuraPalette.RAINBOW.size + 1)
    private var sweep: SweepGradient? = null
    // sweep 对应的配色：目标色板 + 过渡进度，两者都没变才复用
    private var sweepPalette: IntArray? = null
    private var sweepPaletteT = -1f
    private var cometShader: RadialGradient? = null
    private var cometRadius = 0f
    private val cometPoint = FloatArray(2)

    // 高光珠沿着的圆角矩形（和细亮线重合）
    private val track = RectF()
    private var trackRadius = 0f
    private var perimeter = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildMasks()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode != Mode.OFF) invalidate()
    }

    override fun onDetachedFromWindow() {
        intensityAnimator?.cancel()
        paletteAnimator?.cancel()
        revealAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun resolveCornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = rootWindowInsets
            val corner = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                ?: insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            if (corner != null && corner.radius > 0) return corner.radius.toFloat()
        }
        return 28f * density
    }

    // 先铺一层宽而淡的光晕，再叠一层窄而亮的：贴边最亮，往里拖出长长的柔光
    private fun bands(): List<Band> = listOf(
        Band(top = 30f, side = 30f, bottom = 30f, blur = 32f, alpha = 105),
        Band(top = 11f, side = 11f, bottom = 11f, blur = 11f, alpha = 210),
    )

    private fun rebuildMasks() {
        val w = width
        val h = height
        glowMask?.recycle()
        coreMask?.recycle()
        glowMask = null
        coreMask = null
        sweep = null
        if (w <= 0 || h <= 0) return

        cornerRadius = resolveCornerRadius()
        glowMask = buildGlowMask(w, h)
        coreMask = buildCoreMask(w, h)

        val inset = CORE_WIDTH_DP * density / 2f
        track.set(inset, inset, w - inset, h - inset)
        trackRadius = max(0f, cornerRadius - inset)
        perimeter = 2f * (track.width() - 2f * trackRadius) + 2f * (track.height() - 2f * trackRadius) +
            (2f * PI.toFloat() * trackRadius)
        cometRadius = 64f * density
        cometShader = RadialGradient(
            0f, 0f, cometRadius,
            intArrayOf(0xFFFFFFFF.toInt(), 0x99FFF6EC.toInt(), 0x33FFF0E0, 0x00FFFFFF),
            floatArrayOf(0f, 0.18f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        cometPaint.shader = cometShader
    }

    private fun buildGlowMask(w: Int, h: Int): Bitmap {
        val bw = ceil(w * GLOW_SCALE).toInt()
        val bh = ceil(h * GLOW_SCALE).toInt()
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        val px = density * GLOW_SCALE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        val inner = RectF()
        for (band in bands()) {
            val blur = band.blur * px
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            // 外框比画布大出几倍模糊半径，模糊后贴边处仍是满的，只往里衰减
            path.addRect(-3 * blur, -3 * blur, bw + 3 * blur, bh + 3 * blur, Path.Direction.CW)
            inner.set(band.side * px, band.top * px, bw - band.side * px, bh - band.bottom * px)
            val r = max(cornerRadius * GLOW_SCALE - min(band.side, band.top) * px, 10f * px)
            path.addRoundRect(inner, r, r, Path.Direction.CW)
            paint.alpha = band.alpha
            paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    private fun buildCoreMask(w: Int, h: Int): Bitmap {
        val bw = ceil(w * CORE_SCALE).toInt()
        val bh = ceil(h * CORE_SCALE).toInt()
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        val px = density * CORE_SCALE
        val stroke = CORE_WIDTH_DP * px
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            maskFilter = BlurMaskFilter(1.4f * px, BlurMaskFilter.Blur.NORMAL)
        }
        val r = max(cornerRadius * CORE_SCALE - stroke / 2f, 0f)
        canvas.drawRoundRect(stroke / 2f, stroke / 2f, bw - stroke / 2f, bh - stroke / 2f, r, r, paint)
        // 细亮线里侧再加一道很淡的内描边，让线有“厚度”而不是一根发丝
        paint.strokeWidth = stroke * 2.6f
        paint.alpha = 70
        paint.maskFilter = BlurMaskFilter(3f * px, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(stroke * 1.3f, stroke * 1.3f, bw - stroke * 1.3f, bh - stroke * 1.3f, r, r, paint)
        return bmp
    }

    // ── 状态切换 ──

    private fun applyMode(previous: Mode) {
        modeStartMs = SystemClock.uptimeMillis()
        val appearing = intensity < 0.05f && mode != Mode.OFF
        when (mode) {
            Mode.OFF -> {
                targetCometAlpha = 0f
                animateIntensity(0f, 520)
            }
            Mode.RUNNING -> {
                setPalette(AuraPalette.RAINBOW)
                targetSpeed = 360f / 9000f
                cometLapMs = 4200f
                targetCometAlpha = 1f
                cometCount = 1
                animateIntensity(1f, 700)
            }
            Mode.THINKING -> {
                setPalette(AuraPalette.RAINBOW)
                targetSpeed = 360f / 2600f
                cometLapMs = 1900f
                targetCometAlpha = 1f
                cometCount = 2
                animateIntensity(1f, 400)
            }
            Mode.WAITING -> {
                setPalette(AuraPalette.AMBER)
                targetSpeed = 360f / 16000f
                targetCometAlpha = 0f
                animateIntensity(1f, 600)
            }
            Mode.PAUSED, Mode.SUCCESS, Mode.ERROR -> {
                // 停转但一直亮着，用来标明“这是预览屏”：接管时保留彩虹，完成变绿，失败变红
                setPalette(
                    when (mode) {
                        Mode.SUCCESS -> AuraPalette.SUCCESS
                        Mode.ERROR -> AuraPalette.ERROR
                        else -> AuraPalette.RAINBOW
                    }
                )
                targetSpeed = 0f
                targetCometAlpha = 0f
                animateIntensity(1f, 600)
            }
        }
        if (appearing) startReveal()
        if (previous == Mode.OFF && speed == 0f) speed = targetSpeed
        lastFrameMs = 0L
        invalidate()
    }

    private fun setPalette(target: IntArray) {
        if (target === toPalette && paletteT >= 1f) return
        val current = AuraPalette.blendLoop(fromPalette, toPalette, paletteT)
        fromPalette = current.copyOf(target.size)
        toPalette = target
        paletteAnimator?.cancel()
        paletteT = 0f
        paletteAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener { paletteT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateIntensity(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        intensityAnimator?.cancel()
        intensityAnimator = ValueAnimator.ofFloat(intensity, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { intensity = it.animatedValue as Float; invalidate() }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd() }
                })
            }
            start()
        }
    }

    /** 入场时光从底边升起，沿两侧往上铺满。 */
    private fun startReveal() {
        revealAnimator?.cancel()
        reveal = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 950
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ── 绘制 ──

    override fun onDraw(canvas: Canvas) {
        val glow = glowMask ?: return
        val core = coreMask ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16f else min((now - lastFrameMs).toFloat(), 64f)
        lastFrameMs = now
        stepPhysics(dt)

        val t = (now - modeStartMs).toFloat()
        val breath = when (mode) {
            Mode.WAITING -> 0.62f + 0.38f * (0.5f + 0.5f * sin(2f * PI.toFloat() * t / 1900f))
            Mode.RUNNING, Mode.THINKING -> 0.9f + 0.1f * sin(2f * PI.toFloat() * t / 3400f)
            else -> 1f
        }
        val glowAlpha = (intensity * breath).coerceIn(0f, 1f)
        val coreAlpha = (intensity * breath).coerceIn(0f, 1f)
        val visible = glowAlpha > 0.004f || coreAlpha > 0.004f

        if (visible) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            updateSweep()
            rotation.setRotate(angle, width / 2f, height / 2f)
            sweep?.setLocalMatrix(rotation)
            rainbowPaint.shader = sweep
            if (reveal < 1f) updateRevealShader()

            drawLayer(canvas, glow, glowAlpha, cometAlpha * 0.6f)
            drawLayer(canvas, core, coreAlpha, cometAlpha)
        }

        val settling = intensityAnimator?.isRunning == true || paletteAnimator?.isRunning == true ||
            revealAnimator?.isRunning == true || cometAlpha != targetCometAlpha
        // 停稳的结果光圈是静止画面，不用每帧重画
        val animating = mode == Mode.RUNNING || mode == Mode.THINKING || mode == Mode.WAITING ||
            speed != 0f || cometAlpha != 0f
        if ((animating && visible) || settling) postInvalidateOnAnimation()
    }

    private fun drawLayer(canvas: Canvas, mask: Bitmap, alpha: Float, comet: Float) {
        if (alpha <= 0.004f) return
        layerPaint.alpha = (alpha * 255).toInt()
        val save = canvas.saveLayer(bounds, layerPaint)
        canvas.drawRect(bounds, rainbowPaint)
        if (comet > 0.01f && perimeter > 0f) drawComets(canvas, comet)
        canvas.drawBitmap(mask, null, bounds, maskPaint)
        if (reveal < 1f) canvas.drawRect(bounds, revealPaint)
        canvas.restoreToCount(save)
    }

    /** 一颗头亮尾淡的高光珠：头部后面按周长等距排几颗渐小渐淡的光点，SCREEN 叠加成一条连续的拖尾。 */
    private fun drawComets(canvas: Canvas, alpha: Float) {
        val spacing = 15f * density
        for (c in 0 until cometCount) {
            val head = cometPos + c * perimeter / cometCount
            val strength = if (c == 0) 1f else 0.7f
            for (i in 0 until TAIL_BEADS) {
                pointOnTrack(head - i * spacing, cometPoint)
                val k = 1f - i.toFloat() / TAIL_BEADS
                cometPaint.alpha = (255 * alpha * strength * k * k).toInt()
                val scale = 0.55f + 0.45f * k
                val save = canvas.save()
                canvas.translate(cometPoint[0], cometPoint[1])
                canvas.scale(scale, scale)
                canvas.drawCircle(0f, 0f, cometRadius, cometPaint)
                canvas.restoreToCount(save)
            }
        }
    }

    private fun stepPhysics(dt: Float) {
        // 速度 / 音量 / 高光透明度都做指数平滑，模式切换时不会突变
        val k = 1f - exp(-dt / 260f)
        speed += (targetSpeed - speed) * k
        val targetCometSpeed = if (perimeter > 0f) perimeter / cometLapMs else 0f
        cometSpeed += (targetCometSpeed - cometSpeed) * k
        val ka = 1f - exp(-dt / 220f)
        cometAlpha += (targetCometAlpha - cometAlpha) * ka
        if (kotlin.math.abs(cometAlpha - targetCometAlpha) < 0.003f) cometAlpha = targetCometAlpha

        if (targetSpeed == 0f && kotlin.math.abs(speed) < 0.00005f) speed = 0f
        angle = (angle + speed * dt) % 360f
        if (perimeter > 0f) cometPos = (cometPos + cometSpeed * dt) % perimeter
    }

    private fun updateSweep() {
        if (sweep != null && toPalette === sweepPalette && paletteT == sweepPaletteT) return
        sweepPalette = toPalette
        sweepPaletteT = paletteT
        AuraPalette.blendLoop(fromPalette, toPalette, paletteT, sweepColors)
        sweep = SweepGradient(width / 2f, height / 2f, sweepColors, null)
    }

    private fun updateRevealShader() {
        // 从底边往上：r=0 全隐，r=1 全显；过渡带占屏高 35%
        val h = height.toFloat()
        val edge = h * (1.35f - reveal * 1.7f)
        revealPaint.shader = LinearGradient(
            0f, edge, 0f, edge + h * 0.35f,
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP,
        )
    }

    /** 圆角矩形轨道上弧长 s 处的点，从顶边中点起顺时针。 */
    private fun pointOnTrack(sIn: Float, out: FloatArray) {
        val r = trackRadius
        val w = track.width() - 2f * r
        val h = track.height() - 2f * r
        val arc = (PI.toFloat() / 2f) * r
        var s = sIn % perimeter
        if (s < 0f) s += perimeter
        val cx = track.centerX()
        // 顶边右半
        var seg = w / 2f
        if (s < seg) { out[0] = cx + s; out[1] = track.top; return }
        s -= seg
        if (s < arc) { corner(track.right - r, track.top + r, -90f, s / r, out); return }
        s -= arc
        seg = h
        if (s < seg) { out[0] = track.right; out[1] = track.top + r + s; return }
        s -= seg
        if (s < arc) { corner(track.right - r, track.bottom - r, 0f, s / r, out); return }
        s -= arc
        seg = w
        if (s < seg) { out[0] = track.right - r - s; out[1] = track.bottom; return }
        s -= seg
        if (s < arc) { corner(track.left + r, track.bottom - r, 90f, s / r, out); return }
        s -= arc
        seg = h
        if (s < seg) { out[0] = track.left; out[1] = track.bottom - r - s; return }
        s -= seg
        if (s < arc) { corner(track.left + r, track.top + r, 180f, s / r, out); return }
        s -= arc
        out[0] = track.left + r + s
        out[1] = track.top
    }

    private fun corner(cx: Float, cy: Float, startDeg: Float, sweepRad: Float, out: FloatArray) {
        val a = Math.toRadians(startDeg.toDouble()) + sweepRad
        out[0] = cx + trackRadius * cos(a).toFloat()
        out[1] = cy + trackRadius * sin(a).toFloat()
    }
}
