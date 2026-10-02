package com.shiro.tyfo.engine

import com.shiro.tyfo.action.ActionExecutor
import com.shiro.tyfo.capture.ScreenCapture
import com.shiro.tyfo.fixtures.FakeModelClient
import com.shiro.tyfo.fixtures.buildAskUserResponse
import com.shiro.tyfo.fixtures.buildFinishResponse
import com.shiro.tyfo.fixtures.buildTapResponse
import com.shiro.tyfo.llm.LlmMessage
import com.shiro.tyfo.llm.images
import com.shiro.tyfo.llm.text
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 一段会话两轮任务，用真的 AgentEngine + 假模型核对发给模型的内容：
 * 第一轮中途的补充、提问后的回答都进了消息历史；第二轮带着第一轮的完整记录。
 */
class SessionContinuityTest {

    @Before
    fun setUp() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any(), any(), any()) } returns "/9j/4AAQSkZJRg=="
    }

    @After
    fun tearDown() {
        unmockkObject(ScreenCapture)
    }

    private fun executor(): ActionExecutor = mockk<ActionExecutor>(relaxed = true).also {
        coEvery { it.execute(any(), any()) } answers {
            StepResult(secondArg(), "tap", "", true, "ok", 0, false)
        }
    }

    private fun textOf(message: LlmMessage): String = when (message) {
        is LlmMessage.System -> message.text
        is LlmMessage.User -> message.text
        is LlmMessage.Assistant -> message.text.orEmpty() + message.toolCalls.joinToString { it.name + it.arguments }
        is LlmMessage.ToolResult -> message.text
    }

    /** 每个工具调用后面紧跟它的结果，结果前面一定有对应的调用。 */
    private fun assertWellFormed(messages: List<LlmMessage>) {
        var pending = emptySet<String>()
        for (m in messages) {
            when (m) {
                is LlmMessage.ToolResult -> {
                    assertTrue("result ${m.callId} without its call", m.callId in pending)
                    pending = pending - m.callId
                }
                else -> {
                    assertTrue("calls $pending have no result", pending.isEmpty())
                    if (m is LlmMessage.Assistant) pending = m.toolCalls.map { it.id }.toSet()
                }
            }
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out")
            delay(20)
        }
    }

    @Test
    fun `supplement, answer and the whole first turn reach the model in the second turn`() = runBlocking {
        // ── 第一轮：点一下 → 问城市 → 完成；第一步之后补充“用摄氏度”，被问时回答“上海”
        val llm1 = FakeModelClient()
        llm1.enqueue(
            buildTapResponse(500, 500, narration = "打开天气"),
            buildAskUserResponse("你想查哪个城市？"),
            buildFinishResponse("上海今天晴，24°C"),
        )
        var transcript: List<LlmMessage> = emptyList()
        val engine1 = AgentEngine(llm1, executor(), maxSteps = 10, onTranscript = { transcript = it })
        engine1.start("查一下天气")
        waitFor { llm1.requestHistory.size >= 1 }
        engine1.submitUserSupplement("用摄氏度")
        waitFor { engine1.state.value is AgentState.WaitingForUser }
        engine1.submitUserResponse("上海")
        waitFor { (engine1.state.value as? AgentState.Idle)?.resultMessage != null }
        waitFor { (transcript.lastOrNull() as? LlmMessage.ToolResult)?.name == "finish" }

        assertEquals(3, llm1.requestHistory.size)
        // 点完之后的截图附在 tap 的结果里，补充是随后的一条用户消息
        val second = llm1.requestHistory[1]
        val tapResult = second[second.size - 2] as LlmMessage.ToolResult
        assertEquals("tap", tapResult.name)
        assertEquals(1, tapResult.images.size)
        assertTrue("补充进了第二步", textOf(second.last()).contains("用摄氏度"))
        // 回答是 ask_user 的工具结果，新截图附在里面
        val answer = llm1.requestHistory[2].last() as LlmMessage.ToolResult
        assertEquals("ask_user", answer.name)
        assertTrue(answer.text.startsWith(AgentPrompts.userAnswered("上海")))
        assertEquals(1, answer.images.size)
        llm1.requestHistory.forEach(::assertWellFormed)

        // 存下来的记录：任务、补充，三次调用和结果，没有截图
        assertEquals(8, transcript.size)
        assertWellFormed(transcript)
        val saved = transcript.joinToString("\n") { textOf(it) }
        listOf("查一下天气", "用摄氏度", "上海", "你想查哪个城市？", "打开天气", "上海今天晴").forEach {
            assertTrue("记录里有 $it", saved.contains(it))
        }
        assertFalse(transcript.any { m ->
            (m is LlmMessage.User && m.images.isNotEmpty()) || (m is LlmMessage.ToolResult && m.images.isNotEmpty())
        })

        // ── 第二轮：接着这段会话说“那明天呢”
        val llm2 = FakeModelClient()
        llm2.enqueue(buildFinishResponse("上海明天多云"))
        val engine2 = AgentEngine(llm2, executor(), maxSteps = 10)
        engine2.start(
            "那明天呢",
            conversationContext = "User: 查一下天气\nResult: 上海今天晴，24°C",
            history = SessionHistory.forContinuation(SessionHistory.decode(SessionHistory.encode(transcript))),
        )
        waitFor { (engine2.state.value as? AgentState.Idle)?.resultMessage != null }

        val request = llm2.requestHistory.single()
        assertWellFormed(request)
        assertTrue("摘要在系统提示词里", textOf(request.first()).contains("# Earlier in this conversation"))
        // 第一轮的 8 条原样接在系统提示词后面，最后是这一轮的新指令（带截图）
        assertEquals(1 + 8 + 1, request.size)
        assertEquals(transcript, request.subList(1, 9))
        val last = request.last() as LlmMessage.User
        assertTrue(last.text.startsWith(AgentPrompts.task("那明天呢")))
        assertEquals(1, last.images.size)
    }
}
