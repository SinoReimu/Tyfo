package com.shiro.tyfo.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.shiro.tyfo.AgentService
import com.shiro.tyfo.R
import com.shiro.tyfo.engine.AgentState
import com.shiro.tyfo.engine.HandoffUiState
import com.shiro.tyfo.input.InputInjector
import com.shiro.tyfo.voice.DoubaoStreamingSpeechInputEngine
import com.shiro.tyfo.voice.VoiceInputController
import com.shiro.tyfo.voice.VoiceInputTestBridge
import com.shiro.tyfo.ui.fx.AgentIconView
import com.shiro.tyfo.ui.fx.EdgeLightView
import com.shiro.tyfo.ui.fx.MonetPalette
import com.shiro.tyfo.ui.fx.MorphPanelLayout
import com.shiro.tyfo.ui.fx.ProgressLineView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen activity that renders VD content via SurfaceView.
 *
 * - VIEW mode: SurfaceView shows VD content, touches blocked, agent keeps running
 * - TAKEOVER mode: Touches and key events forwarded to VD, agent paused.
 *
 * 底部控制和顶部任务浮窗是同一套莫奈扁平样式：收起是一枚状态胶囊（状态图标 + 当前进展），
 * 点开形变成控制卡片（任务、状态、进展 / 问题 / 结果，补充或回答的输入框，返回 / 接管 / 停止）。
 * 等用户回答、需要接管、任务结束时自动展开；因为等用户而自动展开的，恢复执行后自动收起。
 */
class ViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ViewActivity"
        private const val REQUEST_RECORD_AUDIO = 2002
        const val EXTRA_SHOW_CONTROLS = "com.shiro.tyfo.ui.ViewActivity.SHOW_CONTROLS"
    }

    private var agentService: AgentService? = null
    private var bound = false
    private var isTakeoverMode = false
    private var stateJob: Job? = null
    private var handoffUiJob: Job? = null

    private lateinit var palette: MonetPalette
    private lateinit var surfaceView: SurfaceView
    private lateinit var touchInterceptor: View
    private lateinit var glowBorder: EdgeLightView
    private lateinit var controlPanel: MorphPanelLayout
    private lateinit var fab: View
    private lateinit var fabIcon: AgentIconView
    private lateinit var fabText: TextView
    private lateinit var panelIcon: AgentIconView
    private lateinit var panelTitle: TextView
    private lateinit var panelMeta: TextView
    private lateinit var panelDetail: TextView
    private lateinit var panelProgress: ProgressLineView
    private lateinit var btnCollapse: ImageView
    private lateinit var handoffPanel: View
    private lateinit var handoffNote: TextView
    private lateinit var handoffCountdownText: TextView
    private lateinit var handoffPendingActions: View
    private lateinit var btnHandoffNow: TextView
    private lateinit var btnHandoffCancel: TextView
    private lateinit var inputRow: View
    private lateinit var answerInput: EditText
    private lateinit var btnSendAnswer: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnTakeover: TextView
    private lateinit var btnStop: TextView
    private lateinit var takeoverKeyboardProxy: EditText
    private lateinit var btnKeyboard: ImageView

    private var lastAgentState: AgentState? = null
    private var handoffUi: HandoffUiState = HandoffUiState.Idle
    // 模型的真实想法只在拿到回复的那一下出现，随后就被“截图中 / 思考中”覆盖，保留最近一条
    private var lastThought = ""
    // 因为等用户（回答 / 接管）或任务结束而自动展开的，恢复执行后自动收起；用户自己点开的不收
    private var autoExpanded = false
    // 输入框当前是回答问题（true）还是补充任务信息（false）
    private var answering = false
    private var navBottom = 0
    private var imeBottom = 0

    private lateinit var voiceAnswerController: VoiceInputController
    private var voiceTestReceiverRegistered = false
    private var proxyTextMutation = false
    private var pendingShowControls = false

    private val voiceTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = intent.getStringExtra(VoiceInputTestBridge.EXTRA_TARGET)
            if (target != VoiceInputTestBridge.TARGET_VIEW_ANSWER) return
            val result = intent.getStringExtra(VoiceInputTestBridge.EXTRA_RESULT) ?: return
            voiceAnswerController.injectFinalResult(result)
            Log.i(TAG, "Voice input injected target=$target text=$result")
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // 卡片展开时返回手势先收起卡片
    private val collapseOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            collapsePanel()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.AgentBinder).getService()
            agentService = service
            bound = true
            Log.i(TAG, "Bound to AgentService")
            service.markViewing()

            // Connect SurfaceView to VD if surface is already ready
            if (surfaceView.holder.surface.isValid) {
                service.getVirtualDisplayManager()?.setSurface(surfaceView.holder.surface)
                service.setViewerAttached(true)
            }

            observeAgentState(service)
            observeHandoffUiState(service)
            syncTakeoverModeFromService()
            render()
            if (pendingShowControls) {
                pendingShowControls = false
                expandPanel()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            stateJob?.cancel()
            handoffUiJob?.cancel()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_view)
        palette = MonetPalette(this)

        surfaceView = findViewById(R.id.surface_view)
        touchInterceptor = findViewById(R.id.touch_interceptor)
        glowBorder = findViewById(R.id.glow_border)
        controlPanel = findViewById(R.id.control_panel)
        fab = findViewById(R.id.fab)
        fabIcon = findViewById(R.id.fab_icon)
        fabText = findViewById(R.id.fab_text)
        panelIcon = findViewById(R.id.panel_icon)
        panelTitle = findViewById(R.id.panel_title)
        panelMeta = findViewById(R.id.panel_meta)
        panelDetail = findViewById(R.id.panel_detail)
        panelProgress = findViewById(R.id.panel_progress)
        btnCollapse = findViewById(R.id.btn_collapse)
        handoffPanel = findViewById(R.id.handoff_panel)
        handoffNote = findViewById(R.id.handoff_note)
        handoffCountdownText = findViewById(R.id.handoff_countdown_text)
        handoffPendingActions = findViewById(R.id.handoff_pending_actions)
        btnHandoffNow = findViewById(R.id.btn_handoff_now)
        btnHandoffCancel = findViewById(R.id.btn_handoff_cancel)
        inputRow = findViewById(R.id.input_row)
        answerInput = findViewById(R.id.answer_input)
        btnSendAnswer = findViewById(R.id.btn_send_answer)
        btnBack = findViewById(R.id.btn_back)
        btnTakeover = findViewById(R.id.btn_takeover)
        btnStop = findViewById(R.id.btn_stop)
        takeoverKeyboardProxy = findViewById(R.id.takeover_keyboard_proxy)
        btnKeyboard = findViewById(R.id.btn_keyboard)

        fabIcon.showRing = true
        applyPalette()

        voiceAnswerController = VoiceInputController(
            DoubaoStreamingSpeechInputEngine(this),
            editTextTarget(answerInput),
            object : VoiceInputController.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    renderInputButton()
                }

                override fun onError(message: String) {
                    android.widget.Toast.makeText(this@ViewActivity, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
        registerVoiceTestReceiver()
        setupTakeoverKeyboardProxy()

        // 边缘流光按任务状态切换，首个状态到达前先保持熄灭

        // 虚拟屏里没有系统栏，内容从最顶上开始画；铺满全屏会和手机自己的状态栏、导航条叠在一起。
        // 所以预览放在状态栏和导航栏之间。虚拟屏本身就按这块区域的尺寸创建（AgentService.previewSizedDisplay），
        // 正好铺满；尺寸对不上时（比如沿用了旧虚拟屏）才等比缩放、留黑边。流光盖在画面边缘上。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.view_root)) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            fitPreview(root.width, root.height, bars.top, bars.bottom)
            // 返回按钮浮在预览画面左上角，状态栏下方
            val lp = btnBack.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != bars.top + 8.dpToPx()) {
                lp.topMargin = bars.top + 8.dpToPx()
                btnBack.layoutParams = lp
            }
            insets
        }
        findViewById<View>(R.id.view_root).addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val bars = ViewCompat.getRootWindowInsets(root)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ) ?: return@addOnLayoutChangeListener
            fitPreview(root.width, root.height, bars.top, bars.bottom)
        }

        // 控制面板悬浮在导航栏上方；展开后输入框弹出键盘时让到键盘上方
        ViewCompat.setOnApplyWindowInsetsListener(controlPanel) { _, insets ->
            navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            placePanel()
            renderKeyboardButton()
            insets
        }

        // SurfaceView callback: connect/disconnect VD surface
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created, connecting to VD")
                agentService?.getVirtualDisplayManager()?.setSurface(holder.surface)
                agentService?.setViewerAttached(true)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface destroyed, switching VD back to ImageReader")
                agentService?.getVirtualDisplayManager()?.setSurface(null)
                agentService?.setViewerAttached(false)
            }
        })

        // Touch interceptor: blocks touches in VIEW mode; tapping the preview collapses the card
        touchInterceptor.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && controlPanel.isExpanded) collapsePanel()
            true
        }

        // SurfaceView touch: forwards to VD in TAKEOVER mode
        surfaceView.setOnTouchListener { _, event ->
            if (isTakeoverMode) forwardTouchToVD(event)
            true
        }

        fab.setOnClickListener { expandPanel() }
        btnCollapse.setOnClickListener { collapsePanel() }
        btnBack.setOnClickListener { onBackToChat() }
        btnTakeover.setOnClickListener { toggleTakeover() }
        btnKeyboard.setOnClickListener { toggleTakeoverKeyboard() }
        btnStop.setOnClickListener { stopAndReturn() }

        // 回答 / 补充输入框：单行输入法动作（发送），但内容长了自动折行
        answerInput.setHorizontallyScrolling(false)
        answerInput.maxLines = 4
        answerInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput()
                true
            } else false
        }
        answerInput.doAfterTextChanged { renderInputButton() }
        btnSendAnswer.setOnClickListener {
            when {
                voiceAnswerController.isListening -> voiceAnswerController.stop()
                answerInput.text.isNotBlank() -> submitInput()
                ensureRecordAudioPermission() -> voiceAnswerController.start()
            }
        }

        btnHandoffNow.setOnClickListener {
            agentService?.completeUserHandoff("manual_confirm_auto_return")
            setTakeoverMode(false)
        }
        btnHandoffCancel.setOnClickListener {
            agentService?.cancelPendingHandoffReturn()
        }

        onBackPressedDispatcher.addCallback(this, collapseOnBack)
        render()
        controlPanel.playEntry()

        // Bind to AgentService
        Intent(this, AgentService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        handleIntent(intent)
    }

    private fun observeAgentState(service: AgentService) {
        stateJob?.cancel()
        // 跟着 engineFlow 走：预览页开着时换了新任务（新引擎），也能收到新任务的状态
        stateJob = lifecycleScope.launch {
            service.engineFlow.collectLatest { engine ->
                lastThought = ""
                engine?.state?.collect { state -> onAgentStateChanged(state) }
            }
        }
    }

    /** 边缘流光跟任务状态走；接管时用户在操作，熄灭免得挡视线。 */
    private fun applyGlowForState(state: AgentState?) {
        glowBorder.mode = when {
            isTakeoverMode -> EdgeLightView.Mode.PAUSED
            state is AgentState.Running ->
                if (state.thought.startsWith("Thinking") || state.thought.startsWith("Capturing")) EdgeLightView.Mode.THINKING
                else EdgeLightView.Mode.RUNNING
            state is AgentState.WaitingForUser || state is AgentState.WaitingForHandoff -> EdgeLightView.Mode.WAITING
            state is AgentState.Idle -> if (state.resultMessage != null) EdgeLightView.Mode.SUCCESS else EdgeLightView.Mode.OFF
            state is AgentState.Error -> EdgeLightView.Mode.ERROR
            else -> EdgeLightView.Mode.OFF
        }
    }

    private fun observeHandoffUiState(service: AgentService) {
        handoffUiJob?.cancel()
        handoffUiJob = lifecycleScope.launch {
            service.handoffUiState.collect { state ->
                onHandoffUiStateChanged(state)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val showControls = intent?.getBooleanExtra(EXTRA_SHOW_CONTROLS, false) == true ||
            intent?.getBooleanExtra(AgentService.EXTRA_SHOW_HANDOFF, false) == true
        if (showControls) {
            pendingShowControls = true
            mainHandler.post {
                if (bound) {
                    syncTakeoverModeFromService()
                    pendingShowControls = false
                    expandPanel()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun onAgentStateChanged(state: AgentState) {
        Log.d(TAG, "Agent state: $state")
        lastAgentState = state
        applyGlowForState(state)
        if (state is AgentState.Running && isRealThought(state.thought)) {
            lastThought = state.thought.trim().replace('\n', ' ')
        }

        when {
            state is AgentState.WaitingForUser -> {
                autoExpand()
                answerInput.requestFocus()
            }
            state is AgentState.WaitingForHandoff -> autoExpand()
            (state is AgentState.Idle && !state.resultMessage.isNullOrBlank()) || state is AgentState.Error -> autoExpand()
        }
        render()
        maybeAutoCollapse()
    }

    private fun onHandoffUiStateChanged(state: HandoffUiState) {
        handoffUi = state
        when (state) {
            HandoffUiState.Idle -> {
                if (isTakeoverMode &&
                    agentService?.displayState != AgentService.DisplayState.TAKEOVER) {
                    setTakeoverMode(false)
                }
            }
            is HandoffUiState.Active, is HandoffUiState.PendingReturn -> autoExpand()
        }
        render()
        maybeAutoCollapse()
    }

    /** 敏感输入接管中：卡片不能收起，内容换成接管说明。 */
    private fun isInHandoff(): Boolean {
        val s = lastAgentState
        if (s is AgentState.WaitingForHandoff) return true
        return handoffUi !is HandoffUiState.Idle && s !is AgentState.Idle && s !is AgentState.Error
    }

    // ── 卡片内容 ─────────────────────────────────────────────────────────

    /** 按当前任务状态刷新胶囊和卡片里的全部内容；尺寸变化由面板自己弹过去。 */
    private fun render() {
        val s = lastAgentState
        val handoff = isInHandoff()

        val orb = when {
            handoff -> AgentIconView.Mode.WAITING
            isTakeoverMode -> AgentIconView.Mode.IDLE
            s is AgentState.Running ->
                if (s.thought.startsWith("Thinking") || s.thought.startsWith("Capturing")) AgentIconView.Mode.THINKING
                else AgentIconView.Mode.RUNNING
            s is AgentState.WaitingForUser -> AgentIconView.Mode.WAITING
            s is AgentState.Idle && s.resultMessage != null -> AgentIconView.Mode.SUCCESS
            s is AgentState.Error -> AgentIconView.Mode.ERROR
            else -> AgentIconView.Mode.IDLE
        }
        fabIcon.mode = orb
        panelIcon.mode = orb

        setTextIfChanged(fabText, compactLine(s, handoff))
        setTextIfChanged(panelTitle, taskTitle())
        setTextIfChanged(panelMeta, metaLine(s, handoff))
        val detail = detailLine(s, handoff)
        // 进展只看最近一句（超出省略）；问题、结果要读全，超出 8 行就在卡片里滚动
        val readAll = !(s is AgentState.Running && !handoff && !isTakeoverMode)
        if (readAll != (panelDetail.movementMethod != null)) {
            panelDetail.movementMethod = if (readAll) ScrollingMovementMethod() else null
            panelDetail.ellipsize = if (readAll) null else TextUtils.TruncateAt.END
            panelDetail.maxLines = if (readAll) 8 else 3
        }
        if (panelDetail.text.toString() != detail) {
            panelDetail.text = detail
            panelDetail.scrollTo(0, 0)
        }
        panelDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE

        handoffPanel.visibility = if (handoff) View.VISIBLE else View.GONE
        val pending = handoffUi as? HandoffUiState.PendingReturn
        handoffNote.visibility = if (pending == null) View.VISIBLE else View.GONE
        if (handoff && pending != null && pending.countdownSeconds > 0) {
            handoffCountdownText.text = getString(R.string.handoff_pending_message, pending.countdownSeconds)
            handoffCountdownText.visibility = View.VISIBLE
            handoffPendingActions.visibility = View.VISIBLE
        } else {
            handoffCountdownText.visibility = View.GONE
            handoffPendingActions.visibility = View.GONE
        }

        panelProgress.visibility = if (s is AgentState.Running && !handoff) View.VISIBLE else View.GONE

        val answer = s is AgentState.WaitingForUser && !handoff
        val supplement = s is AgentState.Running && !handoff && !isTakeoverMode
        inputRow.visibility = if (answer || supplement) View.VISIBLE else View.GONE
        if (answer != answering) {
            answering = answer
            answerInput.text.clear()
        }
        answerInput.hint = getString(if (answering) R.string.vd_hint_answer else R.string.supplement_hint)
        if (inputRow.visibility != View.VISIBLE && answerInput.hasFocus()) {
            if (voiceAnswerController.isListening) voiceAnswerController.stop()
            hideInputKeyboard()
            answerInput.clearFocus()
        }
        renderInputButton()

        btnTakeover.text = getString(if (isTakeoverMode) R.string.vd_btn_return_control else R.string.vd_btn_take_over)
        // 接管中这颗按钮是“当前状态”，用强调色实心标出来
        if (isTakeoverMode) styleButton(btnTakeover, palette.accent, palette.onAccent)
        else styleButton(btnTakeover, palette.tonalButton, palette.onTonalButton)
        btnCollapse.visibility = if (handoff) View.INVISIBLE else View.VISIBLE
        // 接管时返回按钮正好压在画面里 App 自己的返回箭头上，先藏起来
        val showBack = !isTakeoverMode
        if (showBack != (btnBack.tag != false)) {
            btnBack.tag = showBack
            btnBack.animate().cancel()
            if (showBack) {
                btnBack.visibility = View.VISIBLE
                btnBack.animate().alpha(1f).setDuration(180).start()
            } else {
                btnBack.animate().alpha(0f).setDuration(180)
                    .withEndAction { btnBack.visibility = View.GONE }.start()
            }
            btnBack.isClickable = showBack
        }
        syncBackCallback()
    }

    private fun syncBackCallback() {
        collapseOnBack.isEnabled = controlPanel.isExpanded && !isInHandoff()
    }

    private fun compactLine(s: AgentState?, handoff: Boolean): String = when {
        handoff -> getString(R.string.island_waiting_handoff)
        isTakeoverMode -> getString(R.string.vd_status_takeover)
        s is AgentState.Running -> {
            val step = getString(R.string.island_step, s.step)
            val thought = currentThought()
            if (thought.isEmpty()) step else "$step · $thought"
        }
        s is AgentState.WaitingForUser -> getString(R.string.island_waiting_answer)
        s is AgentState.Paused -> getString(R.string.overlay_paused)
        s is AgentState.Idle && s.resultMessage != null -> getString(R.string.island_done)
        s is AgentState.Error -> getString(R.string.island_failed)
        else -> taskTitle()
    }

    private fun metaLine(s: AgentState?, handoff: Boolean): String = when {
        handoff -> getString(R.string.handoff_title)
        isTakeoverMode -> getString(R.string.vd_meta_takeover)
        s is AgentState.Running -> getString(R.string.assist_task_running, s.step)
        s is AgentState.WaitingForUser -> getString(R.string.assist_task_waiting_answer)
        s is AgentState.Paused -> getString(R.string.overlay_paused)
        s is AgentState.Idle && s.resultMessage != null -> getString(R.string.island_done)
        s is AgentState.Error -> getString(R.string.island_failed)
        else -> getString(R.string.vd_status_idle)
    }

    private fun detailLine(s: AgentState?, handoff: Boolean): String = when {
        handoff -> when (val h = handoffUi) {
            is HandoffUiState.Active -> h.reason
            is HandoffUiState.PendingReturn -> h.reason
            HandoffUiState.Idle -> (s as? AgentState.WaitingForHandoff)?.reason
        }.orEmpty().ifBlank { getString(R.string.handoff_reason_default) }
        isTakeoverMode -> getString(R.string.vd_takeover_hint)
        s is AgentState.Running -> currentThought().ifBlank { getString(R.string.overlay_running) }
        s is AgentState.WaitingForUser -> s.question
        s is AgentState.Idle -> s.resultMessage.orEmpty()
        s is AgentState.Error -> s.message
        else -> ""
    }

    private fun taskTitle(): String =
        agentService?.currentInstruction?.trim()?.replace('\n', ' ')?.ifBlank { null } ?: getString(R.string.app_name)

    private fun currentThought(): String = lastThought.ifBlank { agentService?.latestThought.orEmpty() }

    /** 引擎内部的占位文案（截图中 / 思考中 / 执行中）不适合给人看，只显示模型的真实想法。 */
    private fun isRealThought(thought: String): Boolean {
        val t = thought.trim()
        return t.isNotEmpty() && !t.startsWith("Capturing") && !t.startsWith("Thinking") && !t.startsWith("Executing")
    }

    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    // ── 样式 ─────────────────────────────────────────────────────────────

    /** 把莫奈色板套到文字、按钮、输入框上（布局里不写死颜色，色板随壁纸变）。 */
    private fun applyPalette() {
        fabText.setTextColor(palette.text)
        panelTitle.setTextColor(palette.text)
        panelMeta.setTextColor(palette.textSecondary)
        panelDetail.setTextColor(palette.textBody)
        handoffNote.setTextColor(palette.textSecondary)
        handoffCountdownText.setTextColor(palette.text)
        answerInput.setTextColor(palette.text)
        answerInput.setHintTextColor(palette.textSecondary)
        answerInput.highlightColor = palette.accent and 0x00FFFFFF or 0x55000000
        answerInput.textCursorDrawable = GradientDrawable().apply {
            setColor(palette.accent)
            setSize(maxOf(2, 2.dpToPx()), 0)
        }
        inputRow.background = GradientDrawable().apply {
            cornerRadius = 24.dpToPx().toFloat()
            setColor(palette.ghost)
        }
        // 返回：浮在画面上的半透明圆形按钮，比面板低一级（更透明、细描边、不带投影）
        btnBack.imageTintList = ColorStateList.valueOf(palette.text)
        btnBack.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.surfaceLite)
                setStroke(maxOf(1, 1.dpToPx() / 2), palette.hairlineStrong)
            },
            null,
        )
        btnCollapse.imageTintList = ColorStateList.valueOf(palette.textSecondary)
        btnCollapse.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.ghost) },
            null,
        )
        styleButton(btnStop, palette.dangerButton, palette.onDangerButton)
        styleButton(btnHandoffNow, palette.accent, palette.onAccent)
        styleButton(btnHandoffCancel, palette.tonalButton, palette.onTonalButton)
    }

    /** 扁平按钮：色块底 + 左侧线性图标 + 文字，图标和文字同色（同任务浮窗 / 语音助手）。 */
    private fun styleButton(button: TextView, bg: Int, fg: Int) {
        button.setTextColor(fg)
        button.compoundDrawableTintList = ColorStateList.valueOf(fg)
        button.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply { cornerRadius = 20.dpToPx().toFloat(); setColor(bg) },
            null,
        )
    }

    /** 输入框右边只有一颗按钮：空着是麦克风（聆听时强调色实心），有内容就变成发送。 */
    private fun renderInputButton() {
        val listening = voiceAnswerController.isListening
        val send = !listening && answerInput.text.isNotBlank()
        val filled = listening || send
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (filled) palette.accent else 0xFFFFFFFF.toInt())
        }
        btnSendAnswer.background = if (filled) {
            RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), oval, null)
        } else {
            RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, oval)
        }
        btnSendAnswer.imageTintList = ColorStateList.valueOf(if (filled) palette.onAccent else palette.textSecondary)
        btnSendAnswer.setImageResource(if (send) R.drawable.ic_assist_send else R.drawable.ic_assist_mic)
        btnSendAnswer.contentDescription = getString(if (send) R.string.cd_send else R.string.cd_voice_input)
    }

    // ── 展开 / 收起 ──────────────────────────────────────────────────────

    private fun placePanel() {
        val lp = controlPanel.layoutParams as FrameLayout.LayoutParams
        val bottom = if (controlPanel.isExpanded) maxOf(navBottom, imeBottom) else navBottom
        val margin = bottom + 10.dpToPx() - controlPanel.panel.shadowMargin
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            controlPanel.layoutParams = lp
        }
    }

    /** 接管且卡片收起时才露出键盘按钮；贴着导航栏，键盘弹出时浮在键盘上方，打开时用强调色实心。 */
    private fun renderKeyboardButton() {
        val show = isTakeoverMode && !controlPanel.isExpanded
        btnKeyboard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val lp = btnKeyboard.layoutParams as FrameLayout.LayoutParams
        val margin = maxOf(navBottom, imeBottom) + 10.dpToPx()
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            btnKeyboard.layoutParams = lp
        }
        val on = isTakeoverKeyboardShown()
        btnKeyboard.imageTintList = ColorStateList.valueOf(if (on) palette.onAccent else palette.text)
        btnKeyboard.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) palette.accent else palette.surfaceLite)
                if (!on) setStroke(maxOf(1, 1.dpToPx() / 2), palette.hairlineStrong)
            },
            null,
        )
    }

    private fun expandPanel() {
        if (controlPanel.isExpanded) return
        Log.i(TAG, "Bottom panel expanded")
        hideTakeoverKeyboard()
        if (lastAgentState is AgentState.WaitingForUser) Log.i(TAG, "Ask user section restored")
        controlPanel.setExpanded(true)
        placePanel()
        renderKeyboardButton()
        syncBackCallback()
    }

    /** 自动展开（等用户 / 任务结束）；用户已经自己点开的不算自动展开。 */
    private fun autoExpand() {
        if (controlPanel.isExpanded) return
        expandPanel()
        autoExpanded = true
    }

    private fun maybeAutoCollapse() {
        if (autoExpanded && lastAgentState is AgentState.Running && !isInHandoff()) collapsePanel()
    }

    private fun collapsePanel() {
        if (!controlPanel.isExpanded || isInHandoff()) return
        autoExpanded = false
        if (voiceAnswerController.isListening) voiceAnswerController.stop()
        hideInputKeyboard()
        answerInput.clearFocus()
        controlPanel.setExpanded(false)
        placePanel()
        renderKeyboardButton()
        syncBackCallback()
        Log.i(TAG, "Bottom panel collapsed")
    }

    private fun hideInputKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(answerInput.windowToken, 0)
    }

    // ── 回答 / 补充 ──────────────────────────────────────────────────────

    private fun submitInput() {
        val text = answerInput.text.toString().trim()
        if (text.isEmpty()) return
        if (voiceAnswerController.isListening) voiceAnswerController.stop()
        if (answering) {
            agentService?.submitUserResponse(text)
        } else {
            agentService?.submitUserSupplement(text)
        }
        answerInput.text.clear()
        collapsePanel()
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        android.widget.Toast.makeText(
            this,
            getString(R.string.toast_microphone_permission_required),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return false
    }

    private fun registerVoiceTestReceiver() {
        val filter = IntentFilter(VoiceInputTestBridge.ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceTestReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(voiceTestReceiver, filter)
        }
        voiceTestReceiverRegistered = true
    }

    private fun editTextTarget(editText: EditText): VoiceInputController.TextTarget =
        object : VoiceInputController.TextTarget {
            override fun getText(): String = editText.text.toString()
            override fun setText(text: String) {
                editText.setText(text)
                editText.setSelection(editText.text.length)
            }
        }

    // ── Takeover ────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTakeoverMode) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || shouldHandleKeyLocally()) {
                return super.dispatchKeyEvent(event)
            }
            val vdm = agentService?.getVirtualDisplayManager() ?: return super.dispatchKeyEvent(event)
            val displayId = vdm.displayId
            if (displayId != -1) {
                val result = InputInjector.injectKeyToDisplay(this, event, displayId)
                if (result) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        syncTakeoverModeFromService()
        if (agentService?.displayState == AgentService.DisplayState.TAKEOVER &&
            agentService?.stateFlow?.value !is AgentState.WaitingForHandoff) {
            mainHandler.post { expandPanel() }
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        handoffUiJob?.cancel()
        voiceAnswerController.destroy()
        if (voiceTestReceiverRegistered) {
            unregisterReceiver(voiceTestReceiver)
            voiceTestReceiverRegistered = false
        }
        agentService?.getVirtualDisplayManager()?.setSurface(null)
        agentService?.setViewerAttached(false)

        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun onBackToChat() {
        if (isTakeoverMode) {
            setTakeoverMode(false)
            agentService?.exitTakeoverMode()
        }
        agentService?.returnToChat()
        finish()
    }

    private fun stopAndReturn() {
        if (isTakeoverMode) {
            setTakeoverMode(false)
        }
        val intent = Intent(this, ControlPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        agentService?.stopAgent()
        finish()
    }

    private fun toggleTakeover() {
        if (isTakeoverMode && agentService?.stateFlow?.value is AgentState.WaitingForHandoff) {
            agentService?.completeUserHandoff("manual_return_control")
            setTakeoverMode(false)
            return
        }
        val newMode = !isTakeoverMode
        setTakeoverMode(newMode)
        if (newMode) {
            agentService?.enterTakeoverMode()
        } else {
            agentService?.exitTakeoverMode()
        }
    }

    private fun setTakeoverMode(takeover: Boolean) {
        isTakeoverMode = takeover
        if (takeover) {
            touchInterceptor.visibility = View.GONE
            takeoverKeyboardProxy.isEnabled = true
            collapsePanel()
        } else {
            hideTakeoverKeyboard()
            takeoverKeyboardProxy.isEnabled = false
            touchInterceptor.visibility = View.VISIBLE
        }
        applyGlowForState(lastAgentState)
        render()
        renderKeyboardButton()
        Log.i(TAG, "Takeover mode: $takeover")
    }

    private fun syncTakeoverModeFromService() {
        if (agentService?.displayState == AgentService.DisplayState.TAKEOVER && !isTakeoverMode) {
            setTakeoverMode(true)
        }
    }

    /** 预览区域：可用高度内按虚拟屏宽高比等比缩放，水平居中，顶在状态栏下方。 */
    private fun fitPreview(rootW: Int, rootH: Int, topInset: Int, bottomInset: Int) {
        if (rootW <= 0 || rootH <= 0) return
        val (vdW, vdH) = vdSize()
        val availH = (rootH - topInset - bottomInset).coerceAtLeast(1)
        var w = rootW
        var h = (w.toLong() * vdH / vdW).toInt()
        if (h > availH) {
            h = availH
            w = (h.toLong() * vdW / vdH).toInt()
        }
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        val top = topInset + (availH - h) / 2
        if (lp.width == w && lp.height == h && lp.topMargin == top) return
        lp.width = w
        lp.height = h
        lp.topMargin = top
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        surfaceView.layoutParams = lp
        // 缓冲区保持虚拟屏原始分辨率，由 SurfaceView 缩放显示，画面不糊
        surfaceView.holder.setFixedSize(vdW, vdH)
    }

    private fun vdSize(): Pair<Int, Int> {
        val vdm = agentService?.getVirtualDisplayManager()
        if (vdm != null && vdm.width > 0 && vdm.height > 0) return vdm.width to vdm.height
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun forwardTouchToVD(event: MotionEvent) {
        val vdm = agentService?.getVirtualDisplayManager() ?: return
        val displayId = vdm.displayId
        if (displayId == -1) return

        val injected = MotionEvent.obtain(event)
        // 预览是缩放过的，触摸坐标按比例换算回虚拟屏坐标
        if (surfaceView.width > 0 && surfaceView.height > 0) {
            injected.transform(android.graphics.Matrix().apply {
                setScale(vdm.width.toFloat() / surfaceView.width, vdm.height.toFloat() / surfaceView.height)
            })
        }
        val result = InputInjector.injectToDisplay(this, injected, displayId)
        Log.d(TAG, "Injected touch to display $displayId: result=$result action=${event.action} x=${event.x} y=${event.y}")
        injected.recycle()
    }

    private fun setupTakeoverKeyboardProxy() {
        takeoverKeyboardProxy.isEnabled = false
        takeoverKeyboardProxy.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (proxyTextMutation || !isTakeoverMode) return
                val inserted = s?.subSequence(start, start + count)?.toString().orEmpty()
                val displayId = agentService?.getVirtualDisplayManager()?.displayId ?: return
                if (displayId == -1) return

                if (before > count) {
                    repeat(before - count) {
                        InputInjector.injectKeyToDisplay(
                            this@ViewActivity,
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL),
                            displayId
                        )
                        InputInjector.injectKeyToDisplay(
                            this@ViewActivity,
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL),
                            displayId
                        )
                    }
                }
                if (inserted.isNotEmpty()) {
                    Log.i(TAG, "Takeover keyboard proxy forwarding text: ${inserted.length} chars")
                    lifecycleScope.launch {
                        InputInjector(this@ViewActivity, displayId).typeText(inserted)
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (proxyTextMutation || s.isNullOrEmpty()) return
                proxyTextMutation = true
                s.clear()
                proxyTextMutation = false
            }
        })
    }

    /**
     * 接管时的键盘只由右下角按钮开关：虚拟屏里的输入框聚焦时系统不会弹键盘（虚拟屏不是顶层焦点屏），
     * 而点一下画面也不知道点到的是不是输入框，所以不再随点击自动弹出。
     */
    private fun toggleTakeoverKeyboard() {
        if (isTakeoverKeyboardShown()) hideTakeoverKeyboard() else showTakeoverKeyboard()
    }

    private fun isTakeoverKeyboardShown(): Boolean = imeBottom > 0 && takeoverKeyboardProxy.hasFocus()

    private fun showTakeoverKeyboard() {
        if (!isTakeoverMode || controlPanel.isExpanded) return
        takeoverKeyboardProxy.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(takeoverKeyboardProxy, InputMethodManager.SHOW_IMPLICIT)
        Log.i(TAG, "Takeover keyboard proxy requested IME")
    }

    private fun hideTakeoverKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(takeoverKeyboardProxy.windowToken, 0)
        takeoverKeyboardProxy.clearFocus()
    }

    private fun shouldHandleKeyLocally(): Boolean {
        val focus = currentFocus ?: return false
        return focus == answerInput || controlPanel.isExpanded || handoffPendingActions.visibility == View.VISIBLE
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}
