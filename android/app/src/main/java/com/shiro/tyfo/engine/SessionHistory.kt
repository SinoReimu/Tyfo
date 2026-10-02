package com.shiro.tyfo.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一段会话里发给模型的完整消息记录（不含系统提示词），继续这段会话时原样接在新任务前面，
 * 模型能看到之前每一步自己想了什么、做了什么，以及用户的补充和回答。
 *
 * 存的是纯文字：截图去掉；每一步附带的界面元素清单和全局记忆在新任务里会重新给，也去掉，免得越存越大。
 */
object SessionHistory {

    /** 存库上限：只留最近的这么多条 / 这么多字。 */
    private const val STORE_MAX_MESSAGES = 120
    private const val STORE_MAX_CHARS = 80_000

    /** 继续会话时最多带上的条数 / 字数（模型的上下文和费用都有限，更早的靠摘要）。 */
    private const val RESTORE_MAX_MESSAGES = 60
    private const val RESTORE_MAX_CHARS = 30_000

    /** 引擎当前的消息列表（第一条是系统提示词）→ 要保存的纯文字记录。 */
    fun snapshot(messages: List<Map<String, Any>>): List<Map<String, String>> {
        val dropHeaders = listOf(
            LlmPrompts.agentFeedback("ui_elements_header"),
            LlmPrompts.agentFeedback("global_memory_header"),
        )
        val keepHeaders = listOf(
            LlmPrompts.agentFeedback("user_supplement_header"),
            LlmPrompts.agentFeedback("notes_header"),
        )
        val out = messages.filter { it["role"] != "system" }.mapNotNull { m ->
            val role = m["role"] as? String ?: return@mapNotNull null
            var text = textOf(m["content"]) ?: return@mapNotNull null
            if (role == "user") text = stripSections(text, dropHeaders, dropHeaders + keepHeaders)
            if (text.isBlank()) null else mapOf("role" to role, "content" to text)
        }
        return takeRecent(out, STORE_MAX_MESSAGES, STORE_MAX_CHARS)
    }

    /**
     * 存下来的记录 → 接到新任务前面的消息：只取最近的一段；必须从用户消息开始，
     * 以模型的回复结束（被停止时最后一条可能是还没回复的用户消息，去掉，免得连着两条用户消息）。
     */
    fun forContinuation(history: List<Map<String, String>>): List<Map<String, Any>> {
        val recent = takeRecent(history, RESTORE_MAX_MESSAGES, RESTORE_MAX_CHARS).toMutableList()
        while (recent.isNotEmpty() && recent.first()["role"] != "user") recent.removeAt(0)
        while (recent.isNotEmpty() && recent.last()["role"] != "assistant") recent.removeAt(recent.lastIndex)
        return recent.map { mapOf("role" to it.getValue("role"), "content" to it.getValue("content")) }
    }

    fun encode(history: List<Map<String, String>>): String =
        JSONArray().apply {
            history.forEach { put(JSONObject().put("role", it["role"]).put("content", it["content"])) }
        }.toString()

    fun decode(json: String): List<Map<String, String>> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            mapOf("role" to o.getString("role"), "content" to o.getString("content"))
        }
    }.getOrDefault(emptyList())

    /** 消息内容：纯文字，或者 [图片, 文字] 这样的分段列表（只取文字段）。 */
    private fun textOf(content: Any?): String? = when (content) {
        is String -> content
        is List<*> -> content.mapNotNull { part ->
            val p = part as? Map<*, *> ?: return@mapNotNull null
            if (p["type"] == "text") p["text"] as? String else null
        }.joinToString("\n").ifEmpty { null }
        else -> null
    }

    /** 去掉以 [drop] 里某个标题开头的段落，一直到下一个已知标题（[all]）或结尾。 */
    private fun stripSections(text: String, drop: List<String>, all: List<String>): String {
        var result = text
        for (header in drop) {
            val start = result.indexOf(header)
            if (start < 0) continue
            val afterHeader = start + header.length
            val end = all.mapNotNull { h -> result.indexOf(h, afterHeader).takeIf { it >= 0 } }.minOrNull()
                ?: result.length
            result = result.removeRange(start, end)
        }
        return result
    }

    private fun takeRecent(list: List<Map<String, String>>, maxMessages: Int, maxChars: Int): List<Map<String, String>> {
        var chars = 0
        var count = 0
        for (m in list.asReversed()) {
            val len = m["content"]?.length ?: 0
            if (count >= maxMessages || (count > 0 && chars + len > maxChars)) break
            chars += len
            count++
        }
        return list.takeLast(count)
    }
}
