package com.shiro.tyfo.engine

import com.shiro.tyfo.db.ChatMessageEntity
import com.shiro.tyfo.ui.MessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ConversationContextTest {

    private lateinit var savedLocale: Locale
    private var nextId = 1L

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    private fun msg(type: MessageType, text: String) =
        ChatMessageEntity(id = nextId, conversationId = 1, type = type.name, text = text, timestamp = nextId++)

    @Test
    fun `empty history gives no context`() {
        assertNull(ConversationContext.build(emptyList()))
    }

    @Test
    fun `turns keep instruction, question, answer and result but not step thoughts`() {
        val context = ConversationContext.build(
            listOf(
                msg(MessageType.USER_INPUT, "Check the weather"),
                msg(MessageType.AGENT_QUESTION, "Which city?"),
                msg(MessageType.USER_ANSWER, "Beijing"),
                msg(MessageType.USER_SUPPLEMENT, "in Celsius"),
                msg(MessageType.AGENT_DEBUG, "[LLM] raw"),
                msg(MessageType.AGENT_RESULT, "✅ Sunny, 24°C"),
            )
        )!!
        assertEquals(
            "User: Check the weather\nYou asked: Which city?\nUser answered: Beijing\n" +
                "User added: in Celsius\nResult: Sunny, 24°C",
            context,
        )
    }

    @Test
    fun `a stopped turn keeps its last progress and the stop note`() {
        val context = ConversationContext.build(
            listOf(
                msg(MessageType.USER_INPUT, "Order coffee"),
                msg(MessageType.AGENT_STATUS, "Opening the coffee app"),
                msg(MessageType.SYSTEM, "Stopped"),
                msg(MessageType.USER_INPUT, "Check mail"),
                msg(MessageType.AGENT_RESULT, "✅ 3 unread"),
            )
        )!!
        assertTrue(context.contains("User: Order coffee\nEnded without a result: Stopped"))
        assertFalse(context.contains("Opening the coffee app"))
        assertTrue(context.endsWith("User: Check mail\nResult: 3 unread"))
    }

    @Test
    fun `a turn without any outcome keeps its last progress`() {
        val context = ConversationContext.build(
            listOf(
                msg(MessageType.USER_INPUT, "Order coffee"),
                msg(MessageType.AGENT_STATUS, "step 1"),
                msg(MessageType.AGENT_STATUS, "Opening the coffee app"),
            )
        )!!
        assertEquals("User: Order coffee\nLast progress (no result): Opening the coffee app", context)
    }

    @Test
    fun `long histories drop the oldest turns first`() {
        val messages = (1..40).flatMap { i ->
            listOf(
                msg(MessageType.USER_INPUT, "Task $i " + "x".repeat(200)),
                msg(MessageType.AGENT_RESULT, "✅ Result $i"),
            )
        }
        val context = ConversationContext.build(messages)!!
        assertTrue(context.length <= 6100)
        assertTrue(context.startsWith("(Earlier turns omitted.)"))
        assertTrue(context.endsWith("Result: Result 40"))
        assertFalse(context.contains("Task 1 "))
    }
}
