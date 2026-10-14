package com.biyahe.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class RatingsActivity : BaseActivity() {

    private lateinit var rvRatedRoutes: RecyclerView
    private lateinit var pbRatingsLoading: ProgressBar
    private lateinit var tvEmptyRatings: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var adapter: RatedRoutesAdapter
    private var emptyMessage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ratings)

        btnBack = findViewById(R.id.btnBack)
        rvRatedRoutes = findViewById(R.id.rvRatedRoutes)
        pbRatingsLoading = findViewById(R.id.pbRatingsLoading)
        tvEmptyRatings = findViewById(R.id.tvEmptyRatings)
        emptyMessage = tvEmptyRatings.text.toString()

        btnBack.setOnClickListener { finish() }

        adapter = RatedRoutesAdapter(emptyList()) { selectedRating ->
            val routeId = selectedRating.optInt("route_id", -1)
            val routeCode = selectedRating.optString("route_code")
            val origin = selectedRating.optString("origin_name")
            val dest = selectedRating.optString("destination_name")

            if (routeId != -1 && routeCode.isNotBlank()) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("EXTRA_ROUTE_ID", routeId)
                    putExtra("EXTRA_ROUTE_CODE", routeCode)
                    putExtra("EXTRA_ORIGIN", origin)
                    putExtra("EXTRA_DESTINATION", dest)
                    putExtra("EXTRA_VEHICLE_TYPE", selectedRating.optString("vehicle_type", "Jeepney"))
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        rvRatedRoutes.layoutManager = LinearLayoutManager(this)
        rvRatedRoutes.adapter = adapter

        loadRatings()
    }

    private fun loadRatings() {
        pbRatingsLoading.visibility = View.VISIBLE
        tvEmptyRatings.visibility = View.GONE
        tvEmptyRatings.setOnClickListener(null)
        tvEmptyRatings.text = emptyMessage
        rvRatedRoutes.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cookie = getSharedPreferences("app_session", Context.MODE_PRIVATE)
                    .getString("session_cookie", null)
                val requestBuilder = Request.Builder().url("${ApiConfig.GET_PROFILE_URL}/ratings").get()
                if (!cookie.isNullOrEmpty()) requestBuilder.addHeader("Cookie", cookie)

                val client = OkHttpClient()
                val response = client.newCall(requestBuilder.build()).execute()
                val bodyText = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyText) } catch (_: Exception) { null }

                withContext(Dispatchers.Main) {
                    pbRatingsLoading.visibility = View.GONE

                    if (response.isSuccessful && json?.optBoolean("success", false) == true) {
                        val arr = json.optJSONArray("ratings")
                        val list = mutableListOf<JSONObject>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                list.add(arr.getJSONObject(i))
                            }
                        }

                        if (list.isEmpty()) {
                            tvEmptyRatings.visibility = View.VISIBLE
                            rvRatedRoutes.visibility = View.GONE
                        } else {
                            tvEmptyRatings.visibility = View.GONE
                            rvRatedRoutes.visibility = View.VISIBLE
                            adapter.updateList(list)
                        }
                    } else if (response.code == 401) {
                        getSharedPreferences("app_session", MODE_PRIVATE).edit().clear().apply()
                        Toast.makeText(this@RatingsActivity, "Session expired. Please log in.", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@RatingsActivity, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    } else {
                        // A real failure is not "no ratings": say so and let the user retry.
                        showLoadError(json?.optString("message").orEmpty().ifBlank { "HTTP ${response.code}" })
                    }
                }
            } catch (e: Exception) {
                Log.w("RatingsActivity", "loadRatings failed", e)
                withContext(Dispatchers.Main) {
                    pbRatingsLoading.visibility = View.GONE
                    showLoadError("Check your connection")
                }
            }
        }
    }

    private fun showLoadError(reason: String) {
        rvRatedRoutes.visibility = View.GONE
        tvEmptyRatings.text = "Couldn't load your ratings ($reason). Tap here to retry."
        tvEmptyRatings.setOnClickListener { loadRatings() }
        tvEmptyRatings.visibility = View.VISIBLE
    }

    private class RatedRoutesAdapter(
        private var items: List<JSONObject>,
        private val onItemClick: (JSONObject) -> Unit
    ) : RecyclerView.Adapter<RatedRoutesAdapter.ViewHolder>() {

        fun updateList(newList: List<JSONObject>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rated_route, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvCode = itemView.findViewById<TextView>(R.id.tvRatedRouteCode)
            private val tvName = itemView.findViewById<TextView>(R.id.tvRatedRouteName)
            private val tvDate = itemView.findViewById<TextView>(R.id.tvRatedDate)
            private val rbRoute = itemView.findViewById<RatingBar>(R.id.rbRouteAccuracy)
            private val rbFare = itemView.findViewById<RatingBar>(R.id.rbFareAccuracy)
            private val tvComment = itemView.findViewById<TextView>(R.id.tvRatedComment)

            fun bind(item: JSONObject) {
                val code = item.optString("route_code", "Route")
                val origin = item.optString("origin_name", "N/A")
                val dest = item.optString("destination_name", "N/A")
                val routeAcc = item.optInt("route_accuracy_rating", 5)
                val fareAcc = item.optInt("fare_accuracy_rating", 5)
                val comment = item.optString("comment", "").trim()
                val dateStr = item.optString("date_updated", "").take(10)

                tvCode.text = code
                tvName.text = if (dest != "N/A" && dest.isNotBlank()) "$origin – $dest" else origin
                tvDate.text = dateStr

                rbRoute.rating = routeAcc.toFloat()
                rbFare.rating = fareAcc.toFloat()

                if (comment.isNotBlank() && !comment.equals("null", ignoreCase = true)) {
                    tvComment.text = "\"$comment\""
                    tvComment.visibility = View.VISIBLE
                } else {
                    tvComment.visibility = View.GONE
                }

                itemView.setOnClickListener { onItemClick(item) }
            }
        }
    }
}