package com.shiro.tyfo.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 模型回答里常见的几样 Markdown 渲染成富文本：**加粗**、`代码`、# 标题（加粗），
 * “- ” / “* ” 开头的列表换成圆点。其余原样显示。
 */
object SimpleMarkdown {

    private val BULLET = Regex("^(\\s*)[-*+]\\s+")
    private val HEADING = Regex("^#{1,6}\\s+")
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`")

    fun render(source: String): CharSequence {
        val out = SpannableStringBuilder()
        source.lines().forEachIndexed { index, rawLine ->
            if (index > 0) out.append('\n')
            var line = rawLine
            var bold = false
            HEADING.find(line)?.let { line = line.removeRange(it.range); bold = true }
            BULLET.find(line)?.let { line = it.groupValues[1] + "• " + line.substring(it.range.last + 1) }
            val lineStart = out.length
            appendInline(out, line)
            if (bold && out.length > lineStart) {
                out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, line: String) {
        var last = 0
        for (match in INLINE.findAll(line)) {
            out.append(line, last, match.range.first)
            val start = out.length
            val code = match.groupValues[3]
            if (code.isNotEmpty()) {
                out.append(code)
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(match.groupValues[1].ifEmpty { match.groupValues[2] })
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = match.range.last + 1
        }
        out.append(line, last, line.length)
    }
}
