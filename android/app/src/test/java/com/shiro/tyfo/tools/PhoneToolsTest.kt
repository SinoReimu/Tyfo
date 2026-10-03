package com.shiro.tyfo.tools

import com.shiro.tyfo.action.Action
import com.shiro.tyfo.llm.ToolCall
import com.shiro.tyfo.llm.ToolParam
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the tool set in [PhoneTools]: each tool does what its declaration promises, through the
 * right [ToolContext] call, and has the kind / settle time the engine schedules it by.
 */
class PhoneToolsTest {

    /** Records what the tools ask of the engine; [labelAt] answers with [label]. */
    private class RecordingContext(
        var label: String? = null,
        var performResult: ToolOutcome.Result? = null,
        var elements: String? = null,
    ) : ToolContext {
        val performed = mutableListOf<Action>()
        val executed = mutableListOf<Action>()
        val labelQueries = mutableListOf<Pair<Int, Int>>()
        val notes = mutableListOf<Pair<String, String>>()
        val remembered = mutableListOf<Triple<String, String, String>>()

        override suspend fun perform(action: Action): ToolOutcome.Result {
            performed += action
            return performResult ?: ToolOutcome.Result("performed ${action.typeName}")
        }

        override suspend fun execute(action: Action): ToolOutcome.Result {
            executed += action
            return ToolOutcome.Result("executed ${action.typeName}")
        }

        override fun labelAt(x: Int, y: Int): String? {
            labelQueries += x to y
            return label
        }

        override fun note(key: String, value: String) {
            notes += key to value
        }

        override suspend fun remember(key: String, value: String, category: String) {
            remembered += Triple(key, value, category)
        }
    }

    private val ctx = RecordingContext()
    private val phoneTools = PhoneTools()
    private val registry = phoneTools.registry()
    private val tools = phoneTools.all().associateBy { it.name }

    private suspend fun call(name: String, arguments: String): ToolOutcome {
        val r = registry.resolve(ToolCall("call_1", name, arguments))
        assertTrue("Expected $name $arguments to resolve, got $r", r is ToolRegistry.Resolution.Ok)
        r as ToolRegistry.Resolution.Ok
        return r.tool.call(r.args, ctx)
    }

    private suspend fun performs(name: String, arguments: String): Pair<Action, ToolOutcome> {
        val outcome = call(name, arguments)
        assertTrue("$name must not bypass the repeat guard", ctx.executed.isEmpty())
        return ctx.performed.single() to outcome
    }

    // ── Points ─────────────────────────────────────────────────────────────

    @Test
    fun `tap performs a Tap at the point and asks what's there`() = runTest {
        val (action, _) = performs("tap", """{"point":[10,20]}""")
        assertEquals(Action.Tap(10, 20), action)
        assertEquals(listOf(10 to 20), ctx.labelQueries)
    }

    @Test
    fun `tap reports the model's point and the element it hit`() = runTest {
        ctx.label = "添加会议室"
        val (_, outcome) = performs("tap", """{"point":[209,661]}""")
        assertEquals(ToolOutcome.Result("Tapped [209, 661] on \"添加会议室\""), outcome)
    }

    @Test
    fun `tap without a known element reports just the point`() = runTest {
        val (_, outcome) = performs("tap", """{"point":[209,661]}""")
        assertEquals(ToolOutcome.Result("Tapped [209, 661]"), outcome)
    }

    @Test
    fun `a failed tap keeps the executor's error`() = runTest {
        ctx.performResult = ToolOutcome.Result("Action blocked: launcher is the foreground app", isError = true)
        ctx.label = "OK"
        val (_, outcome) = performs("tap", """{"point":[1,2]}""")
        assertEquals(ToolOutcome.Result("Action blocked: launcher is the foreground app", isError = true), outcome)
    }

    @Test
    fun `long_press passes the point and its duration`() = runTest {
        ctx.label = "消息"
        val (action, outcome) = performs("long_press", """{"point":[30,40],"duration_ms":1500}""")
        assertEquals(Action.LongPress(30, 40, 1500L), action)
        assertEquals(ToolOutcome.Result("Long-pressed [30, 40] on \"消息\""), outcome)
    }

