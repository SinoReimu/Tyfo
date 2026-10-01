package com.shiro.tyfo.ui.fx

import android.app.WallpaperManager
import android.content.Context
import androidx.core.graphics.ColorUtils

/**
 * 浮窗 / 语音助手的“莫奈色调”：从当前壁纸取主色，按固定的明暗关系生成一整套色调，换壁纸就跟着变。
 *
 * 不直接用系统动态色板（android.R.color.system_*）：用户可以把系统主题设成固定的预设色
 * （比如这台 Pixel 的灰绿“Spritz”），那时系统色板跟壁纸无关，浮窗会变成一片灰。
 * 深色调底：浮层叠在任意 App 上都要清楚，所以不跟随浅色模式。取不到壁纸颜色时用一套暖色兜底。
 */
class MonetPalette(context: Context) {
    val surface: Int
    /** 次级面板（推荐 chip 等），更透明。 */
    val surfaceLite: Int
    val hairline: Int
    val hairlineStrong: Int
    /** 强调色：图标底、麦克风、进度条。 */
    val accent: Int
    val onAccent: Int
    val text: Int
    val textSecondary: Int
    val textBody: Int
    /** 透明的按钮底（收起按钮等）和进度条轨道。 */
    val ghost: Int
    val track: Int
    /** 「查看画面」这类普通按钮。 */
    val tonalButton: Int
    val onTonalButton: Int
    /** 「停止」这类危险按钮、完成 / 失败状态，用固定的语义色。 */
    val dangerButton: Int = 0xFF5C2F2B.toInt()
    val onDangerButton: Int = 0xFFFFB4AB.toInt()
    val success: Int = 0xFFA8D5A2.toInt()
    val onSuccess: Int = 0xFF1F3A1C.toInt()
    val error: Int = 0xFFFFB4AB.toInt()
    val onError: Int = 0xFF5C1512.toInt()
    val shadow: Int = 0x38281405

    init {
        val seed = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        }.getOrNull() ?: FALLBACK_SEED
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seed, hsl)
        val hue = hsl[0]
        // 壁纸越灰，整套颜色越接近中性色，不硬加彩度
        val chroma = (hsl[1] / 0.25f).coerceIn(0.12f, 1f)
        fun tone(sat: Float, light: Float) = ColorUtils.HSLToColor(floatArrayOf(hue, (sat * chroma).coerceIn(0f, 1f), light))

        // 明暗关系按设计稿（暖色壁纸时：底 #302822、强调 #F2B783、正文 #F8EEE7…）
        val base = tone(0.17f, 0.16f)
        surface = withAlpha(base, 0.96f)
        surfaceLite = withAlpha(base, 0.70f)
        text = tone(0.50f, 0.94f)
        hairline = withAlpha(text, 0.07f)
        hairlineStrong = withAlpha(text, 0.16f)
        accent = tone(0.80f, 0.73f)
        onAccent = tone(0.40f, 0.16f)
        textSecondary = tone(0.20f, 0.76f)
        textBody = tone(0.28f, 0.86f)
        ghost = withAlpha(text, 0.08f)
        track = withAlpha(text, 0.12f)
        tonalButton = tone(0.18f, 0.25f)
        onTonalButton = tone(1f, 0.88f)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

    private companion object {
        /** 取不到壁纸颜色时的种子：设计稿里的暖橙。 */
        const val FALLBACK_SEED = 0xFFC98A5A.toInt()
    }
}
