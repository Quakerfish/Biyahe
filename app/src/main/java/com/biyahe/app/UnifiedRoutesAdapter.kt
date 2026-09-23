package com.biyahe.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

sealed class RouteListItem {
    data class Header(val title: String) : RouteListItem()
    data class TerminalItem(val terminalJson: JSONObject) : RouteListItem()
    data class RouteItem(val routeJson: JSONObject) : RouteListItem()
}

class UnifiedRoutesAdapter(
    private var items: List<RouteListItem>,
    private val onRouteClick: (JSONObject) -> Unit,
    private val onTerminalClick: (JSONObject) -> Unit,
    private var savedRouteIds: Set<Int> = emptySet(),
    private val onSaveClick: ((JSONObject) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TERMINAL = 1
        private const val TYPE_ROUTE = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is RouteListItem.Header -> TYPE_HEADER
            is RouteListItem.TerminalItem -> TYPE_TERMINAL
            is RouteListItem.RouteItem -> TYPE_ROUTE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_section_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_TERMINAL -> {
                val view = inflater.inflate(R.layout.item_terminal_display, parent, false)
                TerminalViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_route_display, parent, false)
                RouteViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RouteListItem.Header -> {
                (holder as HeaderViewHolder).tvTitle?.text = item.title
            }
            is RouteListItem.TerminalItem -> {
                val tHolder = holder as TerminalViewHolder
                val tJson = item.terminalJson
                val name = tJson.optString("terminal_name", "Terminal")
                val count = tJson.optInt("route_count", 0)

                tHolder.tvTerminalName?.text = name
                tHolder.tvRouteCount?.text = if (count == 1) "1 Route" else "$count Routes"
                tHolder.itemView.setOnClickListener { onTerminalClick(tJson) }
            }
            is RouteListItem.RouteItem -> {
                val rHolder = holder as RouteViewHolder
                val rJson = item.routeJson

                val code = rJson.optString("route_code")
                val origin = rJson.optString("origin_name")
                val dest = rJson.optString("destination_name")
                val vehicleType = rJson.optString("vehicle_type", "Jeepney")
                val routeId = rJson.optInt("route_id", -1)

                rHolder.tvCode?.text = code
                rHolder.tvName?.text = if (dest.isNotBlank()) "$origin – $dest" else origin
                rHolder.tvVehicleType?.text = vehicleType

                if (origin.isNotBlank() && dest.isNotBlank()) {
                    rHolder.tvTerminals?.apply {
                        visibility = View.VISIBLE
                        text = "Terminals: $origin ➔ $dest"
                    }
                } else {
                    rHolder.tvTerminals?.visibility = View.GONE
                }

                rHolder.itemView.setOnClickListener { onRouteClick(rJson) }

                if (onSaveClick != null) {
                    val isSaved = routeId != -1 && savedRouteIds.contains(routeId)
                    rHolder.btnSave?.apply {
                        visibility = View.VISIBLE
                        setImageResource(if (isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outline)
                        setColorFilter(
                            ContextCompat.getColor(
                                context, if (isSaved) R.color.brand_orange else R.color.text_muted
                            )
                        )
                        setOnClickListener { onSaveClick.invoke(rJson) }
                    }
                } else {
                    rHolder.btnSave?.visibility = View.GONE
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<RouteListItem>, newSavedIds: Set<Int> = savedRouteIds) {
        items = newItems
        savedRouteIds = newSavedIds
        notifyDataSetChanged()
    }

    fun updateSavedIds(newSavedIds: Set<Int>) {
        savedRouteIds = newSavedIds
        notifyDataSetChanged()
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView? = view.findViewById(R.id.tvHeaderTitle)
    }

    class TerminalViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTerminalName: TextView? = view.findViewById(R.id.tvTerminalName)
        val tvRouteCount: TextView? = view.findViewById(R.id.tvRouteCount)
    }

    class RouteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode: TextView? = view.findViewById(R.id.tvRouteCode)
        val tvName: TextView? = view.findViewById(R.id.tvRouteName)
        val tvVehicleType: TextView? = view.findViewById(R.id.tvVehicleType)
        val tvTerminals: TextView? = view.findViewById(R.id.tvTerminals)
        val btnSave: ImageButton? = view.findViewById(R.id.btnSaveRoute)
    }
}
