package com.shiro.tyfo.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import com.shiro.tyfo.R
import com.shiro.tyfo.accessibility.VdAccessibilityService
import com.shiro.tyfo.config.ConfigRepository
import com.shiro.tyfo.engine.AgentState
import com.shiro.tyfo.engine.StepResult
import com.shiro.tyfo.ui.fx.AgentIconView
import com.shiro.tyfo.ui.fx.IconTextButton
import com.shiro.tyfo.ui.fx.MonetPalette
import com.shiro.tyfo.ui.fx.PanelFrameLayout
import com.shiro.tyfo.ui.fx.ProgressLineView
import com.shiro.tyfo.ui.fx.SpringInterpolator
import kotlin.math.abs

/**
 * 任务浮窗：屏幕顶部居中的一颗“灵动岛”，莫奈色调的扁平面板（略透明 + 轻投影）。
 *
 * - 收起：一枚胶囊，左边状态图标（执行中外圈一段弧线在转），右边一行当前进展（第 N 步 · 模型的想法）。
 * - 展开：一张固定宽度的卡片，任务标题 + 状态 + 当前进展 / 需要回答的问题 + 细进度条 + 「查看画面」「停止」。
 * - 收起 ⇄ 展开、文字变长变短，都是同一块面板按弹簧曲线形变，内容跟着面板左边缘走、交叉淡入淡出；
 *   入场从一颗小圆点铺开成胶囊，退场收回圆点再淡出。
 *
 * 形变不抖的关键：窗口只在两个固定尺寸之间切换——收起态是“最长胶囊 + 阴影边距”，展开态是“卡片 + 边距”。
 * 文字变化只在窗口里重绘，不动窗口；展开时先把窗口放大、等排版完成再播形变，收起时播完再缩回。
 * 窗口居中、内容也按窗口中线摆放，所以换尺寸那一下画面不动；窗口位置变化的系统移动动画也关掉了。
 * 形变的目标尺寸一律取真实排版结果，不在布局流程外手动 measure（那样会让子视图的测量和排版对不上）。
 *
 * 点胶囊展开；点卡片空白处收起；手动展开后一直保持，等用户回答 / 接管时自动展开、恢复执行后自动收起。
 * 上下拖动可以挪位置。
 */
class OverlayWindow(private val context: Context) {

    interface Callback {
        fun onReturnToApp()
        fun onStop()
    }

    private val appWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // 当前实际挂载浮窗的 WindowManager：无障碍服务开着时用它的（辅助功能悬浮窗），否则用应用自己的
    private var windowManager: WindowManager = appWindowManager
    private val debugMode = ConfigRepository(context).debugMode
    private val palette = MonetPalette(context)
    private val density = context.resources.displayMetrics.density

    var callback: Callback? = null
    var maxSteps: Int = 100

    var currentInstruction: String = ""
        set(value) {
            field = value
            refreshTexts(animate = false)
        }

    private var isExpanded = false
    // 因为要等用户而自动展开的，恢复执行后自动收回；用户自己点开的不收
    private var autoExpanded = false
    private var isPrepared = false
    private var isAttached = false
    private var _currentState: AgentState = AgentState.Idle()
    private var lastThought: String = ""
    // 窗口当前是展开尺寸还是收起尺寸（与 isExpanded 不同步：展开前先放大、收起播完才缩小）
    private var windowExpanded = false
    // 每次过渡加一，等排版回调时用来丢弃过期的过渡
    private var transitionSeq = 0

    internal var attachCount: Int = 0
        private set
    internal var detachCount: Int = 0
        private set

    // ── Views ──
    private var island: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var compact: LinearLayout
    private lateinit var compactIcon: AgentIconView
    private lateinit var compactText: TextView
    private lateinit var expanded: LinearLayout
    private lateinit var expandedIcon: AgentIconView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressLine: ProgressLineView
    private lateinit var primaryButton: TextView
    private lateinit var stopButton: TextView

