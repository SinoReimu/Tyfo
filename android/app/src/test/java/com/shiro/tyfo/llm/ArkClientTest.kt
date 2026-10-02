package com.shiro.tyfo.llm

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Tests for the Ark chat/completions adapter: message conversion, the request, and the SSE stream. */
class ArkClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ArkClient

    private val tapSpec = ToolSpec(
        "tap", "Tap a point.",
        listOf(ToolParam.Point("point", "Where to tap.", 1000, 1000))
    )

    private val messages = listOf(
        LlmMessage.System("You operate a phone."),
        LlmMessage.User("Task: open settings"),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = newClient()
    }

    @After
    fun tearDown() {
        client.shutdown()
        server.shutdown()
    }

    private fun newClient(thinking: Boolean = true) = ArkClient(
        apiKey = "test-key",
        model = "test-model",
        baseUrl = server.url("/v1").toString(),
        thinking = thinking,
    )

    // --- SSE helpers ---

    private fun chunk(delta: JSONObject, finishReason: String? = null): JSONObject = JSONObject().put(
        "choices", JSONArray().put(
            JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finishReason ?: JSONObject.NULL)
        )
    )

    private fun callDelta(index: Int, id: String? = null, name: String? = null, args: String? = null): JSONObject {
        val function = JSONObject()
        if (name != null) function.put("name", name)
        if (args != null) function.put("arguments", args)
        val call = JSONObject().put("index", index).put("type", "function").put("function", function)
        if (id != null) call.put("id", id)
        return JSONObject().put("tool_calls", JSONArray().put(call))
    }

    private fun usageChunk(prompt: Int = 100, cached: Int = 80) = JSONObject()
        .put("choices", JSONArray())
        .put(
            "usage", JSONObject()
                .put("prompt_tokens", prompt)
                .put("completion_tokens", 10)
                .put("prompt_tokens_details", JSONObject().put("cached_tokens", cached))
        )

    private fun sse(vararg events: JSONObject) =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun simpleCallResponse() = sse(
        chunk(callDelta(0, id = "call_1", name = "tap", args = """{"point":[1,2]}""")),
        chunk(JSONObject(), finishReason = "tool_calls"),
    )

    private fun requestBody(): JSONObject = JSONObject(server.takeRequest().body.readUtf8())

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    // --- profile ---

    @Test
    fun `profile declares ark`() {
        assertEquals("ark", client.profile.provider)
        assertEquals(ArkClient.PROVIDER, client.profile.provider)
        assertEquals("test-model", client.profile.model)
        assertTrue(client.profile.keepScreenshots >= 1)
        assertTrue(client.profile.pruneScreenshotsEvery >= 1)
    }

    // --- request ---

    @Test
    fun `request carries model, tools, tool_choice, thinking and streaming options`() = runBlocking {
        server.enqueue(simpleCallResponse())

        client.chat(messages, listOf(tapSpec))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        assertEquals("required", body.getString("tool_choice"))
        assertTrue(body.getInt("max_tokens") > 0)
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertTrue(tapSpec.toOpenAi().similar(tools.getJSONObject(0)))
        val sent = body.getJSONArray("messages")
        assertEquals(2, sent.length())
        assertEquals("system", sent.getJSONObject(0).getString("role"))
        assertEquals("Task: open settings", sent.getJSONObject(1).getString("content"))
    }

    @Test
    fun `thinking can be turned off`() = runBlocking {
        client.shutdown()
        client = newClient(thinking = false)
        server.enqueue(simpleCallResponse())

        client.chat(messages, listOf(tapSpec))

        assertEquals("disabled", requestBody().getJSONObject("thinking").getString("type"))
    }

    // --- message conversion ---

    @Test
    fun `system and text-only user messages become plain strings`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.System("sys"),
                LlmMessage.User(listOf(LlmMessage.Part.Text("a"), LlmMessage.Part.Text("b"))),
            )
        )
        assertEquals("system", array.getJSONObject(0).getString("role"))
        assertEquals("sys", array.getJSONObject(0).getString("content"))
        assertEquals("user", array.getJSONObject(1).getString("role"))
        assertEquals("a\nb", array.getJSONObject(1).getString("content"))
    }

    @Test
    fun `user message with a screenshot becomes content parts with a data URI`() {
        val json = ArkClient.toJson(
            listOf(LlmMessage.User(listOf(LlmMessage.Part.Text("Task: x"), LlmMessage.Part.Image("QUJD"))))
        ).getJSONObject(0)
        val parts = json.getJSONArray("content")
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("Task: x", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `assistant tool calls use the OpenAI shape and echo the reasoning`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(
                    "打开设置",
                    listOf(ToolCall("call_a", "tap", """{"point":[1,2]}""")),
                    Reasoning(summary = "用户要打开设置", opaque = "ENCRYPTED=="),
                ),
            )
        )
        val assistant = array.getJSONObject(0)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("打开设置", assistant.getString("content"))
        assertEquals("用户要打开设置", assistant.getString("reasoning_content"))
        assertEquals("ENCRYPTED==", assistant.getString("encrypted_content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_a", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertEquals("tap", call.getJSONObject("function").getString("name"))
        assertEquals("""{"point":[1,2]}""", call.getJSONObject("function").getString("arguments"))
    }

    @Test
    fun `assistant without reasoning or calls has neither key`() {
        val plain = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(null),
                LlmMessage.Assistant("plain reply", reasoning = Reasoning(summary = "only a summary", opaque = null)),
            )
        )
        val empty = plain.getJSONObject(0)
        assertEquals("", empty.getString("content"))
        assertFalse(empty.has("tool_calls"))
        assertFalse(empty.has("reasoning_content"))
        assertFalse(empty.has("encrypted_content"))
        val summaryOnly = plain.getJSONObject(1)
        assertEquals("only a summary", summaryOnly.getString("reasoning_content"))
        assertFalse(summaryOnly.has("encrypted_content"))
    }

    @Test
    fun `tool results are strings, with an Error prefix when they failed`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(null, listOf(ToolCall("a", "note", "{}"), ToolCall("b", "swipe", "{}"))),
                LlmMessage.ToolResult("a", "note", "Noted."),
                LlmMessage.ToolResult("b", "swipe", "missing from", isError = true),
            )
        )
        val ok = array.getJSONObject(1)
        assertEquals("tool", ok.getString("role"))
        assertEquals("a", ok.getString("tool_call_id"))
        assertEquals("Noted.", ok.getString("content"))
        assertEquals("Error: missing from", array.getJSONObject(2).getString("content"))
        assertEquals(3, array.length())
    }

    @Test
    fun `screenshots in tool results move to one user message after the group`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(null, listOf(ToolCall("a", "tap", "{}"), ToolCall("b", "type_text", "{}"))),
                LlmMessage.ToolResult("a", "tap", "Tapped [1, 2]"),
                LlmMessage.ToolResult(
                    "b", "type_text",
                    listOf(LlmMessage.Part.Text("Typed \"hi\""), LlmMessage.Part.Text("The screen looks the same."), LlmMessage.Part.Image("SU1H")),
                ),
                LlmMessage.Assistant("next", listOf(ToolCall("c", "wait", "{}"))),
            )
        ).objects()

        assertEquals(listOf("assistant", "tool", "tool", "user", "assistant"), array.map { it.getString("role") })
        // The tool message keeps the text only
        assertEquals("Typed \"hi\"\nThe screen looks the same.", array[2].getString("content"))
        val parts = array[3].getJSONArray("content").objects()
        assertEquals(2, parts.size)
        assertEquals("Screen after type_text:", parts[0].getString("text"))
        assertEquals("data:image/jpeg;base64,SU1H", parts[1].getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `a result that is only a screenshot gets placeholder text`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(null, listOf(ToolCall("a", "wait", "{}"))),
                LlmMessage.ToolResult("a", "wait", listOf(LlmMessage.Part.Image("SU1H"))),
            )
        ).objects()
        assertTrue(array[1].getString("content").startsWith("Done"))
        // A group at the very end still gets its screenshot message
        assertEquals("user", array.last().getString("role"))
        assertEquals("Screen after wait:", array.last().getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `a user message right after the group is merged into the screenshot message`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(null, listOf(ToolCall("a", "tap", "{}"))),
                LlmMessage.ToolResult("a", "tap", listOf(LlmMessage.Part.Text("Tapped"), LlmMessage.Part.Image("SU1H"))),
                LlmMessage.User("The user added while you were working:\n- use Celsius"),
            )
        ).objects()

        assertEquals(listOf("assistant", "tool", "user"), array.map { it.getString("role") })
        val parts = array[2].getJSONArray("content").objects()
        assertEquals(listOf("text", "image_url", "text"), parts.map { it.getString("type") })
        assertEquals("Screen after tap:", parts[0].getString("text"))
        assertTrue(parts[2].getString("text").contains("use Celsius"))
    }

    @Test
    fun `the outline travels with its screenshot and stays out of the tool message`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(null, listOf(ToolCall("a", "tap", "{}"))),
                LlmMessage.ToolResult(
                    "a", "tap",
                    listOf(
                        LlmMessage.Part.Text("Tapped"),
                        LlmMessage.Part.Image("SU1H"),
                        LlmMessage.Part.Text("UI outline:\nButton \"OK\" (500,900) 100x50", screenState = true),
                    ),
                ),
            )
        ).objects()
        assertEquals("Tapped", array[1].getString("content"))
        val parts = array[2].getJSONArray("content").objects()
        assertEquals(listOf("text", "image_url", "text"), parts.map { it.getString("type") })
        assertTrue(parts[2].getString("text").startsWith("UI outline:"))
    }

    @Test
    fun `screenshots from several results are all collected`() {
        val array = ArkClient.toJson(
            listOf(
                LlmMessage.Assistant(null, listOf(ToolCall("a", "ask_user", "{}"), ToolCall("b", "tap", "{}"))),
                LlmMessage.ToolResult("a", "ask_user", listOf(LlmMessage.Part.Text("answered"), LlmMessage.Part.Image("AAAA"))),
                LlmMessage.ToolResult("b", "tap", listOf(LlmMessage.Part.Text("tapped"), LlmMessage.Part.Image("BBBB"))),
            )
        ).objects()
        assertEquals(listOf("assistant", "tool", "tool", "user"), array.map { it.getString("role") })
        val texts = array[3].getJSONArray("content").objects().filter { it.getString("type") == "text" }.map { it.getString("text") }
        assertEquals(listOf("Screen after ask_user:", "Screen after tap:"), texts)
    }

    // --- streaming response ---

    @Test
    fun `narration, reasoning and encrypted reasoning are accumulated`() = runBlocking {
        server.enqueue(
            sse(
                chunk(JSONObject().put("reasoning_content", "用户想")),
                chunk(JSONObject().put("reasoning_content", "打开设置").put("encrypted_content", "ENC1")),
                chunk(JSONObject().put("encrypted_content", "ENC2")),
                chunk(JSONObject().put("content", "我先打开")),
                chunk(JSONObject().put("content", "设置。")),
                chunk(callDelta(0, id = "call_1", name = "open_app", args = """{"app_name":"设置"}""")),
                chunk(JSONObject(), finishReason = "tool_calls"),
                usageChunk(),
            )
        )
        val texts = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        val listener = object : ReplyListener {
            override fun onText(text: String) { texts.add(text) }
            override fun onReasoning(summary: String) { summaries.add(summary) }
        }

        val reply = client.chat(messages, listOf(tapSpec), listener)

        assertEquals("我先打开设置。", reply.text)
        assertEquals(Reasoning(summary = "用户想打开设置", opaque = "ENC1ENC2"), reply.reasoning)
        assertEquals("open_app", reply.toolCalls.single().name)
        assertEquals(listOf("我先打开", "我先打开设置。"), texts)
        assertEquals(listOf("用户想", "用户想打开设置"), summaries)
    }

    @Test
    fun `reply without reasoning has none`() = runBlocking {
        server.enqueue(simpleCallResponse())
        val reply = client.chat(messages, listOf(tapSpec))
        assertNull(reply.reasoning)
        assertNull(reply.text)
    }

    @Test
    fun `tool call split across chunks is assembled`() = runBlocking {
        server.enqueue(
            sse(
                chunk(callDelta(0, id = "call_1", name = "tap", args = "")),
                chunk(callDelta(0, args = """{"poi""")),
                chunk(callDelta(0, args = """nt":[500,""")),
                chunk(callDelta(0, args = """300]}""")),
                chunk(JSONObject(), finishReason = "tool_calls"),
            )
        )

        val call = client.chat(messages, listOf(tapSpec)).toolCalls.single()

        assertEquals("call_1", call.id)
        assertEquals("tap", call.name)
        assertEquals("""{"point":[500,300]}""", call.arguments)
    }

    @Test
    fun `parallel tool calls are kept apart by index`() = runBlocking {
        server.enqueue(
            sse(
                chunk(callDelta(0, id = "call_note", name = "note", args = """{"key":"pr""")),
                chunk(callDelta(1, id = "call_tap", name = "tap", args = """{"point":[1,""")),
                chunk(callDelta(0, args = """ice","value":"5999"}""")),
                chunk(callDelta(1, args = """2]}""")),
                chunk(JSONObject(), finishReason = "tool_calls"),
            )
        )

        val calls = client.chat(messages, listOf(tapSpec)).toolCalls

        assertEquals(listOf("note", "tap"), calls.map { it.name })
        assertEquals(listOf("call_note", "call_tap"), calls.map { it.id })
        assertEquals("price", JSONObject(calls[0].arguments).getString("key"))
        assertEquals("""{"point":[1,2]}""", calls[1].arguments)
    }

    @Test
    fun `a name repeated in every chunk is not concatenated`() = runBlocking {
        server.enqueue(
            sse(
                chunk(callDelta(0, id = "call_1", name = "tap", args = """{"point":""")),
                chunk(callDelta(0, id = "call_1", name = "tap", args = """[1,2]}""")),
                chunk(JSONObject(), finishReason = "tool_calls"),
            )
        )

        val call = client.chat(messages, listOf(tapSpec)).toolCalls.single()

        assertEquals("tap", call.name)
        assertEquals("""{"point":[1,2]}""", call.arguments)
    }

    @Test
    fun `a call without an id gets one`() = runBlocking {
        server.enqueue(
            sse(
                chunk(callDelta(0, name = "wait", args = "{}")),
                chunk(JSONObject(), finishReason = "tool_calls"),
            )
        )

        val call = client.chat(messages, listOf(tapSpec)).toolCalls.single()

        assertTrue(call.id.startsWith("call_"))
        assertEquals("wait", call.name)
    }

    @Test
    fun `a usage chunk without choices doesn't break parsing`() = runBlocking {
        server.enqueue(
            sse(
                usageChunk(prompt = 50, cached = 0),
                chunk(callDelta(0, id = "call_1", name = "tap", args = """{"point":[1,2]}""")),
                usageChunk(),
            )
        )

        val reply = client.chat(messages, listOf(tapSpec))

        assertEquals("tap", reply.toolCalls.single().name)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `text-only reply returns the text and no calls`() = runBlocking {
        server.enqueue(
            sse(
                chunk(JSONObject().put("content", "I think ")),
                chunk(JSONObject().put("content", "it's done.")),
                chunk(JSONObject(), finishReason = "stop"),
            )
        )

        val reply = client.chat(messages, listOf(tapSpec))

        assertEquals("I think it's done.", reply.text)
        assertTrue(reply.toolCalls.isEmpty())
    }

    @Test
    fun `empty stream is retried`() = runBlocking {
        server.enqueue(sse(chunk(JSONObject(), finishReason = "stop")))
        server.enqueue(simpleCallResponse())

        val reply = client.chat(messages, listOf(tapSpec))

        assertEquals("tap", reply.toolCalls.single().name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an always-empty stream ends in a ModelException`() = runBlocking {
        repeat(10) { server.enqueue(sse(chunk(JSONObject(), finishReason = "stop"))) }

        val error = runCatching { client.chat(messages, listOf(tapSpec)) }.exceptionOrNull()

        assertTrue("expected ModelException, got $error", error is ModelException)
        assertTrue(server.requestCount in 2..10)
    }

    // --- errors ---

    @Test
    fun `4xx error is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))

        val error = runCatching { client.chat(messages, listOf(tapSpec)) }.exceptionOrNull()

        assertTrue("expected ModelException, got $error", error is ModelException)
        assertTrue(error!!.message!!.contains("401"))
        assertTrue(error.message!!.contains("Invalid API key"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `5xx error is retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        server.enqueue(simpleCallResponse())

        val reply = client.chat(messages, listOf(tapSpec))

        assertEquals("tap", reply.toolCalls.single().name)
        assertEquals(2, server.requestCount)
    }
}
