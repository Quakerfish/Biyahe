package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread

class RoutesActivity : BaseActivity() {

    private enum class CategoryFilter { ALL, SAVED, ROUTES, TERMINALS }
    private var currentFilter = CategoryFilter.ALL

    private val allItemsList = mutableListOf<JSONObject>()
    private val allTerminalsList = mutableListOf<JSONObject>()
    private var savedRouteIds = mutableSetOf<Int>()

    private lateinit var unifiedAdapter: UnifiedRoutesAdapter

    private val sessionPrefs by lazy { getSharedPreferences("app_session", MODE_PRIVATE) }

    private var etSearch: EditText? = null
    private var rvRoutes: RecyclerView? = null
    private var bottomNav: BottomNavigationView? = null
    private var progressBar: ProgressBar? = null
    private var tvEmptyState: TextView? = null
    private var chipGroupCategory: ChipGroup? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_routes)

        initViews()
        setupAdapter()
        setupSearch()
        setupFilterChips()
        bottomNav?.let { setupBottomNav(it, R.id.nav_routes) }
        loadRoutesFromDb()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bottomNav?.let { setupBottomNav(it, R.id.nav_routes) }
    }

    override fun onResume() {
        super.onResume()
        bottomNav?.let { setupBottomNav(it, R.id.nav_routes) }
        loadSavedRouteIds()
    }

    private fun initViews() {
        etSearch = findViewById(R.id.etSearch)
        rvRoutes = findViewById(R.id.rvRoutes)
        bottomNav = findViewById(R.id.bottomNav)
        progressBar = findViewById(R.id.progressBar)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        chipGroupCategory = findViewById(R.id.chipGroupCategory)
    }

    private fun setupAdapter() {
        unifiedAdapter = UnifiedRoutesAdapter(
            items = emptyList(),
            onRouteClick = { selectedRoute ->
                showRouteDetailBottomSheet(selectedRoute)
            },
            onTerminalClick = { selectedTerminal ->
                showTerminalRoutesBottomSheet(selectedTerminal)
            },
            savedRouteIds = savedRouteIds,
            onSaveClick = { route -> toggleSaveRoute(route) }
        )

        rvRoutes?.layoutManager = LinearLayoutManager(this)
        rvRoutes?.adapter = unifiedAdapter
    }

    private fun setupFilterChips() {
        chipGroupCategory?.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipSaved) -> CategoryFilter.SAVED
                checkedIds.contains(R.id.chipRoutes) -> CategoryFilter.ROUTES
                checkedIds.contains(R.id.chipTerminals) -> CategoryFilter.TERMINALS
                else -> CategoryFilter.ALL
            }

            if (currentFilter == CategoryFilter.TERMINALS && allTerminalsList.isEmpty()) {
                fetchTerminalsFromDb()
            } else {
                applySearchFilter()
            }
        }
    }

    private fun showTerminalRoutesBottomSheet(terminal: JSONObject) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_route_detail, null)
        dialog.setContentView(sheetView)

        val tId = terminal.optInt("terminal_id", -1)
        val tName = terminal.optString("terminal_name", "Terminal")

        val matchingRoutes = allItemsList.filter { r ->
            val origId = r.optInt("origin_terminal_id", -1)
            val destId = r.optInt("destination_terminal_id", -1)
            val origName = r.optString("origin_name", "").lowercase()
            val destName = r.optString("destination_name", "").lowercase()

            (tId != -1 && (origId == tId || destId == tId)) ||
                    (tName.isNotEmpty() && (origName == tName.lowercase() || destName == tName.lowercase()))
        }

        sheetView.findViewById<TextView>(R.id.tvSheetRouteCode)?.text = "📍"
        sheetView.findViewById<TextView>(R.id.tvSheetRouteName)?.text = tName
        sheetView.findViewById<TextView>(R.id.tvSheetVehicleType)?.text = "${matchingRoutes.size} Active Route(s)"
        sheetView.findViewById<TextView>(R.id.tvSheetOriginTerminal)?.text = "Terminal Location: $tName"
        sheetView.findViewById<TextView>(R.id.tvSheetDestinationTerminal)?.text = "Routes passing here: ${matchingRoutes.joinToString { it.optString("route_code") }}"

        sheetView.findViewById<View>(R.id.btnTrackRoute)?.visibility = View.GONE

        dialog.show()
    }

    private fun showRouteDetailBottomSheet(route: JSONObject) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_route_detail, null)
        dialog.setContentView(sheetView)

        val code = route.optString("route_code")
        val origin = route.optString("origin_name", "N/A")
        val dest = route.optString("destination_name", "N/A")
        val type = route.optString("vehicle_type", "Jeepney")
        val routeId = route.optInt("route_id", -1)

        sheetView.findViewById<TextView>(R.id.tvSheetRouteCode)?.text = code
        sheetView.findViewById<TextView>(R.id.tvSheetRouteName)?.text = if (dest != "N/A") "$origin – $dest" else origin
        sheetView.findViewById<TextView>(R.id.tvSheetVehicleType)?.text = type
        sheetView.findViewById<TextView>(R.id.tvSheetOriginTerminal)?.text = origin
        sheetView.findViewById<TextView>(R.id.tvSheetDestinationTerminal)?.text = dest

        sheetView.findViewById<View>(R.id.btnTrackRoute)?.setOnClickListener {
            dialog.dismiss()
            trackRouteOnMap(routeId, code, origin, dest, type)
        }

        dialog.show()
    }

    private fun trackRouteOnMap(routeId: Int, code: String, origin: String, dest: String, type: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_ROUTE_ID", routeId)
            putExtra("EXTRA_ROUTE_CODE", code)
            putExtra("EXTRA_ORIGIN", origin)
            putExtra("EXTRA_DESTINATION", dest)
            putExtra("EXTRA_VEHICLE_TYPE", type)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
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
        val listItems = mutableListOf<RouteListItem>()

        val filteredRoutes = allItemsList.filter { item ->
            val code = item.optString("route_code").lowercase()
            val origin = item.optString("origin_name").lowercase()
            val dest = item.optString("destination_name").lowercase()

            query.isEmpty() || code.contains(query) || origin.contains(query) || dest.contains(query)
        }

        val filteredTerminals = allTerminalsList.filter { term ->
            val name = term.optString("terminal_name").lowercase()
            query.isEmpty() || name.contains(query)
        }

        when (currentFilter) {
            CategoryFilter.ALL -> {
                if (filteredRoutes.isNotEmpty()) {
                    listItems.add(RouteListItem.Header("ACTIVE ROUTES"))
                    filteredRoutes.forEach { listItems.add(RouteListItem.RouteItem(it)) }
                }
                if (filteredTerminals.isNotEmpty()) {
                    listItems.add(RouteListItem.Header("TERMINALS"))
                    filteredTerminals.forEach { listItems.add(RouteListItem.TerminalItem(it)) }
                }
            }
            CategoryFilter.SAVED -> {
                val savedRoutes = filteredRoutes.filter { item ->
                    val id = item.optInt("route_id", -1)
                    id != -1 && savedRouteIds.contains(id)
                }
                if (savedRoutes.isNotEmpty()) {
                    listItems.add(RouteListItem.Header("SAVED ROUTES"))
                    savedRoutes.forEach { listItems.add(RouteListItem.RouteItem(it)) }
                }
            }
            CategoryFilter.ROUTES -> {
                if (filteredRoutes.isNotEmpty()) {
                    listItems.add(RouteListItem.Header("ACTIVE ROUTES"))
                    filteredRoutes.forEach { listItems.add(RouteListItem.RouteItem(it)) }
                }
            }
            CategoryFilter.TERMINALS -> {
                if (filteredTerminals.isNotEmpty()) {
                    listItems.add(RouteListItem.Header("TERMINALS"))
                    filteredTerminals.forEach { listItems.add(RouteListItem.TerminalItem(it)) }
                }
            }
        }

        unifiedAdapter.updateData(listItems, savedRouteIds)
        updateEmptyState(listItems.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        tvEmptyState?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvRoutes?.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun loadRoutesFromDb() {
        setLoading(true)
        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(ApiConfig.ROUTES_URL)
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
                    val responseObj = JSONObject(jsonStr)

                    if (responseObj.optBoolean("success", false)) {
                        val routesArray = responseObj.getJSONArray("routes")

                        allItemsList.clear()
                        for (i in 0 until routesArray.length()) {
                            allItemsList.add(routesArray.getJSONObject(i))
                        }

                        runOnUiThread {
                            setLoading(false)
                            fetchTerminalsFromDb()
                        }
                    } else {
                        runOnUiThread {
                            setLoading(false)
                            val message = responseObj.optString("message", "Failed to load routes.")
                            Toast.makeText(this@RoutesActivity, message, Toast.LENGTH_LONG).show()
                            updateEmptyState(true)
                        }
                    }
                } else {
                    runOnUiThread {
                        setLoading(false)
                        Toast.makeText(this@RoutesActivity, "API Error: HTTP $responseCode", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this@RoutesActivity, "Failed to load routes: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    updateEmptyState(true)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun fetchTerminalsFromDb() {
        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${ApiConfig.ROUTES_URL}?type=terminals")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val sessionCookie = sessionPrefs.getString("session_cookie", null)
                if (!sessionCookie.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", sessionCookie)
                }

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().readText()
                    val responseObj = JSONObject(jsonStr)

                    if (responseObj.optBoolean("success", false)) {
                        val terminalsArray = responseObj.getJSONArray("terminals")
                        allTerminalsList.clear()

                        for (i in 0 until terminalsArray.length()) {
                            val termObj = terminalsArray.getJSONObject(i)
                            val tId = termObj.optInt("terminal_id", -1)
                            val tName = termObj.optString("terminal_name", "").lowercase()

                            val count = allItemsList.count { r ->
                                val origId = r.optInt("origin_terminal_id", -1)
                                val destId = r.optInt("destination_terminal_id", -1)
                                val origName = r.optString("origin_name", "").lowercase()
                                val destName = r.optString("destination_name", "").lowercase()

                                (tId != -1 && (origId == tId || destId == tId)) ||
                                        (tName.isNotEmpty() && (origName == tName || destName == tName))
                            }

                            termObj.put("route_count", count)
                            allTerminalsList.add(termObj)
                        }

                        runOnUiThread {
                            applySearchFilter()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Fetches the user's saved routes just to build the set of route_ids, so rows can show a filled bookmark. */
    private fun loadSavedRouteIds() {
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

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().readText()

                    val savedArray = if (jsonStr.trim().startsWith("{")) {
                        val responseObj = JSONObject(jsonStr)
                        responseObj.optJSONArray("routes") ?: responseObj.optJSONArray("saved_routes") ?: JSONArray()
                    } else {
                        JSONArray(jsonStr)
                    }

                    val ids = mutableSetOf<Int>()
                    for (i in 0 until savedArray.length()) {
                        ids.add(savedArray.getJSONObject(i).optInt("route_id", -1))
                    }
                    ids.remove(-1)

                    runOnUiThread {
                        savedRouteIds = ids
                        unifiedAdapter.updateSavedIds(savedRouteIds)
                        applySearchFilter()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Saves or unsaves [route] depending on its current state, then updates the icon optimistically once the server confirms. */
    private fun toggleSaveRoute(route: JSONObject) {
        val routeId = route.optInt("route_id", -1)
        if (routeId == -1) {
            Toast.makeText(this, "This route can't be saved (missing id).", Toast.LENGTH_SHORT).show()
            return
        }

        val alreadySaved = savedRouteIds.contains(routeId)

        thread {
            var conn: HttpURLConnection? = null
            try {
                val sessionCookie = sessionPrefs.getString("session_cookie", null)

                if (alreadySaved) {
                    val url = URL("${ApiConfig.SAVED_ROUTES_URL}?route_id=$routeId")
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                        connectTimeout = 8000
                        readTimeout = 8000
                        if (!sessionCookie.isNullOrEmpty()) setRequestProperty("Cookie", sessionCookie)
                    }
                } else {
                    val url = URL(ApiConfig.SAVED_ROUTES_URL)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        if (!sessionCookie.isNullOrEmpty()) setRequestProperty("Cookie", sessionCookie)
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    val body = JSONObject().apply { put("route_id", routeId) }
                    OutputStreamWriter(conn.outputStream).use { it.write(body.toString()); it.flush() }
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val success = jsonResponse.optBoolean("success", responseCode in 200..299)

                runOnUiThread {
                    if (success) {
                        if (alreadySaved) savedRouteIds.remove(routeId) else savedRouteIds.add(routeId)
                        unifiedAdapter.updateSavedIds(savedRouteIds)
                        applySearchFilter()
                        Toast.makeText(
                            this@RoutesActivity,
                            if (alreadySaved) "Removed from saved routes" else "Saved route",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val message = jsonResponse.optString("message", getString(R.string.error_generic))
                        Toast.makeText(this@RoutesActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread { showNetworkError("timed out") }
            } catch (e: IOException) {
                runOnUiThread { showNetworkError() }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@RoutesActivity, "Couldn't update saved routes: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) tvEmptyState?.visibility = View.GONE
    }

    override fun onDestroy() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
