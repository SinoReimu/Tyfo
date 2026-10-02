package com.shiro.tyfo.ui

import com.shiro.tyfo.db.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {

    private var nextId = 1L

    private fun msg(type: MessageType, text: String) =
        ChatMessageEntity(id = nextId++, conversationId = 1, type = type.name, text = text, timestamp = nextId)

    @Test
    fun `messages map to rows keyed by message id`() {
        val rows = ChatRows.build(listOf(msg(MessageType.USER_INPUT, "a"), msg(MessageType.AGENT_RESULT, "✅ b")), null, null)
        assertEquals(listOf(1L, 2L), rows.map { it.key })
        assertFalse(rows.any { it.live || it.pending })
    }

    @Test
    fun `pending instruction is appended after the history`() {
        val rows = ChatRows.build(listOf(msg(MessageType.AGENT_RESULT, "✅ done")), "next", null)
        assertEquals(ChatRows.KEY_PENDING, rows.last().key)
        assertTrue(rows.last().pending)
        assertEquals(MessageType.USER_INPUT, rows.last().type)
    }

    @Test
    fun `last progress message becomes the live row`() {
        val rows = ChatRows.build(
            listOf(msg(MessageType.USER_INPUT, "go"), msg(MessageType.AGENT_STATUS, "Opening Settings")),
            null,
            "Thinking…",
        )
        assertEquals(2, rows.size)
        assertTrue(rows.last().live)
        assertEquals("Opening Settings", rows.last().text)
        assertEquals(2L, rows.last().key)
    }

    @Test
    fun `live placeholder is added when the last message is not progress`() {
        val rows = ChatRows.build(
            listOf(msg(MessageType.AGENT_QUESTION, "Which one?"), msg(MessageType.USER_ANSWER, "The first")),
            null,
            "Thinking…",
        )
        assertEquals(3, rows.size)
        assertEquals(ChatRows.KEY_LIVE, rows.last().key)
        assertEquals("Thinking…", rows.last().text)
        assertFalse(rows[0].live)
    }

    @Test
    fun `starting a new task shows the pending message and a live placeholder`() {
        val rows = ChatRows.build(emptyList(), "Turn on dark mode", "Starting")
        assertEquals(listOf(ChatRows.KEY_PENDING, ChatRows.KEY_LIVE), rows.map { it.key })
    }

    @Test
    fun `unknown message types fall back to system rows`() {
        val rows = ChatRows.build(listOf(ChatMessageEntity(9, 1, "SOMETHING_OLD", "x", 0)), null, null)
        assertEquals(MessageType.SYSTEM, rows.single().type)
    }

    @Test
    fun `outcome markers are split off result text`() {
        assertEquals(ChatRows.Outcome.SUCCESS to "Sunny, 24°C", ChatRows.splitOutcome("✅ Sunny, 24°C"))
        assertEquals(ChatRows.Outcome.FAILURE to "Task failed: no network", ChatRows.splitOutcome("❌ Task failed: no network"))
        assertEquals(ChatRows.Outcome.WARNING to "Max steps", ChatRows.splitOutcome("⚠️ Max steps"))
        assertEquals(ChatRows.Outcome.NONE to "plain", ChatRows.splitOutcome("plain"))
    }
}
