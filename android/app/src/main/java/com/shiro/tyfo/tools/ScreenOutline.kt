package com.shiro.tyfo.tools

/**
 * 屏幕上的一个界面元素（无障碍服务读出来的），边界是设备像素。
 *
 * @param type 类名的最后一段，如 TextView
 * @param label 无障碍描述（content-desc），没有时用输入框的提示文字
 * @param id 资源 id 去掉包名前缀，如 `...:id/search_box` → `search_box`
 */
data class UiElement(
    val type: String,
    val text: String? = null,
    val label: String? = null,
    val id: String? = null,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = (right - left).toLong() * (bottom - top)
    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
    fun contains(other: UiElement) =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    /** 能直接操作（不只是显示文字）。 */
    val interactive get() = clickable || longClickable || scrollable || editable || checkable
}

/**
 * 屏幕的元素清单（outline），照 ugc-android/AndroidControl 的做法：截图给模型理解屏幕，
 * 点哪里从 outline 取——每行带元素的点击中心，由真实边界算出来，模型照抄就点得准，不用从图上估位置。
 * 坐标只在目标不在清单里时（webview、画布、游戏、没有文字的图标）才让模型从截图上估。
 *
 * 一行：`Type "text" [label] #id (x,y) flags`，(x,y) 是 0-1000 的点击中心（[Points]）。
 * 可点的行（容器自己没有文字）把里面只用来显示的文字并进来，用 · 隔开，省掉单独的文字行。
 *
 * 输出是确定的：按从上到下、从左到右排序并去重，同一个屏幕每次得到一模一样的文字。
 * 它跟着截图进上下文，内容稳定才不会无谓地打断前缀缓存；屏幕有没有变化也直接比较它。
 */
object ScreenOutline {

    private const val MAX_TEXT = 80
    private const val SEPARATOR = " · "

    fun format(elements: List<UiElement>, screenWidth: Int, screenHeight: Int): String {
        val shown = elements.filter { it.isInteresting() }
        // 只用来显示的文字，并进包住它的最小的可点容器（容器自己没有文字时）
        val containers = shown.filter { (it.clickable || it.longClickable) && it.ownText().isEmpty() }
        val absorbed = HashMap<UiElement, MutableList<UiElement>>()
        for (e in shown) {
            if (e.interactive || e.ownText().isEmpty()) continue
            val owner = containers.filter { it !== e && it.contains(e) }.minByOrNull { it.area } ?: continue
            absorbed.getOrPut(owner) { mutableListOf() }.add(e)
        }
        val swallowed = absorbed.values.flatten().toSet()

        val seen = HashSet<String>()
        val lines = shown.filter { it !in swallowed }
            .map { e ->
                val inner = absorbed[e].orEmpty().sortedWith(compareBy({ it.top }, { it.left })).map { it.ownText() }
                e to describe(e, inner, screenWidth, screenHeight)
            }
            .sortedWith(compareBy({ it.first.top }, { it.first.left }, { it.second }))
            .map { it.second }
            .filter { seen.add(it) }

        if (lines.isEmpty()) {
            return "# no elements with text or actions: likely a webview, canvas or video. Estimate points from the screenshot."
        }
        return (lines + "# ${lines.size} element${if (lines.size == 1) "" else "s"}").joinToString("\n")
    }

    /** 这个点（设备像素）落在哪个有文字的元素上：取包含它的最小的那个。 */
    fun labelAt(elements: List<UiElement>, x: Int, y: Int): String? =
        elements.filter { it.ownText().isNotEmpty() && it.contains(x, y) }
            .minByOrNull { it.area }
            ?.ownText()?.take(40)

    /** 有文字，或者能点 / 滚 / 选 / 输入：纯布局容器不要。 */
    private fun UiElement.isInteresting() =
        !text.isNullOrBlank() || !label.isNullOrBlank() || interactive || focused

    /** 元素自己的文字（没有就用描述）。 */
    private fun UiElement.ownText(): String =
        text?.let(::clean)?.takeIf { it.isNotEmpty() } ?: label?.let(::clean).orEmpty()

    private fun describe(e: UiElement, inner: List<String>, screenWidth: Int, screenHeight: Int): String {
        val text = e.text?.let(::clean).orEmpty()
        val label = e.label?.let(::clean).orEmpty()
        val parts = mutableListOf(e.type)
        when {
            text.isNotEmpty() -> parts.add("\"$text\"")
            inner.isNotEmpty() -> parts.add("\"${clean(inner.joinToString(SEPARATOR))}\"")
        }
        if (label.isNotEmpty() && label != text) parts.add("[$label]")
        if (!e.id.isNullOrBlank()) parts.add("#${e.id}")
        val (x, y) = Points.fromPixels(e.centerX, e.centerY, screenWidth, screenHeight)
        parts.add("($x,$y)")
        val flags = buildList {
            if (e.editable) add("editable")
            if (e.scrollable) add("scrollable")
            if (e.focused) add("focused")
            if (e.selected) add("selected")
            if (e.checkable) add(if (e.checked) "checked" else "checkable")
            if (!e.enabled) add("disabled")
            // 没有文字的可点区域（图标按钮）：标出来，免得模型以为它不能点
            if (text.isEmpty() && label.isEmpty() && inner.isEmpty() && (e.clickable || e.longClickable)) add("clickable")
        }
        if (flags.isNotEmpty()) parts.add(flags.joinToString(" "))
        return parts.joinToString(" ")
    }

    private fun clean(s: String): String {
        val collapsed = s.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length > MAX_TEXT) collapsed.take(MAX_TEXT - 1) + "…" else collapsed
    }
}
