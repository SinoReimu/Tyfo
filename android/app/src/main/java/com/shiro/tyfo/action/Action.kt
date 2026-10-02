package com.shiro.tyfo.action

/**
 * 在屏幕上执行的一个操作，由 [ActionExecutor] 执行。模型通过工具（[com.shiro.tyfo.tools.PhoneTools]）
 * 发起，参数已经校验过；坐标都是 0-1000 归一化的。
 */
sealed class Action(val typeName: String, open val thought: String) {

    data class Tap(
        val x: Int,
        val y: Int,
        override val thought: String = ""
    ) : Action("tap", thought)

    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long = 1000,
        override val thought: String = ""
    ) : Action("long_press", thought)

    data class OpenApp(
        val appName: String,
        override val thought: String = ""
    ) : Action("open_app", thought)

    /** Finger moves from (startX, startY) to (endX, endY) over [durationMs]. */
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = SWIPE_DEFAULT_MS,
        override val thought: String = ""
    ) : Action("swipe", thought)

    data class KeyEvent(
        val key: String,
        override val thought: String = ""
    ) : Action("key_event", thought)

    /** [submit]：输入完按一下 Enter（搜索、发送）。 */
    data class TypeText(
        val text: String,
        val submit: Boolean = false,
        override val thought: String = ""
    ) : Action("type_text", thought)

    data class Wait(
        val seconds: Int = 2,
        override val thought: String = ""
    ) : Action("wait", thought)

    data class ListApps(
        val keyword: String = "",
        override val thought: String = ""
    ) : Action("list_apps", thought)

    companion object {
        const val SWIPE_DEFAULT_MS = 300L
        const val SWIPE_MIN_MS = 100L
        const val SWIPE_MAX_MS = 3000L
    }
}
