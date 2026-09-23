package com.biyahe.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class TerminalAdapter(
    private var items: List<JSONObject>,
    private val onItemClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<TerminalAdapter.TerminalViewHolder>() {

    inner class TerminalViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTerminalName: TextView? = view.findViewById(R.id.tvTerminalName)
        val tvRouteCount: TextView? = view.findViewById(R.id.tvRouteCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TerminalViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_terminal_display, parent, false)
        return TerminalViewHolder(view)
    }

    override fun onBindViewHolder(holder: TerminalViewHolder, position: Int) {
        val item = items[position]

        val name = item.optString("terminal_name", "Terminal")
        val count = item.optInt("route_count", 0)

        holder.tvTerminalName?.text = name
        holder.tvRouteCount?.text = if (count == 1) "1 Route" else "$count Routes"

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<JSONObject>) {
        val diffResult = DiffUtil.calculateDiff(TerminalDiffCallback(items, newItems))
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    private class TerminalDiffCallback(
        private val old: List<JSONObject>,
        private val new: List<JSONObject>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val oldId = old[oldPos].optInt("terminal_id", -1)
            val newId = new[newPos].optInt("terminal_id", -2)
            return if (oldId != -1 && newId != -2) oldId == newId
            else old[oldPos].optString("terminal_name") == new[newPos].optString("terminal_name")
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            return old[oldPos].toString() == new[newPos].toString()
        }
    }
}
