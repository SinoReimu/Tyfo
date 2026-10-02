package com.shiro.tyfo.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the lenient parsing of model output: tool-call arguments that aren't valid JSON,
 * and tool calls written into the reply text instead of the tool-call channel.
 */
class ResponseParserTest {

    private val tapSpec = ToolSpec(
        "tap", "Tap a point.",
        listOf(
            ToolParam.Text("thought", "Reasoning.", default = ""),
            ToolParam.Integer("x", "X.", min = 0, max = 1000),
            ToolParam.Integer("y", "Y.", min = 0, max = 1000),
        )
    )

    private val swipeSpec = ToolSpec(
        "swipe", "Swipe.",
        listOf(
            ToolParam.Text("thought", "Reasoning.", default = ""),
            ToolParam.Integer("start_x", "."), ToolParam.Integer("start_y", "."),
            ToolParam.Integer("end_x", "."), ToolParam.Integer("end_y", "."),
            ToolParam.Integer("duration_ms", ".", required = false),
        )
    )

    private val typeSpec = ToolSpec(
        "type_text", "Type.",
        listOf(ToolParam.Text("thought", "Reasoning.", default = ""), ToolParam.Text("text", "Text."))
    )

    private val pointSpec = ToolSpec(
        "tap", "Tap a point.",
        listOf(ToolParam.Text("reason", "Why."), ToolParam.Point("point", "Where.", 1000, 1000))
    )

    private val finishSpec = ToolSpec(
        "finish", "End.",
        listOf(ToolParam.Text("answer", "Answer."), ToolParam.Bool("success", "Done?", default = true))
    )

    private fun args(call: ToolCall): JSONObject = JSONObject(call.arguments)

    // --- parseArguments ---

    @Test
    fun `parseArguments parses valid JSON`() {
        val result = ResponseParser.parseArguments("""{"thought":"tap it","x":500,"y":300}""", tapSpec)
        assertEquals(mapOf("thought" to "tap it", "x" to 500, "y" to 300), result)
    }

    @Test
    fun `parseArguments treats empty or blank arguments as no arguments`() {
        assertEquals(emptyMap<String, Any?>(), ResponseParser.parseArguments("", tapSpec))
        assertEquals(emptyMap<String, Any?>(), ResponseParser.parseArguments("   ", null))
    }

    @Test
    fun `parseArguments closes JSON truncated before the final brace`() {
        val result = ResponseParser.parseArguments("""{"thought":"open settings","x":12,"y":34""", tapSpec)
        assertEquals(mapOf("thought" to "open settings", "x" to 12, "y" to 34), result)
    }

    @Test
    fun `parseArguments keeps unicode and emoji values`() {
        val result = ResponseParser.parseArguments("""{"thought":"输入中文","text":"你好世界 🌍"}""", typeSpec)
        assertEquals("你好世界 🌍", result!!["text"])
        assertEquals("输入中文", result["thought"])
    }

    @Test
    fun `parseArguments salvages a string value with unescaped quotes up to the next key`() {
        // Streaming sometimes turns \" into a bare quote, which makes the whole object unparseable
        val raw = """{"thought":"用户说"好的"，点击确认","x":500,"y":880}"""
        val result = ResponseParser.parseArguments(raw, tapSpec)
        assertNotNull(result)
        assertEquals("用户说\"好的\"，点击确认", result!!["thought"])
        assertEquals(500, result["x"])
        assertEquals(880, result["y"])
    }

    @Test
    fun `a salvaged string stops at an undeclared key too`() {
        // The unescaped quotes make the JSON invalid for both Android's and the JVM's org.json
        val raw = """{"thought":"say "ok" then tap","note":"n","x":1,"y":3}"""
        val result = ResponseParser.parseArguments(raw, tapSpec)!!
        assertEquals("say \"ok\" then tap", result["thought"])
        assertEquals(1, result["x"])
        assertEquals(3, result["y"])
    }

    @Test
    fun `parseArguments salvages the last string value before the closing brace`() {
        val raw = """{"thought":"type it","text":"say "hi" now"}"""
        val result = ResponseParser.parseArguments(raw, typeSpec)
        assertEquals("say \"hi\" now", result!!["text"])
        assertEquals("type it", result["thought"])
    }

