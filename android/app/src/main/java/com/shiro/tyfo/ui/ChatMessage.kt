package com.shiro.tyfo.ui

import com.shiro.tyfo.db.ChatMessageEntity

/** 聊天记录里的消息类型，按名字存进数据库（chat_messages.type）。 */
enum class MessageType {
    USER_INPUT,
    AGENT_STATUS,
    AGENT_DEBUG,
    AGENT_QUESTION,
    USER_ANSWER,
    USER_SUPPLEMENT,
    AGENT_RESULT,
    AGENT_CYCLE,
    SYSTEM
}

/**
 * 主界面聊天列表里的一行。[key] 是数据库里的消息 id；界面自己补的行（已发出还没落库的指令、进行中的占位）用负数。
 */
data class ChatItem(
    val key: Long,
    val type: MessageType,
    val text: String,
    /** 正在进行的那一步：图标转圈、文字扫光。 */
    val live: Boolean = false,
    /** 已经发出、还没写进记录的用户消息。 */
    val pending: Boolean = false,
)

/** 把一段会话的记录变成列表里的行（纯函数，方便测）。 */
object ChatRows {
    const val KEY_PENDING = -1L
    const val KEY_LIVE = -2L

    /**
     * @param messages 当前看的会话的全部记录，新任务时为空
     * @param pending 刚发出、记录里还没有的指令
     * @param liveText 这段会话的任务正在执行时不为空：最后一条进度就是正在进行的那一步；
     *   最后一条不是进度（刚开始、刚回答完问题）时补一行占位，文字用它
     */
    fun build(messages: List<ChatMessageEntity>, pending: String?, liveText: String?): List<ChatItem> {
        val rows = messages.mapTo(ArrayList(messages.size + 2)) { m ->
            val type = runCatching { MessageType.valueOf(m.type) }.getOrDefault(MessageType.SYSTEM)
            ChatItem(m.id, type, m.text)
        }
        if (pending != null) rows.add(ChatItem(KEY_PENDING, MessageType.USER_INPUT, pending, pending = true))
        if (liveText != null) {
            val last = rows.lastOrNull()
            if (last != null && !last.pending && last.type == MessageType.AGENT_STATUS) {
                rows[rows.lastIndex] = last.copy(live = true)
            } else {
                rows.add(ChatItem(KEY_LIVE, MessageType.AGENT_STATUS, liveText, live = true))
            }
        }
        return rows
    }

    /** 结果消息开头的状态标记（✅ / ❌ / ⚠️，见 AgentService）拆出来，界面用图标表示。 */
    enum class Outcome { SUCCESS, FAILURE, WARNING, NONE }

    fun splitOutcome(text: String): Pair<Outcome, String> {
        val trimmed = text.trimStart()
        for ((marker, outcome) in MARKERS) {
            if (trimmed.startsWith(marker)) return outcome to trimmed.removePrefix(marker).trimStart()
        }
        return Outcome.NONE to text
    }

    private val MARKERS = listOf(
        "✅" to Outcome.SUCCESS,
        "❌" to Outcome.FAILURE,
        "⚠️" to Outcome.WARNING,
        "⚠" to Outcome.WARNING,
    )
}
