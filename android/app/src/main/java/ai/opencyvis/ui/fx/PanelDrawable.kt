package ai.opencyvis.ui.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * 扁平面板底：一层略带透明的实色 + 0.5dp 细描边 + 一层轻投影，不做渐变、高光。
 *
 * 投影只画在形状外面（先把形状挖掉再画），否则会从半透明底色里透出来，让面板中间发灰。
 * 投影画在形状外，所以形状比 bounds 四边各缩进 [shadowMargin]；宿主的内容 padding 要把它算进去。
 * 形变动画时由外部调用 [setShape] 指定形状矩形，不设就跟随 bounds。
 */
class PanelDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density

    val shadowMargin: Int = (16 * density).toInt()

    var fillColor: Int = 0xF0302822.toInt()
        set(value) { field = value; invalidateSelf() }
    var strokeColor: Int = 0x12FFFFFF
        set(value) { field = value; invalidateSelf() }
    var shadowColor: Int = 0x38000000
        set(value) { field = value; shadowPaint.setShadowLayer(14f * density, 0f, 4f * density, value); invalidateSelf() }
    /** 是否画投影（次级元素不要投影，层级才分得开）。 */
    var elevated: Boolean = true
        set(value) { field = value; invalidateSelf() }

    /** 圆角半径 px；取 min(它, 高度一半)，默认就是胶囊。 */
    var cornerRadius: Float = Float.MAX_VALUE
        set(value) { field = value; invalidateSelf() }

    private val shape = RectF()
    private var customShape = false
    private val path = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var alphaValue = 255

    init {
        shadowPaint.setShadowLayer(14f * density, 0f, 4f * density, shadowColor)
        strokePaint.strokeWidth = max(1f, 0.5f * density)
    }

    val shapeRect: RectF get() = shape

    fun setShape(rect: RectF?) {
        if (rect == null) {
            customShape = false
            syncShapeToBounds(bounds)
        } else {
            customShape = true
            shape.set(rect)
        }
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (!customShape) syncShapeToBounds(bounds)
    }

    private fun syncShapeToBounds(b: Rect) {
        shape.set(
            (b.left + shadowMargin).toFloat(), (b.top + shadowMargin).toFloat(),
            (b.right - shadowMargin).toFloat(), (b.bottom - shadowMargin).toFloat(),
        )
    }

    fun radiusFor(rect: RectF): Float = min(cornerRadius, min(rect.height(), rect.width()) / 2f)

    override fun draw(canvas: Canvas) {
        if (shape.width() <= 0f || shape.height() <= 0f) return
        val r = radiusFor(shape)
        path.reset()
        path.addRoundRect(shape, r, r, Path.Direction.CW)
        val a = alphaValue / 255f

        if (elevated) {
            val save = canvas.save()
            canvas.clipOutPath(path)
            shadowPaint.alpha = (255 * a).toInt()
            canvas.drawPath(path, shadowPaint)
            canvas.restoreToCount(save)
        }
        fillPaint.color = fillColor
        fillPaint.alpha = (fillPaint.alpha * a).toInt()
        canvas.drawPath(path, fillPaint)
        strokePaint.color = strokeColor
        strokePaint.alpha = (strokePaint.alpha * a).toInt()
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
