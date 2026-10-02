package com.shiro.tyfo.engine

import com.shiro.tyfo.db.GlobalMemoryEntity
import com.shiro.tyfo.tools.Points
import com.shiro.tyfo.tools.PhoneTools
import com.shiro.tyfo.tools.ToolKind
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgentPromptsTest {

    private val tools = PhoneTools().all()

    private fun memory(key: String, value: String, category: String = "") =
        GlobalMemoryEntity(key = key, value = value, category = category, createdAt = 0, updatedAt = 0)

    private fun prompt(
        environment: AgentPrompts.Environment = AgentPrompts.Environment(),
        memories: List<GlobalMemoryEntity> = emptyList(),
        summary: String? = null,
    ) = AgentPrompts.systemPrompt(environment, memories, summary)

    // ── Rules ───────────────────────────────────────────────────────────────

    @Test
    fun `system prompt asks to write in the user's language`() {
        assertTrue(prompt().contains("in the language of the user's instruction"))
    }

    @Test
    fun `system prompt asks for a progress sentence before acting`() {
        assertTrue(prompt().contains("Start every reply with one short sentence"))
    }

    @Test
    fun `system prompt explains the 0-1000 points and the outline`() {
        assertTrue(prompt().contains(Points.DESCRIPTION))
        assertTrue(prompt().contains("(x,y) is the element's tap center"))
    }

    @Test
    fun `every tool name in the prompt exists`() {
        val names = tools.map { it.name }.toSet()
        val mentioned = Regex("\\b[a-z]+(?:_[a-z]+)+\\b").findAll(prompt()).map { it.value }.toSet()
        assertTrue("no tool names found", mentioned.isNotEmpty())
        for (name in mentioned) assertTrue("prompt mentions unknown tool $name", name in names)
    }

    @Test
    fun `system prompt covers the tools its rules depend on`() {
        val text = prompt()
        val required = tools.filter { it.kind == ToolKind.CONTROL }.map { it.name } +
            listOf("open_app", "list_apps", "type_text", "note", "remember")
        for (name in required) {
            assertTrue("system prompt should mention $name", Regex("\\b$name\\b").containsMatchIn(text))
        }
    }

    @Test
    fun `system prompt doesn't change between calls with the same input`() {
        // The prefix cache depends on it
        val now = ZonedDateTime.of(2026, 10, 2, 10, 52, 0, 0, ZoneId.of("Asia/Shanghai"))
        val env = AgentPrompts.Environment("Google Pixel 7 Pro", "17", 1080, 2340, now)
        assertEquals(prompt(env), prompt(env))
    }

    // ── Environment ─────────────────────────────────────────────────────────

    @Test
    fun `environment lines appear only when given`() {
        assertFalse(prompt().contains("# Environment"))

        val now = ZonedDateTime.of(2026, 10, 2, 10, 52, 0, 0, ZoneId.of("America/Los_Angeles"))
        val text = prompt(AgentPrompts.Environment("Google Pixel 7 Pro", "17", 1080, 2340, now))
        assertTrue(text.contains("# Environment"))
        assertTrue(text.contains("- Phone: Google Pixel 7 Pro, Android 17"))
        assertTrue(text.contains("- Screen: 1080x2340 pixels"))
        assertTrue(text, text.contains("- Now: 2026-10-02 Friday 10:52 (America/Los_Angeles)"))
    }

    @Test
    fun `partial environment shows only what's known`() {
        val text = prompt(AgentPrompts.Environment(android = "17"))
        assertTrue(text.contains("- Phone: Android 17"))
        assertFalse(text.contains("Screen:"))
        assertFalse(text.contains("Now:"))
    }

    // ── Memories and conversation summary ──────────────────────────────────

    @Test
    fun `memories are listed only when there are some`() {
        assertFalse(prompt().contains("What you know about the user"))
        val text = prompt(memories = listOf(memory("default city", "上海", "profile"), memory("diet", "vegetarian")))
        assertTrue(text.contains("What you know about the user:"))
        assertTrue(text.contains("- [profile] default city: 上海"))
        assertTrue(text.contains("- diet: vegetarian"))
    }

    @Test
    fun `conversation summary is added only when given`() {
        assertFalse(prompt().contains("# Earlier in this conversation"))
        assertFalse(prompt(summary = "  ").contains("# Earlier in this conversation"))
        val text = prompt(summary = "User: 查天气\nResult: 晴")
        assertTrue(text.contains("# Earlier in this conversation"))
        assertTrue(text.endsWith("User: 查天气\nResult: 晴"))
    }

    // ── Message helpers ─────────────────────────────────────────────────────

    @Test
    fun `message helpers format their text`() {
        assertEquals("Task: 打开设置", AgentPrompts.task("打开设置"))
        assertEquals("The user added while you were working:\n- 用摄氏度\n- 要明天的", AgentPrompts.userAdded(listOf("用摄氏度", "要明天的")))
        assertEquals("The user answered: 上海", AgentPrompts.userAnswered("上海"))
        val returned = AgentPrompts.handoffReturned("user_returned_control")
        assertTrue(returned.contains("(user_returned_control)"))
        assertTrue(returned.contains("handoff_user"))
    }

    @Test
    fun `guard feedback still points to ask_user`() {
        assertTrue(AgentPrompts.ESCALATION_LOW.contains("ask_user"))
        assertTrue(AgentPrompts.ESCALATION_HIGH.contains("ask_user"))
    }
}