    @Test
    fun `parseArguments salvages the first number of a garbled integer`() {
        // Seen from a real model: "x": 382 896. The broken thought keeps the object unparseable for
        // every org.json flavor (the JVM test library would otherwise read 382 896 as a string).
        val raw = """{"thought":"tap "OK"","x": 382 896, "y": 896}"""
        val result = ResponseParser.parseArguments(raw, tapSpec)
        assertEquals(382, result!!["x"])
        assertEquals(896, result["y"])
        assertEquals("tap \"OK\"", result["thought"])
    }

    @Test
    fun `parseArguments salvages quoted and decimal integers`() {
        val result = ResponseParser.parseArguments("""{"x":"500" "y":299.6}""", tapSpec)
        assertEquals(500, result!!["x"])
        assertEquals(299, result["y"])
    }

    @Test
    fun `parseArguments salvages despite doubled commas`() {
        val raw = """{"thought":"swiping up",,"start_x":620,"start_y":700,,"end_x":620,"end_y":300,"duration_ms":800,}"""
        val result = ResponseParser.parseArguments(raw, swipeSpec)
        assertEquals("swiping up", result!!["thought"])
        assertEquals(620, result["start_x"])
        assertEquals(700, result["start_y"])
        assertEquals(620, result["end_x"])
        assertEquals(300, result["end_y"])
        assertEquals(800, result["duration_ms"])
    }

    @Test
    fun `a point written with a space instead of a comma is salvaged`() {
        // Doubao's GUI training writes points as "x y"; the broken reason keeps every org.json flavor from parsing it
        val raw = """{"reason":"tap "OK"","point": [270 54]}"""
        val result = ResponseParser.parseArguments(raw, pointSpec)!!
        assertEquals(listOf(270.0, 54.0), result["point"])
        assertEquals("tap \"OK\"", result["reason"])
    }

    @Test
    fun `a point missing its closing bracket is salvaged`() {
        val result = ResponseParser.parseArguments("""{"point": [117 265}""", pointSpec)!!
        assertEquals(listOf(117.0, 265.0), result["point"])
    }

    @Test
    fun `a point with a comma inside broken JSON is salvaged`() {
        val result = ResponseParser.parseArguments("""{"reason":"a "b"","point":[208, 661.5]}""", pointSpec)!!
        assertEquals(listOf(208.0, 661.5), result["point"])
    }

