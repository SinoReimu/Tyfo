package com.shiro.tyfo.assistant

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.os.PowerManager
import android.content.res.ColorStateList
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.shiro.tyfo.AgentService
import com.shiro.tyfo.OverlayService
import com.shiro.tyfo.R
import com.shiro.tyfo.db.ChatHistoryRepository
import com.shiro.tyfo.engine.AgentEngine
import com.shiro.tyfo.engine.AgentState
import com.shiro.tyfo.engine.isTaskActive
import com.shiro.tyfo.ui.fx.AgentIconView
import com.shiro.tyfo.ui.fx.AuraPalette
import com.shiro.tyfo.ui.fx.MonetPalette
import com.shiro.tyfo.ui.fx.PanelFrameLayout
import com.shiro.tyfo.ui.fx.ProgressLineView
import com.shiro.tyfo.ui.fx.SpringInterpolator
import com.shiro.tyfo.config.ConfigRepository
import com.shiro.tyfo.voice.DoubaoStreamingSpeechInputEngine
import com.shiro.tyfo.voice.SpeechInputEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语音助手面板（长按电源键唤起）：底部悬浮的一条莫奈色调扁平胶囊，交互参考 Gemini 的浮层。
 *
 * 一弹出就开始听：说完自动交给 Agent 执行，胶囊里的状态图标从“聆听”（声波）转成“思考”，再画出对勾后收起，
 * 执行进度交给顶部的任务浮窗。也可以点键盘图标改成打字。
 *
 * 已有任务在跑时（同一时间只跑一个任务），上方多出一张当前任务卡片（进度、查看、停止），
 * 这时说的话默认当作对当前任务的补充；任务在等用户回答时直接当作回答；
 * 也可以选择停掉当前任务改做新的。
 *
 * 面板本身不关心宿主是什么窗口，[Host.close] 由宿主负责真正收起（VoiceInteractionSession.hide 等）。
 */
class AssistantPanel(private val context: Context, private val host: Host) {

    interface Host {
        fun close()
    }

    private enum class Phase { IDLE, LISTENING, TYPING, SUBMITTING, CHOICE, DONE, NO_MATCH, ERROR, TRACKING }

    private companion object {
        const val TAG = "AssistantPanel"
        const val SUGGESTION_COUNT = 3
    }

    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val palette = MonetPalette(context)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var screenLock: PowerManager.WakeLock? = null

    val view: View = LayoutInflater.from(context).inflate(R.layout.assistant_panel, null)
    private val root: ViewGroup = view.findViewById(R.id.assist_root)
    private val panel: LinearLayout = view.findViewById(R.id.assist_panel)
    private val taskCard: PanelFrameLayout = view.findViewById(R.id.assist_task_card)
    private val taskOrb: AgentIconView = view.findViewById(R.id.assist_task_icon)
    private val taskProgress: ProgressLineView = view.findViewById(R.id.assist_task_progress)
    private val taskViewButton: TextView = view.findViewById(R.id.assist_task_view)
    private val taskStopButton: TextView = view.findViewById(R.id.assist_task_stop)
    private val taskLabel: TextView = view.findViewById(R.id.assist_task_label)
    private val taskTitle: TextView = view.findViewById(R.id.assist_task_title)
    private val taskDetail: TextView = view.findViewById(R.id.assist_task_detail)
    private val chipsScroll: HorizontalScrollView = view.findViewById(R.id.assist_chips_scroll)
    private val chips: LinearLayout = view.findViewById(R.id.assist_chips)
    private val pill: PanelFrameLayout = view.findViewById(R.id.assist_pill)
    private val pillContent: View = view.findViewById(R.id.assist_pill_content)
    private val orb: AgentIconView = view.findViewById(R.id.assist_icon)
    private val text: TextView = view.findViewById(R.id.assist_text)
    private val input: EditText = view.findViewById(R.id.assist_input)
    private val caption: TextView = view.findViewById(R.id.assist_caption)
    private val keyboardButton: ImageView = view.findViewById(R.id.assist_keyboard)
    private val micButton: ImageView = view.findViewById(R.id.assist_mic)
    private val micHalo: View = view.findViewById(R.id.assist_mic_halo)

