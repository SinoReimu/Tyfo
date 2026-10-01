package com.shiro.tyfo.ui.fx

import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 欠阻尼弹簧曲线，给形变 / 入场动画用，比 Overshoot 更“有重量”：先冲过头一点再稳稳落位。
 * [dampingRatio] 越小回弹越明显（0.7 左右约 4% 过冲，0.55 约 12%）；[stiffness] 决定一次动画内振荡快慢，
 * 取值要保证 t=1 时已基本停稳（e^(-ζ·ω) 足够小）。
 */
class SpringInterpolator(
    private val dampingRatio: Float = 0.72f,
    private val stiffness: Float = 11f,
) : Interpolator {
    private val dampedFreq = stiffness * sqrt(1f - dampingRatio * dampingRatio)
    private val sinCoef = dampingRatio / sqrt(1f - dampingRatio * dampingRatio)

    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val decay = exp(-dampingRatio * stiffness * t)
        return 1f - decay * (cos(dampedFreq * t) + sinCoef * sin(dampedFreq * t))
    }
}
