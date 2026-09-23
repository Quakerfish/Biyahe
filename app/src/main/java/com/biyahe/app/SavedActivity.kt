package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread

class SavedActivity : BaseActivity() {

    private val allSavedList = mutableListOf<JSONObject>()
    private lateinit var adapter: RouteAdapter
    private val sessionPrefs by lazy { getSharedPreferences("app_session", MODE_PRIVATE) }

    private var etSearch: EditText? = null
    private var rvSavedRoutes: RecyclerView? = null
    private var bottomNav: BottomNavigationView? = null
    private var progressBar: ProgressBar? = null
    private var emptyStateGroup: LinearLayout? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved)

        initViews()
        setupAdapter()
        setupSearch()
        bottomNav?.let { setupBottomNav(it, -1) }
        loadSavedRoutes()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bottomNav?.let { setupBottomNav(it, -1) }
    }

    override fun onResume() {
        super.onResume()
        bottomNav?.let { setupBottomNav(it, -1) }
        loadSavedRoutes()
    }

    private fun initViews() {
        etSearch = findViewById(R.id.etSearch)
        rvSavedRoutes = findViewById(R.id.rvSavedRoutes)
        bottomNav = findViewById(R.id.bottomNav)
        progressBar = findViewById(R.id.progressBar)
        emptyStateGroup = findViewById(R.id.emptyStateGroup)
    }

    private fun setupAdapter() {
        adapter = RouteAdapter(
            items = emptyList(),
            onItemClick = { selectedItem ->
                val code = selectedItem.optString("route_code")
                val name = selectedItem.optString("origin_name")
                Toast.makeText(this, "Selected: $code $name", Toast.LENGTH_SHORT).show()
            },
            savedRouteIds = emptySet(),
            onSaveClick = { route -> unsaveRoute(route) }
        )
        rvSavedRoutes?.layoutManager = LinearLayoutManager(this)
        rvSavedRoutes?.adapter = adapter
    }

    private fun setupSearch() {
        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingSearch?.let { searchHandler.removeCallbacks(it) }
                pendingSearch = Runnable { applySearchFilter() }.also {
                    searchHandler.postDelayed(it, 150)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applySearchFilter() {
        val query = etSearch?.text.toString().lowercase().trim()

        val filtered = allSavedList.filter { item ->
            val code = item.optString("route_code").lowercase()
            val origin = item.optString("origin_name").lowercase()
            val dest = item.optString("destination_name").lowercase()

            query.isEmpty() || code.contains(query) || origin.contains(query) || dest.contains(query)
        }

        adapter.updateList(filtered)
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        emptyStateGroup?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvSavedRoutes?.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun currentSavedIds(): Set<Int> = allSavedList.mapNotNull {
        it.optInt("route_id", -1).takeIf { id -> id != -1 }
    }.toSet()

    private fun loadSavedRoutes() {
        setLoading(true)
        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(ApiConfig.SAVED_ROUTES_URL)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val sessionCookie = sessionPrefs.getString("session_cookie", null)
                if (!sessionCookie.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", sessionCookie)
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().readText()
                    val savedArray = JSONArray(jsonStr)

                    allSavedList.clear()
                    for (i in 0 until savedArray.length()) {
                        allSavedList.add(savedArray.getJSONObject(i))
                    }

                    runOnUiThread {
                        setLoading(false)
                        adapter.updateList(allSavedList)
                        adapter.updateSavedIds(currentSavedIds())
                        updateEmptyState(allSavedList.isEmpty())
                        etSearch?.text?.let { if (it.isNotEmpty()) applySearchFilter() }
                    }
                } else {
                    // Read error details from conn.errorStream instead of inputStream
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val serverMessage = try {
                        JSONObject(errBody).optString("message", "API Error: HTTP $responseCode")
                    } catch (e: Exception) {
                        "API Error: HTTP $responseCode ($errBody)"
                    }

                    runOnUiThread {
                        setLoading(false)
                        Toast.makeText(this@SavedActivity, serverMessage, Toast.LENGTH_LONG).show()
                        updateEmptyState(true)
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread { setLoading(false); showNetworkError("timed out"); updateEmptyState(true) }
            } catch (e: IOException) {
                runOnUiThread { setLoading(false); showNetworkError(); updateEmptyState(true) }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this@SavedActivity, "Failed to load saved routes: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    updateEmptyState(true)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun unsaveRoute(route: JSONObject) {
        val routeId = route.optInt("route_id", -1)
        if (routeId == -1) return

        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${ApiConfig.SAVED_ROUTES_URL}?route_id=$routeId")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val sessionCookie = sessionPrefs.getString("session_cookie", null)
                if (!sessionCookie.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", sessionCookie)
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val success = jsonResponse.optBoolean("success", responseCode in 200..299)

                runOnUiThread {
                    if (success) {
                        allSavedList.removeAll { it.optInt("route_id", -1) == routeId }
                        applySearchFilter()
                        adapter.updateSavedIds(currentSavedIds())
                        Toast.makeText(this@SavedActivity, "Removed from saved routes", Toast.LENGTH_SHORT).show()
                    } else {
                        val message = jsonResponse.optString("message", getString(R.string.error_generic))
                        Toast.makeText(this@SavedActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread { showNetworkError("timed out") }
            } catch (e: IOException) {
                runOnUiThread { showNetworkError() }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@SavedActivity, "Couldn't remove saved route: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) emptyStateGroup?.visibility = View.GONE
    }

    override fun onDestroy() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}