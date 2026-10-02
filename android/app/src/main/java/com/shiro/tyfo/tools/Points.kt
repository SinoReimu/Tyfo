package com.shiro.tyfo.tools

import kotlin.math.roundToInt

/**
 * 模型用的坐标：所有模型统一用 0-1000 归一化，不按服务商切换单位，工具对谁都一样。
 * outline 里元素的位置由真实边界（设备像素）换算过来，模型照抄；执行器（InputInjector）也用这套坐标。
 *
 * 选它而不是设备像素：豆包（以及 UI-TARS、Qwen3-VL）就是按 0-1000 训练的，outline 用像素时实测它会混用两种单位；
 * 能力强的模型理解归一化也没有问题。
 */
object Points {
    const val MAX = 1000

    const val DESCRIPTION =
        "Points are [x, y] normalized to 0-1000 on both axes, whatever the screen or screenshot size: [0, 0] is the top-left corner of the screen, [1000, 1000] the bottom-right, [500, 500] the center. The outline's (x,y) use the same scale."

    /** 每个坐标参数自己的说明：只看工具定义的模型也知道是归一化坐标。 */
    const val PARAM_HINT =
        "[x, y] normalized to 0-1000 on both axes ([0, 0] top-left, [1000, 1000] bottom-right), e.g. an element's (x,y) from the outline."

    /** 设备像素 → 0-1000。不知道屏幕尺寸时原样返回。 */
    fun fromPixels(px: Int, py: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        if (screenWidth <= 0 || screenHeight <= 0) return px to py
        return (px * 1000.0 / screenWidth).roundToInt().coerceIn(0, MAX) to
            (py * 1000.0 / screenHeight).roundToInt().coerceIn(0, MAX)
    }

    /** 0-1000 → 设备像素。 */
    fun toPixels(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> =
        x * screenWidth / MAX to y * screenHeight / MAX
}
