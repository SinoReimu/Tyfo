package com.shiro.tyfo.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shiro.tyfo.R
import com.shiro.tyfo.db.GlobalMemoryEntity
import com.shiro.tyfo.ui.fx.MonetPalette

/** 记忆列表：一条一张扁平卡片（莫奈色调，同主界面），点开编辑，长按启用 / 停用 / 删除。 */
class MemoryAdapter(
    private val palette: MonetPalette,
    private val onClick: (GlobalMemoryEntity) -> Unit,
    private val onLongClick: (GlobalMemoryEntity) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

    private val items = mutableListOf<GlobalMemoryEntity>()

    fun submitList(memories: List<GlobalMemoryEntity>) {
        items.clear()
        items.addAll(memories)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false)
        return ViewHolder(view, palette, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class ViewHolder(
        itemView: View,
        private val palette: MonetPalette,
        private val onClick: (GlobalMemoryEntity) -> Unit,
        private val onLongClick: (GlobalMemoryEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val keyText: TextView = itemView.findViewById(R.id.text_key)
        private val valueText: TextView = itemView.findViewById(R.id.text_value)
        private val metaText: TextView = itemView.findViewById(R.id.text_meta)
        private val enabledText: TextView = itemView.findViewById(R.id.text_enabled)
        private val badge = GradientDrawable()

        init {
            val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, itemView.resources.displayMetrics)
            itemView.background = RippleDrawable(
                ColorStateList.valueOf(0x22FFFFFF),
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(palette.drawer)
                    setStroke(maxOf(1, (itemView.resources.displayMetrics.density / 2).toInt()), palette.hairline)
                },
                null,
            )
            keyText.setTextColor(palette.text)
            valueText.setTextColor(palette.textBody)
            metaText.setTextColor(palette.textSecondary)
            badge.cornerRadius = radius
            enabledText.background = badge
        }

        fun bind(memory: GlobalMemoryEntity) {
            val ctx = itemView.context
            keyText.text = memory.key
            valueText.text = memory.value
            enabledText.text = if (memory.enabled) ctx.getString(R.string.memory_status_enabled) else ctx.getString(R.string.memory_status_disabled)
            enabledText.setTextColor(if (memory.enabled) palette.success else palette.textSecondary)
            badge.setColor(if (memory.enabled) palette.success and 0x00FFFFFF or 0x26000000 else palette.ghost)
            val source = if (memory.source == GlobalMemoryEntity.SOURCE_AI) ctx.getString(R.string.memory_source_ai) else ctx.getString(R.string.memory_source_user)
            val category = if (memory.category.isBlank()) ctx.getString(R.string.memory_uncategorized) else memory.category
            val updated = DateFormat.format("yyyy-MM-dd HH:mm", memory.updatedAt)
            metaText.text = "$source · $category · $updated"
            itemView.setOnClickListener { onClick(memory) }
            itemView.setOnLongClickListener {
                onLongClick(memory)
                true
            }
        }
    }
}
