package com.biyahe.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class RouteSuggestionAdapter(
    private var items: List<JSONObject>,
    private val onItemClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<RouteSuggestionAdapter.SuggestionViewHolder>() {

    inner class SuggestionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode: TextView = view.findViewById(R.id.tvSuggestionCode)
        val tvName: TextView = view.findViewById(R.id.tvSuggestionName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_suggestion, parent, false)
        return SuggestionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val item = items[position]
        val code = item.optString("route_code")
        val origin = item.optString("origin_name")
        val dest = item.optString("destination_name")

        holder.tvCode.text = code
        holder.tvName.text = if (dest.isNotBlank()) "$origin – $dest" else origin

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<JSONObject>) {
        val diffResult = DiffUtil.calculateDiff(SuggestionDiffCallback(items, newItems))
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    private class SuggestionDiffCallback(
        private val old: List<JSONObject>,
        private val new: List<JSONObject>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val oldId = old[oldPos].optInt("route_id", -1)
            val newId = new[newPos].optInt("route_id", -2)
            return if (oldId != -1 && newId != -2) oldId == newId
            else old[oldPos].optString("route_code") == new[newPos].optString("route_code")
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            return old[oldPos].toString() == new[newPos].toString()
        }
    }
}
