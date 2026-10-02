package com.shiro.tyfo.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOutlineTest {

    private fun el(
        type: String, text: String? = null, label: String? = null, id: String? = null,
        left: Int, top: Int, right: Int, bottom: Int,
        clickable: Boolean = false, editable: Boolean = false, scrollable: Boolean = false,
        checkable: Boolean = false, checked: Boolean = false, focused: Boolean = false,
        selected: Boolean = false, enabled: Boolean = true,
    ) = UiElement(type, text, label, id, left, top, right, bottom, clickable, false, scrollable, editable,
        checkable, checked, focused, selected, enabled)

    // A 1000 x 2000 screen keeps the 0-1000 arithmetic readable: x stays, y halves
    private fun format(elements: List<UiElement>) = ScreenOutline.format(elements, 1000, 2000)

    private val screen = listOf(
        el("FrameLayout", left = 0, top = 0, right = 1000, bottom = 2000),                    // pure container: dropped
        el("Button", text = "Download", id = "downloadBtn", left = 100, top = 740, right = 900, bottom = 860, clickable = true),
        el("EditText", text = "Config ID", id = "idInput", left = 80, top = 560, right = 920, bottom = 680, editable = true, focused = true),
        el("ImageView", label = "返回", left = 0, top = 100, right = 140, bottom = 200, clickable = true),
        el("View", left = 480, top = 1600, right = 600, bottom = 1720, clickable = true),
        el("CheckBox", label = "Enable feature", left = 900, top = 1600, right = 980, bottom = 1680, checkable = true),
        el("TextView", text = "Disabled", left = 0, top = 1900, right = 200, bottom = 1960, enabled = false),
    )

    @Test
    fun `lines read Type text label id point flags, top to bottom, with 0-1000 points`() {
        assertEquals(
            listOf(
                "ImageView [返回] (70,75)",
                "EditText \"Config ID\" #idInput (500,310) editable focused",
                "Button \"Download\" #downloadBtn (500,400)",
                "View (540,830) clickable",
                "CheckBox [Enable feature] (940,820) checkable",
                "TextView \"Disabled\" (100,965) disabled",
                "# 6 elements",
            ),
            format(screen).lines(),
        )
    }

    @Test
    fun `a tappable row carries the texts inside it`() {
        val row = el("LinearLayout", left = 0, top = 200, right = 1000, bottom = 400, clickable = true)
        val title = el("TextView", text = "网络和互联网", left = 100, top = 220, right = 600, bottom = 300)
        val subtitle = el("TextView", text = "移动网络、WLAN", left = 100, top = 300, right = 700, bottom = 380)
        val toggle = el("Switch", left = 850, top = 260, right = 950, bottom = 340, clickable = true, checkable = true, checked = true)
        assertEquals(
            listOf(
                "LinearLayout \"网络和互联网 · 移动网络、WLAN\" (500,150)",
                "Switch (900,150) checked clickable",
                "# 2 elements",
            ),
            format(listOf(subtitle, toggle, row, title)).lines(),
        )
    }

    @Test
    fun `the same screen always gives the same text, whatever the node order`() {
        assertEquals(format(screen), format(screen.reversed() + screen))
    }

    @Test
    fun `long text is collapsed and cut`() {
        val long = el("TextView", text = "a\n  b " + "x".repeat(200), left = 0, top = 0, right = 10, bottom = 10)
        val line = format(listOf(long)).lines().first()
        assertTrue(line, line.startsWith("TextView \"a b xxx"))
        assertTrue(line, line.contains("…\""))
    }

    @Test
    fun `a screen without readable elements says to use the screenshot`() {
        assertTrue(format(listOf(screen.first())).startsWith("# no elements"))
    }

    @Test
    fun `labelAt names the smallest labelled element under the point`() {
        assertEquals("Download", ScreenOutline.labelAt(screen, 500, 800))
        assertEquals("返回", ScreenOutline.labelAt(screen, 50, 150))
        assertNull(ScreenOutline.labelAt(screen, 540, 1660))   // icon without text
    }
}
