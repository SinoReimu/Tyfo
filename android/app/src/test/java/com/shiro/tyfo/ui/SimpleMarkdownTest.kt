package com.shiro.tyfo.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimpleMarkdownTest {

    @Test
    fun `bold markers become bold spans and bullets become dots`() {
        val rendered = SimpleMarkdown.render("结果：\n\n- **手机型号：Pixel 10 Pro**\n1. **颜色：蓝色**\n### 小结")
        assertEquals("结果：\n\n• 手机型号：Pixel 10 Pro\n1. 颜色：蓝色\n小结", rendered.toString())
        val spans = (rendered as Spanned).getSpans(0, rendered.length, StyleSpan::class.java)
        val bold = spans.filter { it.style == Typeface.BOLD }.map { rendered.subSequence(rendered.getSpanStart(it), rendered.getSpanEnd(it)).toString() }
        assertEquals(listOf("手机型号：Pixel 10 Pro", "颜色：蓝色", "小结"), bold)
    }

    @Test
    fun `plain text and unmatched markers are left alone`() {
        assertEquals("2 * 3 = 6, a**b", SimpleMarkdown.render("2 * 3 = 6, a**b").toString())
    }
}
