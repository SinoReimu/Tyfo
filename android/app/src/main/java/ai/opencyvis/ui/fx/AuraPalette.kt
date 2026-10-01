package ai.opencyvis.ui.fx

import android.graphics.Color

/**
 * 光效共用的配色：每套 8 个色标，首尾相接后给 SweepGradient 用（转一圈接缝处不断）。
 * 不同状态之间按色标逐个插值过渡，所以每套色标数量必须一致。
 */
object AuraPalette {
    /** 执行中 / 聆听：偏 Gemini 的柔和彩虹，蓝紫为主，暖色做点缀。 */
    val RAINBOW = intArrayOf(
        0xFF5B8CFF.toInt(), 0xFF8F7BFF.toInt(), 0xFFD27BFF.toInt(), 0xFFFF6FA8.toInt(),
        0xFFFF9A5C.toInt(), 0xFFFFD166.toInt(), 0xFF5EE3A1.toInt(), 0xFF46D3F5.toInt(),
    )

    /** 等用户回答 / 接管：琥珀色呼吸。 */
    val AMBER = intArrayOf(
        0xFFFFB547.toInt(), 0xFFFF8F3F.toInt(), 0xFFFFC76B.toInt(), 0xFFFF9F4A.toInt(),
        0xFFFFD580.toInt(), 0xFFFF8A3D.toInt(), 0xFFFFBE5C.toInt(), 0xFFFFA24D.toInt(),
    )

    /** 任务完成：绿色。 */
    val SUCCESS = intArrayOf(
        0xFF34D399.toInt(), 0xFF6EE7B7.toInt(), 0xFF10B981.toInt(), 0xFF5EEAD4.toInt(),
        0xFF34D399.toInt(), 0xFF86EFAC.toInt(), 0xFF22C55E.toInt(), 0xFF2DD4BF.toInt(),
    )

    val ERROR = intArrayOf(
        0xFFFF5A5F.toInt(), 0xFFFF8A80.toInt(), 0xFFE11D48.toInt(), 0xFFFF6B6B.toInt(),
        0xFFFB7185.toInt(), 0xFFF43F5E.toInt(), 0xFFFF7A7A.toInt(), 0xFFEF4444.toInt(),
    )

    /** from→to 按 t 逐色标插值，结果末尾补上首色，直接喂给 SweepGradient。 */
    fun blendLoop(from: IntArray, to: IntArray, t: Float, out: IntArray = IntArray(from.size + 1)): IntArray {
        for (i in from.indices) out[i] = lerpColor(from[i], to[i], t)
        out[from.size] = out[0]
        return out
    }

    /** 整套色标的平均色，给小光晕、阴影染色用。 */
    fun average(colors: IntArray): Int {
        var r = 0; var g = 0; var b = 0
        for (c in colors) { r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
        val n = colors.size
        return Color.rgb(r / n, g / n, b / n)
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
