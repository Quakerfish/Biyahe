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
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biyahe.app.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
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
    private val availableTerminals = mutableListOf<JSONObject>()
    private val savedRouteIds = mutableSetOf<Int>()

    private lateinit var routeAdapter: RouteSuggestionAdapter

    private val activeMarkers = mutableListOf<Marker>()
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<MaterialCardView>
    private var isProgrammaticTextChange = false

    // Guards against fetchRouteWaypoints() responses arriving out of order:
    // every call captures the current value, and only a response whose
    // captured value still matches this field when it completes is allowed
    // to touch the UI. If the user selects a second route before the first
    // one's (slower) network call has returned, this field has already
    // moved on, so the stale first response is silently discarded instead
    // of overwriting what the second, newer selection already displayed.
    private var routeRequestSeq = 0

    private val sampleCommunityPosts = mutableListOf(
        CommunityPost(1, "MD", "Maricel D.", "4 min ago", "Traffic", "#CE1126", "Heavy traffic heading to Welcome Rotonda — a stalled bus is blocking the middle lane. Add 15 minutes to your trip.", "España – Lacson", 23),
        CommunityPost(2, "PR", "Paolo R.", "12 min ago", "Crowded", "#B45309", "Long line for Cubao jeeps right now. It is faster to board at the Hidalgo corner.", "Quiapo Church", 41),
        CommunityPost(3, "AL", "Ana L.", "27 min ago", "Detour", "#0038A8", "Road works near Pedro Gil. Baclaran jeeps are taking Mabini until UN Ave.", "Taft – Pedro Gil", 17)
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            mapLibreMap?.style?.let { enableLocationComponent(it) }
        }
    }

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
                enableLocationComponent(style)
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

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val locationComponent = mapLibreMap?.locationComponent
            locationComponent?.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build()
            )
            locationComponent?.isLocationComponentEnabled = true
            locationComponent?.cameraMode = CameraMode.TRACKING
            locationComponent?.renderMode = RenderMode.COMPASS
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
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

            if (binding.cvProfileDropdown.visibility == View.VISIBLE) {
                val dropdownRect = Rect()
                val profileBtnRect = Rect()
                binding.cvProfileDropdown.getGlobalVisibleRect(dropdownRect)
                binding.btnProfile.getGlobalVisibleRect(profileBtnRect)

                val x = ev.rawX.toInt()
                val y = ev.rawY.toInt()

                if (!dropdownRect.contains(x, y) && !profileBtnRect.contains(x, y)) {
                    binding.cvProfileDropdown.visibility = View.GONE
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.routeInfoCard)
        bottomSheetBehavior.isHideable = false
        binding.routeInfoCard.visibility = View.GONE

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {}

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val cardTop = bottomSheet.top
                val containerHeight = binding.coordinatorContainer.height
                if (containerHeight > 0 && cardTop > 0) {
                    val marginFromBottom = containerHeight - cardTop + 16
                    val params = binding.btnFindNearest.layoutParams as android.view.ViewGroup.MarginLayoutParams
                    params.bottomMargin = marginFromBottom
                    binding.btnFindNearest.layoutParams = params
                }
            }
        })
    }

    private fun setupRouteCardCloseButton() {
        binding.btnCloseRouteCard.setOnClickListener {
            clearMapRoute()
            binding.routeInfoCard.visibility = View.GONE
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
                lineColor(Color.parseColor("#0038A8")),
                lineWidth(4f),
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
        runOnUiThread { binding.pbRouteSearch.visibility = View.VISIBLE }
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
                            binding.pbRouteSearch.visibility = View.GONE
                            routeAdapter.updateList(availableRoutes)
                            if (binding.etSearchDestination.hasFocus() && availableRoutes.isNotEmpty()) {
                                binding.cvSuggestions.visibility = View.VISIBLE
                            }
                        }
                    } else {
                        runOnUiThread { binding.pbRouteSearch.visibility = View.GONE }
                    }
                } else if (responseCode == 401) {
                    runOnUiThread {
                        binding.pbRouteSearch.visibility = View.GONE
                        sessionPrefs.edit().remove("session_cookie").apply()
                        Toast.makeText(this@MainActivity, "Session expired. Please log in again.", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    }
                } else {
                    val errorStream = conn.errorStream
                    val errorText = errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.d("MainActivity", "loadRouteSuggestions HTTP $responseCode: $errorText")
                    val json = try { JSONObject(errorText) } catch (_: Exception) { null }
                    val serverMsg = json?.optString("message") ?: "API Error: HTTP $responseCode"

                    runOnUiThread {
                        binding.pbRouteSearch.visibility = View.GONE
                        Toast.makeText(this@MainActivity, serverMsg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread {
                    binding.pbRouteSearch.visibility = View.GONE
                }
            } catch (e: IOException) {
                runOnUiThread {
                    binding.pbRouteSearch.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.pbRouteSearch.visibility = View.GONE
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

                    savedRouteIds.clear()
                    val savedList = mutableListOf<JSONObject>()
                    for (i in 0 until savedArray.length()) {
                        val item = savedArray.getJSONObject(i)
                        savedList.add(item)
                        val id = item.optInt("route_id", -1)
                        if (id != -1) savedRouteIds.add(id)
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
                setChipIconTintResource(R.color.ph_blue)
                chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.bg_white)
                )
                chipStrokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.ph_blue)
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
                        val username = responseObj.optString("username", "Commuter")
                        val email = responseObj.optString("email", "")
                        val profileImageUrl = if (responseObj.has("profile_image") && !responseObj.isNull("profile_image")) {
                            responseObj.getString("profile_image")
                        } else null

                        runOnUiThread {
                            binding.tvDropdownUsername.text = username
                            binding.tvDropdownEmail.text = email

                            if (!profileImageUrl.isNullOrEmpty()) {
                                binding.ivMainAvatar.setPadding(0, 0, 0, 0)
                                binding.ivMainAvatar.imageTintList = null
                                Glide.with(this@MainActivity)
                                    .load(profileImageUrl)
                                    .circleCrop()
                                    .placeholder(R.drawable.ic_person)
                                    .into(binding.ivMainAvatar)

                                Glide.with(this@MainActivity)
                                    .load(profileImageUrl)
                                    .circleCrop()
                                    .placeholder(R.drawable.ic_person)
                                    .into(binding.ivDropdownAvatar)
                            } else {
                                val padInPx = (10 * resources.displayMetrics.density).toInt()
                                binding.ivMainAvatar.setPadding(padInPx, padInPx, padInPx, padInPx)
                                binding.ivMainAvatar.setImageResource(R.drawable.ic_person)
                                binding.ivMainAvatar.imageTintList = ColorStateList.valueOf(
                                    ContextCompat.getColor(this@MainActivity, R.color.ph_blue)
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

        var isTrackConfirmed = false

        if (routeId != -1) {
            // Route preview in background is disabled per user request.
        }

        sheetView.findViewById<TextView>(R.id.tvSheetRouteCode)?.text = code
        sheetView.findViewById<TextView>(R.id.tvSheetRouteName)?.text = if (dest != "N/A") "$origin – $dest" else origin
        sheetView.findViewById<TextView>(R.id.tvSheetVehicleType)?.text = type
        sheetView.findViewById<TextView>(R.id.tvSheetOriginTerminal)?.text = origin
        sheetView.findViewById<TextView>(R.id.tvSheetDestinationTerminal)?.text = dest

        sheetView.findViewById<View>(R.id.btnTrackRoute)?.setOnClickListener {
            isTrackConfirmed = true
            dialog.dismiss()
            // Re-fetch with revealCard=true (the default): this both shows
            // the card immediately with the already-known info, and kicks
            // off an authoritative fetch for the path. If the earlier quiet
            // prefetch above is still in flight, the request-sequence guard
            // in fetchRouteWaypoints ensures whichever call's result lands
            // LAST wins cleanly, rather than racing.
            if (routeId != -1) {
                fetchRouteWaypoints(routeId, code, "$origin – $dest", type, origin, dest)
            }
        }

        dialog.setOnDismissListener {
            if (!isTrackConfirmed) {
                clearMapRoute()
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
            binding.routeInfoCard.visibility = View.GONE
            Toast.makeText(this, "Route '$query' not found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchRouteWaypoints(
        routeId: Int, code: String, name: String, type: String, origin: String, dest: String,
        revealCard: Boolean = true
    ) {
        if (routeId <= 0) return

        if (revealCard) {
            runOnUiThread {
                binding.flMainRouteLoadingOverlay.visibility = View.VISIBLE
                binding.routeInfoCard.visibility = View.VISIBLE
                binding.llRoutePathLoading.visibility = View.VISIBLE
                binding.tvRoutePathLoadingLabel.text = "Loading route detail..."
                if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
            }
        }

        val requestId = ++routeRequestSeq

        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${ApiConfig.ROUTES_URL}?id=$routeId")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val sessionCookie = sessionPrefs.getString("session_cookie", null)
                if (!sessionCookie.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", sessionCookie)
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().readText()
                    val routeObj = JSONObject(jsonStr)
                    val waypointsArray = routeObj.optJSONArray("waypoints") ?: JSONArray()
                    val baseFare = routeObj.optDouble("base_fare", 0.0)

                    val originObj = routeObj.optJSONObject("origin")
                    val destObj = routeObj.optJSONObject("destination")

                    val originPhoto = originObj?.optString("image_url", "")?.takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: routeObj.optString("origin_image_url", "").takeIf { it.startsWith("http", ignoreCase = true) }

                    val destPhoto = destObj?.optString("image_url", "")?.takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: routeObj.optString("destination_image_url", "").takeIf { it.startsWith("http", ignoreCase = true) }

                    val points = mutableListOf<LatLng>()
                    for (i in 0 until waypointsArray.length()) {
                        val wp = waypointsArray.getJSONObject(i)
                        val lat = wp.getDouble("latitude")
                        val lng = wp.getDouble("longitude")
                        points.add(LatLng(lat, lng))
                    }

                    runOnUiThread {
                        if (requestId == routeRequestSeq) {
                            displayRouteOnMap(
                                points = points,
                                routeId = routeId,
                                code = code,
                                name = name,
                                type = type,
                                origin = origin,
                                dest = dest,
                                baseFare = baseFare,
                                originPhoto = originPhoto,
                                destPhoto = destPhoto,
                                revealCard = revealCard
                            )
                        }
                    }
                } else {
                    val errorStream = conn.errorStream
                    val errorText = errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.d("MainActivity", "fetchRouteWaypoints HTTP $responseCode: $errorText")
                    val json = try { JSONObject(errorText) } catch (_: Exception) { null }
                    val serverMsg = json?.optString("message") ?: "Error loading path (HTTP $responseCode)"

                    runOnUiThread {
                        if (requestId == routeRequestSeq) {
                            binding.flMainRouteLoadingOverlay.visibility = View.GONE
                            binding.llRoutePathLoading.visibility = View.GONE
                            if (revealCard) {
                                binding.routeInfoCard.visibility = View.GONE
                            }
                            Toast.makeText(this@MainActivity, serverMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread {
                    if (requestId == routeRequestSeq) {
                        binding.flMainRouteLoadingOverlay.visibility = View.GONE
                        binding.llRoutePathLoading.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (requestId == routeRequestSeq) {
                        binding.flMainRouteLoadingOverlay.visibility = View.GONE
                        binding.llRoutePathLoading.visibility = View.GONE
                    }
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
            color = Color.parseColor("#330038A8")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 22f, auraPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0038A8")
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

    private fun displayRouteOnMap(
        points: List<LatLng>, routeId: Int, code: String, name: String, type: String, origin: String, dest: String,
        baseFare: Double = 0.0, originPhoto: String? = null, destPhoto: String? = null,
        revealCard: Boolean = true
    ) {
        if (points.isEmpty()) {
            binding.flMainRouteLoadingOverlay.visibility = View.GONE
            binding.llRoutePathLoading.visibility = View.GONE
            if (revealCard) {
                binding.routeInfoCard.visibility = View.GONE
            }
            Toast.makeText(this, "No waypoints found for this route.", Toast.LENGTH_SHORT).show()
            return
        }

        clearMapRoute()

        binding.tvRouteCode.text = code
        binding.tvRouteCode.setOnClickListener {
            mapLibreMap?.getStyle {
                val boundsBuilder = LatLngBounds.Builder()
                points.forEach { boundsBuilder.include(it) }
                mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120))
            }
            Toast.makeText(this, "Previewing path for $code", Toast.LENGTH_SHORT).show()
        }

        binding.tvRouteName.text = name
        binding.tvVehicleType.text = type
        binding.tvOriginDetail.text = origin
        binding.tvDestinationDetail.text = dest

        binding.flMainRouteLoadingOverlay.visibility = View.GONE
        binding.llRoutePathLoading.visibility = View.GONE
        binding.routeInfoCard.visibility = View.VISIBLE
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        fetchRouteRating(routeId)

        // Fare Information
        if (baseFare > 0.0) {
            binding.tvBaseFare.text = String.format("₱ %.2f", baseFare)
            binding.tvDiscountedFare.text = String.format("₱ %.2f", baseFare * 0.80)
        } else {
            binding.tvBaseFare.text = "₱ 0.00"
            binding.tvDiscountedFare.text = "₱ 0.00"
        }

        // Terminal Photos Side-by-Side
        val hasOriginPhoto = !originPhoto.isNullOrEmpty()
        val hasDestPhoto = !destPhoto.isNullOrEmpty()

        if (hasOriginPhoto || hasDestPhoto) {
            binding.llTerminalImages.visibility = View.VISIBLE

            if (hasOriginPhoto) {
                binding.cvOriginTerminalPhoto.visibility = View.VISIBLE
                Glide.with(this@MainActivity)
                    .load(originPhoto)
                    .placeholder(R.drawable.bg_pill_soft)
                    .into(binding.ivOriginTerminalPhoto)
            } else {
                binding.cvOriginTerminalPhoto.visibility = View.GONE
            }

            if (hasDestPhoto) {
                binding.cvDestinationTerminalPhoto.visibility = View.VISIBLE
                Glide.with(this@MainActivity)
                    .load(destPhoto)
                    .placeholder(R.drawable.bg_pill_soft)
                    .into(binding.ivDestinationTerminalPhoto)
            } else {
                binding.cvDestinationTerminalPhoto.visibility = View.GONE
            }
        } else {
            binding.llTerminalImages.visibility = View.GONE
        }

        // Rate Route Button
        binding.btnRateRoute.setOnClickListener {
            showRateRouteDialog(routeId, code, name, type, origin, dest)
        }

        if (revealCard) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

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

            map.setOnMarkerClickListener { marker ->
                val title = marker.title ?: ""
                if (title.startsWith("Origin:")) {
                    val termName = title.removePrefix("Origin:").trim()
                    showTerminalDetailBottomSheet(termName, originPhoto)
                    true
                } else if (title.startsWith("Destination:")) {
                    val termName = title.removePrefix("Destination:").trim()
                    showTerminalDetailBottomSheet(termName, destPhoto)
                    true
                } else {
                    false
                }
            }
        }
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
            binding.cvProfileDropdown.visibility = if (binding.cvProfileDropdown.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.rowProfileCardDetails.setOnClickListener {
            binding.cvProfileDropdown.visibility = View.GONE
            navigateToTab(ProfileActivity::class.java)
        }

        binding.btnDropdownLogout.setOnClickListener {
            binding.cvProfileDropdown.visibility = View.GONE
            showSignOutConfirmation()
        }

        binding.btnFindNearest.setOnClickListener {
            showFindRouteBottomSheet()
        }

        binding.routeInfoCard.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        binding.btnUntrackRoute.setOnClickListener {
            clearMapRoute()
            binding.routeInfoCard.visibility = View.GONE
        }

        setupMainBottomNav()
    }

    private fun setupMainBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.cvProfileDropdown.visibility = View.GONE
            when (item.itemId) {
                R.id.nav_home -> {
                    true
                }
                R.id.nav_routes -> {
                    showRoutesListBottomSheet()
                    false
                }
                R.id.nav_settings -> {
                    navigateToTab(ProfileActivity::class.java)
                    false
                }
                else -> false
            }
        }
    }

    private fun showTerminalDetailBottomSheet(terminalName: String, photoUrl: String? = null) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_terminal_detail, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<TextView>(R.id.tvTerminalName)?.text = terminalName
        sheetView.findViewById<TextView>(R.id.tvTerminalStatus)?.text = "Active"

        val cvPhoto = sheetView.findViewById<View>(R.id.cvTerminalPhotoContainer)
        val ivPhoto = sheetView.findViewById<ImageView>(R.id.ivTerminalPhoto)

        if (!photoUrl.isNullOrEmpty() && ivPhoto != null && cvPhoto != null) {
            cvPhoto.visibility = View.VISIBLE
            Glide.with(this)
                .load(photoUrl)
                .placeholder(R.drawable.bg_pill_soft)
                .into(ivPhoto)
        } else {
            cvPhoto?.visibility = View.GONE
        }

        val rvRoutes = sheetView.findViewById<RecyclerView>(R.id.rvTerminalRoutes)
        if (rvRoutes != null) {
            rvRoutes.layoutManager = LinearLayoutManager(this)

            val matchingRoutes = availableRoutes.filter { route ->
                val o = route.optString("origin_name", "")
                val d = route.optString("destination_name", "")
                o.equals(terminalName, ignoreCase = true) || d.equals(terminalName, ignoreCase = true)
            }

            val adapter = RouteSuggestionAdapter(matchingRoutes) { selectedRoute ->
                dialog.dismiss()
                showRouteDetailDialog(selectedRoute)
            }
            rvRoutes.adapter = adapter
        }

        dialog.show()
    }

    private fun showRateRouteDialog(
        routeId: Int, routeCode: String, routeName: String = "", routeType: String = "",
        originName: String = "", destName: String = ""
    ) {
        if (routeId == -1) {
            Toast.makeText(this, "Please select a valid route first.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_rate_route, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<TextView>(R.id.tvRateRouteHeader)?.text = "Route Code: $routeCode"

        val rbRouteAccuracy = sheetView.findViewById<RatingBar>(R.id.rbRouteAccuracy)
        val rbFareAccuracy = sheetView.findViewById<RatingBar>(R.id.rbFareAccuracy)
        val etComment = sheetView.findViewById<EditText>(R.id.etRatingComment)
        val btnSubmit = sheetView.findViewById<View>(R.id.btnSubmitRating)

        btnSubmit?.setOnClickListener {
            val routeAccuracy = rbRouteAccuracy?.rating?.toInt() ?: 5
            val fareAccuracy = rbFareAccuracy?.rating?.toInt() ?: 5
            val commentText = etComment?.text?.toString()?.trim() ?: ""

            dialog.dismiss()
            submitRouteRating(
                routeId, routeAccuracy, fareAccuracy, commentText,
                routeCode, routeName, routeType, originName, destName
            )
        }

        dialog.show()
    }

    private fun submitRouteRating(
        routeId: Int, routeAccuracy: Int, fareAccuracy: Int, comment: String,
        routeCode: String, routeName: String, routeType: String, originName: String, destName: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(ApiConfig.getRouteRatingUrl(routeId))
                val jsonBody = JSONObject().apply {
                    put("route_accuracy_rating", routeAccuracy)
                    put("fare_accuracy_rating", fareAccuracy)
                    if (comment.isNotBlank()) put("comment", comment)
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val sessionCookie = sessionPrefs.getString("session_cookie", null)
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)

                if (!sessionCookie.isNullOrEmpty()) {
                    requestBuilder.addHeader("Cookie", sessionCookie)
                }

                val client = OkHttpClient()
                val response = client.newCall(requestBuilder.build()).execute()
                val responseText = response.body?.string() ?: ""
                val json = try { JSONObject(responseText) } catch (_: Exception) { null }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Rating submitted! Reloading route...", Toast.LENGTH_SHORT).show()
                    binding.flMainRouteLoadingOverlay.visibility = View.VISIBLE
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

                    fetchRouteWaypoints(routeId, routeCode, routeName, routeType, originName, destName, revealCard = true)
                    fetchRouteRating(routeId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Rating submitted! Reloading route...", Toast.LENGTH_SHORT).show()
                    binding.flMainRouteLoadingOverlay.visibility = View.VISIBLE
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

                    fetchRouteWaypoints(routeId, routeCode, routeName, routeType, originName, destName, revealCard = true)
                    fetchRouteRating(routeId)
                }
            }
        }
    }

    private fun fetchRouteRating(routeId: Int) {
        if (routeId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(ApiConfig.getRouteRatingUrl(routeId))
                val sessionCookie = sessionPrefs.getString("session_cookie", null)
                val requestBuilder = Request.Builder().url(url).get()
                if (!sessionCookie.isNullOrEmpty()) {
                    requestBuilder.addHeader("Cookie", sessionCookie)
                }

                val client = OkHttpClient()
                val response = client.newCall(requestBuilder.build()).execute()
                val jsonText = response.body?.string() ?: ""
                val json = try { JSONObject(jsonText) } catch (_: Exception) { null }

                if (response.isSuccessful && json?.optBoolean("success", false) == true) {
                    val myRating = json.optJSONObject("my_rating")
                    val avgAccuracy = json.optDouble("average_route_accuracy", 0.0)
                    val avgFare = json.optDouble("average_fare_accuracy", 0.0)

                    withContext(Dispatchers.Main) {
                        if (myRating != null) {
                            val rAcc = myRating.optInt("route_accuracy_rating", 5)
                            val fAcc = myRating.optInt("fare_accuracy_rating", 5)
                            val myAvg = (rAcc + fAcc) / 2.0
                            binding.rbUserRouteRating.rating = myAvg.toFloat()
                            binding.tvUserRouteRatingText.text = String.format("%.1f ★", myAvg)
                            binding.llUserRatingContainer.visibility = View.VISIBLE
                        } else if (avgAccuracy > 0.0 || avgFare > 0.0) {
                            val avg = if (avgAccuracy > 0 && avgFare > 0) (avgAccuracy + avgFare) / 2.0 else (avgAccuracy + avgFare)
                            binding.rbUserRouteRating.rating = avg.toFloat()
                            binding.tvUserRouteRatingText.text = String.format("%.1f ★ (Avg)", avg)
                            binding.llUserRatingContainer.visibility = View.VISIBLE
                        } else {
                            binding.llUserRatingContainer.visibility = View.GONE
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.llUserRatingContainer.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.llUserRatingContainer.visibility = View.GONE
                }
            }
        }
    }

    private fun showRoutesListBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_routes_list, null)
        dialog.setContentView(sheetView)

        val rvList = sheetView.findViewById<RecyclerView>(R.id.rvRoutesSheetList)
        val tvTrackedCount = sheetView.findViewById<TextView>(R.id.tvRoutesTrackedCount)
        val llLoading = sheetView.findViewById<View>(R.id.llRoutesSheetLoading)

        val chipAll = sheetView.findViewById<Chip>(R.id.chipSheetAll)
        val chipSaved = sheetView.findViewById<Chip>(R.id.chipSheetSaved)
        val chipJeepneys = sheetView.findViewById<Chip>(R.id.chipSheetJeepneys)
        val chipTerminals = sheetView.findViewById<Chip>(R.id.chipSheetTerminals)

        val combinedList = mutableListOf<JSONObject>().apply {
            addAll(availableRoutes)
            addAll(availableTerminals)
        }

        if (availableRoutes.isEmpty() || availableTerminals.isEmpty()) {
            llLoading?.visibility = View.VISIBLE
        } else {
            llLoading?.visibility = View.GONE
        }

        val adapter = JeepneyRouteSheetAdapter(
            items = combinedList,
            savedRouteIds = savedRouteIds,
            onItemClick = { selectedItem ->
                val isTerminal = selectedItem.has("terminal_name")
                if (isTerminal) {
                    val terminalName = selectedItem.optString("terminal_name")
                    dialog.dismiss()
                    findRoutePathAndConfirm("Current Location", terminalName)
                } else {
                    dialog.dismiss()
                    showRouteDetailDialog(selectedItem)
                }
            },
            onSaveClick = { item, currentlySaved ->
                val isTerminal = item.has("terminal_name")
                val id = if (isTerminal) item.optInt("terminal_id", -1) else item.optInt("route_id", -1)
                if (id != -1) {
                    if (currentlySaved) {
                        savedRouteIds.remove(id)
                        Toast.makeText(this, "Removed from saved", Toast.LENGTH_SHORT).show()
                    } else {
                        savedRouteIds.add(id)
                        Toast.makeText(this, "Saved successfully!", Toast.LENGTH_SHORT).show()
                    }
                    toggleSaveRouteServer(id, !currentlySaved)
                }
            }
        )

        fun updateChipStyles(activeChip: Chip) {
            val chips = listOf(chipAll, chipSaved, chipJeepneys, chipTerminals)
            for (c in chips) {
                if (c == activeChip) {
                    c?.setTextColor(ContextCompat.getColor(this, R.color.ph_blue))
                    c?.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ph_blue_soft))
                    c?.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ph_blue))
                } else {
                    c?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    c?.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_white))
                    c?.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider))
                }
            }
        }

        chipAll?.setOnClickListener {
            updateChipStyles(chipAll)
            val all = mutableListOf<JSONObject>().apply {
                addAll(availableRoutes)
                addAll(availableTerminals)
            }
            adapter.updateList(all, savedRouteIds)
            tvTrackedCount?.text = "${all.size} Items tracked in Cebu City"
        }

        chipSaved?.setOnClickListener {
            updateChipStyles(chipSaved)
            val savedList = availableRoutes.filter { route ->
                savedRouteIds.contains(route.optInt("route_id", -1))
            }
            adapter.updateList(savedList, savedRouteIds)
            tvTrackedCount?.text = "${savedList.size} saved routes"
        }

        chipJeepneys?.setOnClickListener {
            updateChipStyles(chipJeepneys)
            adapter.updateList(availableRoutes, savedRouteIds)
            tvTrackedCount?.text = "${availableRoutes.size} jeepney routes tracked"
        }

        chipTerminals?.setOnClickListener {
            updateChipStyles(chipTerminals)
            adapter.updateList(availableTerminals, savedRouteIds)
            tvTrackedCount?.text = "${availableTerminals.size} terminals found"
        }

        tvTrackedCount?.text = "${combinedList.size} Items tracked in Cebu City"

        rvList?.layoutManager = LinearLayoutManager(this)
        rvList?.adapter = adapter

        if (availableTerminals.isEmpty()) {
            loadTerminalsForSheet(adapter, llLoading)
        } else {
            llLoading?.visibility = View.GONE
        }

        dialog.show()
    }

    private fun loadTerminalsForSheet(adapter: JeepneyRouteSheetAdapter, llLoading: View? = null) {
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
                        availableTerminals.clear()
                        for (i in 0 until terminalsArray.length()) {
                            availableTerminals.add(terminalsArray.getJSONObject(i))
                        }

                        runOnUiThread {
                            llLoading?.visibility = View.GONE
                            val all = mutableListOf<JSONObject>().apply {
                                addAll(availableRoutes)
                                addAll(availableTerminals)
                            }
                            adapter.updateList(all, savedRouteIds)
                        }
                    } else {
                        runOnUiThread { llLoading?.visibility = View.GONE }
                    }
                } else {
                    runOnUiThread { llLoading?.visibility = View.GONE }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { llLoading?.visibility = View.GONE }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun toggleSaveRouteServer(routeId: Int, shouldSave: Boolean) {
        // NOTE: previously this always sent POST with an "action" field in the
        // body - but the backend (old PHP and new FastAPI alike) only ever
        // dispatched on the HTTP method, never read that field. So "unsaving"
        // a route silently did nothing server-side (the DB row was never
        // removed), even though the UI looked like it worked. Fixed here:
        // POST to save, DELETE to unsave, route_id as a query param either
        // way - matching how /api/saved-routes actually works.
        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${ApiConfig.SAVED_ROUTES_URL}?route_id=$routeId")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = if (shouldSave) "POST" else "DELETE"
                    connectTimeout = 5000
                    readTimeout = 5000

                    val sessionCookie = sessionPrefs.getString("session_cookie", null)
                    if (!sessionCookie.isNullOrEmpty()) {
                        setRequestProperty("Cookie", sessionCookie)
                    }
                }

                conn.responseCode
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun showCommunityBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_community, null)
        dialog.setContentView(sheetView)

        val rvPosts = sheetView.findViewById<RecyclerView>(R.id.rvCommunityPosts)
        val etInput = sheetView.findViewById<EditText>(R.id.etCommunityPostInput)
        val btnPost = sheetView.findViewById<View>(R.id.btnSubmitPost)

        val tagTraffic = sheetView.findViewById<TextView>(R.id.tagTraffic)
        val tagCrowded = sheetView.findViewById<TextView>(R.id.tagCrowded)
        val tagDetour = sheetView.findViewById<TextView>(R.id.tagDetour)
        val tagAllClear = sheetView.findViewById<TextView>(R.id.tagAllClear)

        var selectedTag = "Traffic"
        var selectedColor = "#CE1126"

        fun selectTag(view: TextView?, tag: String, color: String) {
            selectedTag = tag
            selectedColor = color
            Toast.makeText(this, "Tag: $tag selected", Toast.LENGTH_SHORT).show()
        }

        tagTraffic?.setOnClickListener { selectTag(tagTraffic, "Traffic", "#CE1126") }
        tagCrowded?.setOnClickListener { selectTag(tagCrowded, "Crowded", "#B45309") }
        tagDetour?.setOnClickListener { selectTag(tagDetour, "Detour", "#0038A8") }
        tagAllClear?.setOnClickListener { selectTag(tagAllClear, "All clear", "#008751") }

        val adapter = CommunityPostAdapter(sampleCommunityPosts)
        rvPosts?.layoutManager = LinearLayoutManager(this)
        rvPosts?.adapter = adapter

        btnPost?.setOnClickListener {
            val text = etInput?.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please write a road update first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val username = sessionPrefs.getString("username", "Commuter") ?: "Commuter"
            val initials = if (username.length >= 2) username.take(2).uppercase() else "C"

            val newPost = CommunityPost(
                id = (System.currentTimeMillis() % 10000).toInt(),
                avatarInitials = initials,
                username = username,
                timestamp = "Just now",
                tagText = selectedTag,
                tagColorHex = selectedColor,
                bodyText = text,
                locationText = "Cebu City Center",
                helpfulCount = 0
            )

            adapter.addPost(newPost)
            etInput?.text?.clear()
            rvPosts?.scrollToPosition(0)
            Toast.makeText(this, "Road report posted!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showFindRouteBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_find_route, null)
        dialog.setContentView(sheetView)

        val actvOrigin = sheetView.findViewById<AutoCompleteTextView>(R.id.actvOrigin)
        val actvDestination = sheetView.findViewById<AutoCompleteTextView>(R.id.actvDestination)
        val btnFindRoutePath = sheetView.findViewById<View>(R.id.btnFindRoutePath)

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
                        val terminalNames = mutableListOf<String>()
                        for (i in 0 until terminalsArray.length()) {
                            terminalNames.add(terminalsArray.getJSONObject(i).optString("terminal_name"))
                        }

                        val originOptions = mutableListOf("Current Location").apply { addAll(terminalNames) }

                        runOnUiThread {
                            val destAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, terminalNames)
                            actvDestination?.setAdapter(destAdapter)
                            actvDestination?.setOnClickListener { actvDestination.showDropDown() }

                            val origAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, originOptions)
                            actvOrigin?.setAdapter(origAdapter)
                            actvOrigin?.setOnClickListener { actvOrigin.showDropDown() }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                conn?.disconnect()
            }
        }

        btnFindRoutePath?.setOnClickListener {
            val destText = actvDestination?.text.toString().trim()
            val origText = actvOrigin?.text.toString().trim()

            if (destText.isEmpty() || destText.equals("Select terminal", ignoreCase = true)) {
                Toast.makeText(this, "Please select a destination terminal.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            findRoutePathAndConfirm(origText, destText)
        }

        dialog.show()
    }

    private fun findRoutePathAndConfirm(originStr: String, destinationStr: String) {
        val isCurrentLocation = originStr.isEmpty() || originStr.equals("Current Location", ignoreCase = true)

        val matchingRoutes = availableRoutes.filter { route ->
            val dest = route.optString("destination_name", "")
            val orig = route.optString("origin_name", "")

            val matchesDest = dest.equals(destinationStr, ignoreCase = true) || orig.equals(destinationStr, ignoreCase = true)
            if (isCurrentLocation) {
                matchesDest
            } else {
                matchesDest && (orig.equals(originStr, ignoreCase = true) || dest.equals(originStr, ignoreCase = true))
            }
        }

        val bestRoute = if (matchingRoutes.isNotEmpty()) {
            matchingRoutes.first()
        } else {
            availableRoutes.firstOrNull { route ->
                val dest = route.optString("destination_name", "")
                val orig = route.optString("origin_name", "")
                dest.equals(destinationStr, ignoreCase = true) || orig.equals(destinationStr, ignoreCase = true)
            }
        }

        if (bestRoute != null) {
            showRouteDetailDialog(bestRoute)
        } else {
            Toast.makeText(this, "No route found heading to $destinationStr.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSignOutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out") { _, _ ->
                sessionPrefs.edit().clear().apply()
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        setupMainBottomNav()
        loadSavedRouteChips()
        loadUserProfileAvatar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupMainBottomNav()
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

            // revealCard defaults to true, so this shows the card immediately
            // with the already-known info (no separate state assignment
            // needed here anymore - that was the original bug: setting
            // STATE_COLLAPSED here, before the fetch even started, could
            // later get clobbered by a slow/failed response's old hide-on-
            // failure branch. fetchRouteWaypoints now handles revealing
            // correctly and keeps the card's info intact even if the path
            // fetch itself fails.
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