package com.shiro.tyfo.tools

import com.shiro.tyfo.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [ToolRegistry.resolve]: finding the tool and turning the model's raw arguments into
 * validated [ToolArgs], or an error message the model can act on.
 *
 * Unit tests run on org.json 20240303, which is more lenient than Android's (it reads `382 896` or
 * `270 54` as an unquoted string). Cases that must exercise the salvage path use input both parsers
 * reject; cases that only check the final value hold either way.
 */
class ToolRegistryTest {

    private val registry = PhoneTools().registry()

    private fun resolve(name: String, arguments: String, reg: ToolRegistry = registry) =
        reg.resolve(ToolCall("call_1", name, arguments))

    private fun ok(name: String, arguments: String, reg: ToolRegistry = registry): ToolRegistry.Resolution.Ok {
        val r = resolve(name, arguments, reg)
        assertTrue("Expected Ok for $name $arguments but got $r", r is ToolRegistry.Resolution.Ok)
        return r as ToolRegistry.Resolution.Ok
    }

    private fun invalid(name: String, arguments: String, reg: ToolRegistry = registry): String {
        val r = resolve(name, arguments, reg)
        assertTrue("Expected Invalid for $name $arguments but got $r", r is ToolRegistry.Resolution.Invalid)
        return (r as ToolRegistry.Resolution.Invalid).error
    }

    private fun point(name: String, arguments: String, param: String = "point", reg: ToolRegistry = registry) =
        ok(name, arguments, reg).args.point(param)

    // ── Finding the tool ────────────────────────────────────────────────────

    @Test
    fun `unknown tool lists the available tools`() {
        val error = invalid("teleport", "{}")
        assertTrue(error, error.contains("Unknown tool 'teleport'"))
        for (name in listOf("tap", "swipe", "list_apps", "finish")) assertTrue(error, error.contains(name))
    }

    @Test
    fun `tool names are trimmed and case-insensitive`() {
        assertEquals("tap", ok("  TAP ", """{"point":[1,2]}""").tool.name)
    }

    @Test
    fun `old key_event name is no longer accepted`() {
        assertTrue(invalid("key_event", """{"key":"back"}""").contains("Unknown tool"))
    }

    @Test
    fun `resolution keeps the original call`() {
        val call = ToolCall("abc", "tap", """{"point":[1,2]}""")
        assertSame(call, registry.resolve(call).call)
    }

    // ── Required parameters and defaults ────────────────────────────────────

    @Test
    fun `missing required parameter is named in the error`() {
        val error = invalid("tap", "{}")
        assertTrue(error, error.startsWith("tap: "))
        assertTrue(error, error.contains("missing required parameter 'point'"))
    }

    @Test
    fun `blank string counts as missing for a required text parameter`() {
        assertTrue(invalid("open_app", """{"app_name":"   "}""").contains("'app_name'"))
    }

    @Test
    fun `optional parameters get their defaults`() {
        assertEquals(300, ok("swipe", """{"from":[1,2],"to":[3,4]}""").args.int("duration_ms"))
        assertEquals(2, ok("wait", "{}").args.int("seconds"))
        assertEquals(800, ok("long_press", """{"point":[1,2]}""").args.int("duration_ms"))
        assertFalse(ok("type_text", """{"text":"hi"}""").args.bool("submit"))
        assertTrue(ok("finish", """{"answer":"ok"}""").args.bool("success"))
    }

    @Test
    fun `empty arguments are fine for tools without required parameters`() {
        assertEquals("", ok("list_apps", "").args.string("keyword"))
        ok("list_apps", "")
        ok("wait", "  ")
    }

    // ── Integers ────────────────────────────────────────────────────────────

    @Test
    fun `integers accept numeric strings, decimals and single-element arrays`() {
        assertEquals(800, ok("swipe", """{"from":[1,2],"to":[3,4],"duration_ms":"800"}""").args.int("duration_ms"))
        assertEquals(801, ok("swipe", """{"from":[1,2],"to":[3,4],"duration_ms":800.6}""").args.int("duration_ms"))
        assertEquals(700, ok("swipe", """{"from":[1,2],"to":[3,4],"duration_ms":[700]}""").args.int("duration_ms"))
    }

    @Test
    fun `a garbled number keeps its leading value`() {
        // The model once wrote "x": 382 896. Android's parser rejects that JSON and salvage keeps 382;
        // the JVM parser reads it as a string. Both paths must agree.
        assertEquals(1382, ok("swipe", """{"from":[1,2],"to":[3,4],"duration_ms":"1382 896"}""").args.int("duration_ms"))
        assertEquals(1382, ok("swipe", """{"from":[1,2],"to":[3,4],"duration_ms":1382 896}""").args.int("duration_ms"))
    }

    @Test
    fun `non-numeric integer is rejected`() {
        val error = invalid("wait", """{"seconds":"soon"}""")
        assertTrue(error, error.contains("'seconds'"))
        assertTrue(error, error.contains("integer"))
    }

    @Test
    fun `integers are clamped to their range`() {
        assertEquals(3000, ok("swipe", """{"from":[1,2],"to":[3,4],"duration_ms":20000}""").args.int("duration_ms"))
        assertEquals(100, ok("swipe", """{"from":[1,2],"to":[3,4],"duration_ms":5}""").args.int("duration_ms"))
        assertEquals(10, ok("wait", """{"seconds":60}""").args.int("seconds"))
    }

    // ── Points ──────────────────────────────────────────────────────────────

    @Test
    fun `a point is read from an array`() {
        assertEquals(208 to 661, point("tap", """{"point":[208,661]}"""))
        assertEquals(208 to 662, point("tap", """{"point":[208.4,661.6]}"""))
        assertEquals(208 to 661, point("tap", """{"point":["208","661"]}"""))
    }

