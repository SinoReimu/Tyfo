package com.shiro.tyfo.engine

import com.shiro.tyfo.action.ActionExecutor
import com.shiro.tyfo.capture.ScreenCapture
import com.shiro.tyfo.fixtures.FakeLlmClient
import com.shiro.tyfo.fixtures.buildAskUserResponse
import com.shiro.tyfo.fixtures.buildFinishResponse
import com.shiro.tyfo.fixtures.buildTapResponse
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
import java.util.Locale

/**
 * 一段会话两轮任务，用真的 AgentEngine + 假模型核对发给模型的内容：
 * 第一轮中途的补充、提问后的回答都进了消息历史；第二轮带着第一轮的完整记录。
 */
class SessionContinuityTest {

    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
    }

    @After
    fun tearDown() {
        unmockkObject(ScreenCapture)
        Locale.setDefault(savedLocale)
    }

    private fun executor(): ActionExecutor = mockk<ActionExecutor>(relaxed = true).also {
        coEvery { it.execute(any(), any()) } answers {
            StepResult(secondArg(), "tap", "", true, "ok", 0, false)
        }
    }

    /** 一条消息的文字（截图之外的部分）。 */
    @Suppress("UNCHECKED_CAST")
    private fun textOf(message: Map<String, Any>): String = when (val c = message["content"]) {
        is String -> c
        is List<*> -> (c as List<Map<String, Any>>).filter { it["type"] == "text" }.joinToString { it["text"] as String }
        else -> ""
    }

    private fun assertAlternates(messages: List<Map<String, Any>>) {
        val roles = messages.drop(1).map { it["role"] }
        roles.zipWithNext().forEach { (a, b) -> assertFalse("two $a messages in a row: $roles", a == b) }
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
        val llm1 = FakeLlmClient()
        llm1.enqueue(
            buildTapResponse(500, 500, thought = "打开天气"),
            buildAskUserResponse("你想查哪个城市？"),
            buildFinishResponse("上海今天晴，24°C"),
        )
        var transcript: List<Map<String, String>> = emptyList()
        val engine1 = AgentEngine(llm1, executor(), maxSteps = 10, onTranscript = { transcript = it })
        engine1.start("查一下天气")
        waitFor { llm1.requestHistory.size >= 1 }
        engine1.submitUserSupplement("用摄氏度")
        waitFor { engine1.state.value is AgentState.WaitingForUser }
        engine1.submitUserResponse("上海")
        waitFor { (engine1.state.value as? AgentState.Idle)?.resultMessage != null }
        waitFor { transcript.lastOrNull()?.get("role") == "assistant" && transcript.last()["content"]!!.contains("finish") }

        assertEquals(3, llm1.requestHistory.size)
        assertTrue("补充进了第二步", textOf(llm1.requestHistory[1].last()).contains("用摄氏度"))
        assertTrue("回答进了第三步", textOf(llm1.requestHistory[2].last()).contains("用户回答：上海"))
        llm1.requestHistory.forEach(::assertAlternates)

        // 存下来的记录：三步的用户消息 + 三条模型回复，没有截图
        assertEquals(listOf("user", "assistant", "user", "assistant", "user", "assistant"), transcript.map { it["role"] })
        val saved = transcript.joinToString("\n") { it["content"]!! }
        listOf("查一下天气", "用摄氏度", "上海", "你想查哪个城市？", "打开天气", "上海今天晴").forEach {
            assertTrue("记录里有 $it", saved.contains(it))
        }
        assertFalse(saved.contains("base64"))

        // ── 第二轮：接着这段会话说“那明天呢”
        val llm2 = FakeLlmClient()
        llm2.enqueue(buildFinishResponse("上海明天多云"))
        val engine2 = AgentEngine(llm2, executor(), maxSteps = 10)
        engine2.start(
            "那明天呢",
            conversationContext = "用户：查一下天气\n结果：上海今天晴，24°C",
            history = SessionHistory.forContinuation(SessionHistory.decode(SessionHistory.encode(transcript))),
        )
        waitFor { (engine2.state.value as? AgentState.Idle)?.resultMessage != null }

        val request = llm2.requestHistory.single()
        assertAlternates(request)
        assertTrue("摘要在系统提示词里", textOf(request.first()).contains("### 之前的对话 ###"))
        // 第一轮的 6 条原样接在系统提示词后面，最后是这一轮的新指令（带截图）
        assertEquals(1 + 6 + 1, request.size)
        assertEquals(transcript.map { it["content"] }, request.subList(1, 7).map { textOf(it) })
        assertTrue(textOf(request.last()).startsWith("那明天呢"))
        assertTrue(request.last()["content"] is List<*>)
    }
}
