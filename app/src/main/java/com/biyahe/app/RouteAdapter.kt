package com.biyahe.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * @param savedRouteIds route_ids that should render with a filled bookmark. Pass an
 *   ever-growing/shrinking set from the activity (RoutesActivity tracks "which of these
 *   are saved"; SavedActivity — where everything shown is already saved — just passes
 *   every route_id in [items]).
 * @param onSaveClick called when the bookmark button itself is tapped. Kept separate
 *   from [onItemClick] (which fires for taps anywhere else on the row) so saving a route
 *   doesn't also trigger whatever "row tapped" behavior the screen has.
 */
class RouteAdapter(
    private var items: List<JSONObject>,
    private val onItemClick: (JSONObject) -> Unit,
    private var savedRouteIds: Set<Int> = emptySet(),
    private val onSaveClick: ((JSONObject) -> Unit)? = null
) : RecyclerView.Adapter<RouteAdapter.RouteViewHolder>() {

    inner class RouteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode: TextView? = view.findViewById(R.id.tvRouteCode)
        val tvName: TextView? = view.findViewById(R.id.tvRouteName)
        val tvVehicleType: TextView? = view.findViewById(R.id.tvVehicleType)
        val tvTerminals: TextView? = view.findViewById(R.id.tvTerminals)
        val btnSave: ImageButton? = view.findViewById(R.id.btnSaveRoute)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_display, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val item = items[position]

        val code = item.optString("route_code")
        val origin = item.optString("origin_name")
        val dest = item.optString("destination_name")
        val vehicleType = item.optString("vehicle_type", "Jeepney")
        val routeId = item.optInt("route_id", -1)

        holder.tvCode?.text = code
        holder.tvName?.text = if (dest.isNotBlank()) "$origin – $dest" else origin
        holder.tvVehicleType?.text = vehicleType

        if (origin.isNotBlank() && dest.isNotBlank()) {
            holder.tvTerminals?.apply {
                visibility = View.VISIBLE
                text = "Terminals: $origin ➔ $dest"
            }
        } else {
            holder.tvTerminals?.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(item) }

        if (onSaveClick != null) {
            val isSaved = routeId != -1 && savedRouteIds.contains(routeId)
            holder.btnSave?.apply {
                visibility = View.VISIBLE
                setImageResource(if (isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outline)
                setColorFilter(
                    ContextCompat.getColor(
                        context, if (isSaved) R.color.brand_orange else R.color.text_muted
                    )
                )
                setOnClickListener { onSaveClick.invoke(item) }
            }
        } else {
            holder.btnSave?.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    /** Replaces the list, diffing against the previous one so only changed rows re-bind/animate. */
    fun updateList(newItems: List<JSONObject>) {
        val diffResult = DiffUtil.calculateDiff(RouteDiffCallback(items, newItems))
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    /** Updates which route_ids should show a filled bookmark, without touching the list itself. */
    fun updateSavedIds(newSavedIds: Set<Int>) {
        savedRouteIds = newSavedIds
        notifyDataSetChanged()
    }

    private class RouteDiffCallback(
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