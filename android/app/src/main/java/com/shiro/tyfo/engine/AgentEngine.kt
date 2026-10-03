package com.shiro.tyfo.engine

import android.graphics.Bitmap
import android.util.Log
import com.shiro.tyfo.action.Action
import com.shiro.tyfo.action.ActionExecutor
import com.shiro.tyfo.accessibility.VdAccessibilityService
import com.shiro.tyfo.capture.ScreenCapture
import com.shiro.tyfo.db.GlobalMemoryEntity
import com.shiro.tyfo.db.GlobalMemoryRepository
import com.shiro.tyfo.display.VirtualDisplayManager
import com.shiro.tyfo.llm.LlmMessage
import com.shiro.tyfo.llm.LlmReply
import com.shiro.tyfo.llm.ModelClient
import com.shiro.tyfo.llm.ModelException
import com.shiro.tyfo.llm.ReplyListener
import com.shiro.tyfo.llm.ResponseParser
import com.shiro.tyfo.llm.ToolCall
import com.shiro.tyfo.tools.PhoneTool
import com.shiro.tyfo.tools.PhoneTools
import com.shiro.tyfo.tools.Points
import com.shiro.tyfo.tools.ScreenOutline
import com.shiro.tyfo.tools.ToolContext
import com.shiro.tyfo.tools.ToolKind
import com.shiro.tyfo.tools.ToolOutcome
import com.shiro.tyfo.tools.ToolRegistry
import com.shiro.tyfo.tools.UiElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Core agent engine: observe-think-act loop.
 *
 * 对话的形状（参考 Claude computer use / mobile-mcp / ugc-android/AndroidControl）：
 * - 第一条用户消息是任务 + 第一次屏幕观察。一次观察 = 截图（给模型理解屏幕）+ 元素清单 outline
 *   （每个元素算好 0-1000 的点击中心，模型照抄去点，不从图上估）。
 * - 模型每次回复：一句旁白（流式推给界面当进度）+ 若干工具调用。
 * - 引擎按工具的 [ToolKind] 执行：INFO 随时执行；SCREEN 按顺序连着做，遇到失败就停；CONTROL
 *   （finish / ask_user / handoff_user）不能跟在屏幕操作后面。每个调用都回一条结果。
 * - 做过屏幕操作（或等过用户）后重新观察一次，附在最后那个调用的结果里。
 * - 观察只保留最近几次，旧的成批换成占位文字（见 [ModelProfile] 的保留策略）。
 *   删除那一步从第一个被删的观察开始重算，之前的前缀照常命中；其余步只追加，缓存整段命中。
 *
 * 引擎不认识具体的服务商：消息格式是中立的 [LlmMessage]，服务商的特性从 [ModelClient.profile] 读。
 */
