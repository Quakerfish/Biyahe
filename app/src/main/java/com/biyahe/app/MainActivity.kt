package com.biyahe.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.biyahe.app.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private val prefs by lazy { getSharedPreferences("jeepney_search", MODE_PRIVATE) }
    private val sessionPrefs by lazy { getSharedPreferences("app_session", MODE_PRIVATE) }

    private val availableRoutes = mutableListOf<JSONObject>()
    private lateinit var routeAdapter: RouteSuggestionAdapter

    private val activeMarkers = mutableListOf<Marker>()
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<MaterialCardView>
    private var isProgrammaticTextChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        setupBottomSheet()
        setupRouteCardCloseButton()

        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map: MapLibreMap ->
            this.mapLibreMap = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false

            val cebuCityCenter = LatLng(10.3156, 123.8854)
            map.cameraPosition = CameraPosition.Builder()
                .target(cebuCityCenter)
                .zoom(12.0)
                .build()

            val wideCebuBounds = LatLngBounds.Builder()
                .include(LatLng(9.4000, 123.2000))
                .include(LatLng(11.3000, 124.2000))
                .build()
            map.setLatLngBoundsForCameraTarget(wideCebuBounds)
            map.setMinZoomPreference(10.0)
            map.setMaxZoomPreference(18.0)

            val apiKey = "5hqAX6ehvk13Ic2HPmia"
            val styleUrl = "https://api.maptiler.com/maps/01a0aa36-ae40-7883-9e61-8720a18435db/style.json?key=$apiKey"

            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                initRouteLayer(style)
            }
        }

        setupRecyclerView()
        setupSearchBar()
        setupButtonsAndNav()
        loadRouteSuggestions()
        loadSavedRouteChips()
        loadUserProfileAvatar()
        checkTrackIntent(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (binding.cvSuggestions.visibility == View.VISIBLE) {
                val searchRect = Rect()
                val suggestionsRect = Rect()

                binding.etSearchDestination.getGlobalVisibleRect(searchRect)
                binding.cvSuggestions.getGlobalVisibleRect(suggestionsRect)

                val x = ev.rawX.toInt()
                val y = ev.rawY.toInt()

                if (!searchRect.contains(x, y) && !suggestionsRect.contains(x, y)) {
                    binding.cvSuggestions.visibility = View.GONE
                    binding.etSearchDestination.clearFocus()
                    hideKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.routeInfoCard)
        bottomSheetBehavior.isHideable = true
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    clearMapRoute()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })
    }

    private fun setupRouteCardCloseButton() {
        binding.btnCloseRouteCard.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    private fun clearMapRoute() {
        mapLibreMap?.let { map ->
            activeMarkers.forEach { map.removeMarker(it) }
            activeMarkers.clear()

            map.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
                source?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(emptyList())))
            }
        }
    }

    private fun initRouteLayer(style: Style) {
        if (style.getSource(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        }

        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor(Color.parseColor("#FF6D00")),
                lineWidth(3.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )

            val annotationLayer = style.layers.firstOrNull { it.id.contains("annotation", ignoreCase = true) }
            if (annotationLayer != null) {
                style.addLayerBelow(lineLayer, annotationLayer.id)
            } else {
                style.addLayer(lineLayer)
            }
        }
    }

    private fun setupRecyclerView() {
        routeAdapter = RouteSuggestionAdapter(emptyList()) { selectedRoute ->
            onRouteSelected(selectedRoute)
        }
        binding.rvRouteSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvRouteSuggestions.adapter = routeAdapter
    }

    private fun loadRouteSuggestions() {
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

                        availableRoutes.clear()
                        for (i in 0 until routesArray.length()) {
                            availableRoutes.add(routesArray.getJSONObject(i))
                        }

                        runOnUiThread {
                            routeAdapter.updateList(availableRoutes)
                            if (binding.etSearchDestination.hasFocus() && availableRoutes.isNotEmpty()) {
                                binding.cvSuggestions.visibility = View.VISIBLE
                            }
                        }
                    }
                } else if (responseCode == 401) {
                    runOnUiThread {
                        sessionPrefs.edit().remove("session_cookie").apply()
                        Toast.makeText(this@MainActivity, "Session expired. Please log in again.", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "API Error: HTTP $responseCode", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread { showNetworkError("timed out") }
            } catch (e: IOException) {
                runOnUiThread { showNetworkError() }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fetch failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun loadSavedRouteChips() {
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

                    val savedList = mutableListOf<JSONObject>()
                    for (i in 0 until savedArray.length()) {
                        savedList.add(savedArray.getJSONObject(i))
                    }

                    runOnUiThread {
                        populateSavedRouteChips(savedList)
                    }
                } else {
                    runOnUiThread {
                        binding.hsvSavedChips.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.hsvSavedChips.visibility = View.GONE
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun populateSavedRouteChips(savedRoutes: List<JSONObject>) {
        binding.cgSavedRoutes.removeAllViews()
        if (savedRoutes.isEmpty()) {
            binding.hsvSavedChips.visibility = View.GONE
            return
        }

        binding.hsvSavedChips.visibility = View.VISIBLE
        for (route in savedRoutes) {
            val code = route.optString("route_code")
            if (code.isBlank()) continue

            val chip = Chip(this).apply {
                text = code
                isCheckable = false
                isClickable = true
                setChipIconResource(R.drawable.ic_bookmark)
                setChipIconTintResource(R.color.brand_orange)
                chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.bg_white)
                )
                chipStrokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.brand_orange)
                )
                chipStrokeWidth = 2f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)

                setOnClickListener {
                    onRouteSelected(route)
                }
            }
            binding.cgSavedRoutes.addView(chip)
        }
    }

    private fun loadUserProfileAvatar() {
        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(ApiConfig.GET_PROFILE_URL)
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
                        val profileImageUrl = if (responseObj.has("profile_image") && !responseObj.isNull("profile_image")) {
                            responseObj.getString("profile_image")
                        } else null

                        runOnUiThread {
                            if (!profileImageUrl.isNullOrEmpty()) {
                                binding.ivMainAvatar.setPadding(0, 0, 0, 0)
                                binding.ivMainAvatar.imageTintList = null
                                Glide.with(this@MainActivity)
                                    .load(profileImageUrl)
                                    .circleCrop()
                                    .placeholder(R.drawable.ic_person)
                                    .into(binding.ivMainAvatar)
                            } else {
                                val padInPx = (10 * resources.displayMetrics.density).toInt()
                                binding.ivMainAvatar.setPadding(padInPx, padInPx, padInPx, padInPx)
                                binding.ivMainAvatar.setImageResource(R.drawable.ic_person)
                                binding.ivMainAvatar.imageTintList = ColorStateList.valueOf(
                                    ContextCompat.getColor(this@MainActivity, R.color.brand_orange)
                                )
                            }
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

    private fun setupSearchBar() {
        binding.etSearchDestination.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (availableRoutes.isNotEmpty()) {
                    binding.cvSuggestions.visibility = View.VISIBLE
                } else {
                    loadRouteSuggestions()
                }
            } else {
                binding.cvSuggestions.visibility = View.GONE
            }
        }

        binding.etSearchDestination.setOnClickListener {
            if (availableRoutes.isNotEmpty()) {
                binding.cvSuggestions.visibility = View.VISIBLE
            } else {
                loadRouteSuggestions()
            }
        }

        binding.etSearchDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticTextChange) return
                val query = s.toString().trim()
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterSuggestions(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            isProgrammaticTextChange = true
            binding.etSearchDestination.text.clear()
            binding.btnClearSearch.visibility = View.GONE
            isProgrammaticTextChange = false
            filterSuggestions("")
            binding.etSearchDestination.requestFocus()
        }

        binding.etSearchDestination.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString().trim()
                performManualSearch(query)
                true
            } else {
                false
            }
        }
    }

    private fun filterSuggestions(query: String) {
        if (query.isEmpty()) {
            routeAdapter.updateList(availableRoutes)
        } else {
            val filtered = availableRoutes.filter { route ->
                val code = route.optString("route_code")
                val origin = route.optString("origin_name")
                val dest = route.optString("destination_name")
                code.contains(query, ignoreCase = true) ||
                        origin.contains(query, ignoreCase = true) ||
                        dest.contains(query, ignoreCase = true)
            }
            routeAdapter.updateList(filtered)
        }
        binding.cvSuggestions.visibility = View.VISIBLE
    }

    private fun showRouteDetailDialog(routeObj: JSONObject) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_route_detail, null)
        dialog.setContentView(sheetView)

        val code = routeObj.optString("route_code")
        val origin = routeObj.optString("origin_name", "N/A")
        val dest = routeObj.optString("destination_name", "N/A")
        val type = routeObj.optString("vehicle_type", "Jeepney")
        val routeId = routeObj.optInt("route_id", -1)

        sheetView.findViewById<TextView>(R.id.tvSheetRouteCode)?.text = code
        sheetView.findViewById<TextView>(R.id.tvSheetRouteName)?.text = if (dest != "N/A") "$origin – $dest" else origin
        sheetView.findViewById<TextView>(R.id.tvSheetVehicleType)?.text = type
        sheetView.findViewById<TextView>(R.id.tvSheetOriginTerminal)?.text = origin
        sheetView.findViewById<TextView>(R.id.tvSheetDestinationTerminal)?.text = dest

        sheetView.findViewById<View>(R.id.btnTrackRoute)?.setOnClickListener {
            dialog.dismiss()
            if (routeId != -1) {
                fetchRouteWaypoints(routeId, code, "$origin – $dest", type, origin, dest)
            }
        }

        dialog.show()
    }

    private fun onRouteSelected(routeObj: JSONObject) {
        val code = routeObj.optString("route_code")

        isProgrammaticTextChange = true
        binding.etSearchDestination.setText(code)
        binding.btnClearSearch.visibility = View.VISIBLE
        binding.cvSuggestions.visibility = View.GONE
        binding.etSearchDestination.clearFocus()
        isProgrammaticTextChange = false

        hideKeyboard()
        saveRecentSearch(code)

        showRouteDetailDialog(routeObj)
    }

    private fun performManualSearch(query: String) {
        if (query.isEmpty()) return
        binding.cvSuggestions.visibility = View.GONE
        hideKeyboard()

        val matched = availableRoutes.firstOrNull { route ->
            val code = route.optString("route_code")
            code.equals(query, ignoreCase = true)
        }

        if (matched != null) {
            onRouteSelected(matched)
        } else {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            Toast.makeText(this, "Route '$query' not found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchRouteWaypoints(routeId: Int, code: String, name: String, type: String, origin: String, dest: String) {
        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${ApiConfig.ROUTES_URL}?id=$routeId")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val sessionCookie = sessionPrefs.getString("session_cookie", null)
                if (!sessionCookie.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", sessionCookie)
                }

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().readText()
                    val routeObj = JSONObject(jsonStr)
                    val waypointsArray = routeObj.optJSONArray("waypoints") ?: JSONArray()

                    val points = mutableListOf<LatLng>()
                    for (i in 0 until waypointsArray.length()) {
                        val wp = waypointsArray.getJSONObject(i)
                        val lat = wp.getDouble("latitude")
                        val lng = wp.getDouble("longitude")
                        points.add(LatLng(lat, lng))
                    }

                    runOnUiThread {
                        displayRouteOnMap(points, code, name, type, origin, dest)
                    }
                } else {
                    runOnUiThread {
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                        Toast.makeText(this@MainActivity, "Couldn't load that route's path.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    showNetworkError()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun createCircleMarkerIcon(): Icon {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = size / 2f

        val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4DFF6D00")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 22f, auraPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF6D00")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 14f, fillPaint)

        val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 6f, centerDotPaint)

        return IconFactory.getInstance(this).fromBitmap(bitmap)
    }

    private fun displayRouteOnMap(points: List<LatLng>, code: String, name: String, type: String, origin: String, dest: String) {
        if (points.isEmpty()) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            Toast.makeText(this, "No waypoints found for this route.", Toast.LENGTH_SHORT).show()
            return
        }

        clearMapRoute()

        binding.tvRouteCode.text = code
        binding.tvRouteName.text = name
        binding.tvVehicleType.text = type
        binding.tvOriginDetail.text = origin
        binding.tvDestinationDetail.text = dest

        mapLibreMap?.let { map ->
            map.getStyle { style ->
                val geoJsonPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
                val lineString = LineString.fromLngLats(geoJsonPoints)
                val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
                source?.setGeoJson(Feature.fromGeometry(lineString))

                val boundsBuilder = LatLngBounds.Builder()
                points.forEach { boundsBuilder.include(it) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120))
            }

            val startPoint = points.first()
            val endPoint = points.last()
            val dotIcon = createCircleMarkerIcon()

            val startMarker = map.addMarker(
                MarkerOptions()
                    .position(startPoint)
                    .title("Origin: $origin")
                    .icon(dotIcon)
            )
            val endMarker = map.addMarker(
                MarkerOptions()
                    .position(endPoint)
                    .title("Destination: $dest")
                    .icon(dotIcon)
            )

            activeMarkers.add(startMarker)
            activeMarkers.add(endMarker)
        }

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.etSearchDestination
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun getRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    private fun saveRecentSearch(query: String) {
        val updated = LinkedHashSet<String>()
        updated.add(query)
        updated.addAll(getRecentSearches())
        prefs.edit { putString(KEY_RECENT, updated.take(8).joinToString(SEPARATOR)) }
    }

    private fun setupButtonsAndNav() {
        binding.btnProfile.setOnClickListener {
            navigateToTab(ProfileActivity::class.java)
        }

        binding.routeInfoCard.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        binding.btnUntrackRoute.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        setupBottomNav(binding.bottomNav, R.id.nav_home)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        setupBottomNav(binding.bottomNav, R.id.nav_home)
        loadSavedRouteChips()
        loadUserProfileAvatar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupBottomNav(binding.bottomNav, R.id.nav_home)
        checkTrackIntent(intent)
    }

    private fun checkTrackIntent(intent: Intent?) {
        if (intent == null) return
        val routeId = intent.getIntExtra("EXTRA_ROUTE_ID", -1)
        val routeCode = intent.getStringExtra("EXTRA_ROUTE_CODE")
        val origin = intent.getStringExtra("EXTRA_ORIGIN") ?: ""
        val dest = intent.getStringExtra("EXTRA_DESTINATION") ?: ""
        val vehicleType = intent.getStringExtra("EXTRA_VEHICLE_TYPE") ?: "Jeepney"

        if (routeId != -1 && !routeCode.isNullOrEmpty()) {
            isProgrammaticTextChange = true
            binding.etSearchDestination.setText(routeCode)
            binding.btnClearSearch.visibility = View.VISIBLE
            binding.cvSuggestions.visibility = View.GONE
            binding.etSearchDestination.clearFocus()
            isProgrammaticTextChange = false

            intent.removeExtra("EXTRA_ROUTE_ID")

            fetchRouteWaypoints(
                routeId = routeId,
                code = routeCode,
                name = if (dest.isNotBlank()) "$origin – $dest" else origin,
                type = vehicleType,
                origin = origin,
                dest = dest
            )
        }
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_RECENT = "recent_searches"
        private const val SEPARATOR = "||"
        private const val ROUTE_SOURCE_ID = "route-source"
        private const val ROUTE_LAYER_ID = "route-layer"
    }
}
