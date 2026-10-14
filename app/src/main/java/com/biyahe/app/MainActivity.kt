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
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity() {

    // ------------------------------------------------------------------ state

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private var mapStyleReady = false
    private var pendingRouteDraw: (() -> Unit)? = null

    private val prefs by lazy { getSharedPreferences("jeepney_search", MODE_PRIVATE) }
    private val sessionPrefs by lazy { getSharedPreferences("app_session", MODE_PRIVATE) }

    private val availableRoutes = mutableListOf<JSONObject>()
    private val availableTerminals = mutableListOf<JSONObject>()
    private val savedRouteIds = mutableSetOf<Int>()
    private var routesLoading = false

    private lateinit var routeAdapter: RouteSuggestionAdapter
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<MaterialCardView>
    private var isProgrammaticTextChange = false

    // The route currently shown in the info card. All card/map updates are keyed
    // off this, so a slow response for a route the user has already left can
    // never overwrite what is on screen (the job is cancelled AND re-checked).
    private data class RouteInfo(
        val id: Int,
        val code: String,
        val name: String,
        val type: String,
        val origin: String,
        val dest: String
    )

    private class RouteDetail(
        val points: List<LatLng>,
        val baseFare: Double,
        val originPhoto: String?,
        val destPhoto: String?,
        val fetchedAt: Long = SystemClock.elapsedRealtime()
    )

    private var currentRoute: RouteInfo? = null
    private var userDraggedSheet = false
    private var currentDetail: RouteDetail? = null
    private var routeJob: Job? = null
    private var ratingJob: Job? = null

    private val isGuest: Boolean
        get() = sessionPrefs.getBoolean("is_guest", false)

    private val activeMarkers = mutableListOf<Marker>()
    private val originIcon: Icon by lazy { createCircleMarkerIcon("#0038A8") }
    private val destinationIcon: Icon by lazy { createCircleMarkerIcon("#CE1126") }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            mapLibreMap?.style?.let { enableLocationComponent(it) }
        }
    }

    // --------------------------------------------------------------- lifecycle

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
        setupMap(savedInstanceState)
        setupRecyclerView()
        setupSearchBar()
        setupButtonsAndNav()
        setupBackHandling()

        loadRouteSuggestions()
        if (!isGuest) {
            loadSavedRouteChips()
            loadUserProfileAvatar()
        } else {
            setupGuestProfileUI()
        }
        // Coming back from another tab: bring the card and path back (an explicit
        // track intent below, if any, overrides this).
        persistedRoute?.let { trackRoute(it) }
        checkTrackIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        reconcileRouteCard()
        setupMainBottomNav()
        if (!isGuest) {
            loadSavedRouteChips()
            loadUserProfileAvatar()
        } else {
            setupGuestProfileUI()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupMainBottomNav()
        checkTrackIntent(intent)
        ensureRouteCardVisible()
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

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.cvProfileDropdown.visibility == View.VISIBLE ->
                        binding.cvProfileDropdown.visibility = View.GONE

                    binding.cvSuggestions.visibility == View.VISIBLE -> collapseSearch()

                    bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED ->
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // --------------------------------------------------------------------- map

    private fun setupMap(savedInstanceState: Bundle?) {
        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map: MapLibreMap ->
            mapLibreMap = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(10.3156, 123.8854))
                .zoom(12.0)
                .build()

            val wideCebuBounds = LatLngBounds.Builder()
                .include(LatLng(9.4000, 123.2000))
                .include(LatLng(11.3000, 124.2000))
                .build()
            map.setLatLngBoundsForCameraTarget(wideCebuBounds)
            map.setMinZoomPreference(10.0)
            map.setMaxZoomPreference(18.0)

            // Identify markers by reference, not by parsing their titles.
            map.setOnMarkerClickListener { marker ->
                val route = currentRoute
                val detail = currentDetail
                when {
                    route == null || detail == null -> false
                    marker == activeMarkers.getOrNull(0) -> {
                        showTerminalByName(route.origin, detail.originPhoto)
                        true
                    }
                    marker == activeMarkers.getOrNull(1) -> {
                        showTerminalByName(route.dest, detail.destPhoto)
                        true
                    }
                    else -> false
                }
            }

            val apiKey = "5hqAX6ehvk13Ic2HPmia"
            val styleUrl =
                "https://api.maptiler.com/maps/01a0aa36-ae40-7883-9e61-8720a18435db/style.json?key=$apiKey"

            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                initRouteLayer(style)
                enableLocationComponent(style)
                mapStyleReady = true
                // A route can finish loading before the map style does (e.g. when
                // opened from the Routes tab). Draw it now instead of dropping it.
                pendingRouteDraw?.invoke()
                pendingRouteDraw = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val locationComponent = mapLibreMap?.locationComponent
            locationComponent?.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build()
            )
            locationComponent?.isLocationComponentEnabled = true
            // Follow the user until a route is shown (see stopLocationTracking).
            locationComponent?.cameraMode =
                if (currentRoute == null) CameraMode.TRACKING else CameraMode.NONE
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

    /** Otherwise the "follow me" camera keeps pulling the map away from the route. */
    private fun stopLocationTracking() {
        val lc = mapLibreMap?.locationComponent ?: return
        if (lc.isLocationComponentActivated) lc.cameraMode = CameraMode.NONE
    }

    private fun initRouteLayer(style: Style) {
        if (style.getSource(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        }

        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor(Color.parseColor("#0038A8")),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )

            val annotationLayer =
                style.layers.firstOrNull { it.id.contains("annotation", ignoreCase = true) }
            if (annotationLayer != null) {
                style.addLayerBelow(lineLayer, annotationLayer.id)
            } else {
                style.addLayer(lineLayer)
            }
        }
    }

    private fun clearMarkers() {
        mapLibreMap?.let { map -> activeMarkers.forEach { map.removeMarker(it) } }
        activeMarkers.clear()
    }

    private fun clearRouteOnMap() {
        clearMarkers()
        mapLibreMap?.style
            ?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            ?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(emptyList())))
    }

    private fun fitCameraTo(points: List<LatLng>) {
        val map = mapLibreMap ?: return
        if (points.isEmpty()) return
        try {
            if (points.size == 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 15.0))
                return
            }
            val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
            // Leave room for the search bar (top) and the collapsed sheet (bottom).
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, dp(48), dp(150), dp(48), dp(150))
            )
        } catch (e: Exception) {
            Log.w(TAG, "fitCameraTo failed", e)
        }
    }

    private fun createCircleMarkerIcon(colorHex: String): Icon {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val base = Color.parseColor(colorHex)

        val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(51, Color.red(base), Color.green(base), Color.blue(base))
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 22f, auraPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = base
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 14f, fillPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 6f, dotPaint)

        return IconFactory.getInstance(this).fromBitmap(bitmap)
    }

    // ------------------------------------------------------------- bottom sheet

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.routeInfoCard)
        // The card is never toggled GONE/VISIBLE. It stays laid out and is simply
        // parked below the screen (HIDDEN) or raised (COLLAPSED / EXPANDED). Toggling
        // visibility AND state at the same time is what made the card unreliable.
        bottomSheetBehavior.isHideable = true
        bottomSheetBehavior.skipCollapsed = false
        // Never let Android restore the sheet's state: after leaving the screen and coming
        // back, a restored COLLAPSED/EXPANDED state no longer matched our route fields.
        binding.coordinatorContainer.isSaveEnabled = false
        binding.routeInfoCard.isSaveEnabled = false
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                Log.d(TAG, "sheet state=$newState route=${currentRoute?.id} userDrag=$userDraggedSheet")
                when (newState) {
                    BottomSheetBehavior.STATE_DRAGGING -> userDraggedSheet = true
                    BottomSheetBehavior.STATE_COLLAPSED,
                    BottomSheetBehavior.STATE_EXPANDED -> userDraggedSheet = false
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        if (userDraggedSheet) {
                            // The user swiped the card away: same as the close button.
                            userDraggedSheet = false
                            clearRouteState()
                        } else if (currentRoute != null) {
                            // Hidden by the system (layout / window change), not by the
                            // user, while a route is still tracked: put the card back.
                            bottomSheet.post {
                                if (currentRoute != null &&
                                    bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN
                                ) {
                                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                                }
                            }
                        }
                    }
                }
                binding.btnFindNearest.post { updateFabPosition() }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                updateFabPosition()
                // Fade the compass button out as the sheet expands over it.
                binding.btnFindNearest.alpha = 1f - slideOffset.coerceIn(0f, 1f)
            }
        })
    }

    /** True only if the card is really on screen, not merely reporting a state. */
    private fun isRouteCardShown(): Boolean {
        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) return false
        val container = binding.coordinatorContainer
        return container.height == 0 || binding.routeInfoCard.top < container.height - dp(8)
    }

    /** Raise the card (header is already filled in) without disturbing an expanded card. */
    private fun showRouteCard() {
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        // Re-assert after layout in case the view was not laid out / was reset meanwhile.
        binding.routeInfoCard.post {
            if (currentRoute != null &&
                bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN
            ) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }
    }

    /**
     * Keep the sheet and the route fields consistent (call after returning to this screen).
     * No route -> the card is parked. A route -> [ensureRouteCardVisible].
     */
    private fun reconcileRouteCard() {
        binding.routeInfoCard.post {
            if (currentRoute == null) {
                if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                }
                updateFabPosition()
            } else {
                ensureRouteCardVisible()
            }
        }
    }

    /**
     * Watchdog. The card's real position is the ground truth, not the state the sheet
     * reports: after a dialog closes, another screen finishes or the window regains focus,
     * the sheet can claim COLLAPSED while the card is actually off-screen. If a route is
     * tracked and the card is not really on screen, bring it back. Cheap to call often;
     * it checks again shortly after, once layout and animations have settled.
     */
    private fun ensureRouteCardVisible(recheck: Boolean = true) {
        val card = binding.routeInfoCard
        val container = binding.coordinatorContainer
        card.post {
            if (currentRoute == null || container.height == 0) return@post

            val state = bottomSheetBehavior.state
            val settling = state == BottomSheetBehavior.STATE_DRAGGING ||
                    state == BottomSheetBehavior.STATE_SETTLING
            val offscreen = card.top >= container.height - dp(8) ||
                    card.visibility != View.VISIBLE || card.alpha < 0.99f

            if (!settling && (offscreen || state == BottomSheetBehavior.STATE_HIDDEN)) {
                Log.w(
                    TAG,
                    "route card was off-screen: route=${currentRoute?.id} state=$state " +
                            "top=${card.top} containerH=${container.height} vis=${card.visibility} alpha=${card.alpha}"
                )
                card.visibility = View.VISIBLE
                card.alpha = 1f
                card.translationY = 0f
                if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                    // The sheet already believes it is collapsed, so setting COLLAPSED again
                    // is a no-op. Nudge it through EXPANDED (no HIDDEN, so nothing is cleared).
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    card.post {
                        if (currentRoute != null) bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }
                } else {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
            }
            updateFabPosition()
            if (recheck) card.postDelayed({ ensureRouteCardVisible(recheck = false) }, 400)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) ensureRouteCardVisible()
    }

    private fun updateFabPosition() {
        val container = binding.coordinatorContainer
        val card = binding.routeInfoCard
        val above = if (container.height > 0 && card.top > 0) container.height - card.top + dp(16) else 0
        val margin = maxOf(dp(24), above)
        val lp = binding.btnFindNearest.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            binding.btnFindNearest.layoutParams = lp
        }
    }

    // ---------------------------------------------------------------- networking

    private class HttpResult(val code: Int, val body: String)

    private class SessionExpiredException : IOException("Session expired")

    private suspend fun httpCall(
        url: String,
        method: String = "GET",
        body: RequestBody? = null
    ): HttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        sessionPrefs.getString("session_cookie", null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.addHeader("Cookie", it) }

        when (method) {
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(null))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }

        httpClient.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private fun errorMessage(res: HttpResult, fallback: String): String {
        val json: JSONObject? = try {
            JSONObject(res.body)
        } catch (_: Exception) {
            null
        }
        if (json == null) return fallback
        val message = json.optString("message")
        if (message.isNotBlank()) return message
        val detail = json.opt("detail")
        if (detail is String && detail.isNotBlank()) return detail
        return fallback
    }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun handleSessionExpired() {
        if (isGuest) return
        sessionPrefs.edit { remove("session_cookie") }
        toast("Session expired. Please log in again.")
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ------------------------------------------------------------------- search

    private fun setupRecyclerView() {
        routeAdapter = RouteSuggestionAdapter(emptyList()) { selectedRoute ->
            onRouteSelected(selectedRoute)
        }
        binding.rvRouteSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvRouteSuggestions.adapter = routeAdapter

        // Dragging the list should dismiss the keyboard so more rows are visible.
        binding.rvRouteSuggestions.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        binding.tvNoSuggestions.setOnClickListener {
            if (availableRoutes.isEmpty() && !routesLoading) loadRouteSuggestions()
        }
    }

    private fun setupSearchBar() {
        binding.etSearchDestination.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showSuggestions() else setSuggestionsVisible(false)
        }

        binding.etSearchDestination.setOnClickListener { showSuggestions() }

        binding.etSearchDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticTextChange) return
                val query = s.toString().trim()
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                renderSuggestions(query)
                setSuggestionsVisible(true)
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            setSearchText("")
            renderSuggestions("")
            binding.etSearchDestination.requestFocus()
            setSuggestionsVisible(true)
        }

        binding.etSearchDestination.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performManualSearch(textView.text.toString().trim())
                true
            } else {
                false
            }
        }
    }

    private fun currentQuery(): String = binding.etSearchDestination.text?.toString()?.trim().orEmpty()

    private fun setSearchText(text: String) {
        isProgrammaticTextChange = true
        binding.etSearchDestination.setText(text)
        binding.etSearchDestination.setSelection(text.length)
        isProgrammaticTextChange = false
        binding.btnClearSearch.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSuggestions() {
        if (availableRoutes.isEmpty() && !routesLoading) loadRouteSuggestions()
        renderSuggestions(currentQuery())
        setSuggestionsVisible(true)
    }

    private fun collapseSearch() {
        setSuggestionsVisible(false)
        binding.etSearchDestination.clearFocus()
        hideKeyboard()
    }

    /** The suggestion list and the saved-route chips share a slot; only one shows at a time. */
    private fun setSuggestionsVisible(visible: Boolean) {
        binding.cvSuggestions.visibility = if (visible) View.VISIBLE else View.GONE
        refreshChipsVisibility()
    }

    private fun refreshChipsVisibility() {
        val hasChips = binding.cgSavedRoutes.childCount > 0
        binding.hsvSavedChips.visibility =
            if (hasChips && binding.cvSuggestions.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun routeMatches(route: JSONObject, query: String): Boolean =
        route.optString("route_code").contains(query, ignoreCase = true) ||
                route.optString("origin_name").contains(query, ignoreCase = true) ||
                route.optString("destination_name").contains(query, ignoreCase = true)

    private fun renderSuggestions(query: String) {
        val q = query.trim()
        // Always hand the adapter a COPY. Passing the live availableRoutes list
        // meant old and new were the same object after a reload, so DiffUtil saw
        // no change (and could throw an inconsistency error).
        val list = if (q.isEmpty()) availableRoutes.toList()
        else availableRoutes.filter { routeMatches(it, q) }

        routeAdapter.updateList(list)

        binding.tvSuggestionsHeader.text = when {
            q.isEmpty() -> "All routes - ${list.size}"
            else -> "${list.size} result${if (list.size == 1) "" else "s"}"
        }

        val empty = list.isEmpty()
        binding.rvRouteSuggestions.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvNoSuggestions.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            binding.tvNoSuggestions.text = when {
                routesLoading -> "Loading routes..."
                availableRoutes.isEmpty() -> "Couldn't load routes. Tap to retry."
                else -> "No routes match \"$q\""
            }
        }
    }

    private fun performManualSearch(query: String) {
        if (query.isEmpty()) return

        val exact = availableRoutes.firstOrNull { it.optString("route_code").equals(query, ignoreCase = true) }
        val partial = if (exact == null) availableRoutes.filter { routeMatches(it, query) } else emptyList()
        val match = exact ?: partial.singleOrNull()

        when {
            match != null -> onRouteSelected(match)
            partial.isEmpty() -> {
                collapseSearch()
                toast("Route '$query' not found.")
            }
            else -> hideKeyboard() // several matches: leave the list open to pick from
        }
    }

    private fun onRouteSelected(routeObj: JSONObject) {
        setSearchText(routeObj.optString("route_code"))
        collapseSearch()
        saveRecentSearch(routeObj.optString("route_code"))
        showRouteDetailDialog(routeObj)
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

    private fun View.containsRawPoint(x: Int, y: Int): Boolean {
        val rect = Rect()
        return getGlobalVisibleRect(rect) && rect.contains(x, y)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()

            if (binding.cvSuggestions.visibility == View.VISIBLE &&
                !binding.cvSearchBar.containsRawPoint(x, y) &&
                !binding.cvSuggestions.containsRawPoint(x, y)
            ) {
                collapseSearch()
            }

            if (binding.cvProfileDropdown.visibility == View.VISIBLE &&
                !binding.cvProfileDropdown.containsRawPoint(x, y) &&
                !binding.btnProfile.containsRawPoint(x, y)
            ) {
                binding.cvProfileDropdown.visibility = View.GONE
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // -------------------------------------------------------------- data loading

    private fun loadRouteSuggestions() {
        if (routesLoading) return
        routesLoading = true
        binding.pbRouteSearch.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val res = httpCall(ApiConfig.ROUTES_URL)
                when (res.code) {
                    200 -> {
                        val obj = JSONObject(res.body)
                        if (obj.optBoolean("success", false)) {
                            val arr = obj.getJSONArray("routes")
                            availableRoutes.clear()
                            for (i in 0 until arr.length()) availableRoutes.add(arr.getJSONObject(i))
                        }
                    }
                    401 -> handleSessionExpired()
                    else -> toast(errorMessage(res, "Couldn't load routes (HTTP ${res.code})"), long = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadRouteSuggestions failed", e)
            } finally {
                routesLoading = false
                binding.pbRouteSearch.visibility = View.GONE
                renderSuggestions(currentQuery())
            }
        }
    }

    private fun loadSavedRouteChips() {
        lifecycleScope.launch {
            try {
                val res = httpCall(ApiConfig.SAVED_ROUTES_URL)
                if (res.code != 200) {
                    populateSavedRouteChips(emptyList())
                    return@launch
                }
                val arr = if (res.body.trim().startsWith("{")) {
                    val obj = JSONObject(res.body)
                    obj.optJSONArray("routes") ?: obj.optJSONArray("saved_routes") ?: JSONArray()
                } else {
                    JSONArray(res.body)
                }

                savedRouteIds.clear()
                val saved = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    saved.add(item)
                    val id = item.optInt("route_id", -1)
                    if (id != -1) savedRouteIds.add(id)
                }
                populateSavedRouteChips(saved)
                ensureRouteCardVisible()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadSavedRouteChips failed", e)
                populateSavedRouteChips(emptyList())
            }
        }
    }

    private fun populateSavedRouteChips(savedRoutes: List<JSONObject>) {
        binding.cgSavedRoutes.removeAllViews()

        for (route in savedRoutes) {
            val code = route.optString("route_code")
            if (code.isBlank()) continue

            val chip = Chip(this).apply {
                text = code
                isCheckable = false
                isClickable = true
                setEnsureMinTouchTargetSize(false)
                setChipIconResource(R.drawable.ic_bookmark)
                setChipIconTintResource(R.color.ph_blue)
                chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bg_white))
                chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
                chipStrokeWidth = dp(1).toFloat()
                chipCornerRadius = dp(18).toFloat()
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)

                setOnClickListener {
                    // The saved-routes endpoint may return a trimmed object; prefer the full route.
                    val id = route.optInt("route_id", -1)
                    val full = availableRoutes.firstOrNull { it.optInt("route_id", -2) == id } ?: route
                    onRouteSelected(full)
                }
            }
            binding.cgSavedRoutes.addView(chip)
        }
        refreshChipsVisibility()
    }

    private fun loadUserProfileAvatar() {
        lifecycleScope.launch {
            try {
                val res = httpCall(ApiConfig.GET_PROFILE_URL)
                if (res.code != 200) return@launch
                val obj = JSONObject(res.body)
                if (!obj.optBoolean("success", false)) return@launch

                binding.tvDropdownUsername.text = obj.optString("username", "Commuter")
                binding.tvDropdownEmail.text = obj.optString("email", "")

                val imageUrl = if (obj.has("profile_image") && !obj.isNull("profile_image"))
                    obj.getString("profile_image") else null

                if (!imageUrl.isNullOrEmpty()) {
                    binding.ivMainAvatar.setPadding(0, 0, 0, 0)
                    binding.ivMainAvatar.imageTintList = null
                    Glide.with(this@MainActivity).load(imageUrl).circleCrop()
                        .placeholder(R.drawable.ic_person).into(binding.ivMainAvatar)
                    binding.ivDropdownAvatar.imageTintList = null
                    Glide.with(this@MainActivity).load(imageUrl).circleCrop()
                        .placeholder(R.drawable.ic_person).into(binding.ivDropdownAvatar)
                } else {
                    val pad = dp(10)
                    binding.ivMainAvatar.setPadding(pad, pad, pad, pad)
                    binding.ivMainAvatar.setImageResource(R.drawable.ic_person)
                    binding.ivMainAvatar.imageTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.ph_blue))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadUserProfileAvatar failed", e)
            }
        }
    }

    private suspend fun ensureTerminals(): List<JSONObject> {
        if (availableTerminals.isNotEmpty()) return availableTerminals.toList()

        val res = httpCall("${ApiConfig.ROUTES_URL}?type=terminals")
        if (res.code == 200) {
            val obj = JSONObject(res.body)
            if (obj.optBoolean("success", false)) {
                val arr = obj.getJSONArray("terminals")
                availableTerminals.clear()
                for (i in 0 until arr.length()) availableTerminals.add(arr.getJSONObject(i))
            }
        }
        return availableTerminals.toList()
    }

    // ------------------------------------------------------------ route tracking

    private fun routeInfoFrom(obj: JSONObject): RouteInfo {
        val origin = obj.optString("origin_name", "").ifBlank { obj.optString("origin", "") }
        val dest = obj.optString("destination_name", "").ifBlank { obj.optString("destination", "") }
        val id = if (obj.has("route_id")) obj.optInt("route_id", -1) else obj.optInt("id", -1)
        return RouteInfo(
            id = id,
            code = obj.optString("route_code").ifBlank { obj.optString("code", "") },
            name = if (dest.isNotBlank()) "$origin - $dest" else origin,
            type = obj.optString("vehicle_type", "Jeepney"),
            origin = origin,
            dest = dest
        )
    }

    private fun showRouteDetailDialog(routeObj: JSONObject) {
        val info = routeInfoFrom(routeObj)

        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_route_detail, null)
        dialog.setContentView(sheetView)

        val origin = routeObj.optString("origin_name", "N/A")
        val dest = routeObj.optString("destination_name", "N/A")

        sheetView.findViewById<TextView>(R.id.tvSheetRouteCode)?.text = info.code
        sheetView.findViewById<TextView>(R.id.tvSheetRouteName)?.text =
            if (dest != "N/A") "$origin - $dest" else origin
        sheetView.findViewById<TextView>(R.id.tvSheetVehicleType)?.text = info.type
        sheetView.findViewById<TextView>(R.id.tvSheetOriginTerminal)?.text = origin
        sheetView.findViewById<TextView>(R.id.tvSheetDestinationTerminal)?.text = dest

        sheetView.findViewById<View>(R.id.btnTrackRoute)?.setOnClickListener {
            dialog.dismiss()
            if (info.id == -1) {
                toast("This route can't be tracked.")
            } else {
                trackRoute(info)
            }
        }

        // NOTE: the old dismiss listener called clearMapRoute() whenever the preview
        // was closed without tracking. That wiped the route you were ALREADY
        // tracking while the card stayed open. Previewing draws nothing, so there is
        // nothing to clear.
        dialog.show()
    }

    /**
     * Shows [info] in the info card and on the map.
     *
     * The previous version blanked the whole screen with a modal overlay, left the
     * card showing the PREVIOUS route's text until the network answered, re-ran the
     * entire fetch after every rating, and forced the sheet back to collapsed every
     * time. Now: header and terminals appear instantly from data we already have,
     * only the fare/photos/path fill in, results are cached, and the sheet state is
     * left alone unless the card was hidden.
     */
    private fun trackRoute(info: RouteInfo, forceRefresh: Boolean = false) {
        if (info.id <= 0) return
        Log.d(TAG, "trackRoute id=${info.id} state=${bottomSheetBehavior.state} force=$forceRefresh")

        val alreadyShown = currentRoute?.id == info.id && isRouteCardShown()
        if (alreadyShown && !forceRefresh) {
            currentDetail?.let { fitCameraTo(it.points) }
            return
        }

        routeJob?.cancel()
        currentRoute = info
        persistedRoute = info
        currentDetail = null

        // 1. The card is made ready FIRST: header filled in and raised, so it is
        //    already in place while the path is still loading.
        bindRouteHeader(info)
        showRouteCard()
        fetchRouteRating(info.id)

        val cached = routeCache[info.id]?.takeIf {
            SystemClock.elapsedRealtime() - it.fetchedAt < ROUTE_CACHE_TTL_MS
        }
        if (cached != null && !forceRefresh) {
            binding.flMainRouteLoadingOverlay.visibility = View.GONE
            applyRouteDetail(info, cached)
            return
        }

        // 2. Loading screen while the path is fetched (the card sits above it).
        binding.flMainRouteLoadingOverlay.visibility = View.VISIBLE
        showPathStatus(loading = true)
        routeJob = lifecycleScope.launch {
            try {
                val detail = fetchRouteDetail(info.id)
                routeCache[info.id] = detail
                if (currentRoute?.id == info.id) applyRouteDetail(info, detail)
            } catch (e: CancellationException) {
                // A newer route (or close) took over and manages the overlay itself.
                // Touching it here would hide the NEW route's loading screen.
                throw e
            } catch (e: SessionExpiredException) {
                binding.flMainRouteLoadingOverlay.visibility = View.GONE
                handleSessionExpired()
            } catch (e: Exception) {
                Log.w(TAG, "route detail failed", e)
                if (currentRoute?.id == info.id) {
                    binding.flMainRouteLoadingOverlay.visibility = View.GONE
                    showPathStatus(
                        loading = false,
                        message = "Couldn't load the route path. Tap to retry.",
                        onRetry = { trackRoute(info, forceRefresh = true) }
                    )
                }
            }
        }
    }

    private suspend fun fetchRouteDetail(routeId: Int): RouteDetail {
        val res = httpCall("${ApiConfig.ROUTES_URL}?id=$routeId")
        if (res.code == 401) throw SessionExpiredException()
        if (res.code != 200) throw IOException(errorMessage(res, "HTTP ${res.code}"))

        return withContext(Dispatchers.Default) {
            val obj = JSONObject(res.body)
            val waypoints = obj.optJSONArray("waypoints") ?: JSONArray()
            val points = ArrayList<LatLng>(waypoints.length())
            for (i in 0 until waypoints.length()) {
                val wp = waypoints.getJSONObject(i)
                points.add(LatLng(wp.getDouble("latitude"), wp.getDouble("longitude")))
            }

            fun photo(nested: JSONObject?, flatKey: String): String? =
                nested?.optString("image_url", "")?.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: obj.optString(flatKey, "").takeIf { it.startsWith("http", ignoreCase = true) }

            RouteDetail(
                points = points,
                baseFare = obj.optDouble("base_fare", 0.0),
                originPhoto = photo(obj.optJSONObject("origin"), "origin_image_url"),
                destPhoto = photo(obj.optJSONObject("destination"), "destination_image_url")
            )
        }
    }

    /** Instant part: everything we already know about the route. */
    private fun bindRouteHeader(info: RouteInfo) {
        binding.tvRouteCode.text = info.code
        binding.tvRouteName.text = info.name
        binding.tvVehicleType.text = info.type
        binding.tvOriginDetail.text = info.origin.ifBlank { "--" }
        binding.tvDestinationDetail.text = info.dest.ifBlank { "--" }

        // Reset the parts that depend on the network so no stale values linger.
        binding.tvBaseFare.text = "--"
        binding.tvDiscountedFare.text = "--"
        binding.llUserRatingContainer.visibility = View.GONE
        showMyComment(null)
        binding.llTerminalImages.visibility = View.GONE
        Glide.with(this).clear(binding.ivOriginTerminalPhoto)
        Glide.with(this).clear(binding.ivDestinationTerminalPhoto)

        binding.btnFindNearest.alpha = 1f
    }

    private fun applyRouteDetail(info: RouteInfo, detail: RouteDetail) {
        binding.flMainRouteLoadingOverlay.visibility = View.GONE
        currentDetail = detail

        if (detail.baseFare > 0.0) {
            binding.tvBaseFare.text = peso(detail.baseFare)
            binding.tvDiscountedFare.text = peso(detail.baseFare * 0.80)
        } else {
            binding.tvBaseFare.text = "--"
            binding.tvDiscountedFare.text = "--"
        }

        bindTerminalPhotos(detail)

        if (detail.points.isEmpty()) {
            clearRouteOnMap()
            showPathStatus(loading = false, message = "No path has been mapped for this route yet.")
            return
        }

        showPathStatus(loading = false)
        drawRoute(info, detail)
    }

    private fun peso(value: Double): String = String.format(Locale.US, "₱ %.2f", value)

    private fun bindTerminalPhotos(detail: RouteDetail) {
        val hasOrigin = !detail.originPhoto.isNullOrEmpty()
        val hasDest = !detail.destPhoto.isNullOrEmpty()

        binding.llTerminalImages.visibility = if (hasOrigin || hasDest) View.VISIBLE else View.GONE
        binding.cvOriginTerminalPhoto.visibility = if (hasOrigin) View.VISIBLE else View.GONE
        binding.cvDestinationTerminalPhoto.visibility = if (hasDest) View.VISIBLE else View.GONE

        if (hasOrigin) {
            Glide.with(this).load(detail.originPhoto)
                .placeholder(R.drawable.bg_pill_soft).into(binding.ivOriginTerminalPhoto)
        }
        if (hasDest) {
            Glide.with(this).load(detail.destPhoto)
                .placeholder(R.drawable.bg_pill_soft).into(binding.ivDestinationTerminalPhoto)
        }
    }

    /** Loading / error / empty states for the path, shown inline in the card. */
    private fun showPathStatus(loading: Boolean, message: String? = null, onRetry: (() -> Unit)? = null) {
        if (!loading && message == null) {
            binding.llRoutePathLoading.visibility = View.GONE
            return
        }
        binding.llRoutePathLoading.visibility = View.VISIBLE
        binding.pbRoutePathLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvRoutePathLoadingLabel.text = message ?: "Loading route detail..."
        binding.llRoutePathLoading.setOnClickListener(
            onRetry?.let { retry -> View.OnClickListener { retry() } }
        )
        binding.llRoutePathLoading.isClickable = onRetry != null
    }

    private fun drawRoute(info: RouteInfo, detail: RouteDetail) {
        val map = mapLibreMap
        val style = map?.style
        if (!mapStyleReady || map == null || style == null) {
            pendingRouteDraw = { if (currentRoute?.id == info.id) drawRoute(info, detail) }
            return
        }

        clearMarkers()

        val line = if (detail.points.size >= 2) {
            LineString.fromLngLats(detail.points.map { Point.fromLngLat(it.longitude, it.latitude) })
        } else {
            LineString.fromLngLats(emptyList())
        }
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(Feature.fromGeometry(line))

        activeMarkers.add(
            map.addMarker(
                MarkerOptions().position(detail.points.first()).title("Origin: ${info.origin}").icon(originIcon)
            )
        )
        activeMarkers.add(
            map.addMarker(
                MarkerOptions().position(detail.points.last()).title("Destination: ${info.dest}").icon(destinationIcon)
            )
        )

        stopLocationTracking()
        fitCameraTo(detail.points)
    }

    /** Forget the current route and clear the map. Safe to call more than once. */
    private fun clearRouteState() {
        Log.d(TAG, "clearRouteState (route=${currentRoute?.id})")
        binding.flMainRouteLoadingOverlay.visibility = View.GONE
        routeJob?.cancel()
        ratingJob?.cancel()
        pendingRouteDraw = null
        currentRoute = null
        persistedRoute = null
        currentDetail = null
        clearRouteOnMap()
        binding.btnFindNearest.alpha = 1f
    }

    private fun closeRouteCard() {
        clearRouteState()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        binding.btnFindNearest.post { updateFabPosition() }
    }

    private fun checkTrackIntent(intent: Intent?) {
        if (intent == null) return
        val routeId = intent.getIntExtra("EXTRA_ROUTE_ID", -1)
        val routeCode = intent.getStringExtra("EXTRA_ROUTE_CODE")
        val origin = intent.getStringExtra("EXTRA_ORIGIN") ?: ""
        val dest = intent.getStringExtra("EXTRA_DESTINATION") ?: ""
        val vehicleType = intent.getStringExtra("EXTRA_VEHICLE_TYPE") ?: "Jeepney"

        if (routeId != -1 && !routeCode.isNullOrEmpty()) {
            setSearchText(routeCode)
            collapseSearch()
            intent.removeExtra("EXTRA_ROUTE_ID")

            trackRoute(
                RouteInfo(
                    id = routeId,
                    code = routeCode,
                    name = if (dest.isNotBlank()) "$origin - $dest" else origin,
                    type = vehicleType,
                    origin = origin,
                    dest = dest
                )
            )
        }
    }

    // ------------------------------------------------------------------ ratings

    private fun fetchRouteRating(routeId: Int) {
        if (routeId <= 0) return
        ratingJob?.cancel()
        ratingJob = lifecycleScope.launch {
            try {
                val res = httpCall(ApiConfig.getRouteRatingUrl(routeId))
                val json = JSONObject(res.body)
                if (currentRoute?.id != routeId) return@launch

                if (res.code in 200..299 && json.optBoolean("success", false)) {
                    val mine = json.optJSONObject("my_rating")
                    val avgRoute = json.optDouble("average_route_accuracy", 0.0)
                    val avgFare = json.optDouble("average_fare_accuracy", 0.0)

                    when {
                        mine != null -> {
                            val average = (mine.optInt("route_accuracy_rating", 5) +
                                    mine.optInt("fare_accuracy_rating", 5)) / 2.0
                            showRating("Your rating", average)
                            showMyComment(mine.optString("comment", ""))
                        }
                        avgRoute > 0.0 || avgFare > 0.0 -> {
                            val average = if (avgRoute > 0 && avgFare > 0) (avgRoute + avgFare) / 2.0
                            else avgRoute + avgFare
                            showRating("Commuter rating", average)
                            showMyComment(null)
                        }
                        else -> {
                            binding.llUserRatingContainer.visibility = View.GONE
                            showMyComment(null)
                        }
                    }
                } else {
                    binding.llUserRatingContainer.visibility = View.GONE
                    showMyComment(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "fetchRouteRating failed", e)
                if (currentRoute?.id == routeId) {
                    binding.llUserRatingContainer.visibility = View.GONE
                    showMyComment(null)
                }
            }
        }
    }

    /** The signed-in user's own comment, shown right under the rating row (hidden if none). */
    private var tvMyComment: TextView? = null

    private fun showMyComment(comment: String?) {
        val text = comment?.trim().orEmpty()
        val show = text.isNotEmpty() && !text.equals("null", ignoreCase = true)

        var view = tvMyComment
        if (view == null) {
            if (!show) return // nothing to hide yet
            val rating = binding.llUserRatingContainer
            val parent = rating.parent as? ViewGroup ?: return
            view = TextView(this).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, 0)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            parent.addView(view, parent.indexOfChild(rating) + 1)
            tvMyComment = view
        }

        if (show) {
            view.text = "\u201C$text\u201D"
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    private fun showRating(label: String, average: Double) {
        binding.tvRatingLabel.text = label
        binding.rbUserRouteRating.rating = average.toFloat()
        binding.tvUserRouteRatingText.text = String.format(Locale.US, "%.1f", average)
        binding.llUserRatingContainer.visibility = View.VISIBLE
    }

    private fun showGuestLoginPrompt(featureName: String = "save or rate routes") {
        AlertDialog.Builder(this)
            .setTitle("Log in required")
            .setMessage("Please log in or create an account to $featureName.")
            .setPositiveButton("Log In") { _, _ ->
                sessionPrefs.edit { remove("is_guest") }
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupGuestProfileUI() {
        binding.tvDropdownUsername.text = "Guest Commuter"
        binding.tvDropdownEmail.text = "Log in to save & rate routes"
        val pad = dp(10)
        binding.ivMainAvatar.setPadding(pad, pad, pad, pad)
        binding.ivMainAvatar.setImageResource(R.drawable.ic_person)
        binding.ivMainAvatar.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ph_blue))
    }

    private fun showRateRouteDialog(info: RouteInfo) {
        if (isGuest) {
            showGuestLoginPrompt("rate routes")
            return
        }
        if (info.id == -1) {
            toast("Please select a valid route first.")
            return
        }

        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_rate_route, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<TextView>(R.id.tvRateRouteHeader)?.text = "Route Code: ${info.code}"

        val rbRouteAccuracy = sheetView.findViewById<RatingBar>(R.id.rbRouteAccuracy)
        val rbFareAccuracy = sheetView.findViewById<RatingBar>(R.id.rbFareAccuracy)
        val etComment = sheetView.findViewById<EditText>(R.id.etRatingComment)
        val btnSubmit = sheetView.findViewById<View>(R.id.btnSubmitRating)

        btnSubmit?.setOnClickListener {
            val routeAccuracy = rbRouteAccuracy?.rating?.toInt() ?: 0
            val fareAccuracy = rbFareAccuracy?.rating?.toInt() ?: 0
            val comment = etComment?.text?.toString()?.trim().orEmpty()

            if (routeAccuracy < 1 || fareAccuracy < 1) {
                toast("Please rate both route accuracy and fare accuracy.")
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.alpha = 0.6f
            submitRouteRating(info.id, routeAccuracy, fareAccuracy, comment) { success ->
                if (success) {
                    dialog.dismiss()
                } else {
                    btnSubmit.isEnabled = true
                    btnSubmit.alpha = 1f
                }
            }
        }

        dialog.show()
    }

    /**
     * Only the rating summary is refreshed afterwards. The old code re-fetched the
     * whole route (modal overlay, redraw, camera jump, sheet collapse) AND reported
     * "Rating submitted!" even when the request had failed.
     */
    private fun submitRouteRating(
        routeId: Int, routeAccuracy: Int, fareAccuracy: Int, comment: String,
        onDone: (Boolean) -> Unit
    ) {
        lifecycleScope.launch {
            try {
                val json = JSONObject().apply {
                    put("route_accuracy_rating", routeAccuracy)
                    put("fare_accuracy_rating", fareAccuracy)
                    if (comment.isNotBlank()) put("comment", comment)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val res = httpCall(ApiConfig.getRouteRatingUrl(routeId), "POST", body)

                when {
                    res.code in 200..299 -> {
                        toast("Thanks for rating this route!")
                        fetchRouteRating(routeId)
                        onDone(true)
                    }
                    res.code == 401 -> {
                        onDone(false)
                        handleSessionExpired()
                    }
                    else -> {
                        toast(errorMessage(res, "Couldn't submit your rating (HTTP ${res.code})"), long = true)
                        onDone(false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "submitRouteRating failed", e)
                toast("Couldn't reach the server. Check your connection and try again.", long = true)
                onDone(false)
            }
        }
    }

    // -------------------------------------------------------------- buttons / nav

    private fun setupButtonsAndNav() {
        binding.btnProfile.setOnClickListener {
            binding.cvProfileDropdown.visibility =
                if (binding.cvProfileDropdown.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.rowProfileCardDetails.setOnClickListener {
            binding.cvProfileDropdown.visibility = View.GONE
            if (isGuest) {
                showGuestLoginPrompt("view or edit your profile")
            } else {
                navigateToTab(ProfileActivity::class.java)
            }
        }

        binding.btnDropdownLogout.setOnClickListener {
            binding.cvProfileDropdown.visibility = View.GONE
            if (isGuest) {
                showGuestLoginPrompt("log in or sign up")
            } else {
                showSignOutConfirmation()
            }
        }

        binding.btnFindNearest.setOnClickListener {
            val locationComponent = mapLibreMap?.locationComponent
            if (locationComponent != null && locationComponent.isLocationComponentActivated) {
                val lastLoc = locationComponent.lastKnownLocation
                if (lastLoc != null) {
                    mapLibreMap?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(lastLoc.latitude, lastLoc.longitude), 15.0)
                    )
                } else {
                    locationComponent.cameraMode = CameraMode.TRACKING
                    toast("Focusing current location...")
                }
            } else {
                mapLibreMap?.style?.let { enableLocationComponent(it) }
                toast("Focusing current location...")
            }
        }

        // Tap the header to toggle the sheet; the buttons below are bound once here
        // (they used to be re-bound on every route display).
        binding.routeInfoCard.setOnClickListener {
            when (bottomSheetBehavior.state) {
                BottomSheetBehavior.STATE_COLLAPSED -> bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                BottomSheetBehavior.STATE_EXPANDED -> bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        binding.btnCloseRouteCard.setOnClickListener { closeRouteCard() }
        binding.btnUntrackRoute.setOnClickListener { closeRouteCard() }
        binding.btnRateRoute.setOnClickListener { currentRoute?.let { showRateRouteDialog(it) } }
        binding.llPillCode.setOnClickListener { currentDetail?.let { fitCameraTo(it.points) } }

        setupMainBottomNav()
    }

    private fun setupMainBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.cvProfileDropdown.visibility = View.GONE
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_routes -> {
                    showRoutesListBottomSheet()
                    false
                }
                R.id.nav_settings -> {
                    if (isGuest) {
                        showGuestLoginPrompt("access settings and profile")
                    } else {
                        navigateToTab(ProfileActivity::class.java)
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun showSignOutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out") { _, _ ->
                sessionPrefs.edit { clear() }
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ sheets / dialogs

    private fun showTerminalByName(terminalName: String, photoUrl: String? = null) {
        val termObj = availableTerminals.firstOrNull {
            it.optString("terminal_name").equals(terminalName, ignoreCase = true)
        }
        if (termObj != null) {
            showTerminalDetailBottomSheet(termObj)
        } else {
            showTerminalDetailBottomSheet(terminalName = terminalName, photoUrl = photoUrl)
        }
    }

    private fun showTerminalDetailBottomSheet(
        terminalName: String,
        photoUrl: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        description: String? = null
    ) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_terminal_detail, null)
        dialog.setContentView(sheetView)

        // 1. Photo
        val cvPhoto = sheetView.findViewById<View>(R.id.cvTerminalPhotoContainer)
        val ivPhoto = sheetView.findViewById<ImageView>(R.id.ivTerminalPhoto)
        if (!photoUrl.isNullOrEmpty() && ivPhoto != null && cvPhoto != null) {
            cvPhoto.visibility = View.VISIBLE
            Glide.with(this).load(photoUrl).placeholder(R.drawable.bg_pill_soft).into(ivPhoto)
        } else {
            cvPhoto?.visibility = View.GONE
        }

        // 2. Terminal Name & Status
        sheetView.findViewById<TextView>(R.id.tvTerminalName)?.text = terminalName
        sheetView.findViewById<TextView>(R.id.tvTerminalStatus)?.text = "Active"

        // 3. Coordinates (Lat, Lng)
        val tvCoords = sheetView.findViewById<TextView>(R.id.tvTerminalCoordinates)
        if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
            tvCoords?.visibility = View.VISIBLE
            tvCoords?.text = String.format(Locale.US, "Lat: %.4f, Lng: %.4f", lat, lng)
        } else {
            tvCoords?.visibility = View.GONE
        }

        // 4. Description
        val tvDesc = sheetView.findViewById<TextView>(R.id.tvTerminalDescription)
        val cleanDesc = description?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        if (cleanDesc != null) {
            tvDesc?.visibility = View.VISIBLE
            tvDesc?.text = cleanDesc
        } else {
            tvDesc?.visibility = View.GONE
        }

        // 5. Routes Passing Through
        val tvHeader = sheetView.findViewById<View>(R.id.tvPassingRoutesHeader)
        val vDivider = sheetView.findViewById<View>(R.id.vTerminalDivider)
        val rvRoutes = sheetView.findViewById<RecyclerView>(R.id.rvTerminalRoutes)

        val matching = availableRoutes.filter { route ->
            route.optString("origin_name", "").equals(terminalName, ignoreCase = true) ||
                    route.optString("destination_name", "").equals(terminalName, ignoreCase = true)
        }

        if (matching.isNotEmpty() && rvRoutes != null) {
            tvHeader?.visibility = View.VISIBLE
            vDivider?.visibility = View.VISIBLE
            rvRoutes.visibility = View.VISIBLE
            rvRoutes.layoutManager = LinearLayoutManager(this)
            rvRoutes.adapter = RouteSuggestionAdapter(matching) { selectedRoute ->
                dialog.dismiss()
                showRouteDetailDialog(selectedRoute)
            }
        } else {
            tvHeader?.visibility = View.GONE
            vDivider?.visibility = View.GONE
            rvRoutes?.visibility = View.GONE
        }

        dialog.show()
    }

    private fun showTerminalDetailBottomSheet(terminalObj: JSONObject) {
        val name = terminalObj.optString("terminal_name")
        val photo = terminalObj.optString("image_url", "").takeIf { it.startsWith("http", ignoreCase = true) }
        val lat = if (terminalObj.has("latitude") && !terminalObj.isNull("latitude")) terminalObj.optDouble("latitude") else null
        val lng = if (terminalObj.has("longitude") && !terminalObj.isNull("longitude")) terminalObj.optDouble("longitude") else null
        val desc = terminalObj.optString("description", "").takeIf { it.isNotBlank() }

        showTerminalDetailBottomSheet(
            terminalName = name,
            photoUrl = photo,
            lat = lat,
            lng = lng,
            description = desc
        )
    }

    private enum class SheetFilter { ALL, SAVED, JEEPNEYS, TERMINALS }

    private fun showRoutesListBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_routes_list, null)
        dialog.setContentView(sheetView)
        dialog.setOnDismissListener { ensureRouteCardVisible() }

        val rvList = sheetView.findViewById<RecyclerView>(R.id.rvRoutesSheetList)
        val tvTrackedCount = sheetView.findViewById<TextView>(R.id.tvRoutesTrackedCount)
        val llLoading = sheetView.findViewById<View>(R.id.llRoutesSheetLoading)

        val chipAll = sheetView.findViewById<Chip>(R.id.chipSheetAll)
        val chipSaved = sheetView.findViewById<Chip>(R.id.chipSheetSaved)
        val chipJeepneys = sheetView.findViewById<Chip>(R.id.chipSheetJeepneys)
        val chipTerminals = sheetView.findViewById<Chip>(R.id.chipSheetTerminals)

        var filter = SheetFilter.ALL

        val adapter = JeepneyRouteSheetAdapter(
            items = (availableRoutes + availableTerminals).toMutableList(),
            savedRouteIds = savedRouteIds,
            onItemClick = { selectedItem ->
                dialog.dismiss()
                if (selectedItem.has("terminal_name")) {
                    showTerminalDetailBottomSheet(selectedItem)
                } else {
                    showRouteDetailDialog(selectedItem)
                }
            },
            onSaveClick = { item, currentlySaved ->
                // Terminal ids and route ids are different number spaces. Treating a
                // terminal id as a route id used to save an unrelated route.
                if (item.has("terminal_name")) {
                    toast("Only routes can be saved.")
                    return@JeepneyRouteSheetAdapter
                }
                val id = item.optInt("route_id", -1)
                if (id != -1) {
                    if (currentlySaved) savedRouteIds.remove(id) else savedRouteIds.add(id)
                    toggleSaveRoute(id, shouldSave = !currentlySaved)
                }
            }
        )

        fun applyFilter() {
            val saved = availableRoutes.filter { savedRouteIds.contains(it.optInt("route_id", -1)) }
            val (list, label) = when (filter) {
                SheetFilter.ALL -> {
                    val all = availableRoutes + availableTerminals
                    all to "${all.size} items tracked in Cebu City"
                }
                SheetFilter.SAVED -> saved to "${saved.size} saved routes"
                SheetFilter.JEEPNEYS -> availableRoutes.toList() to "${availableRoutes.size} jeepney routes tracked"
                SheetFilter.TERMINALS -> availableTerminals.toList() to "${availableTerminals.size} terminals found"
            }
            adapter.updateList(list, savedRouteIds)
            tvTrackedCount?.text = label

            val empty = list.isEmpty()
            rvList?.visibility = if (empty) View.GONE else View.VISIBLE
            val tvEmpty = sheetView.findViewById<TextView>(R.id.tvEmptyRoutesSheet)
            tvEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
            if (empty) {
                tvEmpty?.text = when (filter) {
                    SheetFilter.SAVED -> "No saved routes yet. Tap the bookmark icon on any route to save it."
                    SheetFilter.TERMINALS -> "No terminals found."
                    SheetFilter.JEEPNEYS -> "No jeepney routes found."
                    else -> "No routes or terminals found."
                }
            }

            val active = when (filter) {
                SheetFilter.ALL -> chipAll
                SheetFilter.SAVED -> chipSaved
                SheetFilter.JEEPNEYS -> chipJeepneys
                SheetFilter.TERMINALS -> chipTerminals
            }
            for (c in listOf(chipAll, chipSaved, chipJeepneys, chipTerminals)) {
                if (c == null) continue
                val on = c == active
                c.setTextColor(ContextCompat.getColor(this, if (on) R.color.ph_blue else R.color.text_secondary))
                c.chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.ph_blue_soft else R.color.bg_white)
                )
                c.chipStrokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.ph_blue else R.color.divider)
                )
            }
        }

        chipAll?.setOnClickListener { filter = SheetFilter.ALL; applyFilter() }
        chipSaved?.setOnClickListener { filter = SheetFilter.SAVED; applyFilter() }
        chipJeepneys?.setOnClickListener { filter = SheetFilter.JEEPNEYS; applyFilter() }
        chipTerminals?.setOnClickListener { filter = SheetFilter.TERMINALS; applyFilter() }

        rvList?.layoutManager = LinearLayoutManager(this)
        rvList?.adapter = adapter
        applyFilter()

        if (availableTerminals.isEmpty()) {
            llLoading?.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    ensureTerminals()
                    applyFilter()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "terminals load failed", e)
                } finally {
                    llLoading?.visibility = View.GONE
                }
            }
        } else {
            llLoading?.visibility = View.GONE
        }

        dialog.show()
    }

    private fun toggleSaveRoute(routeId: Int, shouldSave: Boolean) {
        if (isGuest) {
            showGuestLoginPrompt("save routes")
            return
        }
        // POST saves, DELETE unsaves, route_id as a query param (see /api/saved-routes).
        lifecycleScope.launch {
            try {
                val res = httpCall(
                    "${ApiConfig.SAVED_ROUTES_URL}?route_id=$routeId",
                    if (shouldSave) "POST" else "DELETE"
                )
                val ok = res.code in 200..299 || (shouldSave && res.code == 409)
                if (ok) {
                    toast(if (shouldSave) "Saved to your routes" else "Removed from saved")
                    loadSavedRouteChips()
                    ensureRouteCardVisible()
                } else {
                    // Roll back the optimistic change so the UI doesn't lie.
                    if (shouldSave) savedRouteIds.remove(routeId) else savedRouteIds.add(routeId)
                    toast(errorMessage(res, "Couldn't update saved routes"), long = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "toggleSaveRoute failed", e)
                if (shouldSave) savedRouteIds.remove(routeId) else savedRouteIds.add(routeId)
                toast("Couldn't reach the server.", long = true)
            }
        }
    }

    // ------------------------------------------------------------------- helpers

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_RECENT = "recent_searches"
        private const val SEPARATOR = "||"
        private const val ROUTE_SOURCE_ID = "route-source"
        private const val ROUTE_LAYER_ID = "route-layer"
        private const val ROUTE_CACHE_TTL_MS = 5 * 60 * 1000L

        // Process-wide, so the tracked route and its path survive this Activity being
        // destroyed and recreated while the user visits another tab (Profile, etc.).
        private val routeCache = HashMap<Int, RouteDetail>()
        private var persistedRoute: RouteInfo? = null

        // One client for the whole app process instead of a new one per request.
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}