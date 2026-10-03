package com.shiro.tyfo.engine

import com.shiro.tyfo.llm.LlmMessage
import com.shiro.tyfo.llm.LlmMessage.Part
import com.shiro.tyfo.llm.Reasoning
import com.shiro.tyfo.llm.ToolCall
import com.shiro.tyfo.llm.images
import com.shiro.tyfo.llm.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {

    private fun call(id: String, name: String = "tap") = ToolCall(id, name, """{"point":[1,2]}""")
    private fun assistant(vararg calls: ToolCall) = LlmMessage.Assistant("doing it", calls.toList())
    private fun result(id: String, text: String = "ok", name: String = "tap") = LlmMessage.ToolResult(id, name, text)

    @Test
    fun `snapshot drops the system prompt and every screenshot`() {
        val snapshot = SessionHistory.snapshot(
            listOf(
                LlmMessage.System("SYSTEM"),
                LlmMessage.User(listOf(Part.Text("Task: check the weather"), Part.Image("AAAA"))),
                assistant(call("c1")),
                LlmMessage.ToolResult("c1", "tap", listOf(Part.Text("Tapped [1, 2]"), Part.Image("BBBB"))),
            )
        )
        assertEquals(3, snapshot.size)
        assertEquals("Task: check the weather", (snapshot[0] as LlmMessage.User).text)
        val tool = snapshot[2] as LlmMessage.ToolResult
        assertEquals("Tapped [1, 2]", tool.text)
        assertTrue(tool.images.isEmpty())
    }

    @Test
    fun `a message that was only a screenshot keeps a placeholder`() {
        val snapshot = SessionHistory.snapshot(listOf(LlmMessage.User(listOf(Part.Image("AAAA")))))
        assertEquals(AgentPrompts.SCREENSHOT_OMITTED, (snapshot.single() as LlmMessage.User).text)
    }

    /** 一个长任务：只有开头一条用户消息（任务指令），后面全是工具调用和结果。 */
    private fun longTask(steps: Int) = listOf(LlmMessage.User("Task: finish today's Duolingo")) +
        (1..steps).flatMap { listOf(assistant(call("c$it")), result("c$it")) }

    @Test
    fun `a long task is stored in full`() {
        val task = longTask(steps = 500)
        assertEquals(task, SessionHistory.snapshot(task))
    }

    @Test
    fun `continuing after a long task sends its instruction and last steps`() {
        val restored = SessionHistory.forContinuation(longTask(steps = 200)).restored
        assertTrue(restored.size <= 61)
        assertEquals(LlmMessage.User("Task: finish today's Duolingo"), restored.first())
        assertTrue(restored[1] is LlmMessage.Assistant)
        assertEquals(result("c200"), restored.last())
    }

    @Test
    fun `saving a continued long task keeps the whole earlier history`() {
        val stored = longTask(steps = 200)
        val continuation = SessionHistory.forContinuation(stored)
        val newTurn = listOf(LlmMessage.User("Task: one more lesson"), assistant(call("d1")), result("d1"))
        // 引擎的记录 = 恢复的部分 + 这次的新消息
        val saved = continuation.merge(continuation.restored + newTurn)
        assertEquals(stored + newTurn, saved)
    }

    @Test
    fun `saving a continued short session keeps everything once`() {
        val stored = listOf(LlmMessage.User("u1"), assistant(call("c1")), result("c1"))
        val continuation = SessionHistory.forContinuation(stored)
        assertEquals(stored, continuation.restored)
        val newTurn = listOf(LlmMessage.User("u2"), assistant(call("c2")), result("c2"))
        assertEquals(stored + newTurn, continuation.merge(continuation.restored + newTurn))
    }

    @Test
    fun `continuation starts at a user message and drops a trailing unanswered user message`() {
        val history = listOf(
            result("c0"),
            assistant(call("c1")),
            result("c1"),
            LlmMessage.User("u2"),
            assistant(call("c2")),
            result("c2"),
            LlmMessage.User("u3 (stopped before the reply)"),
        )
        val restored = SessionHistory.forContinuation(history).restored
        assertEquals(listOf(LlmMessage.User("u2"), assistant(call("c2")), result("c2")), restored)
    }

    @Test
    fun `calls left without a result get a not-run result`() {
        // Stopped while waiting for the answer to ask_user
        val history = listOf(
            LlmMessage.User("u1"),
            assistant(call("c1", "note"), call("c2", "ask_user")),
            result("c1", "Noted.", "note"),
        )
        val restored = SessionHistory.forContinuation(history).restored
        assertEquals(4, restored.size)
        val filled = restored.last() as LlmMessage.ToolResult
        assertEquals("c2", filled.callId)
        assertEquals("ask_user", filled.name)
        assertTrue(filled.isError)
    }

    @Test
    fun `orphan tool results are dropped`() {
        val restored = SessionHistory.forContinuation(
            listOf(LlmMessage.User("u1"), LlmMessage.Assistant("thinking"), result("nobody"))
        ).restored
        assertEquals(listOf(LlmMessage.User("u1"), LlmMessage.Assistant("thinking")), restored)
    }

    @Test
    fun `continuation keeps only the most recent messages`() {
        val history = (1..200).flatMap { i ->
            listOf(LlmMessage.User("u$i"), assistant(call("c$i")), result("c$i"))
        }
        val restored = SessionHistory.forContinuation(history).restored
        assertTrue(restored.size <= 60)
        assertEquals(result("c200"), restored.last())
        assertTrue(restored.first() is LlmMessage.User)
    }

    @Test
    fun `encode and decode round trip, reasoning included`() {
        val history = listOf(
            LlmMessage.User("Task: line1\n\"quoted\" 中文"),
            LlmMessage.Assistant(
                "let me look", listOf(call("c1"), call("c2", "note")),
                Reasoning(summary = "the button is top left", opaque = "ENCRYPTED=="),
            ),
            result("c1"),
            LlmMessage.ToolResult("c2", "note", "bad args", isError = true),
            LlmMessage.Assistant(null, reasoning = Reasoning(null, "ONLY-OPAQUE")),
            LlmMessage.Assistant(null),
        )
        assertEquals(history, SessionHistory.decode(SessionHistory.encode(history)))
    }

    @Test
    fun `transcripts saved before tool calling and garbage decode to nothing`() {
        val legacy = """[{"role":"user","content":"u1"},{"role":"assistant","content":"{\"action_type\":\"finish\"}"}]"""
        assertEquals(emptyList<LlmMessage>(), SessionHistory.decode(legacy))
        assertEquals(emptyList<LlmMessage>(), SessionHistory.decode("not json"))
    }
}