    private var morphAnimator: ValueAnimator? = null
    private var exitAnimator: ValueAnimator? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun prepare() {
        if (isPrepared) return
        buildViews()
        val margin = island?.panel?.shadowMargin ?: 0
        windowExpanded = isExpanded
        params = WindowManager.LayoutParams(
            windowWidth(windowExpanded),
            windowHeight(windowExpanded),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            // 两种窗口类型的默认定位基准不同（普通悬浮窗从状态栏下方算起，辅助功能悬浮窗从屏幕顶端算起），
            // 统一改成以整块屏幕为基准，再显式加上状态栏高度；面板四周有投影边距，窗口往上提同样距离，
            // 胶囊正好离状态栏 6dp
            fitInsetsTypes = 0
            y = statusBarTop() + dp(6) - margin
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCanPlayMoveAnimation(false)
            title = "TyfoIsland"
        }
        applyState(animate = false)
        isPrepared = true
        Log.d(TAG, "prepare() done")
    }

    fun attach() {
        if (!isPrepared) {
            Log.w(TAG, "attach() called before prepare(); ignoring")
            return
        }
        if (isAttached) return
        val view = island ?: return
        applyState(animate = false)
        try {
            if (exitAnimator?.isRunning == true && view.isAttachedToWindow) {
                // 退场动画还没播完又要显示：从当前形态弹回来
                exitAnimator?.cancel()
                exitAnimator = null
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                transition(expandedForm = isExpanded)
            } else if (!view.isAttachedToWindow) {
                setWindowExpanded(isExpanded, update = false)
                chooseHost()
                windowManager.addView(view, params)
                playEntry(view)
            }
            attachCount++
            isAttached = true
            Log.d(TAG, "attach() added island expanded=$isExpanded")
        } catch (e: Exception) {
            Log.w(TAG, "attach: addView failed: ${e.message}")
        }
    }

    fun detach() {
        if (!isPrepared) return
        if (!isAttached) return
        isAttached = false
        detachCount++
        val view = island
        if (view != null && view.isAttachedToWindow) {
            playExit(view)
        }
        Log.d(TAG, "detach() done")
    }

    fun dismiss() {
        morphAnimator?.cancel()
        exitAnimator?.cancel()
        island?.let { removeNow(it) }
        if (isAttached) {
            isAttached = false
            detachCount++
        }
        island = null
        isPrepared = false
    }

    fun setExpanded(expanded: Boolean) {
        autoExpanded = false
        setExpandedInternal(expanded)
    }

    private fun setExpandedInternal(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        if (!isPrepared) return
        if (!isAttached) {
            showFormImmediately(expanded)
            return
        }
        transition(expandedForm = expanded)
    }

