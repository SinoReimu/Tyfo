package com.shiro.tyfo.engine

import com.shiro.tyfo.db.ChatMessageEntity
import com.shiro.tyfo.ui.MessageType

/**
 * 继续一段历史对话时，把之前几轮的聊天记录整理成给模型看的文字：每轮的指令、模型问过的问题和用户的回答、
 * 补充信息、最终结果。中间每一步的想法不带（只在某轮没有结果时，留最后一条进度说明停在哪）。
 * 太长时从最早的几轮开始丢，保证最近的上下文完整。
 */
object ConversationContext {

    private const val MAX_CHARS = 6000

    /** 结果消息开头的状态标记（见 AgentService 写入的 ✅ / ❌ / ⚠️），给模型看时去掉。 */
    private val RESULT_MARKER = Regex("^(✅|❌|⚠️|⚠)\\s*")

    fun build(messages: List<ChatMessageEntity>): String? {
        val turns = mutableListOf<String>()
        var lines = mutableListOf<String>()
        var lastProgress: String? = null
        var hasOutcome = false

        fun closeTurn() {
            if (lines.isEmpty()) return
            if (!hasOutcome && lastProgress != null) {
                lines.add(label("ctx_progress") + lastProgress)
            }
            turns.add(lines.joinToString("\n"))
            lines = mutableListOf()
            lastProgress = null
            hasOutcome = false
        }

        for (message in messages) {
            val text = message.text.trim()
            if (text.isEmpty()) continue
            val type = runCatching { MessageType.valueOf(message.type) }.getOrNull() ?: continue
            when (type) {
                MessageType.USER_INPUT -> {
                    closeTurn()
                    lines.add(label("ctx_user") + text)
                }
                MessageType.AGENT_QUESTION -> lines.add(label("ctx_asked") + text)
                MessageType.USER_ANSWER -> lines.add(label("ctx_answered") + text)
                MessageType.USER_SUPPLEMENT -> lines.add(label("ctx_added") + text)
                MessageType.AGENT_STATUS -> lastProgress = text
                MessageType.AGENT_RESULT -> {
                    lines.add(label("ctx_result") + text.replace(RESULT_MARKER, ""))
                    hasOutcome = true
                }
                // 停止 / 系统提示：这一轮没做完
                MessageType.SYSTEM -> if (lines.isNotEmpty() && !hasOutcome) {
                    lines.add(label("ctx_stopped") + text)
                    hasOutcome = true
                }
                MessageType.AGENT_DEBUG, MessageType.AGENT_CYCLE -> Unit
            }
        }
        closeTurn()
        if (turns.isEmpty()) return null

        // 从最新的一轮往前装，装不下就停，开头注明省略了更早的内容
        val kept = ArrayDeque<String>()
        var length = 0
        for (turn in turns.asReversed()) {
            val cost = turn.length + 2
            if (kept.isNotEmpty() && length + cost > MAX_CHARS) break
            kept.addFirst(if (turn.length > MAX_CHARS) turn.takeLast(MAX_CHARS) else turn)
            length += cost
        }
        val omitted = kept.size < turns.size
        return buildString {
            if (omitted) append(label("ctx_omitted")).append("\n\n")
            append(kept.joinToString("\n\n"))
        }
    }

    private fun label(key: String): String = LlmPrompts.agentFeedback(key)
}
