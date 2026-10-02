package com.shiro.tyfo.ui.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 进行中的那条进度文字：一道亮光从左往右扫过文字（同 Gemini 的“思考中”），[shimmering] 关掉就是普通文字。
 * 底色是当前文字颜色，亮光是 [shimmerColor]。
 */
class ShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    var shimmerColor: Int = 0xFFFFFFFF.toInt()
        set(value) { field = value; shader = null; invalidate() }

    var shimmering: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) paint.shader = null
            invalidate()
        }

    private var shader: LinearGradient? = null
    private var shaderBase = 0
    private val shaderMatrix = Matrix()
    private val startMs = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        if (!shimmering || width == 0) {
            paint.shader = null
            super.onDraw(canvas)
            return
        }
        val band = width * 0.6f
        val base = currentTextColor
        if (shader == null || shaderBase != base) {
            shaderBase = base
            shader = LinearGradient(
                0f, 0f, band, 0f,
                intArrayOf(base, shimmerColor, base),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        // 一次扫过约 1.6 秒，扫完停一小会儿再来
        val period = 2000f
        val t = ((SystemClock.uptimeMillis() - startMs) % period.toLong()) / 1600f
        val x = -band + (width + band) * t.coerceAtMost(1f)
        shaderMatrix.setTranslate(x, 0f)
        shader!!.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        super.onDraw(canvas)
        if (isShown) postInvalidateOnAnimation()
    }
}