    /**
     * 选浮窗的宿主。无障碍服务开着时用辅助功能悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）：系统信任它，
     * Agent 打开“设置”等会隐藏普通悬浮窗的页面时照样显示，锁屏上也能显示；没开就退回普通悬浮窗。
     */
    private fun chooseHost() {
        val lp = params ?: return
        val a11y = VdAccessibilityService.instance
        if (a11y != null) {
            windowManager = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            windowManager = appWindowManager
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    /** 无障碍服务开启 / 关闭后，把正在显示的浮窗换到对应的宿主上。 */
    fun onAccessibilityHostChanged() {
        val view = island ?: return
        if (!isPrepared) return
        exitAnimator?.cancel()
        removeNow(view)
        if (!isAttached) return
        chooseHost()
        try {
            windowManager.addView(view, params)
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            showFormImmediately(isExpanded)
            Log.d(TAG, "island re-hosted, a11y=${VdAccessibilityService.instance != null}")
        } catch (e: Exception) {
            Log.w(TAG, "re-host failed: ${e.message}")
        }
    }

    fun isAttachedForTest(): Boolean = isAttached
    fun isExpandedForTest(): Boolean = isExpanded
    fun isPreparedForTest(): Boolean = isPrepared

    // ── State updates ──────────────────────────────────────────────────────

    fun updateState(state: AgentState) {
        Log.d(TAG, "updateState: $state isAttached=$isAttached isExpanded=$isExpanded")
        _currentState = state
        if (state is AgentState.Running && isRealThought(state.thought)) lastThought = state.thought
        if (needsUser(state)) {
            if (!isExpanded) {
                setExpandedInternal(true)
                autoExpanded = true
            }
        } else if (autoExpanded && state is AgentState.Running) {
            autoExpanded = false
            setExpandedInternal(false)
        }
        if (isPrepared) applyState(animate = isAttached)
    }

    fun addStepResult(result: StepResult) {
        if (isRealThought(result.thought)) lastThought = result.thought
        if (isPrepared) refreshTexts(animate = isAttached)
    }

    private fun needsUser(state: AgentState) =
        state is AgentState.WaitingForUser || state is AgentState.WaitingForHandoff

    private fun applyState(animate: Boolean) {
        val orbMode = when (val s = _currentState) {
            is AgentState.Running ->
                if (s.thought.startsWith("Thinking") || s.thought.startsWith("Capturing")) AgentIconView.Mode.THINKING
                else AgentIconView.Mode.RUNNING
            is AgentState.WaitingForUser, is AgentState.WaitingForHandoff -> AgentIconView.Mode.WAITING
            is AgentState.Paused -> AgentIconView.Mode.IDLE
            is AgentState.Error -> AgentIconView.Mode.ERROR
            is AgentState.Idle -> if (s.resultMessage != null) AgentIconView.Mode.SUCCESS else AgentIconView.Mode.IDLE
        }
        compactIcon.mode = orbMode
        expandedIcon.mode = orbMode

        progressLine.running = _currentState is AgentState.Running
        progressLine.visibility = if (needsUser(_currentState) || _currentState is AgentState.Running ||
            _currentState is AgentState.Paused) View.VISIBLE else View.GONE

        val terminal = _currentState is AgentState.Idle || _currentState is AgentState.Error
        stopButton.visibility = if (terminal) View.GONE else View.VISIBLE
        primaryButton.text = context.getString(
            when (_currentState) {
                is AgentState.WaitingForUser -> R.string.island_answer
                is AgentState.WaitingForHandoff -> R.string.island_takeover
                else -> R.string.island_open
            }
        )
        refreshTexts(animate)
    }

    private fun compactLine(): String {
        return when (val s = _currentState) {
            is AgentState.Running -> {
                val step = if (debugMode) "step ${s.step} / $maxSteps" else context.getString(R.string.island_step, s.step)
                val thought = shortThought()
                if (thought.isEmpty()) step else "$step · $thought"
            }
            is AgentState.WaitingForUser -> context.getString(R.string.island_waiting_answer)
            is AgentState.WaitingForHandoff -> context.getString(R.string.island_waiting_handoff)
            is AgentState.Paused -> context.getString(R.string.overlay_paused)
            is AgentState.Idle -> if (s.resultMessage != null) context.getString(R.string.island_done) else trimTask(currentInstruction)
            is AgentState.Error -> context.getString(R.string.island_failed)
        }
    }

    private fun metaLine(): String {
        return when (val s = _currentState) {
            is AgentState.Running -> context.getString(R.string.assist_task_running, s.step)
            is AgentState.WaitingForUser -> context.getString(R.string.assist_task_waiting_answer)
            is AgentState.WaitingForHandoff -> context.getString(R.string.assist_task_waiting_handoff)
            is AgentState.Paused -> context.getString(R.string.overlay_paused)
            is AgentState.Idle -> context.getString(if (s.resultMessage != null) R.string.island_done else R.string.overlay_running)
            is AgentState.Error -> context.getString(R.string.island_failed)
        }
    }

    private fun detailLine(): String {
        return when (val s = _currentState) {
            is AgentState.Running -> shortThought().ifBlank { context.getString(R.string.overlay_running) }
            is AgentState.WaitingForUser -> s.question
            is AgentState.WaitingForHandoff -> s.reason
            is AgentState.Paused -> context.getString(R.string.overlay_paused)
            is AgentState.Idle -> s.resultMessage ?: context.getString(R.string.island_done)
            is AgentState.Error -> s.message
        }
    }

    /** 引擎内部的占位文案（截图中 / 思考中 / 执行中）不适合给人看，只记录、显示模型的真实想法。 */
    private fun isRealThought(thought: String): Boolean {
        val t = thought.trim()
        return t.isNotEmpty() && !t.startsWith("Capturing") && !t.startsWith("Thinking") && !t.startsWith("Executing")
    }

    private fun shortThought(): String = lastThought.trim().replace('\n', ' ')

    private fun refreshTexts(animate: Boolean) {
        if (island == null) return
        val newCompact = compactLine()
        val newTitle = trimTask(currentInstruction, 40)
        val newDetail = detailLine()
        val newMeta = metaLine()
        val compactChanged = compactText.text.toString() != newCompact
        val detailChanged = detailText.text.toString() != newDetail || titleText.text.toString() != newTitle ||
            metaText.text.toString() != newMeta
        if (!compactChanged && !detailChanged) return
        compactText.text = newCompact
        titleText.text = newTitle
        metaText.text = newMeta
        detailText.text = newDetail
        if (!animate || !isAttached) {
            island?.let { settleShapeAfterLayout(it) }
            return
        }
        // 文字换了，胶囊宽度 / 卡片高度也跟着变：排版完成后玻璃弹簧过渡到新尺寸，新文字淡入
        val visibleChanged = if (isExpanded) detailChanged else compactChanged
        if (!visibleChanged) return
        transition(expandedForm = isExpanded)
        val target = if (isExpanded) detailText else compactText
        target.alpha = 0.2f
        target.translationY = dp(5).toFloat()
        target.animate().alpha(1f).translationY(0f).setDuration(340)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // ── 窗口尺寸 ────────────────────────────────────────────────────────────

    private val shadowMargin: Int get() = island?.panel?.shadowMargin ?: dp(16)

    /** 展开卡片的固定宽度。 */
    private fun expandedWidth(): Int =
        dp(EXPANDED_WIDTH_DP).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(24))

    /** 收起胶囊的最大宽度：左右内边距 + 状态图标 + 间距 + 文字最大宽度。 */
    private fun compactMaxWidth(): Int = dp(6 + 32 + 9 + 16) + dp(COMPACT_TEXT_MAX_DP)

    private fun windowWidth(expanded: Boolean): Int =
        (if (expanded) expandedWidth() else compactMaxWidth()) + 2 * shadowMargin

    private fun windowHeight(expanded: Boolean): Int =
        dp(if (expanded) EXPANDED_MAX_HEIGHT_DP else COMPACT_HEIGHT_DP) + 2 * shadowMargin

    /** 切换窗口尺寸；返回尺寸是否真的变了。 */
    private fun setWindowExpanded(expanded: Boolean, update: Boolean = true): Boolean {
        val lp = params ?: return false
        val w = windowWidth(expanded)
        val h = windowHeight(expanded)
        windowExpanded = expanded
        if (lp.width == w && lp.height == h) return false
        lp.width = w
        lp.height = h
        val view = island
        if (update && view != null && view.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (_: Exception) {}
        }
        return true
    }

    // ── 形变 ────────────────────────────────────────────────────────────────

    private fun formView(expanded: Boolean): View = if (expanded) this.expanded else compact

    /** 取某种形态的真实排版尺寸；还没排过版返回 null。 */
    private fun formSize(expanded: Boolean): Pair<Float, Float>? {
        val v = formView(expanded)
        if (v.width <= 0 || v.height <= 0) return null
        return v.width.toFloat() to v.height.toFloat()
    }

    /** 不播动画，直接摆成某种形态（未显示时用）。 */
    private fun showFormImmediately(expanded: Boolean) {
        val incoming = formView(expanded)
        val outgoing = formView(!expanded)
        incoming.visibility = View.VISIBLE
        incoming.alpha = 1f
        incoming.translationY = 0f
        outgoing.visibility = View.INVISIBLE
        outgoing.alpha = 0f
        setWindowExpanded(expanded)
        island?.let { settleShapeAfterLayout(it) }
    }

    /** 排版完成后把玻璃直接摆到当前形态的尺寸。 */
    private fun settleShapeAfterLayout(view: IslandView) {
        val seq = ++transitionSeq
        view.doOnLayout {
            if (seq != transitionSeq) return@doOnLayout
            formSize(isExpanded)?.let { (w, h) -> view.applyShape(w, h) }
        }
    }

    /**
     * 过渡到 [expandedForm] 形态（形态不变时就是内容尺寸变了）。
     * 展开：先放大窗口，等排版完成拿到卡片真实尺寸再播；收起 / 文字变化：等排版完成就播，收起播完再缩窗口。
     * 玻璃从当前尺寸按弹簧曲线变到目标尺寸，两种内容按当前透明度继续交叉淡入淡出，中途再来一次也能接着走。
     */
    private fun transition(expandedForm: Boolean) {
        val view = island ?: return
        val seq = ++transitionSeq
        morphAnimator?.cancel()
        val fromW = view.shapeW
        val fromH = view.shapeH
        val incoming = formView(expandedForm)
        incoming.visibility = View.VISIBLE
        val start = { if (seq == transitionSeq) runMorph(view, expandedForm, fromW, fromH) }
        if (expandedForm && !windowExpanded && setWindowExpanded(true)) {
            view.doOnNextLayout { start() }
        } else {
            view.doOnLayout { start() }
        }
    }

    private fun runMorph(view: IslandView, expandedForm: Boolean, fromW0: Float, fromH0: Float) {
        val incoming = formView(expandedForm)
        val outgoing = formView(!expandedForm)
        val (toW, toH) = formSize(expandedForm) ?: return
        val fromW = if (fromW0 > 0f) fromW0 else toW
        val fromH = if (fromH0 > 0f) fromH0 else toH
        val inStart = incoming.alpha
        val outStart = if (outgoing.visibility == View.VISIBLE) outgoing.alpha else 0f
        val swapping = outStart > 0f || inStart < 1f
        val spring = SpringInterpolator(dampingRatio = 0.74f, stiffness = 12f)
        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (swapping) 560 else 440
            addUpdateListener {
                val t = it.animatedFraction
                val p = spring.getInterpolation(t)
                view.applyShape(lerp(fromW, toW, p), lerp(fromH, toH, p))
                if (swapping) {
                    outgoing.alpha = outStart * (1f - t / 0.3f).coerceIn(0f, 1f)
                    val inT = ((t - 0.2f) / 0.55f).coerceIn(0f, 1f)
                    incoming.alpha = inStart + (1f - inStart) * inT
                    incoming.translationY = (1f - incoming.alpha) * dp(8)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    incoming.alpha = 1f
                    incoming.translationY = 0f
                    outgoing.alpha = 0f
                    outgoing.visibility = View.INVISIBLE
                    view.applyShape(toW, toH)
                    // 收起播完，窗口缩回胶囊尺寸（胶囊居中，缩窗口时画面不动）
                    if (!expandedForm && windowExpanded) setWindowExpanded(false)
                }
            })
            start()
        }
    }