    private var phase = Phase.IDLE
    private var closing = false
    private var scope: CoroutineScope? = null
    private var speech: DoubaoStreamingSpeechInputEngine? = null
    private var listening = false
    private var agentService: AgentService? = null
    private var bound = false
    private var engineJob: Job? = null
    private var pendingUtterance: String? = null
    private var suggestions: List<String> = emptyList()
    private var entryAnimator: ValueAnimator? = null
    // 推荐 chip 要等胶囊铺开、内容开始淡入后再弹出，否则历史记录读得快，会比胶囊先冒出来
    private var chipsReady = false
    // 当前显示的 chip（文案 + 样式）；同一组再设一遍不重建，免得整排消失再弹一次
    private var chipsKey: String? = null
    private val micFill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var micFillColor = 0
    private var micTintColor = 0
    private var micTarget: Pair<Int, Int>? = null
    private var micAnimator: ValueAnimator? = null
    // 模型的真实想法只在拿到回复的那一下出现，随后就被“执行中 / 截图中”覆盖，卡片上保留最近一条
    private var lastThought = ""
    private val autoCloseRunnable = Runnable { close() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AgentService.AgentBinder)?.getService() ?: return
            agentService = service
            observeTask(service)
            pendingUtterance?.let {
                pendingUtterance = null
                route(service, it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            engineJob?.cancel()
        }
    }

