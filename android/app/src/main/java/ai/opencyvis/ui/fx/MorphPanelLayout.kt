package ai.opencyvis.ui.fx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * 贴底的莫奈面板，交互同顶部任务浮窗：收起是一枚胶囊，展开是一张卡片，同一块面板按弹簧曲线形变。
 *
 * 布局里放两个子 View：第一个是收起态内容，第二个是展开态内容，都贴底居中。两种内容一直参与排版
 * （不显示的那个是 INVISIBLE），容器按大的那个排版；面板形状贴底居中、尺寸取当前形态的真实排版结果，
 * 内容跟着形状的左上角走，切换时交叉淡入淡出。当前形态的内容尺寸变了（文字变长、多出输入框），
 * 形状也弹过去，不用外部再管。形状以外的触摸不拦截，交给下面的画面。
 */
class MorphPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PanelFrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private lateinit var compact: View
    private lateinit var expanded: View

    var isExpanded = false
        private set

    private val shape = RectF()
    private var shapeW = -1f
    private var shapeH = -1f
    private var morph: ValueAnimator? = null
    private var entryPending = false
    // 淡入中的内容额外往下沉一点，随透明度升到 1 归位
    private var rise = 0f

    init {
        // 圆角取 min(高度一半, 28dp)：收起时是胶囊，展开成卡片后是正常圆角（不设就成了一个大椭圆）
        panel.cornerRadius = 28 * density
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        compact = getChildAt(0)
        expanded = getChildAt(1)
        expanded.visibility = INVISIBLE
        expanded.alpha = 0f
    }

    /** 首次排版时从一颗小圆点铺开成胶囊，内容随后淡入。 */
    fun playEntry() {
        entryPending = true
        compact.alpha = 0f
        alpha = 0f
        translationY = 24 * density
        animate().alpha(1f).translationY(0f).setDuration(460)
            .setInterpolator(SpringInterpolator(0.72f, 10f)).start()
    }

    fun setExpanded(expand: Boolean, animate: Boolean = true) {
        if (isExpanded == expand) return
        isExpanded = expand
        val incoming = formView(expand)
        val outgoing = formView(!expand)
        incoming.visibility = VISIBLE
        if (!animate || shapeW < 0f || !isLaidOut) {
            morph?.cancel()
            morph = null
            rise = 0f
            incoming.alpha = 1f
            outgoing.alpha = 0f
            outgoing.visibility = INVISIBLE
            shapeW = -1f
            requestLayout()
            return
        }
        startMorph(swap = true)
    }

    private fun formView(expandedForm: Boolean): View = if (expandedForm) expanded else compact

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val target = formView(isExpanded)
        val tw = target.width.toFloat()
        val th = target.height.toFloat()
        if (tw <= 0f || th <= 0f) return
        if (shapeW < 0f) {
            if (entryPending) {
                entryPending = false
                val dot = minOf(th, 52 * density) * 0.86f
                shapeW = dot
                shapeH = dot
                startMorph(swap = false, entry = true)
            } else {
                shapeW = tw
                shapeH = th
            }
        } else if (morph == null && (tw != shapeW || th != shapeH)) {
            // 内容尺寸变了：形状弹到新尺寸（形变进行中不用管，动画每帧都取最新的目标尺寸）
            startMorph(swap = false)
        }
        placeShape()
    }

    /**
     * 形状从当前尺寸弹到当前形态的尺寸。目标尺寸每帧现取，动画途中内容再变也能接着走。
     * [swap] 时两种内容按当前透明度继续交叉淡入淡出。
     */
    private fun startMorph(swap: Boolean, entry: Boolean = false) {
        morph?.cancel()
        val incoming = formView(isExpanded)
        val outgoing = formView(!isExpanded)
        val fromW = shapeW
        val fromH = shapeH
        val inStart = incoming.alpha
        val outStart = if (outgoing.visibility == VISIBLE) outgoing.alpha else 0f
        val spring = if (entry) SpringInterpolator(0.68f, 11.5f) else SpringInterpolator(0.74f, 12f)
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when {
                entry -> 640
                swap -> 560
                else -> 440
            }
            if (entry) startDelay = 90
            addUpdateListener {
                val t = it.animatedFraction
                val p = spring.getInterpolation(t)
                shapeW = lerp(fromW, incoming.width.toFloat(), p)
                shapeH = lerp(fromH, incoming.height.toFloat(), if (entry) p.coerceAtMost(1.03f) else p)
                if (swap) {
                    outgoing.alpha = outStart * (1f - t / 0.3f).coerceIn(0f, 1f)
                    val inT = ((t - 0.2f) / 0.55f).coerceIn(0f, 1f)
                    incoming.alpha = inStart + (1f - inStart) * inT
                    rise = (1f - incoming.alpha) * 8 * density
                } else if (entry) {
                    incoming.alpha = ((t - 0.28f) / 0.45f).coerceIn(0f, 1f)
                }
                placeShape()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    morph = null
                    rise = 0f
                    incoming.alpha = 1f
                    if (swap) {
                        outgoing.alpha = 0f
                        outgoing.visibility = INVISIBLE
                    }
                    shapeW = incoming.width.toFloat()
                    shapeH = incoming.height.toFloat()
                    placeShape()
                }
            })
            start()
        }
    }

    /** 形状贴底居中；两种内容的左上角跟着形状走（按当前排版位置换算成平移），形变时不会先跳再归位。 */
    private fun placeShape() {
        if (shapeW < 0f || width == 0) return
        val cx = width / 2f
        val bottom = (height - panel.shadowMargin).toFloat()
        shape.set(cx - shapeW / 2f, bottom - shapeH, cx + shapeW / 2f, bottom)
        setShape(shape)
        val current = formView(isExpanded)
        for (child in listOf(compact, expanded)) {
            child.translationX = shape.left - child.left
            child.translationY = shape.top - child.top + if (child === current) rise else 0f
        }
    }

    /** 形状以外的触摸不拦截，落到下面的画面上。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && (shapeW < 0f || !shape.contains(ev.x, ev.y))) return false
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        morph?.cancel()
        super.onDetachedFromWindow()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/**
 * 交叉淡入淡出用的内容容器：不走离屏图层（透明度直接乘到每个绘制操作上）。
 * 半透明子视图的离屏图层在面板的圆角裁剪下合成，会把面板底色一起带淡，形变中途整块闪一下。
 */
class FadeLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    override fun hasOverlappingRendering(): Boolean = false
}
