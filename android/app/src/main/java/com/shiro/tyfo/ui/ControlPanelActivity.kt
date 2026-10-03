package com.shiro.tyfo.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.shiro.tyfo.AgentService
import com.shiro.tyfo.R
import com.shiro.tyfo.SettingsActivity
import com.shiro.tyfo.backend.SetupActivity
import com.shiro.tyfo.db.ChatHistoryRepository
import com.shiro.tyfo.db.ChatMessageEntity
import com.shiro.tyfo.db.ConversationEntity
import com.shiro.tyfo.engine.AgentEngine
import com.shiro.tyfo.engine.AgentState
import com.shiro.tyfo.ui.fx.AgentIconView
import com.shiro.tyfo.ui.fx.AuraPalette
import com.shiro.tyfo.ui.fx.MonetDialogs
import com.shiro.tyfo.ui.fx.MonetDialogs.showTinted
import com.shiro.tyfo.ui.fx.MonetPalette
import com.shiro.tyfo.ui.fx.PanelFrameLayout
import com.shiro.tyfo.voice.DoubaoStreamingSpeechInputEngine
import com.shiro.tyfo.voice.VoiceInputController
import com.shiro.tyfo.voice.VoiceInputTestBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 主界面，风格同长按电源键唤起的语音助手（莫奈扁平）：
 * - 首页只放一段对话：正在执行的任务，或者从侧边栏载入的历史会话；没有就是新任务的问候页。
 * - 左上角的半透明圆形按钮打开侧边栏（参考 Gemini）：新任务、管理记忆、设置，下面是全部历史会话。
 * - 底部是任务卡片（状态、查看画面、停止）和输入胶囊。看历史会话时发出的话接着这段会话做，
 *   之前几轮作为上下文交给模型（AgentService.startAgent 的 continueConversationId）。
 *
 * 对话内容直接读数据库（AgentService 边跑边写），界面只补两样：刚发出还没落库的指令，和正在进行的那一步的动画。
 */
class ControlPanelActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ControlPanel"
        private const val REQUEST_RECORD_AUDIO = 2001
        private const val REQUEST_NOTIFICATIONS = 2002
        private const val REQUEST_SETUP = 1001

        /** 悬浮窗权限的提示每个进程只弹一次（它是可选的）。 */
        private var overlayPromptShown = false
    }

    /** 已经发出、还没在记录里出现的指令。 */
    private class Pending(val text: String, val sentAt: Long, val serial: Int)

    /** 首页这段会话的任务进行到哪了（不是这段会话在跑就是 null）。 */
    private enum class Phase { STARTING, RUNNING, ASKING, HANDOFF, PAUSED }

    private val scope: CoroutineScope = MainScope()
    private lateinit var palette: MonetPalette
    private lateinit var historyRepo: ChatHistoryRepository
    private var agentService: AgentService? = null
    private var bound = false
    private var setupLaunched = false
    private var serviceJobs: List<Job> = emptyList()
    private var messagesJob: Job? = null

    // 当前看的会话（null = 新任务）及其记录
    private var viewedConversationId: Long? = null
    private var messages: List<ChatMessageEntity> = emptyList()
    private var messagesLoaded = false
    private var pending: Pending? = null
    private var forceScrollToEnd = false
    // 最近一个引擎和它的状态：用来判断首页的会话是不是正在执行的那段
    private var engine: AgentEngine? = null
    private var engineState: AgentState? = null
    private var engineConversationId: Long? = null

    // Views
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mainRoot: FrameLayout
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var emptyState: View
    private lateinit var emptyIcon: AgentIconView
    private lateinit var textGreeting: TextView
    private lateinit var topScrim: View
    private lateinit var bottomScrim: View
    private lateinit var btnDrawer: ImageView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var taskCard: PanelFrameLayout
    private lateinit var taskIcon: AgentIconView
    private lateinit var statusText: TextView
    private lateinit var btnViewOp: TextView
    private lateinit var btnStop: TextView
    private lateinit var inputPill: PanelFrameLayout
    private lateinit var editInput: EditText
    private lateinit var btnSend: ImageView
    private lateinit var micHalo: View
    private lateinit var drawer: LinearLayout
    private lateinit var drawerActions: View
    private lateinit var historyRecycler: RecyclerView
    private lateinit var historyAdapter: DrawerHistoryAdapter
    private lateinit var drawerEmpty: TextView

    private lateinit var voiceInputController: VoiceInputController
    private var receiversRegistered = false
    private var lastInsets: WindowInsetsCompat? = null
    private var imeAnimating = false
    private val micFill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var micColors: Pair<Int, Int>? = null
    private var micAnimator: ValueAnimator? = null

    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Switch active profile via broadcast
            val profileName = intent.getStringExtra("switch_profile")
            if (profileName != null) {
                val profileRepo = com.shiro.tyfo.config.ProfileRepository(context)
                val success = profileRepo.switchTo(profileName)
                Log.i(TAG, "switch_profile '$profileName' → $success")
                return
            }
            // Set config directly via broadcast
            val setModel = intent.getStringExtra("set_model")
            if (setModel != null) {
                val cfg = com.shiro.tyfo.config.ConfigRepository(context)
                val key = intent.getStringExtra("set_key") ?: cfg.apiKey
                val url = intent.getStringExtra("set_url") ?: cfg.baseUrl
                cfg.applyProfile(cfg.apiProvider, key, setModel, url, "${cfg.apiProvider}/$setModel")
                Log.i(TAG, "set_config: model=$setModel url=$url key=${key.take(10)}…")
                return
            }
            // Run VD POC test
            if (intent.hasExtra("run_vd_poc")) {
                Log.i(TAG, "Running LocalVdPoc test...")
                Thread {
                    val result = com.shiro.tyfo.backend.LocalVdPoc.test(context)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, result.takeLast(100), Toast.LENGTH_LONG).show()
                    }
                }.start()
                return
            }
            // Start pairing service
            if (intent.hasExtra("start_pairing")) {
                Log.i(TAG, "Starting AdbPairingService from foreground")
                try {
                    context.startForegroundService(
                        com.shiro.tyfo.backend.AdbPairingService.startIntent(context))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start pairing service", e)
                }
                return
            }
            // Handle pairing code for wireless ADB testing
            val pairCode = intent.getStringExtra("pair_code")
            if (pairCode != null) {
                Log.i(TAG, "Received pairing code via broadcast: $pairCode")
                val connector = com.shiro.tyfo.backend.DirectConnector.lastInstance
                    ?: com.shiro.tyfo.backend.DirectConnector(context).also {
                        com.shiro.tyfo.backend.DirectConnector.lastInstance = it
                    }
                connector.submitPairingCode(pairCode)
                return
            }
            val text = intent.getStringExtra("demo") ?: return
            val delay = intent.getLongExtra("char_delay", 50L)
            Log.i(TAG, "Demo broadcast: typing '$text' then sending")
            editInput.text.clear()
            editInput.requestFocus()
            var index = 0
            val handler = editInput.handler ?: return
            val typeRunnable = object : Runnable {
                override fun run() {
                    if (index < text.length) {
                        editInput.append(text[index].toString())
                        index++
                        handler.postDelayed(this, delay)
                    } else {
                        handler.postDelayed({ submitInput() }, 500L)
                    }
                }
            }
            handler.postDelayed(typeRunnable, 300L)
        }
    }

    private val voiceTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = intent.getStringExtra(VoiceInputTestBridge.EXTRA_TARGET)
            if (target != VoiceInputTestBridge.TARGET_COMMAND &&
                target != VoiceInputTestBridge.TARGET_CONTROL_ANSWER
            ) return
            val result = intent.getStringExtra(VoiceInputTestBridge.EXTRA_RESULT) ?: return
            voiceInputController.injectFinalResult(result)
            Log.i(TAG, "Voice input injected target=$target text=$result")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AgentService.AgentBinder)?.getService() ?: return
            agentService = service
            bound = true
            Log.i(TAG, "Bound to AgentService")
            observeService(service)
            // Auto-launch setup if no backend available (first launch)
            checkBackendAndPromptSetup(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            serviceJobs.forEach { it.cancel() }
            serviceJobs = emptyList()
            engine = null
            engineState = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_control_panel)
        palette = MonetPalette(this)
        historyRepo = ChatHistoryRepository(this)

        bindViews()
        applyPalette()
        setupInsets()
        setupChat()
        setupInput()
        setupDrawer()

        voiceInputController = VoiceInputController(
            DoubaoStreamingSpeechInputEngine(this),
            object : VoiceInputController.TextTarget {
                override fun getText(): String = editInput.text.toString()
                override fun setText(text: String) {
                    editInput.setText(text)
                    editInput.setSelection(editInput.text.length)
                }
            },
            object : VoiceInputController.Listener {
                override fun onListeningChanged(isListening: Boolean) = renderMic()
                override fun onError(message: String) {
                    Toast.makeText(this@ControlPanelActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
        registerReceivers()

        btnViewOp.setOnClickListener {
            val service = agentService ?: return@setOnClickListener
            if (service.stateFlow?.value is AgentState.Paused) {
                service.resumeAgent()
            } else if (!service.enterViewMode()) {
                // 画面已经回收了（AgentService 会提示），卡片也没用了
                render()
            }
        }
        btnStop.setOnClickListener { agentService?.stopAgent() }

        showConversation(null)
        ensureServiceStarted()

        // Start OverlayService so the floating pill is available when the user
        // backgrounds Tyfo with an active agent.
        startService(Intent(this, com.shiro.tyfo.OverlayService::class.java))

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        voiceInputController.destroy()
        micAnimator?.cancel()
        if (receiversRegistered) {
            unregisterReceiver(voiceTestReceiver)
            unregisterReceiver(demoReceiver)
            receiversRegistered = false
        }
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 通知里点进来（任务在等回答等）：回到正在执行的那段会话
        if (intent.getBooleanExtra(AgentService.EXTRA_FOCUS_ASK, false)) {
            followActiveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        greet()
        // Re-check backend on resume — handles case where user enabled wireless debugging
        // while we were in the background (SetupActivity may have been destroyed)
        val service = agentService ?: return
        val backendName = service.activeBackendName
        if ((backendName == null || backendName == "none") && !service.backendDetecting.value) {
            val shizukuPresent =
                com.shiro.tyfo.backend.ShizukuConnector.status() !=
                    com.shiro.tyfo.backend.ShizukuStatus.UNAVAILABLE
            // Root is retried by the setup screen, wireless ADB only once the user picked it.
            val wirelessReady = com.shiro.tyfo.backend.SetupStateDetector.isWirelessAdbChosen(this) &&
                com.shiro.tyfo.backend.SetupStateDetector.isWirelessDebuggingEnabled(this)
            if (shizukuPresent || wirelessReady) {
                scope.launch {
                    Log.i(TAG, "A privilege backend is available, retrying detection...")
                    if (service.retryBackendDetection()) {
                        Log.i(TAG, "Backend reconnected on resume")
                        setupLaunched = false
                    } else if (!setupLaunched) {
                        Log.i(TAG, "Retry failed, launching SetupActivity")
                        launchSetup()
                    }
                }
            }
        }
        // 闲置回收后虚拟屏没了，“查看画面”也该收起来
        render()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETUP) {
            setupLaunched = false
            if (resultCode == SetupActivity.RESULT_BACKEND_READY) {
                Toast.makeText(this, "Backend ready!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 视图 ─────────────────────────────────────────────────────────────

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        mainRoot = findViewById(R.id.main_root)
        chatRecycler = findViewById(R.id.recycler_chat)
        emptyState = findViewById(R.id.empty_state)
        emptyIcon = findViewById(R.id.empty_icon)
        textGreeting = findViewById(R.id.text_greeting)
        topScrim = findViewById(R.id.top_scrim)
        bottomScrim = findViewById(R.id.bottom_scrim)
        btnDrawer = findViewById(R.id.btn_drawer)
        bottomPanel = findViewById(R.id.bottom_panel)
        taskCard = findViewById(R.id.task_card)
        taskIcon = findViewById(R.id.task_icon)
        statusText = findViewById(R.id.text_status)
        btnViewOp = findViewById(R.id.btn_view_operation)
        btnStop = findViewById(R.id.btn_stop)
        inputPill = findViewById(R.id.input_pill)
        editInput = findViewById(R.id.edit_input)
        btnSend = findViewById(R.id.btn_send)
        micHalo = findViewById(R.id.mic_halo)
        drawer = findViewById(R.id.drawer)
        drawerActions = findViewById(R.id.drawer_actions)
        historyRecycler = findViewById(R.id.recycler_history)
        drawerEmpty = findViewById(R.id.drawer_empty)
    }

    /** 颜色全部来自莫奈色板（随壁纸变），布局里不写死。 */
    private fun applyPalette() {
        window.decorView.setBackgroundColor(palette.background)
        mainRoot.setBackgroundColor(palette.background)
        bottomScrim.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(palette.background, palette.background, palette.background and 0x00FFFFFF),
        )
        textGreeting.setTextColor(palette.text)
        findViewById<TextView>(R.id.text_greeting_sub).setTextColor(palette.textSecondary)
        emptyIcon.mode = AgentIconView.Mode.IDLE
        taskIcon.showRing = true
        taskCard.panel.cornerRadius = dp(28).toFloat()
        inputPill.panel.cornerRadius = dp(30).toFloat()
        statusText.setTextColor(palette.text)
        styleButton(btnViewOp, palette.tonalButton, palette.onTonalButton)
        styleButton(btnStop, palette.dangerButton, palette.onDangerButton)

        editInput.setTextColor(palette.text)
        editInput.setHintTextColor(palette.textSecondary)
        editInput.highlightColor = palette.accent and 0x00FFFFFF or 0x55000000
        editInput.textCursorDrawable = GradientDrawable().apply {
            setColor(palette.accent)
            setSize(maxOf(2, dp(2)), 0)
        }
        btnSend.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), micFill, null)
        micHalo.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accent and 0x00FFFFFF or 0x40000000)
        }

        // 左上角：浮在内容上的半透明圆形按钮（同预览页的返回按钮）
        btnDrawer.imageTintList = ColorStateList.valueOf(palette.text)
        btnDrawer.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.surfaceLite)
                setStroke(maxOf(1, dp(1) / 2), palette.hairlineStrong)
            },
            null,
        )

        // 侧边栏：右侧两个角是圆的
        drawer.background = GradientDrawable().apply {
            val r = dp(28).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            setColor(palette.drawer)
        }
        for ((row, icon, label) in listOf(
            Triple(R.id.drawer_new_task, R.id.drawer_new_task_icon, R.id.drawer_new_task_label),
            Triple(R.id.drawer_memory, R.id.drawer_memory_icon, R.id.drawer_memory_label),
            Triple(R.id.drawer_settings, R.id.drawer_settings_icon, R.id.drawer_settings_label),
        )) {
            findViewById<View>(row).background = RippleDrawable(
                ColorStateList.valueOf(0x22FFFFFF),
                null,
                GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(0xFFFFFFFF.toInt()) },
            )
            findViewById<ImageView>(icon).imageTintList = ColorStateList.valueOf(palette.text)
            findViewById<TextView>(label).setTextColor(palette.text)
        }
        findViewById<View>(R.id.drawer_divider).setBackgroundColor(palette.hairlineStrong)
        findViewById<TextView>(R.id.drawer_recent_label).setTextColor(palette.textSecondary)
        drawerEmpty.setTextColor(palette.textSecondary)
        drawerLayout.setScrimColor(0x99000000.toInt())
        drawerLayout.drawerElevation = 0f
    }

    /** 扁平按钮：色块底 + 左侧线性图标 + 文字，图标和文字同色（同任务浮窗 / 语音助手）。 */
    private fun styleButton(button: TextView, bg: Int, fg: Int) {
        button.setTextColor(fg)
        button.compoundDrawableTintList = ColorStateList.valueOf(fg)
        button.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) },
            null,
        )
    }

    /**
     * 整个窗口画到系统栏后面：左上角按钮和列表让开状态栏，底部面板让开导航条和输入法。
     * 输入法弹出 / 收起时跟着动画逐帧移动，不是等动画结束一下跳过去。
     */
    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            lastInsets = insets
            applyInsets(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            drawerLayout,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    if (imeAnimating) placeBottomPanel(insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimating = false
                        lastInsets?.let { applyInsets(it) }
                    }
                }
            }
        )
        // 列表底部留出底部面板的高度，内容能滚到面板上方；面板变高（多行输入、任务卡片出现）时跟着变
        bottomPanel.addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, _ ->
            if (top != oldTop) updateChatBottomPadding()
        }
    }

    private fun applyInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        (btnDrawer.layoutParams as FrameLayout.LayoutParams).let {
            if (it.topMargin != bars.top + dp(8)) {
                it.topMargin = bars.top + dp(8)
                btnDrawer.layoutParams = it
            }
        }
        // 状态栏那一段完全不透明，下面再渐隐，滚上去的内容不会和状态栏图标叠在一起
        val scrimHeight = bars.top + dp(64)
        topScrim.layoutParams = topScrim.layoutParams.apply { height = scrimHeight }
        topScrim.background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(
                intArrayOf(palette.background, palette.background, palette.background and 0x00FFFFFF),
                floatArrayOf(0f, bars.top.toFloat() / scrimHeight, 1f),
            )
        }
        chatRecycler.setPadding(chatRecycler.paddingLeft, bars.top + dp(64), chatRecycler.paddingRight, chatRecycler.paddingBottom)
        drawerActions.setPadding(drawerActions.paddingLeft, bars.top + dp(16), drawerActions.paddingRight, drawerActions.paddingBottom)
        historyRecycler.setPadding(historyRecycler.paddingLeft, 0, historyRecycler.paddingRight, bars.bottom + dp(16))
        if (!imeAnimating) placeBottomPanel(insets)
    }

    private fun placeBottomPanel(insets: WindowInsetsCompat) {
        val bottom = maxOf(
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
        )
        val lp = bottomPanel.layoutParams as FrameLayout.LayoutParams
        val margin = bottom + dp(10) - inputPill.panel.shadowMargin
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            bottomPanel.layoutParams = lp
        }
    }

    private fun updateChatBottomPadding() {
        val atEnd = isAtEnd()
        val panelTop = bottomPanel.top + inputPill.panel.shadowMargin
        val padding = (mainRoot.height - panelTop + dp(12)).coerceAtLeast(0)
        // 面板下面（导航条那段）也遮住，往上一点渐隐，滚动的内容不会从面板缝里露出来
        val scrimHeight = (mainRoot.height - panelTop + dp(24)).coerceAtLeast(0)
        if (bottomScrim.layoutParams.height != scrimHeight) {
            bottomScrim.layoutParams = bottomScrim.layoutParams.apply { height = scrimHeight }
        }
        if (chatRecycler.paddingBottom != padding) {
            chatRecycler.setPadding(chatRecycler.paddingLeft, chatRecycler.paddingTop, chatRecycler.paddingRight, padding)
            if (atEnd) scrollToEnd(smooth = false)
        }
    }

    // ── 对话 ─────────────────────────────────────────────────────────────

    private fun setupChat() {
        chatAdapter = ChatAdapter(palette) { item -> copyToClipboard(item.text) }
        chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatRecycler.adapter = chatAdapter
        // 进行中那一行的文字每步都在变，别每次都交叉淡入淡出
        (chatRecycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    /** 切换首页显示的会话；null 是新任务。 */
    private fun showConversation(conversationId: Long?) {
        messagesJob?.cancel()
        messagesJob = null
        val changed = conversationId != viewedConversationId
        viewedConversationId = conversationId
        if (changed) {
            messages = emptyList()
            messagesLoaded = conversationId == null
        }
        forceScrollToEnd = true
        if (::historyAdapter.isInitialized) historyAdapter.selectedId = conversationId
        if (conversationId != null) {
            messagesJob = scope.launch {
                historyRepo.observeMessages(conversationId).collect { list ->
                    messages = list
                    messagesLoaded = true
                    // 指令已经写进记录了，去掉界面上那条临时的
                    val p = pending
                    if (p != null && list.any {
                            it.type == MessageType.USER_INPUT.name && it.text == p.text && it.timestamp >= p.sentAt
                        }
                    ) {
                        pending = null
                    }
                    render()
                }
            }
        }
        render()
    }

    /** 有任务在执行就切过去看它。 */
    private fun followActiveTask() {
        val service = agentService ?: return
        val convId = service.currentConversationId ?: return
        if (service.isTaskActive && convId != viewedConversationId) showConversation(convId)
    }

    /**
     * 首页的会话是不是正在执行的那段（含刚发出、引擎还没起来的时候），是的话进行到哪了。
     * 引擎发出结束状态时循环可能还差一点才退出（isTaskActive 晚一拍变 false），结束状态一律不算在跑。
     */
    private fun activePhase(): Phase? {
        val service = agentService ?: return null
        if (!service.isTaskActive) return null
        if (pending != null) return Phase.STARTING
        val convId = service.currentConversationId
        if (viewedConversationId == null || viewedConversationId != convId) return null
        // 只认这段会话自己的引擎；新引擎还没起来时手上的是上一个任务的状态
        val state = if (engine === service.engineFlow.value && engineConversationId == convId) engineState else null
        return when (state) {
            is AgentState.Running -> Phase.RUNNING
            is AgentState.WaitingForUser -> Phase.ASKING
            is AgentState.WaitingForHandoff -> Phase.HANDOFF
            is AgentState.Paused -> Phase.PAUSED
            is AgentState.Error -> null
            is AgentState.Idle -> if (state.resultMessage != null) null else Phase.STARTING
            null -> Phase.STARTING
        }
    }

    private fun render() {
        if (!::chatAdapter.isInitialized) return
        val phase = activePhase()

        // 正在进行的那一步：执行中，或者刚发出、引擎还在准备
        val liveText = when (phase) {
            Phase.RUNNING -> getString(R.string.main_live_thinking)
            Phase.STARTING -> getString(R.string.assist_task_starting)
            else -> null
        }
        emptyState.visibility = if (viewedConversationId == null && pending == null) View.VISIBLE else View.GONE
        if (messagesLoaded) {
            val rows = ChatRows.build(messages, pending?.text, liveText)
            val previousCount = chatAdapter.itemCount
            val stick = forceScrollToEnd || isAtEnd()
            forceScrollToEnd = false
            chatAdapter.submitList(rows) {
                // 一两行的增量平滑滚过去；整段换掉（切换会话）直接定位到底
                if (stick) scrollToEnd(smooth = previousCount > 0 && rows.size - previousCount in 0..3)
            }
        }

        renderTaskCard(phase)
        renderHint(phase)

        // 任务刚结束时循环可能还没退出：过一会儿再看一眼，别停在“执行中”
        if (phase == null && agentService?.isTaskActive == true) {
            chatRecycler.removeCallbacks(rerender)
            chatRecycler.postDelayed(rerender, 400)
        }
    }

    private val rerender = Runnable { render() }

    private fun renderTaskCard(phase: Phase?) {
        val service = agentService
        // 任务结束了、画面还留着：可以回看最后的画面
        val finishedHere = phase == null && service != null && !service.isTaskActive &&
            viewedConversationId != null && viewedConversationId == service.currentConversationId &&
            engineConversationId == viewedConversationId &&
            service.getVirtualDisplayManager()?.isCreated == true
        if (phase == null && !finishedHere) {
            if (taskCard.visibility == View.VISIBLE) fade(taskCard, show = false)
            return
        }
        btnViewOp.setText(R.string.island_open)
        btnViewOp.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ui_eye, 0, 0, 0)
        btnStop.visibility = if (phase != null) View.VISIBLE else View.GONE
        when (phase) {
            Phase.RUNNING -> {
                taskIcon.mode = AgentIconView.Mode.RUNNING
                val step = (engineState as? AgentState.Running)?.step ?: 1
                statusText.text = getString(R.string.assist_task_running, step)
            }
            Phase.ASKING -> {
                taskIcon.mode = AgentIconView.Mode.WAITING
                statusText.setText(R.string.island_waiting_answer)
            }
            Phase.HANDOFF -> {
                taskIcon.mode = AgentIconView.Mode.WAITING
                statusText.setText(R.string.island_waiting_handoff)
            }
            Phase.PAUSED -> {
                taskIcon.mode = AgentIconView.Mode.IDLE
                statusText.setText(R.string.overlay_paused)
                btnViewOp.setText(R.string.main_resume)
                btnViewOp.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
            Phase.STARTING -> {
                taskIcon.mode = AgentIconView.Mode.THINKING
                statusText.setText(R.string.assist_task_starting)
            }
            null -> {
                val failed = engineState is AgentState.Error
                taskIcon.mode = if (failed) AgentIconView.Mode.ERROR else AgentIconView.Mode.SUCCESS
                statusText.setText(if (failed) R.string.main_status_failed else R.string.main_status_done)
            }
        }
        if (taskCard.visibility != View.VISIBLE) fade(taskCard, show = true)
    }

    private fun renderHint(phase: Phase?) {
        val hint = when (phase) {
            Phase.ASKING -> R.string.main_hint_answer
            Phase.HANDOFF -> R.string.handoff_waiting_hint
            Phase.RUNNING, Phase.PAUSED, Phase.STARTING -> R.string.supplement_hint
            null -> if (viewedConversationId != null) R.string.main_hint_continue else R.string.main_hint_new
        }
        val text = getString(hint)
        if (editInput.hint?.toString() != text) editInput.hint = text
    }

    private fun isAtEnd(): Boolean {
        val lm = chatRecycler.layoutManager as? LinearLayoutManager ?: return true
        val count = chatAdapter.itemCount
        return count == 0 || lm.findLastVisibleItemPosition() >= count - 1 || !chatRecycler.canScrollVertically(1)
    }

    private fun scrollToEnd(smooth: Boolean) {
        val count = chatAdapter.itemCount
        if (count == 0) return
        if (smooth) chatRecycler.smoothScrollToPosition(count - 1) else chatRecycler.scrollToPosition(count - 1)
    }

    private fun copyToClipboard(text: String) {
        val (_, body) = ChatRows.splitOutcome(text)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tyfo", body))
        Toast.makeText(this, R.string.main_copied, Toast.LENGTH_SHORT).show()
    }

    private fun greet() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        textGreeting.setText(
            when {
                hour < 12 -> R.string.greeting_morning
                hour < 14 -> R.string.greeting_noon
                hour < 18 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        )
    }

    // ── 服务 ─────────────────────────────────────────────────────────────

    private fun ensureServiceStarted() {
        val intent = Intent(this, AgentService::class.java)
        startForegroundService(intent)
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun observeService(service: AgentService) {
        serviceJobs.forEach { it.cancel() }
        var first = true
        val engineJob = scope.launch {
            service.engineFlow.collectLatest { newEngine ->
                val isNew = newEngine != null && newEngine !== engine
                engine = newEngine
                if (newEngine == null) {
                    engineState = null
                    engineConversationId = null
                    render()
                    return@collectLatest
                }
                if (isNew) {
                    engineConversationId = service.currentConversationId
                    engineState = null
                    // 新起的任务（从这里、语音助手或 IM 发起）切过去看；刚打开界面时只跟正在执行的那个
                    val convId = engineConversationId
                    if (convId != null && (!first || service.isTaskActive) && convId != viewedConversationId) {
                        showConversation(convId)
                    }
                }
                first = false
                newEngine.state.collect { state ->
                    engineState = state
                    render()
                }
            }
        }
        val eventsJob = scope.launch {
            service.taskEvents.collect { event ->
                if (event is AgentService.TaskEvent.StartFailed && event.serial == pending?.serial) {
                    // 没跑起来：撤掉临时消息，指令放回输入框
                    val text = pending?.text
                    pending = null
                    if (!text.isNullOrBlank() && editInput.text.isBlank()) {
                        editInput.setText(text)
                        editInput.setSelection(editInput.text.length)
                    }
                    render()
                }
            }
        }
        // 虚拟屏闲置回收了：“查看画面”收起来
        val displayJob = scope.launch {
            service.virtualDisplayAlive.collect { render() }
        }
        serviceJobs = listOf(engineJob, eventsJob, displayJob)
        render()
    }

    /**
     * 没有可用的特权后端时：还在连 root 就提示稍等，否则打开引导页。返回 true 表示可以开始任务。
     */
    private fun ensureBackendOrSetup(service: AgentService): Boolean {
        val backendName = service.activeBackendName
        if (backendName != null && backendName != "none") return true
        if (service.backendDetecting.value) {
            Toast.makeText(this, R.string.backend_connecting_toast, Toast.LENGTH_SHORT).show()
            return false
        }
        launchSetup()
        return false
    }

    private fun checkBackendAndPromptSetup(service: AgentService) {
        // Wait for the startup detection (root needs the su grant plus starting the
        // service) instead of guessing with a fixed delay.
        scope.launch {
            service.backendDetecting.first { !it }
            val backendName = service.activeBackendName
            if (backendName == null || backendName == "none") {
                // No backend — launch setup wizard on first run
                val prefs = getSharedPreferences("tyfo_config", MODE_PRIVATE)
                val setupDismissed = prefs.getBoolean("setup_dismissed", false)
                if (!setupDismissed && !setupLaunched) {
                    Log.i(TAG, "No backend detected, launching SetupActivity")
                    launchSetup()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchSetup() {
        setupLaunched = true
        startActivityForResult(Intent(this, SetupActivity::class.java), REQUEST_SETUP)
    }

    // ── 输入 ─────────────────────────────────────────────────────────────

    private fun setupInput() {
        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput()
                true
            } else {
                false
            }
        }
        editInput.doAfterTextChanged { renderMic() }
        btnSend.setOnClickListener {
            when {
                editInput.text.isNotBlank() -> {
                    if (voiceInputController.isListening) voiceInputController.stop()
                    submitInput()
                }
                voiceInputController.isListening -> voiceInputController.stop()
                ensureRecordAudioPermission() -> voiceInputController.start()
            }
        }
        renderMic()
    }

    /**
     * 发送：本段会话的任务在跑就当补充（在等回答就当回答）；别的任务在跑先问要不要停掉它；
     * 否则开始新任务——看着历史会话时接着那段会话做。
     */
    private fun submitInput() {
        val text = editInput.text.toString().trim()
        if (text.isEmpty()) return
        val service = agentService
        if (service == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val phase = activePhase()
        if (phase != null) {
            if (phase == Phase.STARTING) {
                // 引擎还在准备，补充没地方放
                Toast.makeText(this, R.string.assist_task_starting, Toast.LENGTH_SHORT).show()
                return
            }
            if (phase == Phase.ASKING) {
                service.submitUserResponse(text)
            } else {
                service.submitUserSupplement(text)
            }
            editInput.text.clear()
            forceScrollToEnd = true
            return
        }
        if (service.isTaskActive) {
            confirmReplace(service, text)
            return
        }
        startTask(service, text, replace = false)
    }

    private fun confirmReplace(service: AgentService, text: String) {
        val running = service.currentInstruction.ifBlank { getString(R.string.app_name) }
        MonetDialogs.builder(this, palette)
            .setTitle(R.string.replace_task_title)
            .setMessage(getString(R.string.replace_task_message, running.take(40)))
            .setPositiveButton(R.string.replace_task_confirm) { _, _ -> startTask(service, text, replace = true) }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .showTinted(palette)
    }

    private fun startTask(service: AgentService, text: String, replace: Boolean) {
        if (!ensureBackendOrSetup(service)) return
        maybePromptOverlayPermission()
        ensureServiceStarted()
        val result = service.startAgent(
            text,
            replaceRunning = replace,
            continueConversationId = viewedConversationId,
        )
        if (result == AgentService.StartResult.BUSY) {
            confirmReplace(service, text)
            return
        }
        pending = Pending(text, System.currentTimeMillis(), service.lastStartSerial)
        editInput.text.clear()
        hideKeyboard()
        forceScrollToEnd = true
        render()
    }

    private fun maybePromptOverlayPermission() {
        if (overlayPromptShown || android.provider.Settings.canDrawOverlays(this)) return
        overlayPromptShown = true
        MonetDialogs.builder(this, palette)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.overlay_permission_grant) { _, _ ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .showTinted(palette)
    }

    private fun hideKeyboard() {
        editInput.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editInput.windowToken, 0)
    }

    /**
     * 输入框右边只有一颗按钮（同语音助手）：空着是麦克风，聆听时强调色实心并带随声音的光环，有内容就变成发送。
     */
    private fun renderMic() {
        val listening = ::voiceInputController.isInitialized && voiceInputController.isListening
        val send = editInput.text.isNotBlank()
        if (listening || send) setMicColors(palette.accent, palette.onAccent)
        else setMicColors(palette.ghost, palette.text)
        btnSend.setImageResource(if (send && !listening) R.drawable.ic_assist_send else R.drawable.ic_assist_mic)
        btnSend.contentDescription = getString(if (send && !listening) R.string.cd_send else R.string.cd_voice_input)
        micHalo.animate().alpha(if (listening) 1f else 0f).setDuration(220).start()
    }

    /** 按钮底色 / 图标色切换时渐变过去。 */
    private fun setMicColors(fill: Int, tint: Int) {
        val target = fill to tint
        val from = micColors
        if (target == from) return
        micColors = target
        micAnimator?.cancel()
        if (from == null) {
            micFill.setColor(fill)
            btnSend.imageTintList = ColorStateList.valueOf(tint)
            return
        }
        micAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val k = it.animatedFraction
                micFill.setColor(AuraPalette.lerpColor(from.first, fill, k))
                btnSend.imageTintList = ColorStateList.valueOf(AuraPalette.lerpColor(from.second, tint, k))
            }
            start()
        }
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        Toast.makeText(this, R.string.toast_microphone_permission_required, Toast.LENGTH_SHORT).show()
        return false
    }

    private fun registerReceivers() {
        val filter = IntentFilter(VoiceInputTestBridge.ACTION)
        val demoFilter = IntentFilter("com.shiro.tyfo.TEST")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceTestReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(demoReceiver, demoFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(voiceTestReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(demoReceiver, demoFilter)
        }
        receiversRegistered = true
    }

    // ── 侧边栏 ───────────────────────────────────────────────────────────

    private fun setupDrawer() {
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                drawerLayout.closeDrawer(drawer)
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                backCallback.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                backCallback.isEnabled = false
            }

            override fun onDrawerStateChanged(newState: Int) {
                if (newState != DrawerLayout.STATE_IDLE) hideKeyboard()
            }
        })
        btnDrawer.setOnClickListener { drawerLayout.openDrawer(drawer) }

        findViewById<View>(R.id.drawer_new_task).setOnClickListener {
            pending = null
            showConversation(null)
            editInput.text.clear()
            drawerLayout.closeDrawer(drawer)
        }
        findViewById<View>(R.id.drawer_memory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
            drawerLayout.closeDrawer(drawer, false)
        }
        findViewById<View>(R.id.drawer_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            drawerLayout.closeDrawer(drawer, false)
        }

        historyAdapter = DrawerHistoryAdapter(
            palette,
            onOpen = { conversation ->
                if (conversation.id != viewedConversationId) {
                    pending = null
                    showConversation(conversation.id)
                }
                drawerLayout.closeDrawer(drawer)
            },
            onLongPress = { conversation -> confirmDelete(conversation) },
        )
        historyAdapter.selectedId = viewedConversationId
        historyRecycler.layoutManager = LinearLayoutManager(this)
        historyRecycler.adapter = historyAdapter
        scope.launch {
            historyRepo.observeConversations().collect { list ->
                historyAdapter.submitList(list)
                drawerEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                // 正在看的会话被删了（比如在别处删的）：回到新任务
                val viewed = viewedConversationId
                if (viewed != null && list.none { it.id == viewed }) showConversation(null)
            }
        }
    }

    private fun confirmDelete(conversation: ConversationEntity) {
        val service = agentService
        if (service != null && service.isTaskActive && service.currentConversationId == conversation.id) {
            Toast.makeText(this, R.string.conversation_delete_running, Toast.LENGTH_SHORT).show()
            return
        }
        MonetDialogs.builder(this, palette)
            .setTitle(R.string.conversation_delete_title)
            .setMessage(conversation.title)
            .setPositiveButton(R.string.conversation_delete_confirm) { _, _ ->
                scope.launch { historyRepo.deleteConversation(conversation.id) }
            }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .showTinted(palette)
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun fade(view: View, show: Boolean) {
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = dp(12).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).withEndAction(null).start()
        } else {
            view.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(180).withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.translationY = 0f
            }.start()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
