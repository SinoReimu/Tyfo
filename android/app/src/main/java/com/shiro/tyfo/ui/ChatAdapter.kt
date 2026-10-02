package com.shiro.tyfo.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shiro.tyfo.R
import com.shiro.tyfo.ui.fx.AgentIconView
import com.shiro.tyfo.ui.fx.MonetPalette
import com.shiro.tyfo.ui.fx.ShimmerTextView

/**
 * 主界面的对话列表（莫奈扁平风格，同长按唤起的语音助手）：用户的话是右侧气泡；Tyfo 的回复不带气泡，
 * 左边一个状态图标（完成打勾、失败感叹号、提问是星芒）；正在进行的那一步图标转圈、文字扫光。
 * 长按消息复制文字。
 */
class ChatAdapter(
    private val palette: MonetPalette,
    private val onLongPress: (ChatItem) -> Unit,
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(Diff) {

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_AGENT = 1
        const val TYPE_LIVE = 2
        const val TYPE_SYSTEM = 3
    }

    private object Diff : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(a: ChatItem, b: ChatItem) = a.key == b.key
        override fun areContentsTheSame(a: ChatItem, b: ChatItem) = a == b
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.live -> TYPE_LIVE
            item.type == MessageType.USER_INPUT || item.type == MessageType.USER_ANSWER ||
                item.type == MessageType.USER_SUPPLEMENT -> TYPE_USER
            item.type == MessageType.SYSTEM -> TYPE_SYSTEM
            else -> TYPE_AGENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_LIVE -> LiveHolder(inflater.inflate(R.layout.item_chat_cycle, parent, false))
            TYPE_SYSTEM -> SystemHolder(inflater.inflate(R.layout.item_chat_system, parent, false))
            else -> AgentHolder(inflater.inflate(R.layout.item_chat_agent, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserHolder -> holder.bind(item)
            is AgentHolder -> holder.bind(item)
            is LiveHolder -> holder.bind(item)
            is SystemHolder -> holder.bind(item)
        }
    }

    private fun dp(view: View, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, view.resources.displayMetrics)

    private inner class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.text_label)
        private val text: TextView = view.findViewById(R.id.text_message)

        init {
            text.setTextColor(palette.text)
            label.setTextColor(palette.textSecondary)
            text.background = GradientDrawable().apply {
                cornerRadius = dp(view, 20f)
                setColor(palette.bubble)
            }
        }

        fun bind(item: ChatItem) {
            val supplement = item.type == MessageType.USER_SUPPLEMENT
            label.visibility = if (supplement) View.VISIBLE else View.GONE
            if (supplement) label.setText(R.string.main_supplement_label)
            text.text = item.text
            itemView.alpha = if (item.pending) 0.6f else 1f
            text.setOnLongClickListener { onLongPress(item); true }
        }
    }

    private inner class AgentHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: AgentIconView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.text_label)
        private val text: TextView = view.findViewById(R.id.text_message)

        init {
            icon.showRing = true
            label.setTextColor(palette.accent)
        }

        fun bind(item: ChatItem) {
            label.visibility = View.GONE
            icon.contentDescription = null
            text.typeface = Typeface.DEFAULT
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            when (item.type) {
                MessageType.AGENT_RESULT -> {
                    val (outcome, body) = ChatRows.splitOutcome(item.text)
                    icon.visibility = View.VISIBLE
                    icon.setModeImmediate(
                        when (outcome) {
                            ChatRows.Outcome.SUCCESS -> AgentIconView.Mode.SUCCESS
                            ChatRows.Outcome.FAILURE, ChatRows.Outcome.WARNING -> AgentIconView.Mode.ERROR
                            ChatRows.Outcome.NONE -> AgentIconView.Mode.IDLE
                        }
                    )
                    // 读屏和 e2e 断言靠它认出结果
                    icon.contentDescription = itemView.context.getString(
                        if (outcome == ChatRows.Outcome.SUCCESS) R.string.main_status_done else R.string.main_status_failed
                    )
                    text.text = SimpleMarkdown.render(body)
                    text.setTextColor(palette.textBody)
                }
                MessageType.AGENT_QUESTION -> {
                    icon.visibility = View.VISIBLE
                    icon.setModeImmediate(AgentIconView.Mode.IDLE)
                    label.visibility = View.VISIBLE
                    label.setText(R.string.main_question_label)
                    text.text = SimpleMarkdown.render(item.text)
                    text.setTextColor(palette.text)
                }
                MessageType.AGENT_DEBUG -> {
                    icon.visibility = View.INVISIBLE
                    text.typeface = Typeface.MONOSPACE
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    text.text = item.text
                    text.setTextColor(palette.textSecondary)
                }
                else -> {
                    // 过去的进度（接管提示、停止前最后一步）：不带图标，次要文字
                    icon.visibility = View.INVISIBLE
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    text.text = item.text
                    text.setTextColor(palette.textSecondary)
                }
            }
            text.setOnLongClickListener { onLongPress(item); true }
        }
    }

    private inner class LiveHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: AgentIconView = view.findViewById(R.id.icon)
        private val text: ShimmerTextView = view.findViewById(R.id.cycle_text)

        init {
            icon.showRing = true
            icon.mode = AgentIconView.Mode.RUNNING
            text.setTextColor(palette.textSecondary)
            text.shimmerColor = palette.text
            text.shimmering = true
        }

        fun bind(item: ChatItem) {
            text.text = item.text
        }
    }

    private inner class SystemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.text_message)

        init {
            text.setTextColor(palette.textSecondary)
            text.background = GradientDrawable().apply {
                cornerRadius = dp(view, 12f)
                setColor(palette.ghost)
            }
        }

        fun bind(item: ChatItem) {
            text.text = item.text
        }
    }
}
