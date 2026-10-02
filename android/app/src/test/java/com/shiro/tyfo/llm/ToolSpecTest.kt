package com.shiro.tyfo.llm

import com.shiro.tyfo.tools.PhoneTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Tests for tool declarations and the JSON schema generated from them. */
class ToolSpecTest {

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private val spec = ToolSpec(
        "swipe", "Move a finger.",
        listOf(
            ToolParam.Point("from", "Start point.", 1000, 1000),
            ToolParam.Integer("duration_ms", "Duration.", required = false, min = 100, max = 3000, default = 300),
            ToolParam.Text("key", "Key.", enum = listOf("back", "enter")),
            ToolParam.Text("keyword", "Keyword.", required = false),
            ToolParam.Bool("submit", "Press Enter.", default = false),
            ToolParam.Integer("count", "Count.", min = 1, max = 5, default = 1),
        )
    )

    private fun props() = spec.inputSchema().getJSONObject("properties")

    @Test
    fun `integer params declare type, range and description`() {
        val schema = props().getJSONObject("duration_ms")
        assertEquals("integer", schema.getString("type"))
        assertEquals(100, schema.getInt("minimum"))
        assertEquals(3000, schema.getInt("maximum"))
        assertEquals("Duration.", schema.getString("description"))
    }

    @Test
    fun `text params declare type and enum`() {
        val props = props()
        assertEquals("string", props.getJSONObject("key").getString("type"))
        assertEquals(listOf("back", "enter"), props.getJSONObject("key").getJSONArray("enum").strings())
        assertFalse(props.getJSONObject("keyword").has("enum"))
        assertFalse(props.getJSONObject("keyword").has("minimum"))
    }

    @Test
    fun `bool params are booleans`() {
        val submit = props().getJSONObject("submit")
        assertEquals("boolean", submit.getString("type"))
        assertEquals("Press Enter.", submit.getString("description"))
        assertFalse(submit.getBoolean("default"))
    }

    @Test
    fun `point params are arrays of two integers`() {
        val from = props().getJSONObject("from")
        assertEquals("array", from.getString("type"))
        assertEquals("integer", from.getJSONObject("items").getString("type"))
        assertEquals(0, from.getJSONObject("items").getInt("minimum"))
        assertEquals(1000, from.getJSONObject("items").getInt("maximum"))
        assertEquals(2, from.getInt("minItems"))
        assertEquals(2, from.getInt("maxItems"))
        assertEquals("Start point.", from.getString("description"))
        assertFalse(from.has("default"))
    }

    @Test
    fun `point items use the larger axis as their bound`() {
        val tall = ToolParam.Point("p", "P.", maxX = 719, maxY = 1559).schema()
        assertEquals(1559, tall.getJSONObject("items").getInt("maximum"))
    }

    @Test
    fun `default is shown only for optional params`() {
        val props = props()
        assertEquals(300, props.getJSONObject("duration_ms").getInt("default"))
        // A required param's default is only a fallback; telling the model would invite it to skip the param
        assertFalse(props.getJSONObject("count").has("default"))
        assertFalse(props.getJSONObject("keyword").has("default"))
    }

    @Test
    fun `input schema is an object listing required params`() {
        val schema = spec.inputSchema()
        assertEquals("object", schema.getString("type"))
        assertEquals(
            setOf("from", "duration_ms", "key", "keyword", "submit", "count"),
            schema.getJSONObject("properties").keySet()
        )
        assertEquals(listOf("from", "key", "count"), schema.getJSONArray("required").strings())
    }

    @Test
    fun `OpenAI shape wraps the schema in a function`() {
        val tool = spec.toOpenAi()
        assertEquals("function", tool.getString("type"))
        val function = tool.getJSONObject("function")
        assertEquals("swipe", function.getString("name"))
        assertEquals("Move a finger.", function.getString("description"))
        assertTrue(spec.inputSchema().similar(function.getJSONObject("parameters")))
    }

    // --- the real tool set ---

    private val tools = PhoneTools().all()
    private val specs = tools.map { it.spec }

    @Test
    fun `phone tools have the expected unique names`() {
        assertEquals(
            listOf(
                "tap", "long_press", "scroll", "swipe", "type_text", "press_key", "open_app", "wait",
                "list_apps", "note", "remember",
                "ask_user", "handoff_user", "finish",
            ),
            specs.map { it.name }
        )
        assertEquals(specs.size, specs.map { it.name }.toSet().size)
    }

    @Test
    fun `no tool has a thought parameter`() {
        // Progress narration comes from the reply text, not from a parameter
        for (s in specs) {
            assertFalse("${s.name} still declares thought", s.params.any { it.name == "thought" })
        }
    }

    @Test
    fun `every phone tool is fully described`() {
        for (s in specs) {
            assertTrue("${s.name} needs a description", s.description.isNotBlank())
            val schema = s.inputSchema()
            val props = schema.getJSONObject("properties")
            for (param in s.params) {
                assertTrue("${s.name}.${param.name} needs a description", param.description.isNotBlank())
            }
            for (required in schema.getJSONArray("required").strings()) {
                assertTrue("${s.name} requires undeclared $required", props.has(required))
            }
            assertEquals("${s.name} has duplicate params", s.params.size, s.params.map { it.name }.toSet().size)
            // Specs serialize to valid JSON
            JSONObject(s.toOpenAi().toString())
        }
    }

    @Test
    fun `points are bounded to the normalized range`() {
        val points = specs.flatMap { s -> s.params.filterIsInstance<ToolParam.Point>().map { s.name to it } }
        assertEquals(
            listOf("tap" to "point", "long_press" to "point", "scroll" to "point", "swipe" to "from", "swipe" to "to"),
            points.map { (tool, p) -> tool to p.name }
        )
        for ((tool, p) in points) {
            assertEquals("$tool.${p.name}", 1000, p.maxX)
            assertEquals("$tool.${p.name}", 1000, p.maxY)
            assertTrue("$tool.${p.name}: ${p.description}", p.description.contains("0-1000"))
        }
        val swipe = specs.first { it.name == "swipe" }.inputSchema()
        assertEquals(listOf("from", "to"), swipe.getJSONArray("required").strings())
        assertEquals(300, swipe.getJSONObject("properties").getJSONObject("duration_ms").getInt("default"))
    }

    @Test
    fun `press_key offers only keys that work on the virtual display`() {
        fun keys(onVirtualDisplay: Boolean) = PhoneTools(onVirtualDisplay).all()
            .first { it.name == "press_key" }.spec.inputSchema()
            .getJSONObject("properties").getJSONObject("key").getJSONArray("enum").strings()

        assertEquals(listOf("back", "enter", "delete"), keys(onVirtualDisplay = true))
        assertEquals(setOf("back", "enter", "delete", "home", "recent"), keys(onVirtualDisplay = false).toSet())
    }

    @Test
    fun `finish takes an answer and an optional success flag`() {
        val finish = specs.first { it.name == "finish" }.inputSchema()
        assertEquals(listOf("answer"), finish.getJSONArray("required").strings())
        val success = finish.getJSONObject("properties").getJSONObject("success")
        assertEquals("boolean", success.getString("type"))
        assertTrue(success.getBoolean("default"))
    }

    @Test
    fun `registry exposes the same specs`() {
        assertEquals(specs.map { it.name }, PhoneTools().registry().specs.map { it.name })
    }
}
