package com.shiro.tyfo.ui.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 细进度条：3dp 的轨道上，一段强调色来回滑动，表示“正在执行”。[running] 关掉后停在中间不动。
 */
class ProgressLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val palette = MonetPalette(context)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.track }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
    private val ease = AccelerateDecelerateInterpolator()
    private val startMs = SystemClock.uptimeMillis()

    var running: Boolean = true
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)
        val segW = w * 0.34f
        val x = if (running) {
            // 1.8 秒一个来回
            val t = ((SystemClock.uptimeMillis() - startMs) % 1800L) / 1800f
            val p = if (t < 0.5f) ease.getInterpolation(t * 2f) else ease.getInterpolation((1f - t) * 2f)
            p * (w - segW)
        } else {
            (w - segW) / 2f
        }
        canvas.drawRoundRect(x, 0f, x + segW, h, r, r, fillPaint)
        if (running && isShown) postInvalidateOnAnimation()
    }
}