class AgentEngine(
    private val model: ModelClient,
    private val actionExecutor: ActionExecutor,
    private val maxSteps: Int = 100,
    private val virtualDisplayManager: VirtualDisplayManager? = null,
    private val debugMode: Boolean = false,
    private val memoryRepository: GlobalMemoryRepository? = null,
    /** 读屏幕上的界面元素：(displayId, 宽, 高) → 元素（设备像素），拿不到返回 null。 */
    private val elementsProvider: ((Int, Int, Int) -> List<UiElement>?)? = null,
    private val blacklistedPackages: Set<String> = emptySet(),
    private val isScreenInteractive: (() -> Boolean)? = null,
    /**
     * 这段会话到目前为止发给模型的消息（见 [SessionHistory.snapshot]）：任务结束时、
     * 停下来等用户回答 / 接管时各给一次，用来存库，下次继续这段会话时接上。
     */
    private val onTranscript: ((List<LlmMessage>) -> Unit)? = null,
    /** 达到步数上限时给用户看的结果文字。 */
    private val maxStepsMessage: (Int) -> String = { "Max steps reached ($it)" },
    /** 写进系统提示词的设备和时间信息。 */
    private val environment: AgentPrompts.Environment = AgentPrompts.Environment(),
) {

    companion object {
        private const val TAG = "AgentEngine"
        /** 模型连续这么多次只回文字、不调用工具，就当出错结束。 */
        private const val MAX_REPLIES_WITHOUT_TOOL = 2
        private const val MAX_NOTES = 10
        private const val VD_CAPTURE_MAX_ATTEMPTS = 8
        private const val VD_CAPTURE_RETRY_DELAY_MS = 300L
        private const val VD_CAPTURE_RECOVERY_THRESHOLD = 3  // Check for an empty VD after this many failures
    }

    private val profile = model.profile
    private val shotWidth = profile.screenshotMaxWidth
    private val shotQuality = profile.screenshotQuality
    private val screenWidth = virtualDisplayManager?.width ?: environment.screenWidth
    private val screenHeight = virtualDisplayManager?.height ?: environment.screenHeight
    private val tools: ToolRegistry = PhoneTools(onVirtualDisplay = virtualDisplayManager != null).registry()

    /**
     * 一次屏幕观察：截图、界面元素和它的 outline（拿不到元素时为 null），以及判断屏幕有没有变化用的指纹。
     */
    private class Screen(
        val base64: String,
        val fingerprint: ScreenFingerprint?,
        val elements: List<UiElement>?,
        val outline: String?,
    )

    /** 拍到的截图，以及拍之前发生的、要告诉模型的事（用户接管过）。 */
    private class Observation(val screen: Screen, val notices: List<String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agentJob: Job? = null
    private val actionRepeatGuard = ActionRepeatGuard()

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _stepResults = MutableSharedFlow<StepResult>(replay = 0, extraBufferCapacity = 20)
    val stepResults: SharedFlow<StepResult> = _stepResults.asSharedFlow()

    @Volatile
    private var paused = false
    // 等用户回答时被接管：循环挂在回答上，不会再去刷新状态，交还时要把这个提问原样恢复
    private var askBeforePause: AgentState.WaitingForUser? = null

    private var consecutiveBlackFrames = 0
    private var lastNonBlackStep = 0

    @Volatile
    private var userResponseDeferred: CompletableDeferred<String?>? = null

    @Volatile
    private var userHandoffDeferred: CompletableDeferred<String?>? = null

    private val pendingUserSupplements = mutableListOf<String>()

    /**
     * Test-only: jump the engine straight into WaitingForUser state without running the LLM loop.
     * Used by ADB test broadcasts to verify ask_user UI / overlay / heads-up plumbing.
     */
    fun debugSimulateAskUser(question: String) {
        Log.i(TAG, "debugSimulateAskUser: $question")
        _state.value = AgentState.WaitingForUser(question, 1)
    }

    fun debugSimulateHandoff(reason: String) {
        Log.i(TAG, "debugSimulateHandoff: $reason")
        _state.value = AgentState.WaitingForHandoff(reason, 1)
    }

    fun debugSimulateRunning(step: Int = 1, thought: String = "debug running") {
        Log.i(TAG, "debugSimulateRunning: step=$step thought=$thought")
        paused = false
        _state.value = AgentState.Running(step, thought)
    }

    /** 执行循环还在跑（含暂停、等用户回答 / 接管），即任务还没结束。 */
    val isLoopActive: Boolean
        get() = agentJob?.isActive == true

    /**
     * Start the agent with the given instruction.
     *
     * 继续一段历史会话时：[history] 是之前几轮发给模型的消息（[SessionHistory.forContinuation]），
     * 接在系统提示词后面，模型能看到之前每一步；[conversationContext] 是整段会话的摘要
     * （[ConversationContext]），附在系统提示词里，历史太长被截掉的早期几轮也不会丢。
     */
    fun start(
        instruction: String,
        conversationContext: String? = null,
        history: List<LlmMessage> = emptyList(),
    ) {
        if (agentJob?.isActive == true) {
            Log.w(TAG, "Agent already running")
            return
        }
        paused = false
        agentJob = scope.launch {
            runAgentLoop(instruction, conversationContext, history)
        }
    }

    /**
     * Pause the agent loop.
     */
    fun pause() {
        // 任务已经结束（比如结束后才进来接管）就不动状态：没有循环会再把 Paused 改回来，
        // 结果会一直停在 Paused，光效熄灭、虚拟屏也因为“任务还在”而不回收
        if (!isLoopActive) return
        paused = true
        askBeforePause = _state.value as? AgentState.WaitingForUser
        _state.value = AgentState.Paused
    }

    /**
     * Resume the agent loop.
     */
    fun resume() {
        paused = false
        // 平时由循环的下一步刷新状态；等回答时循环不会动，直接恢复成等回答，回答框才会回来
        askBeforePause?.let { if (_state.value is AgentState.Paused) _state.value = it }
        askBeforePause = null
    }

    /**
     * Submit user response when engine is in WaitingForUser state.
     */
    fun submitUserResponse(response: String) {
        userResponseDeferred?.complete(response)
    }

    fun completeUserHandoff(source: String = "user_returned_control") {
        userHandoffDeferred?.complete(source)
    }

    fun submitUserSupplement(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        synchronized(pendingUserSupplements) {
            pendingUserSupplements.add(trimmed)
        }
        Log.i(TAG, "submitUserSupplement queued (${trimmed.length} chars)")
    }

    /**
     * Stop the agent.
     */
    fun stop() {
        paused = false
        userResponseDeferred?.complete(null)
        userResponseDeferred = null
        userHandoffDeferred?.complete(null)
        userHandoffDeferred = null
        agentJob?.cancel()
        agentJob = null
        _state.value = AgentState.Idle()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stop()
        model.shutdown()
    }

    /**
     * Main observe-think-act loop. 一步 = 一次模型调用。
     */
    private suspend fun runAgentLoop(
        instruction: String,
        conversationContext: String?,
        history: List<LlmMessage>,
    ) {
        consecutiveBlackFrames = 0
        lastNonBlackStep = 0

        val memories = memoryRepository?.getEnabled().orEmpty()
        val messages = mutableListOf<LlmMessage>(
            LlmMessage.System(AgentPrompts.systemPrompt(environment, memories, conversationContext))
        )
        messages.addAll(history)
        val notes = linkedMapOf<String, String>()
        var screen: Screen
        var consecutiveUnchanged = 0
        var repliesWithoutTool = 0

        try {
            // Wait for VD to stabilize after creation. Android may briefly route
            // the calling app's activity to a new VD; the keyguard dismiss thread
            // in PrivilegedService also needs ~600ms. The mirror cache drain
            // (800ms after creation) clears stale frames.
            if (virtualDisplayManager != null) {
                delay(1200)
            }

            val first = observe(1, messages) ?: return
            screen = first.screen
            messages.add(
                LlmMessage.User(
                    listOf(LlmMessage.Part.Text(AgentPrompts.task(instruction))) +
                        first.notices.map { LlmMessage.Part.Text(it) } +
                        screen.parts()
                )
            )

            for (step in 1..maxSteps) {
                while (paused) {
                    delay(200)
                    if (!scope.isActive) return
                }
                val stepStart = System.currentTimeMillis()
                drainUserSupplements().takeIf { it.isNotEmpty() }?.let {
                    messages.add(LlmMessage.User(AgentPrompts.userAdded(it)))
                }

                // === THINK ===
                _state.value = AgentState.Running(step, "Thinking...")
                val reply = try {
                    model.chat(messages, tools.specs, progressListener(step))
                } catch (e: ModelException) {
                    val llmMs = System.currentTimeMillis() - stepStart
                    Log.w(TAG, "Step $step model error after ${llmMs}ms: ${e.message}")
                    _state.value = AgentState.Error("LLM error: ${e.message}")
                    _stepResults.emit(
                        StepResult(step, "error", e.message ?: "LLM error", false,
                            "LLM API error: ${e.message}", llmMs, true)
                    )
                    return
                }
                val llmMs = System.currentTimeMillis() - stepStart

                val calls = reply.toolCalls.ifEmpty {
                    // 模型没走工具调用：可能把调用写进了正文，认得出来就照常执行
                    reply.text?.let { ResponseParser.toolCallsFromText(it) }.orEmpty()
                        .also { if (it.isNotEmpty()) Log.w(TAG, "Step $step: tool calls recovered from text: ${it.map { c -> c.name }}") }
                }
                val replyText = reply.text.takeIf { reply.toolCalls.isNotEmpty() || calls.isEmpty() }
                if (calls.isEmpty()) {
                    repliesWithoutTool++
                    Log.w(TAG, "Step $step: reply without a tool call ($repliesWithoutTool): ${replyText?.take(200)}")
                    if (repliesWithoutTool > MAX_REPLIES_WITHOUT_TOOL) {
                        val message = "The model replied without calling a tool: ${replyText?.take(200)}"
                        _state.value = AgentState.Error(message)
                        _stepResults.emit(StepResult(step, "error", "", false, message, llmMs, true))
                        return
                    }
                    messages.add(LlmMessage.Assistant(replyText, reasoning = reply.reasoning))
                    messages.add(LlmMessage.User(AgentPrompts.CALL_A_TOOL))
                    continue
                }
                repliesWithoutTool = 0

                val resolutions = calls.map { tools.resolve(it) }
                // 存进历史的是规范化后的参数：原始参数再乱，回放给模型时也是合法 JSON
                messages.add(LlmMessage.Assistant(replyText, resolutions.map { normalizedCall(it) }, reply.reasoning))
                val narration = narrationOf(reply)
                val debugInfo = if (debugMode) formatDebugInfo(reply, calls) else null
                Log.i(TAG, "Step $step (${llmMs}ms) \"${narration.take(80)}\" ${calls.joinToString { "${it.name}${it.arguments}" }.take(400)}")
                _state.value = AgentState.Running(step, narration)

                // === ACT ===
                val resultsStart = messages.size
                var screenActed = false   // 这次回复里已经做过屏幕操作
                var screenChanged = false // 其中有应当改变屏幕的操作成功了
                var failed = false        // 屏幕操作失败，后面的屏幕操作不再执行
                var waited = false        // 已经等过用户（ask_user / handoff_user）
                var observeAfter = -1     // 新截图附在哪条结果上（messages 里的位置）
                var lastActed: PhoneTool? = null // 这次回复里最近执行的屏幕操作，下一个动作前 / 观察前要等它
                for ((index, resolution) in resolutions.withIndex()) {
                    val call = resolution.call
                    val elapsed = { System.currentTimeMillis() - stepStart }
                    if (resolution is ToolRegistry.Resolution.Invalid) {
                        // 参数有问题：把错误告诉模型，让它改了重来，不让整个任务失败
                        Log.w(TAG, "Step $step invalid call ${call.name}: ${resolution.error}")
                        messages.add(LlmMessage.ToolResult(call.id, call.name, resolution.error, isError = true))
                        _stepResults.emit(StepResult(step, call.name, narration, false, resolution.error, elapsed(), false, debugInfo))
                        continue
                    }
                    resolution as ToolRegistry.Resolution.Ok
                    val tool = resolution.tool
                    val skip = when {
                        waited -> AgentPrompts.NOT_RUN_AFTER_CONTROL
                        tool.kind == ToolKind.CONTROL && screenActed -> AgentPrompts.NOT_RUN_CONTROL_AFTER_ACTIONS
                        tool.kind == ToolKind.SCREEN && failed -> AgentPrompts.NOT_RUN_AFTER_FAILURE
                        else -> null
                    }
                    if (skip != null) {
                        messages.add(LlmMessage.ToolResult(call.id, tool.name, skip, isError = true))
                        continue
                    }

                    // 连着的屏幕操作之间只等一小会儿（计算器连按），最后一个做完再等界面稳定
                    if (tool.kind != ToolKind.INFO) lastActed?.let { delay(it.chainDelayMs) }
                    // 界面元素是动作之前拍的，只有第一个屏幕操作能拿它判断点到了什么
                    val context = toolContext(step, screen, labelsValid = !screenActed, notes)
                    when (val outcome = tool.call(resolution.args, context)) {
                        is ToolOutcome.Result -> {
                            messages.add(LlmMessage.ToolResult(call.id, tool.name, outcome.text, outcome.isError))
                            _stepResults.emit(
                                StepResult(step, tool.name, narration, !outcome.isError, outcome.text, elapsed(), false, debugInfo)
                            )
                            if (tool.kind == ToolKind.SCREEN) {
                                screenActed = true
                                observeAfter = messages.lastIndex
                                lastActed = tool
                                if (outcome.isError) {
                                    failed = true
                                    Log.w(TAG, "Step $step ${tool.name} failed: ${outcome.text}")
                                } else if (tool.changesScreen) {
                                    screenChanged = true
                                }
                            }
                        }

                        is ToolOutcome.Finish -> {
                            messages.add(LlmMessage.ToolResult(call.id, tool.name, AgentPrompts.REPORTED))
                            for (rest in resolutions.drop(index + 1)) {
                                messages.add(LlmMessage.ToolResult(rest.call.id, normalizedCall(rest).name, AgentPrompts.NOT_RUN_AFTER_CONTROL, isError = true))
                            }
                            Log.i(TAG, "Task ended at step $step (success=${outcome.success}): ${outcome.answer.take(100)}")
                            if (outcome.success) {
                                _stepResults.emit(StepResult(step, "finish", narration, true, outcome.answer, elapsed(), true, debugInfo))
                                _state.value = AgentState.Idle(resultMessage = outcome.answer)
                            } else {
                                _stepResults.emit(
                                    StepResult(step, "fail", narration, false, "Task failed: ${outcome.answer}", elapsed(), true, debugInfo)
                                )
                                _state.value = AgentState.Error(outcome.answer)
                            }
                            return
                        }

                        is ToolOutcome.AskUser -> {
                            waited = true
                            Log.i(TAG, "WaitingForUser(question=${outcome.question}, step=$step)")
                            // 先备好接收回答的位置再公布“等回答”，否则界面一出现就回答会落空
                            val deferred = CompletableDeferred<String?>()
                            userResponseDeferred = deferred
                            _state.value = AgentState.WaitingForUser(outcome.question, step, outcome.options)
                            _stepResults.emit(
                                StepResult(step, "ask_user", narration, true, "Asking: ${outcome.question}", elapsed(), false, debugInfo)
                            )
                            saveTranscript(messages)
                            val answer = deferred.await()
                            userResponseDeferred = null
                            if (answer == null) {
                                _state.value = AgentState.Idle()
                                return
                            }
                            messages.add(LlmMessage.ToolResult(call.id, tool.name, AgentPrompts.userAnswered(answer)))
                            observeAfter = messages.lastIndex
                        }

                        is ToolOutcome.Handoff -> {
                            waited = true
                            Log.i(TAG, "WaitingForHandoff(reason=${outcome.reason}, step=$step)")
                            val announce = StepResult(step, "handoff_user", narration, true,
                                "Handing off to user: ${outcome.reason}", elapsed(), false, debugInfo)
                            val source = awaitHandoff(step, outcome.reason, announce, messages) ?: return
                            messages.add(LlmMessage.ToolResult(call.id, tool.name, AgentPrompts.handoffReturned(source)))
                            observeAfter = messages.lastIndex
                        }
                    }
                }

                // Wait for the screen to settle before the next screenshot
                lastActed?.let { if (it.settleMs > 0) delay(it.settleMs) }

                // === OBSERVE ===
                if (observeAfter >= 0) {
                    val before = screen
                    val observation = observe(step + 1, messages) ?: return
                    screen = observation.screen
                    val notices = observation.notices.toMutableList()
                    if (screenChanged) {
                        // outline 一样 = 这次没变；指纹相似连续几次 = 卡住了（没开无障碍时也能判断）
                        val similar = screen.fingerprint != null && before.fingerprint != null &&
                            screen.fingerprint!!.isSimilarTo(before.fingerprint!!)
                        consecutiveUnchanged = if (similar) consecutiveUnchanged + 1 else 0
                        if (consecutiveUnchanged >= 3 && consecutiveUnchanged % 3 == 0) {
                            notices.add(AgentPrompts.SCREEN_STUCK)
                            Log.w(TAG, "Step $step: screen stuck for $consecutiveUnchanged replies")
                        } else if (before.outline != null && before.outline == screen.outline) {
                            notices.add(AgentPrompts.SCREEN_UNCHANGED)
                            Log.w(TAG, "Step $step: screen unchanged (outline)")
                        }
                    }
                    val target = messages[observeAfter] as LlmMessage.ToolResult
                    messages[observeAfter] = target.copy(
                        parts = target.parts + notices.map { LlmMessage.Part.Text(it) } + screen.parts()
                    )
                    pruneScreenshots(messages, resultsStart)
                }
                Log.i(TAG, "Step $step TIMING: llm=${llmMs}ms total=${System.currentTimeMillis() - stepStart}ms")
            }

            // Max steps reached
            Log.w(TAG, "max_steps reached at step $maxSteps, stopping agent")
            _state.value = AgentState.Idle(resultMessage = maxStepsMessage(maxSteps))
            _stepResults.emit(
                StepResult(maxSteps, "max_steps", "Reached max steps ($maxSteps)",
                    false, "Max steps reached", 0, true)
            )

        } catch (e: CancellationException) {
            Log.i(TAG, "Agent loop cancelled")
            _state.value = AgentState.Idle()
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop error", e)
            val message = e.message ?: "Unknown error"
            _state.value = AgentState.Error(message)
            // 其他失败路径都会发一条 completed 的结果，这里也补上，聊天记录里才有这次的结局
            _stepResults.tryEmit(StepResult(0, "error", "", false, message, 0, true))
        } finally {
            // 完成、失败、出错、被停止都会走到这里
            saveTranscript(messages)
        }
    }

    /** 给界面看的进度：旁白一边生成一边推（partial）；还没有旁白时先推思考摘要。回复完整后再推一次完整的旁白。 */
    private fun progressListener(step: Int) = object : ReplyListener {
        private var hasText = false

        override fun onText(text: String) {
            hasText = true
            _state.value = AgentState.Running(step, text, partial = true)
        }

        override fun onReasoning(summary: String) {
            if (!hasText) _state.value = AgentState.Running(step, summary, partial = true)
        }
    }

    /** 这一步给用户看的说明：模型的旁白；开着思考时它偶尔不写旁白，用思考摘要顶上。 */
    private fun narrationOf(reply: LlmReply): String =
        reply.text?.trim()?.takeIf { it.isNotEmpty() && reply.toolCalls.isNotEmpty() }
            ?: reply.reasoning?.summary?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?: ""

    /**
     * 拍一张给模型看的截图。拍之前如果受保护的应用在前台、或者连续拍到全黑（隐私保护页面 / 锁屏），
     * 先停下来交给用户，交还后再拍。返回 null 表示任务该结束了（截图失败已报错，或等用户时被停止）。
     */
    private suspend fun observe(step: Int, messages: List<LlmMessage>): Observation? {
        val notices = mutableListOf<String>()
        val vdm = virtualDisplayManager
        while (true) {
            // === BLACKLIST GATE ===
            if (blacklistedPackages.isNotEmpty()) {
                val topPkg = VdAccessibilityService.getTopPackageOnDisplay(vdm?.displayId ?: 0)
                if (topPkg != null && topPkg in blacklistedPackages) {
                    Log.i(TAG, "Blacklist gate: protected app $topPkg active at step $step")
                    val reason = "Protected app ($topPkg) is active. Returning control to user."
                    val announce = StepResult(step, "blacklist_handoff", "Protected app detected", true, reason, 0, false)
                    awaitHandoff(step, reason, announce, messages) ?: return null
                    notices.add(AgentPrompts.RETURNED_AFTER_PROTECTED_APP)
                    continue
                }
            }

            _state.value = AgentState.Running(step, "Capturing screenshot...")
            val base64: String? = if (vdm != null) {
                captureVirtualDisplay(vdm, step)
            } else {
                Log.w(TAG, "No VirtualDisplayManager — capturing physical screen")
                ScreenCapture.captureBase64(maxWidth = shotWidth, quality = shotQuality)
            }
            if (base64 == null) {
                _state.value = AgentState.Error("Failed to capture screenshot")
                _stepResults.emit(StepResult(step, "error", "Screenshot failed", false, "Failed to capture screenshot", 0, true))
                return null
            }

            // Debug: save screenshot to file for inspection
            if (debugMode) {
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    java.io.File("/sdcard/tyfo_step_${step}.jpg").writeBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save debug screenshot", e)
                }
            }

            // FLAG_SECURE detection: consecutive strictly-black frames indicate a
            // privacy-protected screen. Use strict check (all sampled pixels == 0x000000)
            // to avoid false positives from app transition animations which have
            // status bar / navigation bar colors.
            // An empty VD (its last app closed) is simply black, which isn't a
            // privacy-protected screen.
            val strictlyBlack = isBitmapStrictlyBlack(base64)
            val emptyVd = strictlyBlack && vdm != null &&
                withContext(Dispatchers.IO) { vdm.getTopTaskIdOnDisplay(vdm.displayId) } == null
            if (emptyVd) {
                consecutiveBlackFrames = 0
            } else if (strictlyBlack) {
                consecutiveBlackFrames++
                Log.d(TAG, "Strictly black frame detected ($consecutiveBlackFrames consecutive, lastNonBlack=$lastNonBlackStep)")
            } else {
                consecutiveBlackFrames = 0
                lastNonBlackStep = step
            }
            // 3+ consecutive strictly-black frames after a non-black one: hand over to the user
            if (consecutiveBlackFrames >= 3 && lastNonBlackStep > 0) {
                Log.i(TAG, "FLAG_SECURE handoff triggered at step $step")
                consecutiveBlackFrames = 0  // reset to prevent re-trigger if user resumes
                val reason = if (isScreenInteractive?.invoke() == false) {
                    "I can't see the screen while the phone's screen is off or locked. Please wake/unlock the phone, then return control."
                } else {
                    "I may not be able to see this screen — it appears to be privacy-protected. Please take over to handle this part."
                }
                val announce = StepResult(step, "flag_secure_handoff", "Privacy-protected screen detected", true, reason, 0, false)
                awaitHandoff(step, reason, announce, messages) ?: return null
                notices.add(AgentPrompts.RETURNED_AFTER_PRIVATE_SCREEN)
                continue
            }

            val elements = captureElements(step)
            val outline = elements?.let { ScreenOutline.format(it, screenWidth, screenHeight) }
            if (outline != null) {
                // 屏幕内容可能涉及隐私，完整的 outline 只在调试模式下写日志
                if (debugMode) Log.d(TAG, "Step $step outline:\n$outline")
                else Log.d(TAG, "Step $step outline: ${outline.lines().size - 1} lines, ${outline.length} chars")
            }
            return Observation(Screen(base64, ScreenFingerprint.fromBase64(base64), elements, outline), notices)
        }
    }

    private fun captureElements(step: Int): List<UiElement>? {
        val provider = elementsProvider ?: return null
        return try {
            provider(virtualDisplayManager?.displayId ?: 0, screenWidth, screenHeight)
                ?.also { Log.d(TAG, "Step $step: ${it.size} UI elements") }
        } catch (e: Exception) {
            Log.w(TAG, "UI element capture failed", e)
            null
        }
    }

    /** 一次观察给模型的部分：截图 + outline（标成屏幕观察，删旧截图时一起删）。 */
    private fun Screen.parts(): List<LlmMessage.Part> = listOfNotNull(
        LlmMessage.Part.Image(base64),
        outline?.let { LlmMessage.Part.Text(AgentPrompts.outline(it), screenState = true) },
    )

    /**
     * 屏幕观察只留最近 [ModelProfile.keepScreenshots] 次，多出来的攒够 [ModelProfile.pruneScreenshotsEvery] 次
     * 再一次删掉（截图换成占位文字，outline 去掉）。被删的那条之后的前缀缓存都会失效，所以成批删，
     * 让大多数步都是纯追加；只留 1 次时每步删上一次，改动总在最末尾，代价也小。
     * [protectFrom] 之后的消息（这一步刚加的）不动。
     */
    private fun pruneScreenshots(messages: MutableList<LlmMessage>, protectFrom: Int) {
        fun partsOf(m: LlmMessage) = when (m) {
            is LlmMessage.User -> m.parts
            is LlmMessage.ToolResult -> m.parts
            else -> emptyList()
        }
        val withScreenshots = messages.indices.filter { i -> partsOf(messages[i]).any { it is LlmMessage.Part.Image } }
        var remove = withScreenshots.size - profile.keepScreenshots
        remove -= remove % profile.pruneScreenshotsEvery.coerceAtLeast(1)
        if (remove <= 0) return
        val drop = withScreenshots.take(remove).filter { it < protectFrom }
        for (i in drop) {
            val stripped = partsOf(messages[i]).mapNotNull { p ->
                when {
                    p is LlmMessage.Part.Image -> LlmMessage.Part.Text(AgentPrompts.SCREENSHOT_OMITTED)
                    p is LlmMessage.Part.Text && p.screenState -> null
                    else -> p
                }
            }
            messages[i] = when (val m = messages[i]) {
                is LlmMessage.User -> m.copy(parts = stripped)
                is LlmMessage.ToolResult -> m.copy(parts = stripped)
                else -> m
            }
        }
        Log.d(TAG, "Pruned ${drop.size} old screen observation(s)")
    }

    /** 停下来等用户接管、交还控制。返回交还的来源；被停止时返回 null（状态已置为 Idle）。 */
    private suspend fun awaitHandoff(
        step: Int,
        reason: String,
        announce: StepResult,
        messages: List<LlmMessage>,
    ): String? {
        _state.value = AgentState.WaitingForHandoff(reason, step)
        _stepResults.emit(announce)
        saveTranscript(messages)
        val deferred = CompletableDeferred<String?>()
        userHandoffDeferred = deferred
        val source = deferred.await()
        userHandoffDeferred = null
        if (source == null) _state.value = AgentState.Idle()
        return source
    }

    /**
     * 工具执行时能用的能力：屏幕操作走重复检查和执行器，笔记记在本次任务里，长期记忆写库。
     * [labelsValid]：[screen] 的界面元素还对得上当前屏幕（这次回复里还没做过屏幕操作）。
     */
    private fun toolContext(
        step: Int,
        screen: Screen,
        labelsValid: Boolean,
        notes: MutableMap<String, String>,
    ) = object : ToolContext {
        override suspend fun perform(action: Action): ToolOutcome.Result {
            // 防重复只管跨回复的重复（上一步做过、屏幕没变又做一遍）。同一次回复里连着的动作是模型有意的，
            // 比如在计算器上连按两次 6；而且那时手上只有动作之前的截图，比不出屏幕变没变
            if (!labelsValid) {
                val result = execute(action)
                if (!result.isError) actionRepeatGuard.recordExecuted(action, screen.fingerprint)
                return result
            }
            when (val decision = actionRepeatGuard.evaluate(action, screen.fingerprint)) {
                is ActionRepeatGuard.Decision.Block -> {
                    Log.w(TAG, "Step $step action blocked by repeat guard: ${decision.feedback}")
                    return ToolOutcome.Result(decision.feedback, isError = true)
                }
                ActionRepeatGuard.Decision.Allow -> Unit
            }
            val result = execute(action)
            if (!result.isError) actionRepeatGuard.recordExecuted(action, screen.fingerprint)
            return result
        }

        override suspend fun execute(action: Action): ToolOutcome.Result {
            val result = actionExecutor.execute(action, step)
            return ToolOutcome.Result(result.detail, isError = !result.success)
        }

        override fun labelAt(x: Int, y: Int): String? {
            val elements = screen.elements ?: return null
            if (!labelsValid || screenWidth <= 0 || screenHeight <= 0) return null
            val (px, py) = Points.toPixels(x, y, screenWidth, screenHeight)
            return ScreenOutline.labelAt(elements, px, py)
        }

        override fun note(key: String, value: String) {
            // 最多留 [MAX_NOTES] 条，满了丢最早的
            if (notes.size >= MAX_NOTES && key !in notes) notes.remove(notes.keys.first())
            notes[key] = value
            Log.i(TAG, "Step $step note recorded: $key = $value (total: ${notes.size})")
        }

        override suspend fun remember(key: String, value: String, category: String) {
            memoryRepository?.upsert(key = key, value = value, category = category, source = GlobalMemoryEntity.SOURCE_AI)
            Log.i(TAG, "Step $step global memory recorded: $key (${category.ifBlank { "uncategorized" }})")
        }
    }

    private fun normalizedCall(resolution: ToolRegistry.Resolution): ToolCall = when (resolution) {
        is ToolRegistry.Resolution.Ok ->
            resolution.call.copy(name = resolution.tool.name, arguments = resolution.args.toJson())
        is ToolRegistry.Resolution.Invalid -> resolution.call.copy(
            arguments = resolution.call.arguments.takeIf { runCatching { JSONObject(it) }.isSuccess } ?: "{}"
        )
    }

    private fun saveTranscript(messages: List<LlmMessage>) {
        val callback = onTranscript ?: return
        try {
            callback(SessionHistory.snapshot(messages))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to snapshot transcript", e)
        }
    }

    private fun drainUserSupplements(): List<String> {
        synchronized(pendingUserSupplements) {
            if (pendingUserSupplements.isEmpty()) return emptyList()
            val drained = pendingUserSupplements.toList()
            pendingUserSupplements.clear()
            return drained
        }
    }

    private fun formatDebugInfo(reply: LlmReply, calls: List<ToolCall>): String = buildString {
        reply.reasoning?.summary?.let { append("[thinking] ").append(it).append('\n') }
        reply.text?.let { append(it).append('\n') }
        calls.forEach { append(it.name).append(' ').append(it.arguments).append('\n') }
    }.trimEnd().take(2000)

    /**
     * Capture a frame from the virtual display through the privileged backend.
     *
     * Recovery: with no app on the VD there may be no frame at all. After 3 consecutive
     * failures on an empty VD, hand the model a blank frame so it opens an app.
     */
    private suspend fun captureVirtualDisplay(vdm: VirtualDisplayManager, step: Int): String? {
        for (attempt in 1..VD_CAPTURE_MAX_ATTEMPTS) {
            val backendBase64 = withContext(Dispatchers.IO) {
                Log.d(TAG, "Step $step: trying backend capture (displayId=${vdm.displayId}, backend=${ScreenCapture.backend.javaClass.simpleName})")
                ScreenCapture.captureBase64(displayId = vdm.displayId, maxWidth = shotWidth, quality = shotQuality)
            }
            if (backendBase64 != null) {
                Log.d(TAG, "Step $step: captured via backend (${backendBase64.length} chars)")
                if (isBitmapLikelyBlack(backendBase64)) {
                    Log.w(TAG, "Backend capture returned black frame (FLAG_SECURE?) — returning for handoff detection")
                }
                return backendBase64
            }
            Log.w(TAG, "Step $step: backend capture returned null")

            // Recovery: an empty VD shows nothing. Don't launch HOME — a VD without home support
            // can't host it and the system would open the launcher on the phone's own screen.
            if (attempt == VD_CAPTURE_RECOVERY_THRESHOLD) {
                val topTask = withContext(Dispatchers.IO) { vdm.getTopTaskIdOnDisplay(vdm.displayId) }
                if (topTask == null || topTask <= 0) {
                    Log.w(TAG, "VD empty after $attempt capture failures, using a blank frame")
                    val blank = Bitmap.createBitmap(vdm.width.coerceAtLeast(1), vdm.height.coerceAtLeast(1), Bitmap.Config.RGB_565)
                    return ScreenCapture.captureBase64(virtualDisplayBitmap = blank, maxWidth = shotWidth, quality = shotQuality)
                } else {
                    Log.w(TAG, "VD has task $topTask but no new frame — using cached frame")
                }
            } else if (attempt < VD_CAPTURE_MAX_ATTEMPTS) {
                Log.d(TAG, "VD capture failed at step $step (attempt $attempt/$VD_CAPTURE_MAX_ATTEMPTS), waiting ${VD_CAPTURE_RETRY_DELAY_MS}ms...")
                delay(VD_CAPTURE_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "VD capture failed at step $step after $VD_CAPTURE_MAX_ATTEMPTS attempts")
        return null
    }

    /**
     * Strict black check: ALL sampled pixels must be near-black (< 5 per channel).
     * FLAG_SECURE returns pure black; transition animations have status/nav bar colors.
     * Small threshold accounts for JPEG compression artifacts on pure black input.
     */
    private fun isBitmapStrictlyBlack(base64Jpeg: String): Boolean {
        return try {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w == 0 || h == 0) return true
                val sampleCount = 20
                for (i in 0 until sampleCount) {
                    val x = (w * (i + 1)) / (sampleCount + 1)
                    val y = (h * (i + 1)) / (sampleCount + 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r >= 5 || g >= 5 || b >= 5) return false
                }
                true
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) { false }
    }

    /**
     * Decode a base64 JPEG and check if the image is mostly black (90%+ dark pixels).
     * Used to detect FLAG_SECURE screens where screenshots return black frames.
     */
    private fun isBitmapLikelyBlack(base64Jpeg: String): Boolean {
        return try {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            try {
                // Sample 20 pixels across the image to check if it's mostly black
                val w = bitmap.width
                val h = bitmap.height
                var darkPixels = 0
                val sampleCount = 20
                for (i in 0 until sampleCount) {
                    val x = (w * (i + 1)) / (sampleCount + 1)
                    val y = (h * (i + 1)) / (sampleCount + 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r < 15 && g < 15 && b < 15) darkPixels++
                }
                darkPixels >= sampleCount * 0.9  // 90%+ dark pixels = likely black
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) { false }
    }
}