    @Test
    fun `a point written as a point tag is kept for the registry to read`() {
        // Missing closing brace: repaired, so the tag string comes through as the value
        val result = ResponseParser.parseArguments("""{"point":"<point>208 661</point>"""", pointSpec)!!
        assertEquals("<point>208 661</point>", result["point"])
    }

    @Test
    fun `a boolean is salvaged from broken JSON`() {
        val raw = """{"answer":"没订到 "Warroom"","success": false}"""
        val result = ResponseParser.parseArguments(raw, finishSpec)!!
        assertEquals(false, result["success"])
        assertEquals("没订到 \"Warroom\"", result["answer"])
    }

    @Test
    fun `parseArguments returns null when nothing can be salvaged`() {
        assertNull(ResponseParser.parseArguments("""<tool>tap here</tool>""", tapSpec))
    }

    @Test
    fun `parseArguments without a spec cannot salvage malformed JSON`() {
        assertNull(ResponseParser.parseArguments("""{"x": 382, "y": 896 "oops"}""", null))
    }

    // --- toolCallsFromText ---

    @Test
    fun `toolCallsFromText reads name and arguments object`() {
        val calls = ResponseParser.toolCallsFromText("""{"name":"tap","arguments":{"thought":"t","x":1,"y":2}}""")
        assertEquals(1, calls.size)
        assertEquals("tap", calls[0].name)
        val a = args(calls[0])
        assertEquals(1, a.getInt("x"))
        assertEquals(2, a.getInt("y"))
        assertEquals("t", a.getString("thought"))
    }

    @Test
    fun `toolCallsFromText keeps arguments given as a JSON string`() {
        val calls = ResponseParser.toolCallsFromText("""{"name":"open_app","arguments":"{\"app_name\":\"微信\"}"}""")
        assertEquals("open_app", calls.single().name)
        assertEquals("""{"app_name":"微信"}""", calls.single().arguments)
    }

    @Test
    fun `toolCallsFromText accepts parameters and input as the arguments key`() {
        val viaParameters = ResponseParser.toolCallsFromText("""{"name":"press_key","parameters":{"key":"back"}}""")
        assertEquals("back", args(viaParameters.single()).getString("key"))
        val viaInput = ResponseParser.toolCallsFromText("""{"name":"press_key","input":{"key":"enter"}}""")
        assertEquals("enter", args(viaInput.single()).getString("key"))
    }

    @Test
    fun `toolCallsFromText without arguments gives an empty object`() {
        val call = ResponseParser.toolCallsFromText("""{"name":"wait"}""").single()
        assertEquals("wait", call.name)
        assertEquals(0, args(call).length())
    }

    @Test
    fun `toolCallsFromText maps the legacy single-tool format`() {
        val call = ResponseParser.toolCallsFromText(
            """{"thought":"opening settings","action_type":"tap","x":500,"y":300}"""
        ).single()
        assertEquals("tap", call.name)
        val a = args(call)
        assertFalse("action_type must not leak into the arguments", a.has("action_type"))
        assertEquals(500, a.getInt("x"))
        assertEquals(300, a.getInt("y"))
        assertEquals("opening settings", a.getString("thought"))
    }

    @Test
    fun `toolCallsFromText maps an action key`() {
        val call = ResponseParser.toolCallsFromText("""{"action":" wait ","seconds":3}""").single()
        assertEquals("wait", call.name)
        assertFalse(args(call).has("action"))
        assertEquals(3, args(call).getInt("seconds"))
    }

    @Test
    fun `toolCallsFromText finds a call inside a markdown fence`() {
        val text = """Here is my action:
```json
{"name":"tap","arguments":{"x":500,"y":300}}
```
That should do it."""
        val call = ResponseParser.toolCallsFromText(text).single()
        assertEquals("tap", call.name)
        assertEquals(300, args(call).getInt("y"))
    }

    @Test
    fun `toolCallsFromText finds a call inside tool_call tags`() {
        val text = """<tool_call>{"name":"finish","arguments":{"answer":"北京 25°C"}}</tool_call>"""
        val call = ResponseParser.toolCallsFromText(text).single()
        assertEquals("finish", call.name)
        assertEquals("北京 25°C", args(call).getString("answer"))
    }

    @Test
    fun `toolCallsFromText ignores braces and quotes inside string values`() {
        val text = """I'll type it. {"name":"type_text","arguments":{"text":"a } b { \"c\""}} done."""
        val call = ResponseParser.toolCallsFromText(text).single()
        assertEquals("a } b { \"c\"", args(call).getString("text"))
    }

    @Test
    fun `toolCallsFromText returns every call when the text has several`() {
        val text = """{"name":"note","arguments":{"key":"price","value":"5999"}}
{"name":"tap","arguments":{"x":1,"y":2}}"""
        val calls = ResponseParser.toolCallsFromText(text)
        assertEquals(listOf("note", "tap"), calls.map { it.name })
        assertNotEquals("each call gets its own id", calls[0].id, calls[1].id)
    }

    @Test
    fun `toolCallsFromText skips objects that aren't calls`() {
        val text = """{"foo":1} and then {"name":"wait","arguments":{}}"""
        assertEquals(listOf("wait"), ResponseParser.toolCallsFromText(text).map { it.name })
    }

    @Test
    fun `toolCallsFromText returns nothing for plain prose or broken JSON`() {
        assertTrue(ResponseParser.toolCallsFromText("I think the task is done.").isEmpty())
        assertTrue(ResponseParser.toolCallsFromText("""{"name":"tap","arguments":{"x":1""").isEmpty())
        assertTrue(ResponseParser.toolCallsFromText("").isEmpty())
    }

    @Test
    fun `toolCallsFromText finds a call after a stray brace in the prose`() {
        val calls = ResponseParser.toolCallsFromText("""use {braces like this. {"name":"tap","arguments":{"x":1,"y":2}}""")
        assertEquals(listOf("tap"), calls.map { it.name })
    }

    // --- helpers ---

    @Test
    fun `newCallId is unique and prefixed`() {
        val ids = (1..50).map { ResponseParser.newCallId() }.toSet()
        assertEquals(50, ids.size)
        assertTrue(ids.all { it.startsWith("call_") })
    }

    @Test
    fun `jsonObjectToMap converts nulls, nested objects and arrays`() {
        val json = JSONObject()
            .put("a", "v")
            .put("n", JSONObject.NULL)
            .put("o", JSONObject().put("k", 1))
            .put("l", JSONArray().put(1).put(JSONObject.NULL).put(JSONArray().put("x")))
        val map = ResponseParser.jsonObjectToMap(json)
        assertEquals("v", map["a"])
        assertTrue(map.containsKey("n"))
        assertNull(map["n"])
        assertEquals(mapOf("k" to 1), map["o"])
        assertEquals(listOf(1, null, listOf("x")), map["l"])
    }
}