    @Test
    fun `long_press defaults to 800 ms`() = runTest {
        val (action, _) = performs("long_press", """{"point":[30,40]}""")
        assertEquals(800L, (action as Action.LongPress).durationMs)
    }

    @Test
    fun `swipe passes both points and the duration`() = runTest {
        val (action, outcome) = performs("swipe", """{"from":[620,440],"to":[620,395],"duration_ms":800}""")
        assertEquals(Action.Swipe(620, 440, 620, 395, 800L), action)
        assertEquals(ToolOutcome.Result("Swiped [620, 440] → [620, 395] in 800ms"), outcome)
    }

    @Test
    fun `swipe defaults to a 300 ms flick`() = runTest {
        val (action, _) = performs("swipe", """{"from":[500,800],"to":[500,200]}""")
        assertEquals(Action.SWIPE_DEFAULT_MS, (action as Action.Swipe).durationMs)
    }

    @Test
    fun `scroll down pushes the finger up from the screen center`() = runTest {
        val (action, outcome) = performs("scroll", """{"direction":"down"}""")
        assertEquals(Action.Swipe(500, 700, 500, 300, 400L), action)
        assertEquals(ToolOutcome.Result("Scrolled down from [500, 500]"), outcome)
    }

    @Test
    fun `scroll starts from the given point and stays on screen`() = runTest {
        val (action, _) = performs("scroll", """{"direction":"right","point":[900,120]}""")
        assertEquals(Action.Swipe(950, 120, 700, 120, 400L), action)
    }

    @Test
    fun `scroll up and left move the finger the other way`() = runTest {
        performs("scroll", """{"direction":"up","point":[500,500]}""")
        assertEquals(Action.Swipe(500, 300, 500, 700, 400L), ctx.performed.single())
        ctx.performed.clear()
        performs("scroll", """{"direction":"left","point":[500,500]}""")
        assertEquals(Action.Swipe(300, 500, 700, 500, 400L), ctx.performed.single())
    }

    // ── Other screen actions ───────────────────────────────────────────────

    @Test
    fun `type_text passes submit and describes it`() = runTest {
        val (action, outcome) = performs("type_text", """{"text":"电池","submit":true}""")
        assertEquals(Action.TypeText("电池", submit = true), action)
        assertEquals(ToolOutcome.Result("Typed \"电池\" and pressed Enter"), outcome)
    }

    @Test
    fun `type_text without submit only types`() = runTest {
        val (action, outcome) = performs("type_text", """{"text":"hello"}""")
        assertEquals(Action.TypeText("hello", submit = false), action)
        assertEquals(ToolOutcome.Result("Typed \"hello\""), outcome)
    }

    @Test
    fun `press_key performs a KeyEvent`() = runTest {
        val (action, outcome) = performs("press_key", """{"key":"delete"}""")
        assertEquals(Action.KeyEvent("delete"), action)
        assertEquals(ToolOutcome.Result("Pressed delete"), outcome)
    }

    @Test
    fun `open_app performs an OpenApp and keeps the launcher's text`() = runTest {
        val (action, outcome) = performs("open_app", """{"app_name":"设置"}""")
        assertEquals(Action.OpenApp("设置"), action)
        assertEquals(ToolOutcome.Result("performed open_app"), outcome)
    }

    @Test
    fun `wait performs a Wait for the requested seconds`() = runTest {
        val (action, _) = performs("wait", """{"seconds":4}""")
        assertEquals(Action.Wait(4), action)
    }

    // ── Tools that don't touch the screen ──────────────────────────────────

    @Test
    fun `list_apps goes through execute, not the repeat guard`() = runTest {
        assertEquals(ToolOutcome.Result("executed list_apps"), call("list_apps", """{"keyword":"天气"}"""))
        assertEquals(listOf<Action>(Action.ListApps("天气")), ctx.executed)
        assertTrue(ctx.performed.isEmpty())
    }

    @Test
    fun `note records the key and value`() = runTest {
        assertEquals(ToolOutcome.Result("Noted."), call("note", """{"key":"JD price","value":"5999"}"""))
        assertEquals(listOf("JD price" to "5999"), ctx.notes)
    }

