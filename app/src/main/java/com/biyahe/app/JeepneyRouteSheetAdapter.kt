package com.biyahe.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class JeepneyRouteSheetAdapter(
    private var items: List<JSONObject>,
    private var savedRouteIds: Set<Int>,
    private val onItemClick: (JSONObject) -> Unit,
    private val onSaveClick: (JSONObject, Boolean) -> Unit
) : RecyclerView.Adapter<JeepneyRouteSheetAdapter.ViewHolder>() {

    private val colors = listOf("#0038A8", "#CE1126", "#FCD116", "#008751")

    fun updateList(newItems: List<JSONObject>, newSavedIds: Set<Int> = savedRouteIds) {
        items = newItems
        savedRouteIds = newSavedIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_jeepney_route_sheet, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], colors[position % colors.size])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val vIndicator = itemView.findViewById<View>(R.id.vRouteColorIndicator)
        private val tvName = itemView.findViewById<TextView>(R.id.tvSheetItemRouteName)
        private val tvCode = itemView.findViewById<TextView>(R.id.tvSheetItemRouteCode)
        private val tvSubtitle = itemView.findViewById<TextView>(R.id.tvSheetItemRouteSubtitle)
        private val btnSave = itemView.findViewById<ImageView>(R.id.btnSheetSaveRoute)

        fun bind(item: JSONObject, colorHex: String) {
            vIndicator.setBackgroundColor(Color.parseColor(colorHex))

            val isTerminal = item.has("terminal_name") || item.has("terminal_id")
            val id = if (isTerminal) item.optInt("terminal_id", -1) else item.optInt("route_id", -1)

            if (isTerminal) {
                val terminalName = item.optString("terminal_name", "Terminal")
                val routeCount = item.optInt("route_count", 3)

                tvCode.text = "Terminal"
                tvName.text = terminalName
                tvSubtitle.text = "$routeCount active jeepney routes connected"
            } else {
                val code = item.optString("route_code")
                val origin = item.optString("origin_name", "N/A")
                val dest = item.optString("destination_name", "N/A")

                tvCode.text = code
                tvName.text = if (dest != "N/A") "$origin – $dest" else origin

                val waypointsCount = item.optJSONArray("waypoints")?.length() ?: 12
                tvSubtitle.text = "via $origin, $dest · $waypointsCount jeeps on route"
            }

            val isSaved = savedRouteIds.contains(id)
            if (isSaved) {
                btnSave.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(itemView.context, R.color.ph_blue)
                )
            } else {
                btnSave.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(itemView.context, R.color.text_muted)
                )
            }

            btnSave.setOnClickListener {
                onSaveClick(item, isSaved)
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