    init {
        // 圆角取 min(高度一半, 固定值)：单行时是胶囊，内容变多成卡片时不会变成一大团
        taskCard.panel.cornerRadius = dp(28).toFloat()
        pill.panel.cornerRadius = dp(32).toFloat()
        micButton.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), micFill, null)
        applyPalette()
        root.setOnClickListener { close() }
        keyboardButton.setOnClickListener { enterTyping(null) }
        micButton.setOnClickListener { onMicClicked() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTyped()
                true
            } else {
                false
            }
        }
        input.doAfterTextChanged { if (phase == Phase.TYPING) renderMic() }
        view.findViewById<View>(R.id.assist_task_view).setOnClickListener {
            agentService?.enterViewMode()
            close()
        }
        view.findViewById<View>(R.id.assist_task_stop).setOnClickListener {
            agentService?.stopAgent()
            stopListening()
            showDone(context.getString(R.string.assist_done_stopped))
        }
        // 底部让开导航栏和输入法
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.ime()).bottom,
            )
            val lp = panel.layoutParams as ViewGroup.MarginLayoutParams
            lp.bottomMargin = bottom + dp(10) - pill.panel.shadowMargin
            panel.layoutParams = lp
            insets
        }
    }

    // ── 宿主生命周期 ──────────────────────────────────────────────────────

    fun onShow() {
        Log.i(TAG, "onShow")
        main.removeCallbacksAndMessages(null)
        closing = false
        scope?.cancel()
        scope = MainScope()
        // 锁屏时系统的亮屏超时很短（约 10 秒），说着说着就会暗下去；面板显示期间保持亮屏
        acquireScreenLock()
        resetUi()
        bindAgentService()
        loadSuggestions()
        playEntry()
        when {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                enterTyping(context.getString(R.string.assist_no_mic))
            ConfigRepository(context).apiKey.isBlank() ->
                enterTyping(context.getString(R.string.asr_error_no_key))
            // 等胶囊铺开一半再开麦，开场动画不会被模型加载卡住
            else -> main.postDelayed({ startListening() }, 260)
        }
    }

    fun onHide() {
        Log.i(TAG, "onHide")
        main.removeCallbacksAndMessages(null)
        stopListening()
        hideKeyboard()
        engineJob?.cancel()
        scope?.cancel()
        scope = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        agentService = null
        releaseScreenLock()
    }

    fun onDestroy() {
        onHide()
        speech?.destroy()
        speech = null
    }

    /** 返回键直接收起（输入法开着时返回键先被输入法消费）。 */
    fun onBackPressed() {
        close()
    }

    // ── 听写 ──────────────────────────────────────────────────────────────

    private val speechListener = object : SpeechInputEngine.Listener {
        override fun onReady() {
            if (!listening) return
            phase = Phase.LISTENING
        }

        override fun onPartialResult(text: String) {
            if (!listening) return
            showTranscript(text)
        }

        override fun onFinalResult(text: String) {
            if (!listening) return
            listening = false
            renderMic()
            submit(text)
        }

        override fun onError(message: String) {
            if (!listening) return
            listening = false
            showError(message)
        }

        override fun onEnd() {
            if (!listening) return
            listening = false
            showNoMatch()
        }

        override fun onAudioLevel(level: Float) {
            if (!listening) return
            orb.setLevel(level)
            val s = 0.85f + 0.45f * level
            micHalo.scaleX = s
            micHalo.scaleY = s
        }
    }

    private fun startListening() {
        if (closing) return
        main.removeCallbacks(autoCloseRunnable)
        hideKeyboard()
        input.visibility = View.GONE
        text.visibility = View.VISIBLE
        phase = Phase.LISTENING
        setHint(listeningHint())
        setCaption(null)
        orb.mode = AgentIconView.Mode.LISTENING
        showSuggestionsIfIdle()
        val engine = speech ?: DoubaoStreamingSpeechInputEngine(context).also { speech = it }
        listening = true
        renderMic()
        engine.startListening(speechListener)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        speech?.stopListening()
        orb.setLevel(0f)
        renderMic()
    }

    private fun onMicClicked() {
        when {
            phase == Phase.TYPING && input.text.isNotBlank() -> submitTyped()
            listening -> {
                // 手动结束：有字就按当前听到的提交，没字就当没听清
                val heard = text.tag as? String
                stopListening()
                if (!heard.isNullOrBlank()) submit(heard) else showNoMatch()
            }
            else -> startListening()
        }
    }

    private fun enterTyping(reason: String?) {
        if (closing) return
        stopListening()
        main.removeCallbacks(autoCloseRunnable)
        phase = Phase.TYPING
        text.visibility = View.GONE
        input.visibility = View.VISIBLE
        input.hint = context.getString(if (isBusy()) R.string.assist_hint_busy else R.string.assist_hint_typing)
        setCaption(reason)
        orb.mode = AgentIconView.Mode.IDLE
        renderMic()
        input.requestFocus()
        main.postDelayed({
            context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun submitTyped() {
        val typed = input.text.toString().trim()
        if (typed.isEmpty()) return
        hideKeyboard()
        input.visibility = View.GONE
        text.visibility = View.VISIBLE
        input.text.clear()
        submit(typed)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ── 交给 Agent ────────────────────────────────────────────────────────

    private fun submit(utterance: String) {
        val cleaned = utterance.trim()
        if (cleaned.isEmpty()) {
            showNoMatch()
            return
        }
        showTranscript(cleaned)
        hideChips()
        phase = Phase.SUBMITTING
        orb.mode = AgentIconView.Mode.THINKING
        setCaption(context.getString(R.string.assist_submitting))
        val service = agentService
        if (service == null) {
            pendingUtterance = cleaned
            bindAgentService()
            return
        }
        route(service, cleaned)
    }

    /** 同一时间只跑一个任务：在等回答就当回答，在跑就让用户选补充还是换新任务，空闲就直接开跑。 */
    private fun route(service: AgentService, utterance: String) {
        val state = service.stateFlow?.value
        when {
            state is AgentState.WaitingForUser -> {
                service.submitUserResponse(utterance)
                handedOff(context.getString(R.string.assist_done_answered))
            }
            service.isTaskActive -> showChoice(utterance)
            else -> startTask(service, utterance, replace = false)
        }
    }

    private fun startTask(service: AgentService, utterance: String, replace: Boolean) {
        phase = Phase.SUBMITTING
        orb.mode = AgentIconView.Mode.THINKING
        setCaption(context.getString(R.string.assist_submitting))
        hideChips()
        // 先把服务升成前台服务，助手收起、解绑后任务也能继续跑；再拉起顶部任务浮窗
        runCatching { context.startForegroundService(Intent(context, AgentService::class.java)) }
            .onFailure { Log.w(TAG, "startForegroundService failed: ${it.message}") }
        runCatching { context.startService(Intent(context, OverlayService::class.java)) }
        val previousEngine = service.engineFlow.value
        if (service.startAgent(utterance, replaceRunning = replace) == AgentService.StartResult.BUSY) {
            showChoice(utterance)
            return
        }
        val serial = service.lastStartSerial
        val s = scope ?: return
        s.launch {
            // 等真正跑起来（出现新引擎）或启动失败；启动慢就先当成功，后续进度看顶部浮窗。
            // 结果："" 表示已开跑，非空是失败原因
            val outcome = withTimeoutOrNull(4000) {
                merge(
                    service.engineFlow.filter { it != null && it !== previousEngine }.map { "" },
                    service.taskEvents.filterIsInstance<AgentService.TaskEvent.StartFailed>()
                        .filter { it.serial == serial }.map { it.reason },
                ).first()
            }
            if (!outcome.isNullOrEmpty()) {
                showError(outcome)
            } else {
                handedOff(
                    context.getString(if (replace) R.string.assist_done_replaced else R.string.assist_done_started)
                )
            }
        }
    }

    // ── 各种结果态 ────────────────────────────────────────────────────────

    private fun showChoice(utterance: String) {
        phase = Phase.CHOICE
        orb.mode = AgentIconView.Mode.WAITING
        showTranscript(utterance)
        setCaption(context.getString(R.string.assist_choice_caption))
        setChips(
            listOf(
                Chip(context.getString(R.string.assist_choice_supplement), primary = true) {
                    agentService?.submitUserSupplement(utterance)
                    handedOff(context.getString(R.string.assist_done_supplemented))
                },
                Chip(context.getString(R.string.assist_choice_replace), primary = false) {
                    val service = agentService ?: return@Chip
                    startTask(service, utterance, replace = true)
                },
            )
        )
    }

    /**
     * 交给 Agent 之后打个勾就收起，进度交给实况窗（锁屏上也能看到）或解锁时的顶部浮窗。
     * 只有锁屏且系统不支持实况窗时，浮窗又被锁屏挡住，面板才留在锁屏上实时显示任务进度，直到用户关掉或任务结束。
     */
    private fun handedOff(message: String) {
        val trackOnLockScreen = keyguard?.isKeyguardLocked == true && !com.shiro.tyfo.LiveUpdates.isAvailable(context)
        if (trackOnLockScreen) enterTracking(message) else showDone(message)
    }

    private fun enterTracking(message: String) {
        phase = Phase.TRACKING
        stopListening()
        hideChips()
        orb.mode = AgentIconView.Mode.RUNNING
        setCaption(context.getString(R.string.assist_tracking_locked))
        main.removeCallbacks(autoCloseRunnable)
        agentService?.let { renderTask(it, it.stateFlow?.value) }
    }

    /** 锁屏跟进度时任务结束了：给出结果，停一会儿再收起。 */
    private fun finishTracking(state: AgentState?) {
        if (taskCard.visibility == View.VISIBLE) fadeOut(taskCard)
        when {
            state is AgentState.Error -> showError(state.message)
            state is AgentState.Idle && state.resultMessage != null -> {
                phase = Phase.DONE
                orb.mode = AgentIconView.Mode.SUCCESS
                setCaption(context.getString(R.string.assist_tracking_done, state.resultMessage.replace('\n', ' ')))
                main.removeCallbacks(autoCloseRunnable)
                main.postDelayed(autoCloseRunnable, 6000)
            }
            else -> close()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenLock() {
        if (screenLock?.isHeld == true) return
        screenLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "Tyfo:assistant")
            ?.apply {
                setReferenceCounted(false)
                acquire(10 * 60_000L)
            }
    }

    private fun releaseScreenLock() {
        screenLock?.let { if (it.isHeld) it.release() }
        screenLock = null
    }

    private fun showDone(message: String) {
        phase = Phase.DONE
        stopListening()
        hideChips()
        orb.mode = AgentIconView.Mode.SUCCESS
        setCaption(message)
        main.removeCallbacks(autoCloseRunnable)
        main.postDelayed(autoCloseRunnable, 1300)
    }

    private fun showNoMatch() {
        phase = Phase.NO_MATCH
        orb.mode = AgentIconView.Mode.IDLE
        setHint(context.getString(R.string.assist_no_match))
        setCaption(null)
        renderMic()
        showSuggestionsIfIdle()
        main.removeCallbacks(autoCloseRunnable)
        main.postDelayed(autoCloseRunnable, 8000)
    }

    private fun showError(message: String) {
        phase = Phase.ERROR
        stopListening()
        orb.mode = AgentIconView.Mode.ERROR
        setCaption(message)
        renderMic()
        main.removeCallbacks(autoCloseRunnable)
        main.postDelayed(autoCloseRunnable, 4000)
    }

    // ── 当前任务卡片 ──────────────────────────────────────────────────────

    private fun bindAgentService() {
        if (bound) return
        bound = context.bindService(Intent(context, AgentService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun observeTask(service: AgentService) {
        engineJob?.cancel()
        engineJob = scope?.launch {
            service.engineFlow.collectLatest { engine: AgentEngine? ->
                if (engine == null) {
                    renderTask(service, null)
                    return@collectLatest
                }
                engine.state.collect { state -> renderTask(service, state) }
            }
        }
    }

    private fun isBusy(): Boolean = agentService?.isTaskActive == true

    private fun renderTask(service: AgentService, state: AgentState?) {
        // 以状态为准：引擎发出结束状态时协程可能还差几行才退出，isTaskActive 会晚一拍变 false
        val finished = (state is AgentState.Idle && state.resultMessage != null) || state is AgentState.Error
        val busy = state.isTaskActive || (!finished && service.isTaskActive)
        if (!busy) {
            if (phase == Phase.TRACKING) {
                finishTracking(state)
                return
            }
            if (taskCard.visibility == View.VISIBLE) fadeOut(taskCard)
            if (phase == Phase.LISTENING && text.tag == null) setHint(listeningHint())
            return
        }
        hideSuggestions()
        taskTitle.text = service.currentInstruction.ifBlank { context.getString(R.string.app_name) }
        when (state) {
            is AgentState.Running -> {
                taskProgress.running = true
                taskLabel.text = context.getString(R.string.assist_task_running, state.step)
                val t = state.thought.trim()
                if (t.isNotEmpty() && !t.startsWith("Capturing") && !t.startsWith("Thinking") && !t.startsWith("Executing")) {
                    lastThought = t.replace('\n', ' ')
                }
                taskDetail.text = lastThought.ifBlank { service.latestThought }
                    .ifBlank { context.getString(R.string.overlay_running) }
                taskOrb.mode = AgentIconView.Mode.RUNNING
            }
            is AgentState.WaitingForUser -> {
                taskProgress.running = false
                taskLabel.text = context.getString(R.string.assist_task_waiting_answer)
                taskDetail.text = state.question
                taskOrb.mode = AgentIconView.Mode.WAITING
            }
            is AgentState.WaitingForHandoff -> {
                taskProgress.running = false
                taskLabel.text = context.getString(R.string.assist_task_waiting_handoff)
                taskDetail.text = state.reason
                taskOrb.mode = AgentIconView.Mode.WAITING
            }
            is AgentState.Paused -> {
                taskLabel.text = context.getString(R.string.overlay_paused)
                taskDetail.text = context.getString(R.string.overlay_paused)
                taskOrb.mode = AgentIconView.Mode.IDLE
            }
            else -> {
                taskLabel.text = context.getString(R.string.assist_task_starting)
                taskDetail.text = context.getString(R.string.assist_submitting)
                taskOrb.mode = AgentIconView.Mode.THINKING
            }
        }
        if (taskCard.visibility != View.VISIBLE) fadeIn(taskCard, delay = 0)
        if (phase == Phase.LISTENING && text.tag == null) setHint(listeningHint())
    }

    private fun listeningHint(): String {
        val state = agentService?.stateFlow?.value
        return context.getString(
            when {
                state is AgentState.WaitingForUser -> R.string.assist_hint_answer
                isBusy() -> R.string.assist_hint_busy
                else -> R.string.assist_hint_idle
            }
        )
    }

    // ── 建议 chips ────────────────────────────────────────────────────────

    private class Chip(val label: String, val primary: Boolean, val onClick: () -> Unit)

    private fun loadSuggestions() {
        val s = scope ?: return
        s.launch {
            val recent = withContext(Dispatchers.IO) {
                runCatching { ChatHistoryRepository(context).getRecentConversations(12) }.getOrDefault(emptyList())
            }
            suggestions = recent.map { it.title.trim() }.filter { it.isNotEmpty() }.distinct().take(SUGGESTION_COUNT)
            showSuggestionsIfIdle()
        }
    }

    private fun showSuggestionsIfIdle() {
        if (!chipsReady || suggestions.isEmpty() || isBusy()) return
        if (phase != Phase.LISTENING && phase != Phase.NO_MATCH && phase != Phase.IDLE) return
        setChips(suggestions.map { s -> Chip(s, primary = false) { stopListening(); submit(s) } })
    }

    private fun hideSuggestions() {
        if (phase == Phase.CHOICE) return
        hideChips()
    }

    private fun setChips(items: List<Chip>) {
        val key = items.joinToString("\u0000") { "${it.primary}:${it.label}" }
        if (key == chipsKey && chipsScroll.visibility == View.VISIBLE) {
            // 同一组 chip：只换点击动作，不重播入场
            items.forEachIndexed { i, chip -> chips.getChildAt(i)?.setOnClickListener { chip.onClick() } }
            return
        }
        chipsKey = key.ifEmpty { null }
        chipsScroll.animate().cancel()
        chipsScroll.alpha = 1f
        chipsScroll.translationY = 0f
        chips.removeAllViews()
        items.forEachIndexed { index, chip ->
            val tv = TextView(context).apply {
                text = chip.label
                setTextColor(if (chip.primary) palette.onAccent else palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 1
                maxWidth = dp(240)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(14), 0, dp(14), 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = dp(34)
                background = chipBackground(chip.primary)
                setOnClickListener { chip.onClick() }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (index > 0) lp.marginStart = dp(8)
            chips.addView(tv, lp)
            // 依次弹出
            tv.alpha = 0f
            tv.translationY = dp(10).toFloat()
            tv.animate().alpha(1f).translationY(0f).setStartDelay(60L * index).setDuration(320)
                .setInterpolator(SpringInterpolator(0.75f, 10f)).start()
        }
        chipsScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun hideChips() {
        chipsKey = null
        if (chipsScroll.visibility == View.VISIBLE) fadeOut(chipsScroll) { chips.removeAllViews() }
    }

    // ── 文案 ──────────────────────────────────────────────────────────────

    private fun setHint(hint: String) {
        text.tag = null
        text.text = hint
        text.setTextColor(palette.textSecondary)
    }

    private fun showTranscript(value: String) {
        if (text.visibility != View.VISIBLE) {
            input.visibility = View.GONE
            text.visibility = View.VISIBLE
        }
        text.tag = value
        text.text = value
        text.setTextColor(palette.text)
    }

    private fun setCaption(value: String?) {
        if (value.isNullOrBlank()) {
            caption.visibility = View.GONE
            return
        }
        val changed = caption.text.toString() != value || caption.visibility != View.VISIBLE
        caption.text = value
        caption.visibility = View.VISIBLE
        if (changed) {
            caption.alpha = 0f
            caption.translationY = dp(4).toFloat()
            caption.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /**
     * 麦克风按钮：强调色实心圆；聆听时外面多一圈随音量涨缩的淡色光环；打字且有内容时变成发送。
     * 打字时右边只留这一个按钮（没内容是麦克风，点了切回说话），不再另放一个切换按钮，免得出现两个麦克风。
     */
    private fun renderMic() {
        val typingSend = phase == Phase.TYPING && input.text.isNotBlank()
        if (listening || typingSend) setMicColors(palette.accent, palette.onAccent)
        else setMicColors(palette.ghost, palette.text)
        micButton.setImageResource(if (typingSend) R.drawable.ic_assist_send else R.drawable.ic_assist_mic)
        micButton.contentDescription = context.getString(if (typingSend) R.string.cd_send else R.string.assist_cd_mic)
        micHalo.animate().alpha(if (listening) 1f else 0f).setDuration(220).start()
        if (!listening) {
            micHalo.scaleX = 1f
            micHalo.scaleY = 1f
        }
        keyboardButton.visibility = if (phase == Phase.TYPING) View.GONE else View.VISIBLE
    }

    /** 麦克风按钮底色 / 图标色切换时渐变过去（开始聆听时不是一下子跳成强调色）。 */
    private fun setMicColors(fill: Int, tint: Int) {
        val target = fill to tint
        if (target == micTarget) return
        micTarget = target
        micAnimator?.cancel()
        fun apply(f: Int, t: Int) {
            micFillColor = f
            micTintColor = t
            micFill.setColor(f)
            micButton.imageTintList = ColorStateList.valueOf(t)
        }
        if (micFillColor == 0) {
            apply(fill, tint)
            return
        }
        val fromFill = micFillColor
        val fromTint = micTintColor
        micAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val k = it.animatedFraction
                apply(AuraPalette.lerpColor(fromFill, fill, k), AuraPalette.lerpColor(fromTint, tint, k))
            }
            start()
        }
    }

    /** 推荐 chip：比主面板低一级——更透明、不带投影、一圈细描边；主操作用强调色实心。 */
    private fun chipBackground(primary: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            if (primary) {
                setColor(palette.accent)
            } else {
                setColor(palette.surfaceLite)
                setStroke(maxOf(1, dp(1) / 2), palette.hairlineStrong)
            }
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
    }

    /** 把莫奈色板套到面板里的文字、按钮、图标上（布局里不写死颜色，动态色板随壁纸变）。 */
    private fun applyPalette() {
        taskTitle.setTextColor(palette.text)
        taskLabel.setTextColor(palette.textSecondary)
        taskDetail.setTextColor(palette.textBody)
        caption.setTextColor(palette.textSecondary)
        input.setTextColor(palette.text)
        input.setHintTextColor(palette.textSecondary)
        keyboardButton.imageTintList = ColorStateList.valueOf(palette.textSecondary)
        micHalo.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accent and 0x00FFFFFF or 0x40000000)
        }
        for ((button, bg, fg) in listOf(
            Triple(taskViewButton, palette.tonalButton, palette.onTonalButton),
            Triple(taskStopButton, palette.dangerButton, palette.onDangerButton),
        )) {
            button.setTextColor(fg)
            button.compoundDrawableTintList = ColorStateList.valueOf(fg)
            button.background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(bg) },
                null,
            )
        }
    }

    // ── 动画 ──────────────────────────────────────────────────────────────

    private fun resetUi() {
        phase = Phase.IDLE
        listening = false
        pendingUtterance = null
        text.visibility = View.VISIBLE
        input.visibility = View.GONE
        input.text.clear()
        setHint(context.getString(R.string.assist_hint_idle))
        setCaption(null)
        chipsReady = false
        chipsKey = null
        chips.removeAllViews()
        chipsScroll.visibility = View.GONE
        taskCard.visibility = View.GONE
        orb.mode = AgentIconView.Mode.IDLE
        renderMic()
        pill.animate().cancel()
        pill.alpha = 1f
        pill.translationY = 0f
        pill.setShape(null)
        pillContent.alpha = 1f
        for (v in listOf(taskCard, chipsScroll)) {
            v.animate().cancel()
            v.alpha = 1f
            v.translationY = 0f
        }
    }

    /**
     * 入场：胶囊先是一颗从导航条位置升起的小圆点，随后按弹簧曲线横向铺开，内容淡入。
     */
    private fun playEntry() {
        entryAnimator?.cancel()
        pill.animate().setStartDelay(0)
        pill.alpha = 0f
        pill.translationY = dp(56).toFloat()
        pillContent.alpha = 0f

        pill.post {
            if (closing) return@post
            val m = pill.panel.shadowMargin.toFloat()
            val w = pill.width - 2 * m
            val h = pill.height - 2 * m
            val dot = h * 0.86f
            val rect = RectF()
            fun shape(p: Float) {
                val cw = dot + (w - dot) * p
                val ch = dot + (h - dot) * p.coerceAtMost(1.03f)
                val cx = pill.width / 2f
                val bottom = m + h
                rect.set(cx - cw / 2f, bottom - ch, cx + cw / 2f, bottom)
                pill.setShape(rect)
            }
            shape(0f)
            pill.animate().alpha(1f).translationY(0f).setDuration(460)
                .setInterpolator(SpringInterpolator(0.72f, 10f)).start()
            val spring = SpringInterpolator(0.68f, 11.5f)
            entryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 90
                duration = 640
                addUpdateListener {
                    val t = it.animatedFraction
                    shape(spring.getInterpolation(t))
                    pillContent.alpha = ((t - 0.28f) / 0.45f).coerceIn(0f, 1f)
                    if (!chipsReady && t >= 0.45f) onEntrySettled()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        pillContent.alpha = 1f
                        pill.setShape(null)
                        if (!chipsReady) onEntrySettled()
                    }
                })
                start()
            }
        }
    }

    /** 胶囊铺开得差不多了：放出推荐 chip。 */
    private fun onEntrySettled() {
        chipsReady = true
        showSuggestionsIfIdle()
    }

    /**
     * 退场：内容淡出，胶囊收回小圆点并沉下去，上面的卡片 / 推荐一起淡出。
     * 整段只由这一个动画驱动，播完才让宿主真正收起窗口；时长跟着系统动画缩放走，不会中途被截断。
     */
    fun close() {
        if (closing) return
        closing = true
        main.removeCallbacksAndMessages(null)
        stopListening()
        hideKeyboard()
        entryAnimator?.cancel()
        pill.animate().cancel()

        val m = pill.panel.shadowMargin.toFloat()
        val w = pill.width - 2 * m
        val h = pill.height - 2 * m
        val dot = h * 0.86f
        val rect = RectF()
        val ease = PathInterpolator(0.4f, 0f, 1f, 1f)
        val startY = pill.translationY
        val others = listOf(taskCard, chipsScroll).filter { it.visibility == View.VISIBLE }
        others.forEach { it.animate().cancel() }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            addUpdateListener {
                val t = it.animatedFraction
                val p = ease.getInterpolation(t)
                pillContent.alpha = (1f - t / 0.3f).coerceIn(0f, 1f)
                val cw = w + (dot - w) * p
                val ch = h + (dot - h) * p
                val cx = pill.width / 2f
                rect.set(cx - cw / 2f, m + h - ch, cx + cw / 2f, m + h)
                pill.setShape(rect)
                val late = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                pill.alpha = 1f - late
                pill.translationY = startY + dp(64) * ease.getInterpolation(late)
                val fade = (t / 0.5f).coerceIn(0f, 1f)
                for (v in others) {
                    v.alpha = 1f - fade
                    v.translationY = dp(8) * fade
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.close()
                }
            })
            start()
        }
    }

    private fun fadeIn(v: View, delay: Long) {
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.translationY = dp(14).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380)
            .setInterpolator(SpringInterpolator(0.75f, 10f)).withEndAction(null).start()
    }

    private fun fadeOut(v: View, then: (() -> Unit)? = null) {
        v.animate().alpha(0f).translationY(dp(8).toFloat()).setStartDelay(0).setDuration(180)
            .withEndAction {
                v.visibility = View.GONE
                v.alpha = 1f
                v.translationY = 0f
                then?.invoke()
            }.start()
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