    @Test
    fun `remember stores the key, value and category`() = runTest {
        assertEquals(
            ToolOutcome.Result("Saved to long-term memory."),
            call("remember", """{"key":"city","value":"上海","category":"profile"}"""),
        )
        assertEquals(listOf(Triple("city", "上海", "profile")), ctx.remembered)
    }

    @Test
    fun `remember without a category stores an empty one`() = runTest {
        call("remember", """{"key":"city","value":"上海"}""")
        assertEquals("", ctx.remembered.single().third)
    }

    // ── Waiting for the user and ending ────────────────────────────────────

    @Test
    fun `ask_user, handoff_user and finish return their outcomes`() = runTest {
        assertEquals(ToolOutcome.AskUser("哪个城市？"), call("ask_user", """{"question":"哪个城市？"}"""))
        assertEquals(
            ToolOutcome.AskUser("几点吃饭？", listOf("明天下午4点", "今天晚些")),
            call("ask_user", """{"question":"几点吃饭？","options":["明天下午4点","今天晚些"]}"""),
        )
        assertEquals(ToolOutcome.Handoff("请输入支付密码"), call("handoff_user", """{"reason":"请输入支付密码"}"""))
        assertEquals(ToolOutcome.Finish("今天晴", true), call("finish", """{"answer":"今天晴"}"""))
        assertEquals(ToolOutcome.Finish("找不到该应用", false), call("finish", """{"answer":"找不到该应用","success":false}"""))
        assertTrue(ctx.performed.isEmpty() && ctx.executed.isEmpty())
    }

    // ── Declarations the engine schedules by ───────────────────────────────

    @Test
    fun `every tool has the expected kind`() {
        val expected = mapOf(
            "tap" to ToolKind.SCREEN, "long_press" to ToolKind.SCREEN, "scroll" to ToolKind.SCREEN, "swipe" to ToolKind.SCREEN,
            "type_text" to ToolKind.SCREEN, "press_key" to ToolKind.SCREEN, "open_app" to ToolKind.SCREEN,
            "wait" to ToolKind.SCREEN,
            "list_apps" to ToolKind.INFO, "note" to ToolKind.INFO,
            "remember" to ToolKind.INFO,
            "ask_user" to ToolKind.CONTROL, "handoff_user" to ToolKind.CONTROL, "finish" to ToolKind.CONTROL,
        )
        assertEquals(expected, tools.mapValues { it.value.kind })
    }

    @Test
    fun `settle times and screen-change expectations`() {
        assertEquals(2000L, tools.getValue("open_app").settleMs)
        for (name in listOf("tap", "long_press", "scroll", "swipe", "type_text", "press_key")) {
            assertEquals(name, 1000L, tools.getValue(name).settleMs)
            assertTrue(name, tools.getValue(name).changesScreen)
        }
        assertTrue(tools.getValue("open_app").changesScreen)
        assertEquals(0L, tools.getValue("wait").settleMs)
        assertFalse(tools.getValue("wait").changesScreen)
        for (name in listOf("list_apps", "note", "remember", "ask_user", "handoff_user", "finish")) {
            assertFalse(name, tools.getValue(name).changesScreen)
            assertEquals(name, 0L, tools.getValue(name).settleMs)
        }
    }

    @Test
    fun `no tool declares a thought parameter`() {
        for (tool in tools.values) {
            assertFalse(tool.name, tool.spec.params.any { it.name == "thought" })
        }
    }

    @Test
    fun `press_key offers only back, enter and delete on a virtual display`() {
        val key = tools.getValue("press_key").spec.params.single() as ToolParam.Text
        assertEquals(listOf("back", "enter", "delete"), key.enum)
    }

    @Test
    fun `press_key adds home and recent off a virtual display`() {
        val physical = PhoneTools(onVirtualDisplay = false).all().single { it.name == "press_key" }
        val key = physical.spec.params.single() as ToolParam.Text
        assertEquals(listOf("back", "enter", "delete", "home", "recent"), key.enum)
    }

    @Test
    fun `point parameters are 0-1000`() {
        val point = tools.getValue("tap").spec.params.single() as ToolParam.Point
        assertEquals(1000, point.maxX)
        assertEquals(1000, point.maxY)
        assertTrue(point.description, point.description.contains("0-1000"))
    }
}