    /** 入场：先是一颗小圆点从上方落下，再横向铺开成胶囊 / 卡片，内容随后淡入。 */
    private fun playEntry(view: IslandView) {
        morphAnimator?.cancel()
        val seq = ++transitionSeq
        val incoming = formView(isExpanded)
        val outgoing = formView(!isExpanded)
        outgoing.visibility = View.INVISIBLE
        outgoing.alpha = 0f
        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationY = 0f
        view.alpha = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.doOnLayout {
            if (seq != transitionSeq) return@doOnLayout
            val (toW, toH) = formSize(isExpanded) ?: return@doOnLayout
            val dot = toH
            view.translationY = -dp(16).toFloat()
            view.applyShape(dot, dot)
            view.animate().alpha(1f).translationY(0f).setDuration(460)
                .setInterpolator(SpringInterpolator(0.66f, 11f)).start()
            val spring = SpringInterpolator(dampingRatio = 0.7f, stiffness = 12f)
            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 110
                duration = 620
                addUpdateListener {
                    val t = it.animatedFraction
                    view.applyShape(lerp(dot, toW, spring.getInterpolation(t)), toH)
                    incoming.alpha = ((t - 0.3f) / 0.45f).coerceIn(0f, 1f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        incoming.alpha = 1f
                        view.applyShape(toW, toH)
                    }
                })
                start()
            }
        }
    }

    /** 退场：内容先淡出，玻璃收回成小圆点，再缩小淡出，最后移除窗口。 */
    private fun playExit(view: IslandView) {
        morphAnimator?.cancel()
        exitAnimator?.cancel()
        ++transitionSeq
        val fromW = view.shapeW
        val fromH = view.shapeH
        val ease = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            addUpdateListener {
                val t = it.animatedFraction
                val p = ease.getInterpolation(t)
                val contentAlpha = (1f - t / 0.4f).coerceIn(0f, 1f)
                compact.alpha = minOf(compact.alpha, contentAlpha)
                expanded.alpha = minOf(expanded.alpha, contentAlpha)
                view.applyShape(lerp(fromW, fromH, p), fromH)
                view.alpha = if (t > 0.55f) 1f - (t - 0.55f) / 0.45f else 1f
                val s = if (t > 0.55f) 1f - 0.3f * (t - 0.55f) / 0.45f else 1f
                view.scaleX = s
                view.scaleY = s
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    removeNow(view)
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    showFormImmediately(isExpanded)
                }
            })
            start()
        }
    }

    private fun removeNow(view: View) {
        try {
            if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
        } catch (_: Exception) {}
    }

    // ── 构建视图 ─────────────────────────────────────────────────────────────

    private fun buildViews() {
        val root = IslandView(context)
        // 圆角取 min(高度一半, 28dp)：收起时是胶囊，展开成卡片后是正常圆角，不会把按钮切掉
        root.panel.cornerRadius = dp(28).toFloat()
        root.onDragBy = { dy -> moveBy(dy) }
        island = root

        compact = FadeLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(16), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
        }
        compactIcon = AgentIconView(context).apply { showRing = true }
        compact.addView(compactIcon, LinearLayout.LayoutParams(dp(32), dp(32)))
        compactText = TextView(context).apply {
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxWidth = dp(COMPACT_TEXT_MAX_DP)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        compact.addView(compactText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(9) })
        compact.setOnClickListener { setExpanded(true) }

        expanded = FadeLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                expandedWidth(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
            visibility = View.INVISIBLE
            alpha = 0f
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        expandedIcon = AgentIconView(context)
        header.addView(expandedIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleText = TextView(context).apply {
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        metaText = TextView(context).apply {
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
        }
        texts.addView(titleText)
        texts.addView(metaText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(1) })
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        val collapse = ImageView(context).apply {
            setImageResource(R.drawable.ic_ui_chevron_up)
            imageTintList = ColorStateList.valueOf(palette.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.ghost) },
                null,
            )
            contentDescription = context.getString(R.string.island_collapse)
            setOnClickListener { setExpanded(false) }
        }
        header.addView(collapse, LinearLayout.LayoutParams(dp(34), dp(34)))
        expanded.addView(header)

        detailText = TextView(context).apply {
            setTextColor(palette.textBody)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.3f)
        }
        expanded.addView(detailText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        progressLine = ProgressLineView(context)
        expanded.addView(progressLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3),
        ).apply { topMargin = dp(16) })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        primaryButton = pillButton(palette.tonalButton, palette.onTonalButton, R.drawable.ic_ui_eye).apply {
            setOnClickListener {
                callback?.onReturnToApp()
                setExpanded(false)
            }
        }
        stopButton = pillButton(palette.dangerButton, palette.onDangerButton, R.drawable.ic_ui_stop).apply {
            text = context.getString(R.string.overlay_stop)
            setOnClickListener { callback?.onStop() }
        }
        buttons.addView(primaryButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(10) })
        expanded.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) })
        expanded.setOnClickListener { setExpanded(false) }

        root.addView(compact)
        root.addView(expanded)
    }

    /** 扁平按钮：色块底 + 左侧线性图标 + 文字，图标和文字同色。 */
    private fun pillButton(bg: Int, fg: Int, icon: Int) = IconTextButton(context).apply {
        gravity = Gravity.CENTER
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(bg)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = dp(6)
        compoundDrawableTintList = ColorStateList.valueOf(fg)
        isClickable = true
        isFocusable = true
    }

    private fun moveBy(dy: Float) {
        val view = island ?: return
        val lp = params ?: return
        val maxY = context.resources.displayMetrics.heightPixels - lp.height
        lp.y = (lp.y + dy.toInt()).coerceIn(-view.panel.shadowMargin, maxY)
        try {
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, lp)
        } catch (_: Exception) {}
    }

    private fun statusBarTop(): Int = appWindowManager.currentWindowMetrics.windowInsets
        .getInsets(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()).top

    private fun trimTask(text: String, max: Int = 20): String {
        if (text.isBlank()) return "Tyfo"
        return if (text.length > max) text.take(max) + "…" else text
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * 两种形态的内容容器。交叉淡入淡出时不走离屏图层（透明度直接乘到每个绘制操作上）：
     * 实测半透明子视图的离屏图层在玻璃的圆角裁剪下合成，会把玻璃背景一起带淡，形变中途整块闪一下。
     */
    private class FadeLayout(context: Context) : LinearLayout(context) {
        override fun hasOverlappingRendering(): Boolean = false
    }

    /**
     * 浮窗根视图，铺满窗口。玻璃形状顶边居中，由 [applyShape] 指定尺寸；
     * 两种内容都跟着玻璃左边缘走（按当前布局位置换算成 translationX），形变时不会先跳再归位。
     * 竖向拖动超过阈值就当成挪位置，否则把点击交给子视图。
     */
    private class IslandView(context: Context) : PanelFrameLayout(context) {
        var onDragBy: ((Float) -> Unit)? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downY = 0f
        private var lastY = 0f
        private var dragging = false
        private val shape = RectF()
        var shapeW = -1f
            private set
        var shapeH = -1f
            private set

        fun applyShape(w: Float, h: Float) {
            shapeW = w
            shapeH = h
            placeShape()
        }

        private fun placeShape() {
            if (shapeW < 0f || width == 0) return
            val m = panel.shadowMargin.toFloat()
            val cx = width / 2f
            shape.set(cx - shapeW / 2f, m, cx + shapeW / 2f, m + shapeH)
            setShape(shape)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.translationX = shape.left - child.left
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            placeShape()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    lastY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (!dragging && abs(ev.rawY - downY) > slop) {
                    dragging = true
                    lastY = ev.rawY
                    return true
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    lastY = ev.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(ev.rawY - downY) > slop) dragging = true
                    if (dragging) {
                        onDragBy?.invoke(ev.rawY - lastY)
                        lastY = ev.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    companion object {
        private const val TAG = "OverlayWindow"
        private const val EXPANDED_WIDTH_DP = 360
        // 展开卡片最高：标题 + 三行说明 + 按钮（内容少时卡片更矮，下面留空）
        private const val EXPANDED_MAX_HEIGHT_DP = 232
        private const val COMPACT_HEIGHT_DP = 44
        private const val COMPACT_TEXT_MAX_DP = 220
    }
}
