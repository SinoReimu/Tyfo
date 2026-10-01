package com.shiro.tyfo.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shiro.tyfo.R
import com.shiro.tyfo.db.RoutineEntity

class RoutineRecentAdapter(
    private val onItemClick: (RoutineEntity) -> Unit,
    private val onItemLongClick: ((RoutineEntity) -> Unit)? = null
) : RecyclerView.Adapter<RoutineRecentAdapter.ViewHolder>() {

    private var routines: List<RoutineEntity> = emptyList()

    fun submitList(list: List<RoutineEntity>) {
        routines = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_routine_recent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val routine = routines[position]
        holder.bind(routine)
        holder.itemView.setOnClickListener { onItemClick(routine) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(routine)
            true
        }
    }

    override fun getItemCount(): Int = routines.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconText: TextView = itemView.findViewById(R.id.recent_icon)
        private val nameText: TextView = itemView.findViewById(R.id.recent_name)
        private val descText: TextView = itemView.findViewById(R.id.recent_desc)

        fun bind(routine: RoutineEntity) {
            val context = itemView.context
            iconText.text = routine.icon

            // Resolve string resource by key
            val nameResId = context.resources.getIdentifier(routine.name, "string", context.packageName)
            nameText.text = if (nameResId != 0) context.getString(nameResId) else routine.name

            // Description
            if (routine.description != null) {
                val descResId = context.resources.getIdentifier(routine.description, "string", context.packageName)
                descText.text = if (descResId != 0) context.getString(descResId) else routine.description
                descText.visibility = View.VISIBLE
            } else {
                descText.visibility = View.GONE
            }
        }
    }
}
