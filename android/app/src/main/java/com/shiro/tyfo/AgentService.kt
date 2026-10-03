package com.shiro.tyfo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.shiro.tyfo.action.ActionExecutor
import com.shiro.tyfo.accessibility.VdAccessibilityService
import com.shiro.tyfo.backend.BackendDetector
import com.shiro.tyfo.backend.ConnectionState
import com.shiro.tyfo.backend.DetectionResult
import com.shiro.tyfo.backend.PrivilegeBackend
import com.shiro.tyfo.backend.RemoteBackend
import com.shiro.tyfo.backend.SetupStateDetector
import com.shiro.tyfo.backend.NoBackend
import com.shiro.tyfo.capture.ScreenCapture
import com.shiro.tyfo.config.ConfigRepository
import com.shiro.tyfo.db.ChatHistoryRepository
import com.shiro.tyfo.db.GlobalMemoryRepository
import com.shiro.tyfo.backend.DisplayOps
import com.shiro.tyfo.display.AppOccupancyController
import com.shiro.tyfo.display.TaskDisplayGuard
import com.shiro.tyfo.display.TaskSnapshot
import com.shiro.tyfo.display.VirtualDisplayManager
import com.shiro.tyfo.engine.ActionRepeatGuard
import com.shiro.tyfo.engine.AgentEngine
import com.shiro.tyfo.engine.AgentState
import com.shiro.tyfo.engine.ConversationContext
import com.shiro.tyfo.engine.SessionHistory
import com.shiro.tyfo.engine.HandoffUiState
import com.shiro.tyfo.engine.ScreenFingerprint
import com.shiro.tyfo.engine.StepResult
import com.shiro.tyfo.engine.isTaskActive
import com.shiro.tyfo.engine.AgentPrompts
import com.shiro.tyfo.llm.ArkClient
import com.shiro.tyfo.llm.LlmMessage
import com.shiro.tyfo.llm.LlmReply
import com.shiro.tyfo.llm.ModelClient
import com.shiro.tyfo.llm.ReplyListener
import com.shiro.tyfo.llm.ToolCall
import com.shiro.tyfo.llm.ToolSpec
import com.shiro.tyfo.overlay.OverlayStatePolicy
import com.shiro.tyfo.ui.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that hosts the AgentEngine.
 * Manages display state transitions (CHAT/VIEW/TAKEOVER) via task reparenting.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "tyfo_agent"
        private const val CHANNEL_ASK = "tyfo_ask_user"
        // 执行中的实时进度：普通重要性（锁屏上能看到）但不响铃不振动
        private const val CHANNEL_PROGRESS = "tyfo_progress"
        private const val ACTION_STOP_TASK = "com.shiro.tyfo.STOP_TASK"
        private const val ACTION_DISMISS_RESULT = "com.shiro.tyfo.DISMISS_RESULT"
        /** 用户划掉了执行中的进度实况窗：onStartCommand 开头的 startForeground 会把它重新挂回去。 */
        private const val ACTION_NOTIFICATION_SWIPED = "com.shiro.tyfo.NOTIFICATION_SWIPED"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ASK_ID = 1002
        private const val NOTIFICATION_COMPLETED_ID = 1003
        private const val HANDOFF_TIMEOUT_MS = 30_000L
        private const val HANDOFF_COUNTDOWN_SECONDS = 10
        private const val HANDOFF_SAMPLE_INTERVAL_MS = 1_000L
        private const val OCCUPIED_TOAST_DEBOUNCE_MS = 2_000L
        // 任务结束、也没人在预览时，虚拟屏多留这么久给用户回看，之后回收
        private const val VD_IDLE_RELEASE_MS = 3 * 60_000L
        const val EXTRA_FOCUS_ASK = "focus_ask_user"
        const val EXTRA_SHOW_HANDOFF = "show_handoff"
        /** 停下来等用户的几种步骤（见 AgentEngine）。 */
        private val WAITING_ACTIONS = setOf("ask_user", "handoff_user", "blacklist_handoff", "flag_secure_handoff")
    }

    // ── Display state management ────────────────────────────────────────

    enum class DisplayState { CHAT, VIEW, TAKEOVER }

    /** 启动任务时从会话记录里准备好的东西。 */
    private class TaskStart(
        val conversationId: Long,
        val summary: String?,
        /** 继续历史会话时：接到新任务前面的消息，以及存库时怎么接回整段历史。 */
        val continuation: SessionHistory.Continuation?,
    )

    /** [startAgent] 的受理结果。 */
    enum class StartResult { STARTED, BUSY }

    /** 任务生命周期里 AgentState 表达不了的事件。 */
    sealed class TaskEvent {
        /** 受理后没能真正跑起来（虚拟屏建不出来、特权服务没连上等）；[serial] 对应 [lastStartSerial]。 */
        data class StartFailed(val serial: Int, val reason: String) : TaskEvent()
    }

    var displayState = DisplayState.CHAT
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: AgentEngine? = null

    /**
     * Multi-subscriber flow that emits whenever the active AgentEngine changes
     * (or is cleared). Replaces the previous single-listener `onEngineCreated`
     * var, which silently overwrote prior listeners when both
     * `OverlayService` and `ControlPanelActivity` registered against it.
     */
    private val _engineFlow = MutableStateFlow<AgentEngine?>(null)
    val engineFlow: StateFlow<AgentEngine?> = _engineFlow.asStateFlow()
    private val _handoffUiState = MutableStateFlow<HandoffUiState>(HandoffUiState.Idle)
    val handoffUiState: StateFlow<HandoffUiState> = _handoffUiState.asStateFlow()
    // replay 1：刚受理就失败时，晚一步开始监听的界面也能收到
    private val _taskEvents = MutableSharedFlow<TaskEvent>(replay = 1, extraBufferCapacity = 4)
    val taskEvents: SharedFlow<TaskEvent> = _taskEvents.asSharedFlow()

    // ── 单任务闸门 ──
    // 从 startAgent 受理到任务结束（完成 / 失败 / 被停止）都算占用，期间新的 startAgent 一律返回 BUSY，
    // 除非调用方明确要替换。这几个字段只在主线程读写。
    private var taskStarting = false
    private var startJob: Job? = null
    // 正在进行的闲置回收（移除任务要等应用真正关掉，在后台跑）；新任务要建虚拟屏得先等它结束
    private var pendingRelease: Job? = null
    private var startSerial = 0
    private var viewerAttached = false
    private val releaseIdleVdRunnable = Runnable { releaseIdleVirtualDisplay() }

    /** 最近一次受理的任务序号。 */
    val lastStartSerial: Int
        get() = startSerial

    /** 是否有任务占用着：正在准备虚拟屏，或引擎循环还没结束（含暂停、等回答、等接管）。 */
    val isTaskActive: Boolean
        get() = taskStarting || engine?.isLoopActive == true
    private var virtualDisplayManager: VirtualDisplayManager? = null
        set(value) {
            field = value
            _virtualDisplayAlive.value = value != null
        }
    private val _virtualDisplayAlive = MutableStateFlow(false)
    /** 虚拟屏还在（任务结束后会留一阵供回看 / 接着做，闲置回收后变 false）。 */
    val virtualDisplayAlive: StateFlow<Boolean> = _virtualDisplayAlive.asStateFlow()
    // 会话记录按顺序一条条写，后存的快照不会被先存的覆盖
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val historyWriter = Dispatchers.IO.limitedParallelism(1)
    private var taskDisplayGuard: TaskDisplayGuard? = null
    @Volatile private var occupancyController: AppOccupancyController? = null
    private var lastOccupiedToast: Pair<String, Long>? = null
    private var agentWakeLock: PowerManager.WakeLock? = null
    private lateinit var config: ConfigRepository
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var memoryRepo: GlobalMemoryRepository
    var currentConversationId: Long? = null
        private set
    var currentInstruction: String = ""
        private set
    /** 当前任务里模型最近一条真实想法（不含“截图中 / 思考中”这类占位），给中途打开的界面补上下文。 */
    @Volatile var latestThought: String = ""
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backendDeferred = CompletableDeferred<PrivilegeBackend>()
    private var backend: PrivilegeBackend? = null

    private val _backendDetecting = MutableStateFlow(true)
    /** 正在探测 / 重连特权后端。root 要等 su 授权再拉起服务，可能要好几秒，这期间别急着弹引导页。 */
    val backendDetecting: StateFlow<Boolean> = _backendDetecting.asStateFlow()

    /** The name of the currently active backend (e.g. "system", "shizuku", "adb-direct"), or null. */
    val activeBackendName: String?
        get() = backend?.capabilities?.name

    /** 当前特权后端；接管时预览画面要用它把触控 / 按键转发到虚拟屏。 */
    val privilegeBackend: PrivilegeBackend?
        get() = backend

    /** Disconnect the privilege backend. The user can reconnect later from the setup flow. */
    fun revokeBackend() {
        backend?.destroy()
        backend = null
        Log.i(TAG, "Backend revoked by user")
    }

    /** Update the backend after pairing/reconnection (called by AdbPairingService). */
    fun updateBackend(newBackend: PrivilegeBackend) {
        val previousBackend = backend
        backend = newBackend
        ScreenCapture.backend = newBackend
        observeConnectorState(newBackend)
        if (previousBackend !== newBackend) {
            previousBackend?.destroy()
        }
        Log.i(TAG, "Backend updated: ${newBackend.capabilities.name}")
    }
    private var stateObserverJob: Job? = null
    private var historyObserverJob: Job? = null
    private var historyStateObserverJob: Job? = null
    private var progressObserverJob: Job? = null
    /** 进度通知的已执行时长从这里算。 */
    private var taskStartedAt = 0L
    /** 任务结束后的结果实况窗还挂着：别让后续的状态文案把它顶掉，用户关掉或开始新任务时清除。 */
    @Volatile
    private var resultNotificationShown = false
    /**
     * 进度观察和完成处理跑在 Dispatchers.Default 的不同线程上：任务结束那一刻，最后一条 Running（带总结的
     * thought）可能比结果卡片晚发出去，把“已完成”顶回执行中的进度卡。两种通知都在这把锁里发，结果已挂上就不再发进度。
     */
    private val progressNotificationLock = Any()
    /** 当前是否处于前台服务状态（挂着前台通知）。 */
    private var inForeground = false
    private var lastNotification: Notification? = null
    private var lastHistoryStatus: String? = null
    private var handoffObserverJob: Job? = null
    private var handoffMonitorJob: Job? = null
    private var activeHandoffStep: Int? = null
    private var activeHandoffReason: String = ""
    private var disconnectTimestamp: Long? = null
    private var disconnectObserverJob: Job? = null

    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private val binder = AgentBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        App.agentService = this
        config = ConfigRepository(this)
        // Auto-apply active profile to ensure config values match saved profile
        val activeProfile = config.activeProfileName
        if (activeProfile.isNotEmpty()) {
            val profileRepo = com.shiro.tyfo.config.ProfileRepository(config, this)
            profileRepo.switchTo(activeProfile)
        }
        historyRepo = ChatHistoryRepository(this)
        memoryRepo = GlobalMemoryRepository(this)
        createNotificationChannel()
        ensureAccessibilityServiceEnabled()
        // Mark stale "running" conversations as stopped — agent can't survive process restart
        scope.launch {
            historyRepo.markStaleRunningAsStopped()
        }
        // Detect privilege backend (root / Shizuku / wireless ADB)
        scope.launch {
            when (val result = BackendDetector.detect(this@AgentService)) {
                is DetectionResult.Ready -> {
                    backend = result.backend
                    ScreenCapture.backend = result.backend
                    backendDeferred.complete(result.backend)
                    observeConnectorState(result.backend)
                    Log.i(TAG, "Backend ready: ${result.backend.capabilities.name}")
                }
                is DetectionResult.SetupRequired -> {
                    backendDeferred.complete(NoBackend) // unblock startAgent (will fail gracefully)
                    Log.w(TAG, "Backend setup required — user needs to complete setup wizard")
                }
                is DetectionResult.NoneAvailable -> {
                    backendDeferred.complete(NoBackend) // unblock startAgent
                    Log.w(TAG, "No backend available — root not granted?")
                }
            }
            _backendDetecting.value = false
        }
        Log.i(TAG, "AgentService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() 拉起后必须进一次前台（任务进行中沿用当前的进度通知）。
        // 空闲时处理完马上退出：前台通知系统会推迟约 10 秒才显示，所以不会露出一条“待命”通知
        enterForeground()
        handleStartCommand(intent)
        leaveForegroundIfIdle()
        return START_STICKY
    }

    private fun handleStartCommand(intent: Intent?) {
        if (intent?.action == ACTION_NOTIFICATION_SWIPED) {
            Log.i(TAG, "Live notification swiped away, re-posted")
            return
        }
        if (intent?.action == ACTION_DISMISS_RESULT) {
            resultNotificationShown = false
            return
        }
        if (intent?.action == ACTION_STOP_TASK) {
            Log.i(TAG, "Stop requested from notification")
            if (isTaskActive) stopAgent()
            return
        }

        // Support starting agent via intent extra (e.g. from adb)
        val instruction = intent?.getStringExtra("instruction")
        if (!instruction.isNullOrBlank()) {
            Log.i(TAG, "Starting agent from intent: $instruction")
            startAgent(instruction)
        }
    }

    /**
     * 只有任务进行中、或任务结束后的结果实况窗还挂着时才做前台服务，空闲时不挂常驻的“待命”通知。
     * 远程 IM 有自己的前台服务，不靠这里常驻。
     */
    private fun enterForeground(notification: Notification? = null) {
        try {
            startForeground(NOTIFICATION_ID, notification ?: lastNotification ?: buildNotification("Tyfo"))
            inForeground = true
        } catch (e: Exception) {
            // 后台不允许起前台服务时任务照跑，只是进程没有前台保护
            Log.w(TAG, "startForeground refused: ${e.message}")
        }
    }

    private fun needsForeground(): Boolean =
        taskStarting || engine?.state?.value.isTaskActive || resultNotificationShown

    /** 空闲了就退出前台，前台通知随之撤掉。 */
    private fun leaveForegroundIfIdle() {
        if (needsForeground()) return
        if (inForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        lastNotification = null
    }

    override fun onDestroy() {
        App.agentService = null
        startJob?.cancel()
        mainHandler.removeCallbacks(releaseIdleVdRunnable)
        stopTaskDisplayGuard()
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        disconnectObserverJob?.cancel()
        releaseAgentWakeLock()
        engine?.destroy()
        engine = null
        _engineFlow.value = null
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null
        backend?.destroy()
        backend = null
        Log.i(TAG, "AgentService destroyed")
        super.onDestroy()
    }

    private fun observeConnectorState(b: PrivilegeBackend) {
        disconnectObserverJob?.cancel()
        if (b !is RemoteBackend) return

        disconnectObserverJob = scope.launch {
            b.connector.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        val isEngineRunning = engine?.state?.value is AgentState.Running
                        if (isEngineRunning) {
                            Log.w(TAG, "Backend disconnected during active task, pausing")
                            engine?.pause()
                            disconnectTimestamp = System.currentTimeMillis()
                            updateNotification("⏸ Paused — reconnecting...")
                        } else {
                            Log.w(TAG, "Backend disconnected while idle")
                        }

                        // Shizuku and root are independent of wireless debugging. Only the
                        // direct ADB connector should use adb_wifi_enabled as a retry condition.
                        val wirelessOn = SetupStateDetector.isWirelessDebuggingEnabled(this@AgentService)
                        val canRetry = b.connector.name == "shizuku" || b.connector.name == "root" || wirelessOn
                        if (canRetry) {
                            Log.i(TAG, "Attempting ${b.connector.name} backend reconnect...")
                            var reconnected = false
                            for (attempt in 1..5) {
                                delay(3000L * attempt)
                                if (retryBackendDetection()) {
                                    Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt")
                                    reconnected = true
                                    break
                                }
                            }
                            if (!reconnected) {
                                Log.w(TAG, "Auto-reconnect failed after 5 attempts")
                                backend = null
                                ScreenCapture.backend = NoBackend
                            }
                        } else {
                            Log.i(TAG, "Wireless debugging is off for ADB backend, clearing backend")
                            backend = null
                            ScreenCapture.backend = NoBackend
                        }
                    }
                    is ConnectionState.Connected -> {
                        val wasDisconnected = disconnectTimestamp != null
                        val isEnginePaused = engine?.state?.value is AgentState.Paused
                        if (wasDisconnected && isEnginePaused) {
                            val elapsed = System.currentTimeMillis() - disconnectTimestamp!!
                            disconnectTimestamp = null

                            if (elapsed > 10_000) {
                                Log.i(TAG, "Long disconnect (${elapsed}ms), resuming with fresh state")
                            }
                            engine?.resume()
                            updateNotification("Running: ${currentInstruction.take(30)}...")
                            Log.i(TAG, "Backend reconnected after ${elapsed}ms, resumed agent")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun ensureAccessibilityServiceEnabled() {
        try {
            val componentName = accessibilityComponentName()
            val existing = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (componentName in existing) return

            val updated = if (existing.isBlank()) componentName else "$existing:$componentName"
            android.provider.Settings.Secure.putString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
            )
            android.provider.Settings.Secure.putInt(
                contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
            Log.i(TAG, "Auto-enabled VdAccessibilityService")
        } catch (e: SecurityException) {
            // 标准版没有 WRITE_SECURE_SETTINGS：有 root 就用 root 开。界面元素（outline）靠它读，没有它模型只能看图估位置
            scope.launch(Dispatchers.IO) { enableAccessibilityServiceWithRoot() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-enable accessibility service", e)
        }
    }

    /**
     * 无障碍服务的组件名。标准版的包名带 .standard 后缀，类却在 com.shiro.tyfo 下，
     * 不能用包名拼类名（拼出来的类不存在，系统会把它从已启用列表里删掉）。
     */
    private fun accessibilityComponentName(): String =
        android.content.ComponentName(this, VdAccessibilityService::class.java).flattenToString()

    private fun enableAccessibilityServiceWithRoot() {
        if (!com.shiro.tyfo.backend.RootConnector.checkRootAvailable()) {
            Log.w(TAG, "Accessibility service is off and there's no root to turn it on")
            return
        }
        val componentName = accessibilityComponentName()
        val existing = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (componentName in existing) return
        val updated = if (existing.isBlank()) componentName else "$existing:$componentName"
        try {
            val process = ProcessBuilder(
                "su", "-c",
                "settings put secure enabled_accessibility_services '$updated' && settings put secure accessibility_enabled 1"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code == 0) Log.i(TAG, "Enabled VdAccessibilityService via root")
            else Log.w(TAG, "Enabling accessibility via root failed ($code): $output")
        } catch (e: Exception) {
            Log.w(TAG, "Enabling accessibility via root failed", e)
        }
    }

    // ── Agent lifecycle ─────────────────────────────────────────────────

    /**
     * 受理一个新任务。同一时间只跑一个任务：已有任务没结束时返回 [StartResult.BUSY]，
     * 除非 [replaceRunning]（先停掉旧任务，虚拟屏留给新任务复用）。须在主线程调用。
     * 受理后如果没能跑起来，会发 [TaskEvent.StartFailed]。
     *
     * [continueConversationId] 不为空时接着那段历史对话往下做：新指令记在同一个会话里，
     * 之前几轮的记录整理成上下文交给模型（见 [ConversationContext]）。
     */
    fun startAgent(
        instruction: String,
        replaceRunning: Boolean = false,
        continueConversationId: Long? = null,
    ): StartResult {
        if (isTaskActive) {
            if (!replaceRunning) {
                Log.i(TAG, "startAgent refused, task still active: ${currentInstruction.take(40)}")
                return StartResult.BUSY
            }
            Log.i(TAG, "startAgent replacing active task: ${currentInstruction.take(40)}")
            endActiveTask()
        }
        taskStarting = true
        resultNotificationShown = false
        enterForeground(buildNotification(getString(R.string.notif_progress_running)))
        latestThought = ""
        val serial = ++startSerial
        cancelIdleVdRelease()
        currentInstruction = instruction

        // If in VIEW/TAKEOVER, return to CHAT first
        if (displayState != DisplayState.CHAT) {
            returnToChat()
        }

        lastHistoryStatus = null
        cancelCompletionNotification()
        stateObserverJob?.cancel()
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        stopTaskDisplayGuard()
        activeHandoffStep = null
        _handoffUiState.value = HandoffUiState.Idle
        releaseAgentWakeLock()

        Log.i(TAG, "startAgent config: provider=${config.apiProvider} model=${config.model} baseUrl=${config.baseUrl} key=${config.apiKey.take(10)}...")
        val llmClient = createModelClient()

        // 在主线程上串行地准备虚拟屏和引擎：stopAgent / 替换任务也在主线程，取消 startJob 就能干净地作废这次启动
        startJob = scope.launch(Dispatchers.Main) {
            val b = backend ?: backendDeferred.await()
            val vdm = obtainVirtualDisplay(b)
            if (vdm == null) {
                llmClient.shutdown()
                failStart(
                    serial,
                    if (backend == null) getString(R.string.task_start_failed_backend)
                    else getString(R.string.task_start_failed_display)
                )
                return@launch
            }
            val displayId = vdm.displayId
            val displaySize = Point(vdm.width, vdm.height)

            // 会话记录先落库再启动引擎，这样第一步的结果就有地方写。
            // 接着历史对话做时，先读出之前几轮（不含这次的指令）再把新指令追加进去：
            // 之前发给模型的完整消息接在新任务前面，整段会话的摘要放进系统提示词
            val start = withContext(NonCancellable) {
                val previous = continueConversationId?.let { historyRepo.getConversation(it) }
                if (previous != null) {
                    val summary = ConversationContext.build(historyRepo.getMessages(previous.id))
                    val continuation = SessionHistory.forContinuation(
                        historyRepo.getSessionHistory(previous.id)?.let(SessionHistory::decode).orEmpty()
                    )
                    historyRepo.continueConversation(previous.id, instruction)
                    Log.i(TAG, "Continuing conversation ${previous.id}: ${continuation.restored.size} messages restored, summary ${summary?.length ?: 0} chars")
                    TaskStart(previous.id, summary, continuation)
                } else {
                    val id = historyRepo.startConversation(instruction).also {
                        historyRepo.addMessage(it, MessageType.USER_INPUT, instruction)
                    }
                    TaskStart(id, null, null)
                }
            }
            val convId = start.conversationId
            if (!isActive) {
                // 准备期间被停止或被新任务替换
                llmClient.shutdown()
                scope.launch { historyRepo.updateStatus(convId, "stopped") }
                return@launch
            }

            acquireAgentWakeLock()

            val actionExecutor = ActionExecutor(
                this@AgentService,
                displayId,
                displaySize,
                onOpenAppSuccess = { launchedPkg ->
                    taskDisplayGuard?.trackLaunch(launchedPkg)
                    mainHandler.postDelayed({ refreshControlledTasksFromVd("open_app_success") }, 250)
                },
                backend = b,
                blacklistedPackages = config.blacklistedPackages
            )

            val newEngine = AgentEngine(
                llmClient,
                actionExecutor,
                config.maxSteps,
                vdm,
                config.debugMode,
                memoryRepo,
                environment = taskEnvironment(vdm),
                elementsProvider = { displayId, w, h ->
                    VdAccessibilityService.captureElements(displayId, w, h)
                },
                blacklistedPackages = config.blacklistedPackages,
                isScreenInteractive = { getSystemService(PowerManager::class.java).isInteractive },
                maxStepsMessage = { getString(R.string.task_max_steps, it) },
                onTranscript = { transcript ->
                    scope.launch(historyWriter) {
                        val full = start.continuation?.merge(transcript) ?: transcript
                        runCatching { historyRepo.saveSessionHistory(convId, SessionHistory.encode(full)) }
                            .onFailure { Log.w(TAG, "Failed to save session history: ${it.message}") }
                    }
                },
            )
            engine?.destroy()
            engine = newEngine
            currentConversationId = convId
            _engineFlow.value = newEngine
            observeConnectorState(b)
            newEngine.start(instruction, start.summary, start.continuation?.restored.orEmpty())
            taskStarting = false
            startJob = null
            taskStartedAt = System.currentTimeMillis()

            updateNotification("Running: ${instruction.take(30)}...")
            Log.i(TAG, "Agent started with instruction: $instruction")

            observeHistoryWrites()
            observeStateForVdLifecycle()
            observeAskUserNotifications()
            observeHandoffRequests()
            observeProgressNotification()
            startTaskDisplayGuard(vdm)
        }
        return StartResult.STARTED
    }

    /** 复用还活着的虚拟屏，否则按预览区域的尺寸新建一块（见 [previewSizedDisplay]）。建不出来返回 null。 */
    private suspend fun obtainVirtualDisplay(b: PrivilegeBackend): VirtualDisplayManager? {
        // 特权服务只管一块虚拟屏：上一块还在回收时先等它释放完，免得回收把新建的这块也放掉
        pendingRelease?.join()
        pendingRelease = null
        virtualDisplayManager?.let {
            if (it.isCreated) {
                Log.i(TAG, "Reusing existing virtual display ${it.displayId}")
                return it
            }
        }
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null

        val vdm = VirtualDisplayManager(this, b)
        val (vdWidth, vdHeight) = previewSizedDisplay()
        val dpi = resources.displayMetrics.densityDpi
        val id = try {
            withContext(Dispatchers.IO) {
                vdm.create(width = vdWidth, height = vdHeight, dpi = dpi)
            }
        } catch (e: CancellationException) {
            // 建到一半被取消：这块屏没人要了，别漏掉
            vdm.destroy()
            throw e
        }
        if (id == -1) {
            Log.e(TAG, "Failed to create virtual display")
            vdm.destroy()
            return null
        }
        virtualDisplayManager = vdm
        Log.i(TAG, "Virtual display created: $id (${vdm.width}x${vdm.height})")
        return vdm
    }

    private fun failStart(serial: Int, reason: String) {
        Log.w(TAG, "Task failed to start: $reason")
        taskStarting = false
        startJob = null
        currentInstruction = ""
        leaveForegroundIfIdle()
        _taskEvents.tryEmit(TaskEvent.StartFailed(serial, reason))
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        scheduleIdleVdRelease()
    }

    /**
     * 结束当前任务：停引擎、撤掉各类观察者和占用闸门，没跑完的会话标成 stopped。
     * 不碰虚拟屏，调用方自己决定是复用还是回收。
     */
    private fun endActiveTask() {
        val wasActive = isTaskActive
        startJob?.cancel()
        startJob = null
        taskStarting = false
        stopTaskDisplayGuard()
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        handoffObserverJob?.cancel()
        activeHandoffStep = null
        activeHandoffReason = ""
        _handoffUiState.value = HandoffUiState.Idle
        releaseAgentWakeLock()

        // Resume agent if paused (for clean stop)
        if (displayState == DisplayState.TAKEOVER) {
            engine?.resume()
        }
        // 先撤掉完成监听，别把 stop 带来的 Idle 当成“任务完成”去发通知
        stateObserverJob?.cancel()
        engine?.stop()
        historyObserverJob?.cancel()
        historyStateObserverJob?.cancel()
        progressObserverJob?.cancel()
        resultNotificationShown = false
        askObserverJob?.cancel()
        cancelAskNotification()

        val convId = currentConversationId
        if (wasActive && convId != null) {
            scope.launch {
                historyRepo.addMessage(convId, MessageType.SYSTEM, getString(R.string.task_stopped))
                historyRepo.updateStatus(convId, "stopped")
            }
        }
        currentConversationId = null
        currentInstruction = ""
    }

    /**
     * 虚拟屏尺寸 = 手机宽 ×（屏高 − 状态栏 − 底部导航条），正好是预览页能用的区域。
     * 预览页保留手机自己的系统栏，虚拟屏里没有系统栏；和整屏同比例的话塞进去只能缩小、两边留黑，
     * 尺寸一致就能铺满，流光直接盖在画面边缘上。横屏或拿不到 insets 时退回整屏尺寸。
     */
    private fun previewSizedDisplay(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val fullScreen = dm.widthPixels to dm.heightPixels
        return try {
            val display = getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return fullScreen
            val metrics = createDisplayContext(display)
                .createWindowContext(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                .getSystemService(android.view.WindowManager::class.java)
                .maximumWindowMetrics
            val bounds = metrics.bounds
            if (bounds.width() >= bounds.height()) return fullScreen
            // 和 ViewActivity.fitPreview 用同一组 insets
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            val height = bounds.height() - bars.top - bars.bottom
            if (height > bounds.width()) bounds.width() to height else fullScreen
        } catch (e: Exception) {
            Log.w(TAG, "previewSizedDisplay: ${e.message}")
            fullScreen
        }
    }

    /** ViewActivity 的预览挂上 / 摘下虚拟屏时调用；没人看且任务已结束，才开始倒计时回收虚拟屏。 */
    fun setViewerAttached(attached: Boolean) {
        viewerAttached = attached
        // 虚拟屏自成一个电源组，没人唤醒它约 10 秒就休眠、不再出画面；任务结束后再打开预览会是黑屏
        if (agentWakeLock?.isHeld != true) backend?.setVirtualDisplayAwake(attached)
        if (attached) cancelIdleVdRelease() else scheduleIdleVdRelease()
    }

    private fun scheduleIdleVdRelease() {
        mainHandler.removeCallbacks(releaseIdleVdRunnable)
        // 这里按状态判断：任务刚结束时引擎协程可能还差几行才退出，到点回收时会再按 isTaskActive 确认一次
        val busy = taskStarting || engine?.state?.value.isTaskActive
        if (busy || viewerAttached || virtualDisplayManager == null) return
        mainHandler.postDelayed(releaseIdleVdRunnable, VD_IDLE_RELEASE_MS)
    }

    private fun cancelIdleVdRelease() {
        mainHandler.removeCallbacks(releaseIdleVdRunnable)
    }

    /**
     * 任务结束后的闲置回收。直接释放的话，系统会把虚拟屏上的任务挪到主屏最顶上（Android 16 实测），
     * Agent 用过的应用会突然跳到用户眼前；所以先把这些任务移除（等同于在最近任务里划掉），再释放虚拟屏。
     * 用户主动“停止”时不走这里，那时仍把应用挪回主屏交给用户。
     */
    private fun releaseIdleVirtualDisplay() {
        if (isTaskActive || viewerAttached) return
        val vdm = virtualDisplayManager ?: return
        virtualDisplayManager = null
        val b = backend
        // 移除任务会阻塞到应用真正关掉，放到后台线程等；等完再释放虚拟屏，全程主屏上什么都不会闪
        pendingRelease = scope.launch(Dispatchers.Main) {
            val removed = if (vdm.isCreated) withContext(Dispatchers.IO) { b?.removeTasksOnDisplay(vdm.displayId) } else null
            vdm.destroy()
            Log.i(TAG, "Released idle virtual display, removed tasks=${removed?.joinToString()}")
        }
    }

    /** 按配置创建模型服务的适配器。目前只有方舟；加服务商时在这里按 [ConfigRepository.apiProvider] 分派。 */
    private fun createModelClient(): ModelClient = ArkClient(
        apiKey = config.apiKey,
        model = config.model,
        baseUrl = config.baseUrl.trimEnd('/'),
    )

    /** 写进系统提示词的设备和时间信息。 */
    private fun taskEnvironment(vdm: VirtualDisplayManager) = AgentPrompts.Environment(
        device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .ifBlank { null },
        android = Build.VERSION.RELEASE,
        screenWidth = vdm.width,
        screenHeight = vdm.height,
        now = java.time.ZonedDateTime.now(),
    )

    private class DebugModelClient : ModelClient {
        override val profile = ArkClient("", "debug", "").profile

        override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>, listener: ReplyListener?): LlmReply =
            LlmReply("debug wait", listOf(ToolCall("debug", "wait", "{}")))

        override fun shutdown() {}
    }

    internal fun debugStartRunningAgent() {
        if (displayState != DisplayState.CHAT) {
            returnToChat()
        }

        engine?.destroy()
        stateObserverJob?.cancel()
        historyObserverJob?.cancel()
        askObserverJob?.cancel()
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        releaseAgentWakeLock()

        val b = backend ?: NoBackend
        var vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            vdm?.destroy()
            vdm = VirtualDisplayManager(this, b)
            val dm = resources.displayMetrics
            val displayId = vdm.create(
                width = dm.widthPixels,
                height = dm.heightPixels,
                dpi = dm.densityDpi
            )
            if (displayId == -1) {
                Log.e(TAG, "debugStartRunningAgent: failed to create VD")
                return
            }
            virtualDisplayManager = vdm
        }

        acquireAgentWakeLock()

        val displaySize = Point(vdm.width, vdm.height)
        val newEngine = AgentEngine(
            DebugModelClient(),
            ActionExecutor(
                this,
                vdm.displayId,
                displaySize,
                onOpenAppSuccess = { launchedPkg ->
                    taskDisplayGuard?.trackLaunch(launchedPkg)
                    mainHandler.postDelayed({ refreshControlledTasksFromVd("debug_open_app_success") }, 250)
                },
                backend = b,
                blacklistedPackages = config.blacklistedPackages
            ),
            config.maxSteps,
            vdm,
            debugMode = true,
            memoryRepository = memoryRepo,
            elementsProvider = { displayId, w, h ->
                VdAccessibilityService.captureElements(displayId, w, h)
            },
            blacklistedPackages = config.blacklistedPackages,
            isScreenInteractive = { getSystemService(PowerManager::class.java).isInteractive }
        )
        engine = newEngine
        currentInstruction = "debug takeover verification"
        currentConversationId = null
        displayState = DisplayState.CHAT
        _engineFlow.value = newEngine
        newEngine.debugSimulateRunning()
        updateNotification("Debug agent running")
        observeStateForVdLifecycle()
        observeHandoffRequests()
        startTaskDisplayGuard(vdm)
        Log.i(TAG, "Debug running agent ready on display ${vdm.displayId}")
    }

    internal fun debugRepeatGuardTypeText() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x0f0f0f0f0f0f0f0fL)
        guard.recordExecuted(com.shiro.tyfo.action.Action.TypeText("京东"), screen)
        when (val decision = guard.evaluate(com.shiro.tyfo.action.Action.TypeText("京东"), screen)) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard type_text decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard type_text decision=ALLOW")
        }
    }

    internal fun debugRepeatGuardTapBlocked() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x1111111111111111L)
        guard.recordExecuted(com.shiro.tyfo.action.Action.Tap(500, 600), screen)
        when (val decision = guard.evaluate(com.shiro.tyfo.action.Action.Tap(520, 590), screen)) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard tap decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard tap decision=ALLOW")
        }
    }

    internal fun debugRepeatGuardTapAllowed() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x1111111111111111L)
        guard.recordExecuted(com.shiro.tyfo.action.Action.Tap(500, 600), screen)
        when (val decision = guard.evaluate(com.shiro.tyfo.action.Action.Tap(520, 590), ScreenFingerprint(-1L))) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard tap_changed decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard tap_changed decision=ALLOW")
        }
    }

    private var askObserverJob: Job? = null

    /**
     * Watch for WaitingForUser state and post a high-priority heads-up notification
     * so the user sees the prompt even when Tyfo is in the background.
     */
    private fun observeAskUserNotifications() {
        askObserverJob?.cancel()
        val flow = engine?.state ?: return
        askObserverJob = scope.launch {
            var wasAsking = false
            flow.collect { state ->
                val asking = state is AgentState.WaitingForUser
                if (asking && !wasAsking) {
                    val q = (state as AgentState.WaitingForUser).question
                    postAskNotification(q)
                } else if (!asking && wasAsking) {
                    cancelAskNotification()
                }
                wasAsking = asking
            }
        }
    }

    private fun postAskNotification(question: String) {
        val cls = OverlayService.lastForegroundActivityClass
            ?: com.shiro.tyfo.ui.ControlPanelActivity::class.java
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_FOCUS_ASK, true)
        }
        val pi = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(getString(R.string.notif_needs_answer))
            .setContentText(question)
            .setStyle(Notification.BigTextStyle().bigText(question))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_open_app), pi
                ).build()
            )
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ASK_ID, notif)
    }

    private fun cancelAskNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ASK_ID)
    }

    private fun postCompletionNotification(title: String, body: String) {
        val cls = OverlayService.lastForegroundActivityClass
            ?: com.shiro.tyfo.ui.ControlPanelActivity::class.java
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val pi = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_COMPLETED_ID, notif)
    }

    private fun cancelCompletionNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_COMPLETED_ID)
    }

    private fun observeHandoffRequests() {
        handoffObserverJob?.cancel()
        val flow = engine?.state ?: return
        handoffObserverJob = scope.launch {
            flow.collect { state ->
                if (state is AgentState.WaitingForHandoff) {
                    if (activeHandoffStep != state.step) {
                        activeHandoffStep = state.step
                        activeHandoffReason = state.reason
                        startUserHandoff(state.reason)
                    }
                } else if (activeHandoffStep != null && state !is AgentState.Paused) {
                    activeHandoffStep = null
                    activeHandoffReason = ""
                    handoffMonitorJob?.cancel()
                    _handoffUiState.value = HandoffUiState.Idle
                }
            }
        }
    }

    private fun startUserHandoff(reason: String) {
        Log.i(TAG, "Starting user handoff: $reason")
        _handoffUiState.value = HandoffUiState.Active(reason)
        enterViewMode(showHandoff = true)
        enterTakeoverMode()
        startHandoffTimer(reason)
        updateNotification("Waiting for sensitive input")
    }

    /** 交接期间刷新已等待时长，到时限后进入倒计时把控制交回 Agent。 */
    private fun startHandoffTimer(reason: String) {
        handoffMonitorJob?.cancel()
        if (virtualDisplayManager == null) return
        handoffMonitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()

            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                _handoffUiState.value = HandoffUiState.Active(reason, elapsed)

                if (elapsed >= HANDOFF_TIMEOUT_MS) {
                    showPendingHandoffReturn(reason, "timeout")
                    return@launch
                }

                delay(HANDOFF_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun showPendingHandoffReturn(reason: String, source: String) {
        handoffMonitorJob?.cancel()
        handoffMonitorJob = scope.launch {
            for (remaining in HANDOFF_COUNTDOWN_SECONDS downTo 1) {
                _handoffUiState.value = HandoffUiState.PendingReturn(reason, remaining, source)
                delay(1_000L)
            }
            completeUserHandoff("${source}_countdown_elapsed")
        }
    }

    fun completeUserHandoff(source: String = "manual_return_control") {
        Log.i(TAG, "Completing user handoff: $source")
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        activeHandoffStep = null
        _handoffUiState.value = HandoffUiState.Idle
        if (displayState == DisplayState.TAKEOVER) {
            displayState = DisplayState.VIEW
        }
        engine?.completeUserHandoff(source)
        updateNotification("Agent running")
    }

    fun cancelPendingHandoffReturn() {
        val reason = activeHandoffReason.ifBlank { getString(R.string.handoff_reason_default) }
        Log.i(TAG, "Cancelling pending handoff return")
        startHandoffTimer(reason)
    }

    /**
     * Observe step results and state changes to persist chat history.
     */
    private fun observeHistoryWrites() {
        historyObserverJob?.cancel()
        val eng = engine ?: return

        // Observe step results
        historyObserverJob = scope.launch {
            eng.stepResults.collect { result ->
                if (!result.completed) {
                    refreshControlledTasksFromVd("step_${result.step}_${result.actionType}")
                }

                val convId = currentConversationId ?: return@collect
                if (config.debugMode) {
                    val prefix = if (result.success) "" else "[FAIL] "
                    val msg = "[Step ${result.step}] ${prefix}${result.actionType}: ${result.detail}"
                    historyRepo.addMessage(convId, MessageType.AGENT_STATUS, msg)
                    if (!result.debugInfo.isNullOrBlank()) {
                        historyRepo.addMessage(convId, MessageType.AGENT_DEBUG, "[LLM]\n${result.debugInfo}")
                    }
                } else if (!result.completed && result.actionType !in WAITING_ACTIONS) {
                    // 提问 / 交给用户接管的那一步，问题和接管说明由下面的状态监听记下，这里的想法就不再单独记一条
                    val userMsg = result.thought.ifBlank { result.detail }
                    if (userMsg != lastHistoryStatus) {
                        if (lastHistoryStatus == null) {
                            historyRepo.addMessage(convId, MessageType.AGENT_STATUS, userMsg)
                        } else {
                            historyRepo.updateLastAgentStatus(convId, userMsg)
                        }
                        lastHistoryStatus = userMsg
                    }
                }

                if (result.completed) {
                    val summary = when (result.actionType) {
                        // 完成时 detail 是模型给用户的答案（finish 的 answer），直接展示；✅ / ❌ / ⚠️ 是给界面认状态用的标记
                        "finish" -> "✅ ${result.detail.ifBlank { getString(R.string.task_complete_step, result.step) }}"
                        "fail" -> "❌ ${getString(R.string.task_failed, result.detail.removePrefix("Task failed: "))}"
                        "max_steps" -> "⚠️ ${getString(R.string.task_max_steps, result.step)}"
                        "error" -> "❌ ${getString(R.string.task_error, result.detail)}"
                        else -> "✅ ${getString(R.string.task_complete_step, result.step)}"
                    }
                    if (!config.debugMode && lastHistoryStatus != null) {
                        historyRepo.updateLastAgentStatus(convId, summary)
                        historyRepo.updateLastAgentStatusToResult(convId)
                    } else {
                        historyRepo.addMessage(convId, MessageType.AGENT_RESULT, summary)
                    }
                    lastHistoryStatus = null
                    val status = when (result.actionType) {
                        "finish" -> "completed"
                        "fail", "error" -> "failed"
                        else -> "completed"
                    }
                    historyRepo.updateStatus(convId, status)
                }
            }
        }

        // Observe ask_user state
        historyStateObserverJob?.cancel()
        historyStateObserverJob = scope.launch {
            eng.state.collect { state ->
                val convId = currentConversationId ?: return@collect
                if (state is AgentState.WaitingForUser) {
                    if (!config.debugMode) {
                        lastHistoryStatus = null
                    }
                    historyRepo.addMessage(convId, MessageType.AGENT_QUESTION, state.question)
                } else if (state is AgentState.WaitingForHandoff) {
                    if (!config.debugMode) {
                        lastHistoryStatus = null
                    }
                    historyRepo.addMessage(
                        convId,
                        MessageType.AGENT_STATUS,
                        getString(R.string.handoff_sensitive_input, state.reason)
                    )
                }
            }
        }
    }

    /**
     * Watch for agent completion. Keep VD alive for user inspection.
     */
    private fun observeStateForVdLifecycle() {
        stateObserverJob?.cancel()
        val flow = engine?.state ?: return

        stateObserverJob = scope.launch {
            var wasRunning = false
            flow.collect { state ->
                if (state is AgentState.Running) {
                    wasRunning = true
                }
                if (wasRunning && (state is AgentState.Idle || state is AgentState.Error)) {
                    Log.i(TAG, "Task completed, keeping virtual display for user inspection")
                    // The VD keeps its last app, but the agent is done with it: release it.
                    stopTaskDisplayGuard()
                    releaseAgentWakeLock()
                    mainHandler.post { scheduleIdleVdRelease() }

                    // 任务结束：撤掉进度通知（实况窗），换回普通的前台通知
                    progressObserverJob?.cancel()
                    // Update foreground notification + post completion notification if in background
                    val isBackground = !androidx.lifecycle.ProcessLifecycleOwner.get()
                        .lifecycle.currentState.isAtLeast(
                            androidx.lifecycle.Lifecycle.State.STARTED)
                    // 有实况窗时结果常驻在实况窗里，不再另发一条完成通知
                    val liveUpdates = LiveUpdates.isAvailable(this@AgentService)
                    when (state) {
                        is AgentState.Idle -> {
                            val body = state.resultMessage ?: currentInstruction
                            if (liveUpdates) {
                                postResultNotification(success = true, summary = body)
                            } else {
                                leaveForegroundIfIdle()
                                if (isBackground) {
                                    postCompletionNotification(getString(R.string.notif_task_completed), body)
                                }
                            }
                        }
                        is AgentState.Error -> {
                            if (liveUpdates) {
                                postResultNotification(success = false, summary = state.message)
                            } else {
                                leaveForegroundIfIdle()
                                if (isBackground) {
                                    postCompletionNotification(getString(R.string.notif_task_failed_title), state.message)
                                }
                            }
                        }
                        else -> {}
                    }

                    stateObserverJob?.cancel()
                }
            }
        }
    }

    fun pauseAgent() {
        engine?.pause()
        updateNotification("Agent paused")
    }

    fun resumeAgent() {
        engine?.resume()
        updateNotification("Agent running")
    }

    suspend fun retryBackendDetection(): Boolean {
        _backendDetecting.value = true
        val result = try {
            BackendDetector.detect(this@AgentService)
        } finally {
            _backendDetecting.value = false
        }
        if (result is DetectionResult.Ready) {
            backend = result.backend
            ScreenCapture.backend = result.backend
            observeConnectorState(result.backend)
            // New PrivilegedService won't have the old VD's ImageReader — destroy stale VDM
            virtualDisplayManager?.destroy()
            virtualDisplayManager = null
            Log.i(TAG, "Backend re-detected: ${result.backend.capabilities.name}")
            return true
        }
        Log.w(TAG, "Backend re-detection failed: $result")
        return false
    }

    fun stopAgent() {
        endActiveTask()
        cancelIdleVdRelease()

        // Capture task ID before destroying VD
        val vdm = virtualDisplayManager
        var rescueTaskId: Int? = null
        if (vdm != null && vdm.isCreated) {
            rescueTaskId = vdm.getTopTaskIdOnDisplay(vdm.displayId)
        }

        // Destroy VD first so it stops rendering
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null

        // Move task to Display 0 on next frame — deferred so the foreground
        // activity is fully composited and the controlled app won't flash.
        // Bring Tyfo UI to front BEFORE the move to prevent z-order flicker.
        if (rescueTaskId != null) {
            Handler(Looper.getMainLooper()).post {
                bringTyfoUiToFront()
                vdm?.moveTaskToDisplay(rescueTaskId, 0)
                Log.i(TAG, "Deferred move of task $rescueTaskId to Display 0")
            }
        }

        displayState = DisplayState.CHAT
        leaveForegroundIfIdle()
        Log.i(TAG, "Agent stopped, app moved to Display 0")
    }

    @Suppress("DEPRECATION")
    private fun acquireAgentWakeLock() {
        backend?.setVirtualDisplayAwake(true)
        val current = agentWakeLock
        if (current?.isHeld == true) return

        try {
            val powerManager = getSystemService(PowerManager::class.java)
            agentWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:Agent"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
            Log.i(TAG, "Agent wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire agent wake lock: ${e.message}")
            agentWakeLock = null
        }
    }

    private fun releaseAgentWakeLock() {
        // 预览还开着就让虚拟屏继续亮着
        backend?.setVirtualDisplayAwake(viewerAttached)
        val wakeLock = agentWakeLock ?: return
        try {
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "Agent wake lock released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release agent wake lock: ${e.message}")
        } finally {
            agentWakeLock = null
        }
    }

    fun submitUserResponse(answer: String) {
        engine?.submitUserResponse(answer)
        updateNotification("Agent running")
        currentConversationId?.let { convId ->
            scope.launch { historyRepo.addMessage(convId, MessageType.USER_ANSWER, answer) }
        }
    }

    fun submitUserSupplement(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        Log.i(TAG, "submitUserSupplement(${trimmed.length} chars)")
        engine?.submitUserSupplement(trimmed)
        updateNotification("Agent running")
        currentConversationId?.let { convId ->
            scope.launch { historyRepo.addMessage(convId, MessageType.USER_SUPPLEMENT, trimmed) }
        }
    }

    // ── Display state transitions (SurfaceView-based) ────────────────────

    /**
     * Enter VIEW mode: launch ViewActivity with SurfaceView rendering VD content.
     * Agent keeps running — user can watch the agent work in real-time.
     * App stays on VD (no task reparenting).
     */
    fun enterViewMode(showHandoff: Boolean = false): Boolean {
        val vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            mainHandler.post {
                Toast.makeText(this, R.string.vd_released, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        // Prevent infinite recursion: if VD shows our own app, move it off the VD.
        // Without this, SurfaceView → VD → Tyfo UI → SurfaceView → ... (套娃)
        // Use backend (shell uid) for task queries — main process can't access hidden APIs.
        // getTopTaskIdOnDisplay returns -1 if only Tyfo tasks are on VD.
        // In that case we just proceed — the VD will show whatever was last rendered.

        // Launch ViewActivity — SurfaceView will connect to VD via setSurface()
        val intent = Intent(this, com.shiro.tyfo.ui.ViewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (showHandoff) {
                putExtra(EXTRA_SHOW_HANDOFF, true)
                putExtra(com.shiro.tyfo.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        startActivity(intent)

        displayState = DisplayState.VIEW
        updateNotification("Viewing agent operation")
        Log.i(TAG, "Entered VIEW mode (agent keeps running)")
        return true
    }

    /**
     * Enter TAKEOVER mode: pause agent, let user interact via touch forwarding.
     * Called by ViewActivity when user taps "Takeover".
     */
    fun enterTakeoverMode() {
        if (displayState != DisplayState.VIEW) return
        if (engine?.state?.value !is AgentState.WaitingForHandoff) {
            engine?.pause()
        }
        displayState = DisplayState.TAKEOVER
        updateNotification("Takeover mode")
        Log.i(TAG, "Entered TAKEOVER mode (agent paused)")
    }

    /**
     * Exit TAKEOVER mode back to VIEW mode: resume agent.
     * Called by ViewActivity when user taps "Return Control".
     */
    fun exitTakeoverMode() {
        if (displayState != DisplayState.TAKEOVER) return
        if (engine?.state?.value !is AgentState.WaitingForHandoff) {
            engine?.resume()
        }
        displayState = DisplayState.VIEW
        updateNotification("Viewing agent operation")
        refreshControlledTasksFromVd("exit_takeover")
        Log.i(TAG, "Exited TAKEOVER → VIEW mode (agent resumed)")
    }

    /**
     * Return to CHAT mode: close ViewActivity, bring ControlPanelActivity to front.
     * Agent keeps running on VD.
     */
    fun returnToChat() {
        // Resume agent if it was paused for TAKEOVER
        if (displayState == DisplayState.TAKEOVER) {
            engine?.resume()
        }

        // Bring ControlPanelActivity to front
        val intent = Intent(this, com.shiro.tyfo.ui.ControlPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)

        displayState = DisplayState.CHAT
        updateNotification("Tyfo agent running")
        Log.i(TAG, "Returned to CHAT mode")
    }

    // ── VD task escape guard ─────────────────────────────────────────────

    private fun startTaskDisplayGuard(vdm: VirtualDisplayManager) {
        stopTaskDisplayGuard()
        taskDisplayGuard = TaskDisplayGuard(
            context = this,
            vdm = vdm,
            scope = scope,
            onEscape = { task -> handleControlledTaskEscape(task) }
        ).also { it.start() }
        backend?.let { startAppOccupancy(it) }
    }

    private fun stopTaskDisplayGuard() {
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        stopAppOccupancy()
    }

    /**
     * Gate launches of the app the agent is driving, so tapping its icon on the phone
     * shows a toast instead of pulling the agent's task onto Display 0.
     */
    private fun startAppOccupancy(b: PrivilegeBackend) {
        stopAppOccupancy()
        val controller = AppOccupancyController(
            onLaunchBlocked = { pkg -> showAppOccupiedToast(pkg) },
            onOccupiedStart = { taskDisplayGuard?.requestScan() }
        )
        if (b.setActivityController(controller)) {
            occupancyController = controller
            Log.i(TAG, "App occupancy gate installed")
        } else {
            Log.w(TAG, "App occupancy gate unavailable, relying on TaskDisplayGuard only")
        }
    }

    private fun stopAppOccupancy() {
        val controller = occupancyController ?: return
        occupancyController = null
        controller.setOccupiedPackages(emptySet())
        backend?.setActivityController(null)
        Log.i(TAG, "App occupancy gate removed")
    }

    /**
     * Occupy only the app currently on top of the VD. Anything the agent has left is
     * released right away, and a VD showing its home screen occupies nothing.
     */
    private fun refreshControlledTasksFromVd(reason: String) {
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        // Use backend (shell uid) for task queries — main process can't access hidden APIs on real devices
        val b = backend ?: return
        val top = b.getTopTaskOnDisplay(vdm.displayId, packageName)
        val taskId = top?.getInt(DisplayOps.KEY_TASK_ID, -1) ?: -1
        val basePackage = top?.getString(DisplayOps.KEY_BASE_PACKAGE)
        // Home is judged by activity type server-side: resolving HOME from this app is
        // subject to package visibility and can return Settings' FallbackHome.
        val isHome = top?.getBoolean(DisplayOps.KEY_IS_HOME) == true
        if (taskId <= 0 || basePackage == null || isHome) {
            taskDisplayGuard?.setControlledTask(null)
            occupancyController?.setOccupiedPackages(emptySet())
            Log.d(TAG, "No app occupied on VD ($reason): top=#$taskId $basePackage home=$isHome")
            return
        }
        taskDisplayGuard?.setControlledTask(taskId)
        occupancyController?.setOccupiedPackages(setOf(basePackage))
        Log.d(TAG, "Occupied VD task ($reason): #$taskId $basePackage")
    }

    private fun handleControlledTaskEscape(task: TaskSnapshot) {
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        if (task.containsPackage(packageName)) return

        // TaskDisplayGuard already moved the task back to the VD, so Display 0 is back on
        // whatever the user was using. Tell them why instead of pulling up our own UI.
        Log.i(TAG, "Escape callback for task ${task.taskId}, returned to VD")
        val pkg = task.basePackage ?: task.topPackage
            ?: occupancyController?.occupiedPackages?.firstOrNull()
        pkg?.let { showAppOccupiedToast(it) }
    }

    private fun showAppOccupiedToast(pkg: String) {
        val now = SystemClock.uptimeMillis()
        val last = lastOccupiedToast
        if (last != null && last.first == pkg && now - last.second < OCCUPIED_TOAST_DEBOUNCE_MS) return
        lastOccupiedToast = pkg to now
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        Toast.makeText(this, getString(R.string.app_occupied_by_agent, label), Toast.LENGTH_SHORT).show()
    }

    private fun bringTyfoUiToFrontAfterRescue() {
        bringTyfoUiToFront()
    }

    private fun bringTyfoUiToFront() {
        val cls = when (displayState) {
            DisplayState.VIEW, DisplayState.TAKEOVER -> com.shiro.tyfo.ui.ViewActivity::class.java
            DisplayState.CHAT -> com.shiro.tyfo.ui.ControlPanelActivity::class.java
        }
        Log.i(TAG, "bringTyfoUiToFront: ${cls.simpleName} on Display 0")
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (cls == com.shiro.tyfo.ui.ViewActivity::class.java) {
                putExtra(com.shiro.tyfo.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        val options = android.app.ActivityOptions.makeBasic()
        try {
            options.setLaunchDisplayId(0)
        } catch (_: Exception) {}
        startActivity(intent, options.toBundle())
    }

    // ── Public accessors ────────────────────────────────────────────────

    val stateFlow: StateFlow<AgentState>?
        get() = engine?.state

    val stepResultFlow: SharedFlow<StepResult>?
        get() = engine?.stepResults

    fun getEngine(): AgentEngine? = engine

    fun getVirtualDisplayManager(): VirtualDisplayManager? = virtualDisplayManager

    /** 预览页被直接打开（实况窗上的“查看画面”）时补上 VIEW 状态，接管等逻辑依赖它。 */
    fun markViewing() {
        if (displayState == DisplayState.CHAT) displayState = DisplayState.VIEW
    }

    fun isOverlayActiveState(state: AgentState?): Boolean {
        return OverlayStatePolicy.isActive(state, displayState)
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Tyfo Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AI phone control agent service" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                getString(R.string.notif_channel_progress),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notif_channel_progress_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASK,
                getString(R.string.notif_channel_ask),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_ask_desc)
                enableVibration(true)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.shiro.tyfo.ui.ControlPanelActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tyfo")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        // 进度 / 结果实况窗挂着时由它占着前台通知，进入预览、接管这些状态文案别把它顶掉
        if (progressObserverJob?.isActive == true || resultNotificationShown) return
        if (!inForeground) return
        val nm = getSystemService(NotificationManager::class.java)
        val notification = buildNotification(text)
        lastNotification = notification
        nm.notify(NOTIFICATION_ID, notification)
    }

    private enum class ProgressPhase { RUNNING, WAITING, HANDOFF, PAUSED }

    /**
     * 任务进行中把前台通知换成实时进度：模型在做什么 / 在等你什么，外加已执行时长。
     * 系统支持时它会被提升为实况窗（见 [LiveUpdates]）：执行中状态栏胶囊走表显示已执行时长，
     * 等输入 / 等接管 / 暂停时显示对应短字，点开直接到要处理的地方。
     * 锁屏隐藏敏感内容时只显示脱敏的公开版本，不泄露任务内容。
     */
    private fun observeProgressNotification() {
        progressObserverJob?.cancel()
        val eng = engine ?: return
        progressObserverJob = scope.launch {
            var lastKey: String? = null
            var thought = ""
            eng.state.collect { state ->
                val phase: ProgressPhase
                val detail: String
                when (state) {
                    is AgentState.Running -> {
                        // 旁白流式生成中的半句不发通知，等这一步的完整旁白
                        if (state.partial) return@collect
                        phase = ProgressPhase.RUNNING
                        val t = state.thought.trim()
                        // 引擎内部的占位文案（截图中 / 思考中 / 执行中）不展示
                        if (t.isNotEmpty() && !t.startsWith("Capturing") && !t.startsWith("Thinking") && !t.startsWith("Executing")) {
                            thought = t.replace('\n', ' ')
                            latestThought = thought
                        }
                        detail = thought
                    }
                    is AgentState.WaitingForUser -> {
                        phase = ProgressPhase.WAITING
                        detail = state.question.replace('\n', ' ')
                    }
                    is AgentState.WaitingForHandoff -> {
                        phase = ProgressPhase.HANDOFF
                        detail = state.reason.replace('\n', ' ')
                    }
                    is AgentState.Paused -> {
                        phase = ProgressPhase.PAUSED
                        detail = ""
                    }
                    else -> return@collect
                }
                val key = "$phase|$detail"
                if (key == lastKey) return@collect
                lastKey = key
                postProgressNotification(phase, detail)
            }
        }
    }

    /**
     * Android 14 起常驻通知也能被用户划掉，没法直接禁止；执行中的进度实况窗被划掉时服务收到这个回调，
     * 重新挂上同一条通知，等于划不掉。结果卡片不用它，划掉就是关闭。
     */
    private fun swipeBackIntent(): PendingIntent = PendingIntent.getService(
        this, 9,
        Intent(this, AgentService::class.java).setAction(ACTION_NOTIFICATION_SWIPED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun postProgressNotification(phase: ProgressPhase, detail: String) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // 点开到要处理的地方：等输入打开回答框，等接管打开预览页的接管面板，其余回到对话
        val contentIntent = when (phase) {
            ProgressPhase.WAITING -> PendingIntent.getActivity(
                this, 7,
                Intent(this, com.shiro.tyfo.ui.ControlPanelActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(EXTRA_FOCUS_ASK, true),
                flags
            )
            ProgressPhase.HANDOFF -> PendingIntent.getActivity(
                this, 8,
                Intent(this, com.shiro.tyfo.ui.ViewActivity::class.java)
                    .putExtra(EXTRA_SHOW_HANDOFF, true)
                    .putExtra(com.shiro.tyfo.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true),
                flags
            )
            else -> PendingIntent.getActivity(
                this, 0, Intent(this, com.shiro.tyfo.ui.ControlPanelActivity::class.java), flags
            )
        }
        // 直接打开预览页（悬浮岛上“查看画面”的替代），预览页连上服务后自己挂到虚拟屏上
        val viewIntent = PendingIntent.getActivity(
            this, 4, Intent(this, com.shiro.tyfo.ui.ViewActivity::class.java), flags
        )
        val stopIntent = PendingIntent.getService(
            this, 3, Intent(this, AgentService::class.java).setAction(ACTION_STOP_TASK), flags
        )
        val status = when (phase) {
            ProgressPhase.RUNNING -> getString(R.string.notif_progress_running)
            ProgressPhase.WAITING -> getString(R.string.notif_progress_waiting)
            ProgressPhase.HANDOFF -> getString(R.string.notif_progress_handoff)
            ProgressPhase.PAUSED -> getString(R.string.notif_progress_paused)
        }
        val body = when {
            detail.isBlank() -> status
            phase == ProgressPhase.RUNNING -> detail
            else -> "$status · $detail"
        }
        val publicVersion = Notification.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(getString(R.string.notif_progress_public_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setWhen(taskStartedAt)
            .setUsesChronometer(true)
            .build()
        val builder = Notification.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(currentInstruction.ifBlank { getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setDeleteIntent(swipeBackIntent())
            .addAction(Notification.Action.Builder(null, getString(R.string.island_open), viewIntent).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.overlay_stop), stopIntent).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            // 已执行时长：通知头部和实况窗胶囊都会自己走表
            .setWhen(taskStartedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            // 不放进度条：总步数事先不知道，给不出真实进度
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        if (android.os.Build.VERSION.SDK_INT >= 36 && phase != ProgressPhase.RUNNING) {
            // 胶囊上的短字；执行中不设，胶囊就显示走表的已执行时长
            builder.setShortCriticalText(
                getString(
                    when (phase) {
                        ProgressPhase.WAITING -> R.string.notif_progress_short_waiting
                        ProgressPhase.HANDOFF -> R.string.notif_progress_short_handoff
                        else -> R.string.notif_progress_short_paused
                    }
                )
            )
        }
        LiveUpdates.requestPromotion(builder)
        val notification = builder.build()
        synchronized(progressNotificationLock) {
            if (resultNotificationShown) return
            lastNotification = notification
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 任务结束后常驻一个结果实况窗：胶囊显示“已完成 / 失败”，点开看完成总结（对话页），
     * 用户点“关闭”或开始新任务时才撤掉。
     */
    private fun postResultNotification(success: Boolean, summary: String) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(
            this, 5,
            Intent(this, com.shiro.tyfo.ui.ControlPanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags
        )
        val dismissIntent = PendingIntent.getService(
            this, 6, Intent(this, AgentService::class.java).setAction(ACTION_DISMISS_RESULT), flags
        )
        val title = getString(if (success) R.string.notif_task_completed else R.string.notif_task_failed_title)
        val body = summary.replace('\n', ' ').ifBlank { currentInstruction }
        val publicVersion = Notification.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        val builder = Notification.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            // 结果卡片可以划掉，划掉等同于点“关闭”
            .setDeleteIntent(dismissIntent)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_result_dismiss), dismissIntent).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(
                getString(if (success) R.string.notif_result_short_done else R.string.notif_result_short_failed)
            )
        }
        LiveUpdates.requestPromotion(builder)
        val notification = builder.build()
        synchronized(progressNotificationLock) {
            lastNotification = notification
            resultNotificationShown = true
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }
}
