package com.shiro.tyfo.engine

import com.shiro.tyfo.llm.LlmMessage
import com.shiro.tyfo.llm.Reasoning
import com.shiro.tyfo.llm.ToolCall
import com.shiro.tyfo.llm.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一段会话里发给模型的消息记录（不含系统提示词），继续这段会话时接在新任务前面，
 * 模型能看到之前每一步调用了什么工具、结果如何，以及用户的补充和回答。
 *
 * 只存文字：截图和 outline 去掉，免得越存越大。模型的思考（含方舟要求原样回传的加密部分）一起存，接着聊时照样回传。
 */
object SessionHistory {

    private const val FORMAT_VERSION = 2

    /** 存库上限：只留最近的这么多条 / 这么多字。 */
    private const val STORE_MAX_MESSAGES = 160
    private const val STORE_MAX_CHARS = 80_000

    /** 继续会话时最多带上的条数 / 字数（模型的上下文和费用都有限，更早的靠摘要）。 */
    private const val RESTORE_MAX_MESSAGES = 60
    private const val RESTORE_MAX_CHARS = 30_000

    private const val NOT_RUN = "Not run: the task ended before this call was handled."

    /** 引擎当前的消息列表 → 要保存的记录。 */
    fun snapshot(messages: List<LlmMessage>): List<LlmMessage> {
        val out = messages.mapNotNull { m ->
            when (m) {
                is LlmMessage.System -> null
                is LlmMessage.User -> m.copy(parts = withoutImages(m.parts))
                is LlmMessage.ToolResult -> m.copy(parts = withoutImages(m.parts))
                is LlmMessage.Assistant -> m
            }
        }
        return takeRecent(out, STORE_MAX_MESSAGES, STORE_MAX_CHARS)
    }

    /** 去掉屏幕观察（截图、outline）；什么都不剩时留一句占位，让模型知道这里曾有一张截图。 */
    private fun withoutImages(parts: List<LlmMessage.Part>): List<LlmMessage.Part> {
        val kept = parts.filter { it is LlmMessage.Part.Text && !it.screenState }
        return if (kept.size == parts.size) parts
        else kept.ifEmpty { listOf(LlmMessage.Part.Text(AgentPrompts.SCREENSHOT_OMITTED)) }
    }

    /**
     * 存下来的记录 → 接到新任务前面的消息：只取最近的一段，从用户消息开始；
     * 任务在等回答时被停止，最后的工具调用没有结果，补一条“没执行”；结尾没等到回复的用户消息去掉。
     */
    fun forContinuation(history: List<LlmMessage>): List<LlmMessage> {
        val recent = takeRecent(history, RESTORE_MAX_MESSAGES, RESTORE_MAX_CHARS)
        val out = mutableListOf<LlmMessage>()
        val pending = mutableListOf<ToolCall>()
        fun closePending() {
            pending.forEach { out.add(LlmMessage.ToolResult(it.id, it.name, NOT_RUN, isError = true)) }
            pending.clear()
        }
        for (m in recent) {
            if (m is LlmMessage.ToolResult) {
                if (pending.removeAll { it.id == m.callId }) out.add(m)
                continue
            }
            closePending()
            out.add(m)
            if (m is LlmMessage.Assistant) pending.addAll(m.toolCalls)
        }
        closePending()
        while (out.isNotEmpty() && out.last() is LlmMessage.User) out.removeAt(out.lastIndex)
        return out
    }

    fun encode(history: List<LlmMessage>): String {
        val array = JSONArray()
        for (m in history) {
            val o = when (m) {
                is LlmMessage.System -> continue
                is LlmMessage.User -> JSONObject().put("role", "user").put("content", m.text)
                is LlmMessage.Assistant -> JSONObject()
                    .put("role", "assistant")
                    .put("content", m.text ?: JSONObject.NULL)
                    .put("reasoning", m.reasoning?.summary ?: JSONObject.NULL)
                    .put("reasoning_opaque", m.reasoning?.opaque ?: JSONObject.NULL)
                    .put("tool_calls", JSONArray().apply {
                        m.toolCalls.forEach {
                            put(JSONObject().put("id", it.id).put("name", it.name).put("arguments", it.arguments))
                        }
                    })
                is LlmMessage.ToolResult -> JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", m.callId)
                    .put("name", m.name)
                    .put("content", m.text)
                    .put("is_error", m.isError)
            }
            array.put(o)
        }
        return JSONObject().put("v", FORMAT_VERSION).put("messages", array).toString()
    }

    /**
     * 存的记录 → 消息。改用工具调用之前存的旧记录（单个 phone_action 工具、纯文字）格式对不上，
     * 直接不用，那几轮的内容还有系统提示词里的会话摘要兜着。
     */
    fun decode(json: String): List<LlmMessage> = runCatching {
        val root = JSONObject(json)
        if (root.optInt("v") != FORMAT_VERSION) return emptyList()
        val array = root.getJSONArray("messages")
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            when (o.getString("role")) {
                "user" -> LlmMessage.User(o.getString("content"))
                "assistant" -> {
                    val calls = o.optJSONArray("tool_calls") ?: JSONArray()
                    val summary = o.optStringOrNull("reasoning")
                    val opaque = o.optStringOrNull("reasoning_opaque")
                    LlmMessage.Assistant(
                        text = o.optStringOrNull("content"),
                        toolCalls = (0 until calls.length()).map { j ->
                            val c = calls.getJSONObject(j)
                            ToolCall(c.getString("id"), c.getString("name"), c.getString("arguments"))
                        },
                        reasoning = if (summary == null && opaque == null) null else Reasoning(summary, opaque),
                    )
                }
                "tool" -> LlmMessage.ToolResult(
                    callId = o.getString("tool_call_id"),
                    name = o.getString("name"),
                    text = o.getString("content"),
                    isError = o.optBoolean("is_error"),
                )
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    /** 从后往前取不超过上限的一段，起点挪到一条用户消息上（不能从半截工具调用开始）。 */
    private fun takeRecent(list: List<LlmMessage>, maxMessages: Int, maxChars: Int): List<LlmMessage> {
        var chars = 0
        var count = 0
        for (m in list.asReversed()) {
            val len = length(m)
            if (count >= maxMessages || (count > 0 && chars + len > maxChars)) break
            chars += len
            count++
        }
        val tail = list.takeLast(count)
        val start = tail.indexOfFirst { it is LlmMessage.User }
        return if (start < 0) emptyList() else tail.drop(start)
    }

    private fun length(m: LlmMessage): Int = when (m) {
        is LlmMessage.System -> m.text.length
        is LlmMessage.User -> m.text.length
        is LlmMessage.Assistant -> (m.text?.length ?: 0) + (m.reasoning?.summary?.length ?: 0) +
            m.toolCalls.sumOf { it.name.length + it.arguments.length }
        is LlmMessage.ToolResult -> m.text.length
    }
}
