package com.shiro.tyfo.ui.fx

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/**
 * 左侧带图标的按钮，图标和文字作为一个整体居中。普通 TextView 的 drawableStart 会贴在左内边距上、
 * 文字另外居中，两者中间隔开一大截；这里按内容宽度动态调左右内边距。
 */
class IconTextButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val icon = compoundDrawablesRelative[0] ?: return
        val content = icon.intrinsicWidth + compoundDrawablePadding + paint.measureText(text.toString())
        val side = ((width - content) / 2f).toInt().coerceAtLeast(0)
        if (paddingStart != side || paddingEnd != side) {
            setPaddingRelative(side, paddingTop, side, paddingBottom)
        }
    }
}
