package com.shiro.tyfo.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SessionHistoryTest {

    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    private fun f(key: String) = LlmPrompts.agentFeedback(key)

    private fun userStep(text: String): Map<String, Any> = mapOf(
        "role" to "user",
        "content" to listOf(
            mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,AAAA")),
            mapOf("type" to "text", "text" to text),
        ),
    )

    @Test
    fun `snapshot drops the system prompt, screenshots, UI elements and global memory`() {
        val text = "Check the weather" +
            f("ui_elements_header") + "[1] Button 'OK' (0,0)-(10,10)" +
            f("user_supplement_header") + "- in Celsius" +
            f("global_memory_header") + "- city: Beijing" +
            f("notes_header") + "- temp: 24"
        val snapshot = SessionHistory.snapshot(
            listOf(
                mapOf("role" to "system", "content" to "SYSTEM"),
                userStep(text),
                mapOf("role" to "assistant", "content" to """{"action_type":"finish"}"""),
            )
        )
        assertEquals(listOf("user", "assistant"), snapshot.map { it["role"] })
        val user = snapshot[0]["content"]!!
        assertTrue(user.startsWith("Check the weather"))
        assertTrue(user.contains("- in Celsius"))
        assertTrue(user.contains("- temp: 24"))
        assertFalse(user.contains("Button 'OK'"))
        assertFalse(user.contains("city: Beijing"))
        assertFalse(user.contains("base64"))
    }

    @Test
    fun `plain string content (older steps) is kept as is`() {
        val snapshot = SessionHistory.snapshot(listOf(mapOf("role" to "user", "content" to "older step")))
        assertEquals("older step", snapshot.single()["content"])
    }

    @Test
    fun `continuation starts with a user message and ends with the model's reply`() {
        val history = listOf(
            mapOf("role" to "assistant", "content" to "a0"),
            mapOf("role" to "user", "content" to "u1"),
            mapOf("role" to "assistant", "content" to "a1"),
            mapOf("role" to "user", "content" to "u2 (stopped before the reply)"),
        )
        val restored = SessionHistory.forContinuation(history)
        assertEquals(listOf("u1", "a1"), restored.map { it["content"] })
    }

    @Test
    fun `continuation keeps only the most recent messages`() {
        val history = (1..200).flatMap { i ->
            listOf(mapOf("role" to "user", "content" to "u$i"), mapOf("role" to "assistant", "content" to "a$i"))
        }
        val restored = SessionHistory.forContinuation(history)
        assertTrue(restored.size <= 60)
        assertEquals("a200", restored.last()["content"])
        assertEquals("user", restored.first()["role"])
    }

    @Test
    fun `encode and decode round trip`() {
        val history = listOf(
            mapOf("role" to "user", "content" to "line1\n\"quoted\" 中文"),
            mapOf("role" to "assistant", "content" to """{"action_type":"finish","thought":"ok"}"""),
        )
        assertEquals(history, SessionHistory.decode(SessionHistory.encode(history)))
        assertEquals(emptyList<Map<String, String>>(), SessionHistory.decode("not json"))
    }
}
