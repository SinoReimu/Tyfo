package com.shiro.tyfo.ui.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 以 [PanelDrawable] 为底的容器：子 View 裁在面板形状里，形变动画时内容不会溢出圆角。
 * 自动把投影边距加到 padding 上，布局里写的 padding 就是面板的内边距。默认套用莫奈色调。
 */
open class PanelFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val panel = PanelDrawable(context)
    private val clip = Path()

    init {
        val palette = MonetPalette(context)
        panel.fillColor = palette.surface
        panel.strokeColor = palette.hairline
        panel.shadowColor = palette.shadow
        background = panel
        val m = panel.shadowMargin
        setPadding(paddingLeft + m, paddingTop + m, paddingRight + m, paddingBottom + m)
        clipToPadding = false
    }

    /** 形变时由外部指定形状；传 null 恢复跟随自身尺寸。 */
    fun setShape(rect: RectF?) {
        panel.setShape(rect)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val shape = panel.shapeRect
        if (shape.width() <= 0f || shape.height() <= 0f) return
        val r = panel.radiusFor(shape)
        clip.reset()
        clip.addRoundRect(shape, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
