package com.shiro.tyfo.tools

import com.shiro.tyfo.action.Action
import com.shiro.tyfo.input.InputInjector
import com.shiro.tyfo.llm.ToolParam
import com.shiro.tyfo.llm.ToolSpec

/**
 * 模型能用的全部工具。每个工具在这里一次写清：名字、说明、参数声明、实现；
 * 加一个工具 = 在 [all] 里加一项，schema、校验、分发都自动跟上。
 *
 * 工具的设计参考 mobile-mcp / ugc-android/AndroidControl / Claude computer use：一个工具一件事，
 * 坐标是 `[x, y]` 一个点（0-1000，见 [Points]），从 outline 里照抄元素的点击中心；
 * 屏幕操作只回一句文字结果，截图和 outline 由引擎在一次回复的操作都做完后统一附上。
 *
 * @param onVirtualDisplay 在虚拟屏上运行。虚拟屏上 home / recent 键无效，不提供给模型
 */
class PhoneTools(
    private val onVirtualDisplay: Boolean = true,
) {

    private companion object {
        /** scroll 的手势时长：比默认的 300ms 慢一点，滚得稳，惯性小。 */
        const val SCROLL_MS = 400L

        /** 同一次回复里连着点、按、输入时，两下之间等多久（计算器连按），最后一下之后再等界面稳定。 */
        const val CHAIN_DELAY_MS = 300L
    }

    fun all(): List<PhoneTool> = listOf(
        tap, longPress, scroll, swipe, typeText, pressKey, openApp, wait,
        listApps, note, remember,
        askUser, handoffUser, finish,
    )

    fun registry() = ToolRegistry(all())

    // ── 屏幕操作 ─────────────────────────────────────────────────────────

    private val tap = tool(
        "tap", ToolKind.SCREEN,
        "Tap a point on the screen. To tap an element, pass the tap center from its outline line.",
        point("point", "Where to tap."),
        settleMs = 1000, changesScreen = true, chainDelayMs = CHAIN_DELAY_MS,
    ) { args, ctx ->
        val (x, y) = args.point("point")
        ctx.perform(Action.Tap(x, y)).describe("Tapped ${args.point("point").show()}${ctx.labelAt(x, y).on()}")
    }

    private val longPress = tool(
        "long_press", ToolKind.SCREEN,
        "Touch and hold a point, e.g. to open a context menu or select text.",
        point("point", "Where to press."),
        ToolParam.Integer("duration_ms", "How long to hold, in milliseconds.", required = false, min = 300, max = 3000, default = 800),
        settleMs = 1000, changesScreen = true, chainDelayMs = CHAIN_DELAY_MS,
    ) { args, ctx ->
        val (x, y) = args.point("point")
        ctx.perform(Action.LongPress(x, y, args.int("duration_ms").toLong()))
            .describe("Long-pressed ${args.point("point").show()}${ctx.labelAt(x, y).on()}")
    }

    private val scroll = tool(
        "scroll", ToolKind.SCREEN,
        "Scroll to reveal more content: down shows what's below, up what's above, right what's to the right. " +
            "Without a point it scrolls the screen from its center; to scroll something smaller (a list in a sheet, " +
            "a carousel), pass a point inside it, e.g. the tap center of a scrollable element.",
        ToolParam.Text("direction", "Which way to move through the content.", enum = listOf("down", "up", "right", "left")),
        point("point", "Where to start, inside the thing to scroll.", required = false),
        settleMs = 1000, changesScreen = true,
    ) { args, ctx ->
        val direction = args.string("direction")
        val start = args.pointOrNull("point") ?: (500 to 500)
        val (x1, y1, x2, y2) = scrollStroke(start, direction)
        ctx.perform(Action.Swipe(x1, y1, x2, y2, SCROLL_MS))
            .describe("Scrolled $direction from ${start.show()}")
    }

    private val swipe = tool(
        "swipe", ToolKind.SCREEN,
        "Move a finger from one point to another, for drags that scroll can't do. A quick swipe (the default 300 ms) " +
            "flings, so lists keep scrolling after release. ${InputInjector.SLOW_DRAG_MS} ms or longer drags without " +
            "a fling: use it for wheel pickers, sliders and moving content by an exact distance.",
        point("from", "Start point."),
        point("to", "End point."),
        ToolParam.Integer(
            "duration_ms", "How long the finger moves, in milliseconds.",
            required = false, min = Action.SWIPE_MIN_MS.toInt(), max = Action.SWIPE_MAX_MS.toInt(),
            default = Action.SWIPE_DEFAULT_MS.toInt(),
        ),
        settleMs = 1000, changesScreen = true,
    ) { args, ctx ->
        val (x1, y1) = args.point("from")
        val (x2, y2) = args.point("to")
        val ms = args.int("duration_ms").toLong()
        ctx.perform(Action.Swipe(x1, y1, x2, y2, ms))
            .describe("Swiped ${args.point("from").show()} → ${args.point("to").show()} in ${ms}ms")
    }

    private val typeText = tool(
        "type_text", ToolKind.SCREEN,
        "Type text into the focused input field, all at once. It's inserted at the cursor and existing text stays; " +
            "tap the field first if it isn't focused.",
        ToolParam.Text("text", "The text to type."),
        ToolParam.Bool("submit", "Press Enter afterwards, e.g. to search or send.", default = false),
        settleMs = 1000, changesScreen = true, chainDelayMs = CHAIN_DELAY_MS,
    ) { args, ctx ->
        val submit = args.bool("submit")
        ctx.perform(Action.TypeText(args.string("text"), submit = submit))
            .describe("Typed \"${args.string("text")}\"${if (submit) " and pressed Enter" else ""}")
    }

    private val pressKey = run {
        val keys = if (onVirtualDisplay) listOf("back", "enter", "delete") else listOf("back", "enter", "delete", "home", "recent")
        tool(
            "press_key", ToolKind.SCREEN,
            "Press a key. back goes back or closes the keyboard; enter submits; delete erases the character before the cursor.",
            ToolParam.Text("key", "The key.", enum = keys),
            settleMs = 1000, changesScreen = true, chainDelayMs = CHAIN_DELAY_MS,
        ) { args, ctx ->
            ctx.perform(Action.KeyEvent(args.string("key"))).describe("Pressed ${args.string("key")}")
        }
    }

    private val openApp = tool(
        "open_app", ToolKind.SCREEN,
        "Launch an app, or bring it to the front if it's running. Use this to open or switch apps; never tap launcher icons.",
        ToolParam.Text("app_name", "The app's name as shown on the phone, e.g. 微信 or Settings."),
        settleMs = 2000, changesScreen = true,
    ) { args, ctx ->
        ctx.perform(Action.OpenApp(args.string("app_name")))
    }

    private val wait = tool(
        "wait", ToolKind.SCREEN,
        "Wait for loading or an animation to finish, then look at the screen again.",
        ToolParam.Integer("seconds", "How long to wait.", required = false, min = 1, max = 10, default = 2),
    ) { args, ctx ->
        ctx.perform(Action.Wait(args.int("seconds")))
    }

    // ── 不碰屏幕 ─────────────────────────────────────────────────────────

    private val listApps = tool(
        "list_apps", ToolKind.INFO,
        "List installed apps whose name contains the keyword, to get the exact name for open_app.",
        ToolParam.Text("keyword", "Part of the app name, e.g. 天气. Leave it out to list all apps.", required = false),
    ) { args, ctx ->
        ctx.execute(Action.ListApps(args.string("keyword")))
    }

    private val note = tool(
        "note", ToolKind.INFO,
        "Keep a fact for later in this task, e.g. the price found in one app while comparing apps. Saving the same " +
            "key again replaces it.",
        ToolParam.Text("key", "Short label, e.g. JD price."),
        ToolParam.Text("value", "The fact, e.g. 5999 yuan."),
    ) { args, ctx ->
        ctx.note(args.string("key"), args.string("value"))
        ToolOutcome.Result("Noted.")
    }

    private val remember = tool(
        "remember", ToolKind.INFO,
        "Save a stable fact about the user to long-term memory, where future tasks will see it. Only for confirmed, " +
            "long-lived information such as preferences, profile and habits; use note for facts about this task.",
        ToolParam.Text("key", "Unique name, e.g. default city. Saving an existing key overwrites it."),
        ToolParam.Text("value", "The fact."),
        ToolParam.Text("category", "e.g. preference, profile, workflow.", required = false),
    ) { args, ctx ->
        ctx.remember(args.string("key"), args.string("value"), args.string("category"))
        ToolOutcome.Result("Saved to long-term memory.")
    }

    // ── 等用户 / 结束 ────────────────────────────────────────────────────

    private val askUser = tool(
        "ask_user", ToolKind.CONTROL,
        "Ask the user a question and wait for the answer. When the answer is one of a few clear choices " +
            "(yes/no, a time, pick one of several), pass them as options: the user taps one, or still types " +
            "their own answer. Omit options for open-ended questions.",
        ToolParam.Text("question", "The question, in the user's language."),
        ToolParam.TextList(
            "options",
            "A few short answer choices in the user's language, e.g. [\"明天下午4点\", \"今天晚些\"]. " +
                "Leave out for open-ended questions.",
            required = false, maxItems = 5,
        ),
    ) { args, _ ->
        ToolOutcome.AskUser(args.string("question"), args.stringList("options"))
    }

    private val handoffUser = tool(
        "handoff_user", ToolKind.CONTROL,
        "Hand the screen to the user to enter a password, PIN, payment password or verification code " +
            "themselves, and wait until they return control.",
        ToolParam.Text("reason", "What the user needs to do, in their language, e.g. 请输入支付密码."),
    ) { args, _ ->
        ToolOutcome.Handoff(args.string("reason"))
    }

    private val finish = tool(
        "finish", ToolKind.CONTROL,
        "End the task, after you've confirmed the result on screen.",
        ToolParam.Text(
            "answer",
            "Your final reply to the user, in their language: the actual result (the data you found, what was " +
                "done), or why it couldn't be done.",
        ),
        ToolParam.Bool("success", "false if the task couldn't be completed.", default = true),
    ) { args, _ ->
        ToolOutcome.Finish(args.string("answer"), args.bool("success"))
    }

    // ── 声明工具的小工具 ──────────────────────────────────────────────────

    private fun point(name: String, what: String, required: Boolean = true) = ToolParam.Point(
        name, "$what ${Points.PARAM_HINT}", Points.MAX, Points.MAX, required = required,
    )

    /** 滚动的手势：从起点沿反方向推 40% 屏幕（“往下看”是手指往上推），不出 5%-95% 的范围。 */
    private fun scrollStroke(start: Pair<Int, Int>, direction: String): List<Int> {
        val (x, y) = start
        val d = 400
        fun c(v: Int) = v.coerceIn(50, 950)
        return when (direction) {
            "down" -> listOf(x, c(y + d / 2), x, c(y - d / 2))
            "up" -> listOf(x, c(y - d / 2), x, c(y + d / 2))
            "right" -> listOf(c(x + d / 2), y, c(x - d / 2), y)
            else -> listOf(c(x - d / 2), y, c(x + d / 2), y)
        }
    }

    private fun tool(
        name: String,
        kind: ToolKind,
        description: String,
        vararg params: ToolParam,
        settleMs: Long = 0,
        changesScreen: Boolean = false,
        chainDelayMs: Long = settleMs,
        handler: suspend (ToolArgs, ToolContext) -> ToolOutcome,
    ) = PhoneTool(
        ToolSpec(name, description, params.toList()), kind,
        settleMs = settleMs, changesScreen = changesScreen, chainDelayMs = chainDelayMs, handler = handler,
    )
}

private fun Pair<Int, Int>.show() = "[$first, $second]"

private fun String?.on() = if (this == null) "" else " on \"$this\""

/** 操作成功时把结果换成一句更具体的描述（点到了哪个元素）；失败时保留执行器的错误说明。 */
private fun ToolOutcome.Result.describe(success: String) = if (isError) this else copy(text = success)