    @Test
    fun `a point is read from the string forms models write`() {
        for (raw in listOf(""""208 661"""", """["208 661"]""", """"<point>208 661</point>"""", """"(208, 661)"""", """"[208,661]"""")) {
            assertEquals(raw, 208 to 661, point("tap", """{"point":$raw}"""))
        }
    }

    @Test
    fun `a point is read from an x-y object`() {
        assertEquals(208 to 661, point("tap", """{"point":{"x":208,"y":661}}"""))
    }

    @Test
    fun `separate x and y are collected into the single point of tap and long_press`() {
        assertEquals(208 to 661, point("tap", """{"x":208,"y":661}"""))
        assertEquals(30 to 40, point("long_press", """{"x":30,"y":40}"""))
    }

    @Test
    fun `separate x and y are not guessed for swipe, which has two points`() {
        assertTrue(invalid("swipe", """{"x":1,"y":2}""").contains("missing required parameter 'from'"))
    }

    @Test
    fun `a point with one number is rejected`() {
        val error = invalid("tap", """{"point":[500]}""")
        assertTrue(error, error.contains("'point'"))
        assertTrue(error, error.contains("[x, y]"))
        assertTrue(invalid("tap", """{"point":"left"}""").contains("[x, y]"))
    }

    @Test
    fun `normalized points are clamped to 0-1000`() {
        assertEquals(1000 to 0, point("tap", """{"point":[1500,-20]}"""))
    }

    @Test
    fun `swipe reads both of its points`() {
        val args = ok("swipe", """{"from":[500,800],"to":[500,200],"duration_ms":600}""").args
        assertEquals(500 to 800, args.point("from"))
        assertEquals(500 to 200, args.point("to"))
        assertEquals(600, args.int("duration_ms"))
    }

    // ── Booleans and enums ──────────────────────────────────────────────────

    @Test
    fun `booleans accept true, false, strings and 0 or 1`() {
        assertTrue(ok("type_text", """{"text":"a","submit":true}""").args.bool("submit"))
        assertFalse(ok("type_text", """{"text":"a","submit":"false"}""").args.bool("submit"))
        assertTrue(ok("type_text", """{"text":"a","submit":"TRUE"}""").args.bool("submit"))
        assertTrue(ok("type_text", """{"text":"a","submit":1}""").args.bool("submit"))
        assertFalse(ok("finish", """{"answer":"no","success":0}""").args.bool("success"))
    }

    @Test
    fun `a boolean that isn't one is rejected`() {
        val error = invalid("type_text", """{"text":"a","submit":"maybe"}""")
        assertTrue(error, error.contains("'submit'"))
        assertTrue(error, error.contains("true or false"))
    }

    @Test
    fun `enum values are matched case-insensitively and canonicalized`() {
        assertEquals("back", ok("press_key", """{"key":" BACK "}""").args.string("key"))
        assertEquals("delete", ok("press_key", """{"key":"Delete"}""").args.string("key"))
    }

    @Test
    fun `unknown enum value lists the options`() {
        val error = invalid("press_key", """{"key":"home"}""")
        assertTrue(error, error.contains("'key'"))
        assertTrue(error, error.contains("back, enter, delete"))
    }

    @Test
    fun `home is accepted when not running on a virtual display`() {
        val physical = PhoneTools(onVirtualDisplay = false).registry()
        assertEquals("home", ok("press_key", """{"key":"home"}""", physical).args.string("key"))
    }

    @Test
    fun `text parameters accept numbers`() {
        assertEquals("12306", ok("type_text", """{"text":12306}""").args.string("text"))
    }

    // ── Broken JSON ─────────────────────────────────────────────────────────

    @Test
    fun `a point with a space instead of a comma ends up right`() {
        // Doubao writes [270 54]: Android rejects it and salvage reads the two numbers;
        // the JVM parser reads ["270 54"]. Either way the point is (270, 54).
        assertEquals(270 to 54, point("tap", """{"point": [270 54]}"""))
    }

    @Test
    fun `a point missing its closing bracket is salvaged`() {
        // Both parsers reject this, so it goes through the salvage path
        assertEquals(117 to 265, point("tap", """{"point": [117 265}"""))
    }

    @Test
    fun `truncated JSON missing its closing brace is repaired`() {
        assertEquals(1 to 2, point("tap", """{"point":[1,2]"""))
    }

    @Test
    fun `values are salvaged from the declared parameters when quotes break the JSON`() {
        val args = ok("type_text", """{"text":"say "hi" now","submit":true}""").args
        assertEquals("say \"hi\" now", args.string("text"))
        assertTrue(args.bool("submit"))
    }

    @Test
    fun `arguments that aren't JSON at all are rejected`() {
        val error = invalid("tap", "tap the OK button")
        assertTrue(error, error.contains("aren't valid JSON"))
    }

    // ── Normalized arguments for the history ───────────────────────────────

    @Test
    fun `toJson renders points as arrays and is valid JSON`() {
        val json = JSONObject(ok("swipe", """{"from":"500 800","to":[500,200]}""").args.toJson())
        assertEquals(JSONArray("[500,800]").toString(), json.getJSONArray("from").toString())
        assertEquals(JSONArray("[500,200]").toString(), json.getJSONArray("to").toString())
        assertEquals(300, json.getInt("duration_ms"))
    }

    @Test
    fun `toJson leaves out absent optional parameters`() {
        val json = JSONObject(ok("list_apps", "{}").args.toJson())
        assertFalse(json.has("keyword"))
        assertFalse(JSONObject(ok("remember", """{"key":"k","value":"v"}""").args.toJson()).has("category"))
    }

    @Test
    fun `specs match the registered tools`() {
        assertEquals(registry.tools.map { it.name }, registry.specs.map { it.name })
    }
}
