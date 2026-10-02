package com.shiro.tyfo.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shiro.tyfo.R
import com.shiro.tyfo.db.ConversationEntity
import com.shiro.tyfo.ui.fx.MonetPalette

/**
 * 侧边栏里的历史会话：一行一个标题，正在看的那段带强调色底，还在执行的末尾一个小圆点。
 * 点一下载入到主界面继续，长按删除。
 */
class DrawerHistoryAdapter(
    private val palette: MonetPalette,
    private val onOpen: (ConversationEntity) -> Unit,
    private val onLongPress: (ConversationEntity) -> Unit,
) : ListAdapter<ConversationEntity, DrawerHistoryAdapter.Holder>(Diff) {

    /** 主界面正在看的会话，高亮显示。 */
    var selectedId: Long? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            notifyRow(old)
            notifyRow(value)
        }

    private fun notifyRow(id: Long?) {
        id ?: return
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    private object Diff : DiffUtil.ItemCallback<ConversationEntity>() {
        override fun areItemsTheSame(a: ConversationEntity, b: ConversationEntity) = a.id == b.id
        override fun areContentsTheSame(a: ConversationEntity, b: ConversationEntity) =
            a.title == b.title && a.status == b.status
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.history_title)
        private val running: View = view.findViewById(R.id.history_running)
        private val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 23f, view.resources.displayMetrics)

        init {
            running.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent)
            }
        }

        fun bind(conversation: ConversationEntity) {
            val selected = conversation.id == selectedId
            title.text = conversation.title.replace('\n', ' ')
            title.setTextColor(if (selected) palette.text else palette.textBody)
            val mask = GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (selected) palette.selected else 0xFFFFFFFF.toInt())
            }
            itemView.background = RippleDrawable(
                ColorStateList.valueOf(0x22FFFFFF),
                if (selected) mask else null,
                if (selected) null else mask,
            )
            running.visibility = if (conversation.status == "running") View.VISIBLE else View.GONE
            itemView.setOnClickListener { onOpen(conversation) }
            itemView.setOnLongClickListener { onLongPress(conversation); true }
        }
    }
}
