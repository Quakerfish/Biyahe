package com.example.biyahe

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchClearButton: ImageButton
    private lateinit var searchScrim: View
    private lateinit var suggestionsCard: MaterialCardView
    private lateinit var suggestionsHeader: TextView
    private lateinit var suggestionsRecycler: RecyclerView
    private lateinit var suggestionsAdapter: SuggestionAdapter
    private lateinit var bottomNav: BottomNavigationView

    // TODO: replace with real data from the database
    private val recommendedCodes: List<String> = emptyList()

    private val prefs by lazy { getSharedPreferences("jeepney_search", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. MUST initialize MapLibre BEFORE layout inflation (setContentView)
        MapLibre.getInstance(this)

        // 2. Enable edge-to-edge layout
        enableEdgeToEdge()

        // 3. Inflate layout (now MapView can safely construct itself)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Set bottom padding to 0 so the taskbar sits directly at the screen edge
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false

            val cebuCityCenter = LatLng(10.3156, 123.8854)
            val initialPosition = CameraPosition.Builder()
                .target(cebuCityCenter)
                .zoom(12.0)
                .build()
            map.cameraPosition = initialPosition

            // Map Boundary
            val wideCebuBounds = LatLngBounds.Builder()
                .include(LatLng(9.4000, 123.2000))
                .include(LatLng(11.3000, 124.2000))
                .build()
            map.setLatLngBoundsForCameraTarget(wideCebuBounds)
            map.setMinZoomPreference(12.0)
            map.setMaxZoomPreference(18.0)

            // Biyahe Map API KEY
            val apiKey = "5hqAX6ehvk13Ic2HPmia"
            val styleUrl = "https://api.maptiler.com/maps/01a06643-7cd4-7150-869a-610c3182da14/style.json?key=5hqAX6ehvk13Ic2HPmia"

            map.setStyle(Style.Builder().fromUri(styleUrl))
        }

        setupSearchBar()
        setupBottomNav()
    }

    private fun setupSearchBar() {
        searchEditText = findViewById(R.id.searchEditText)
        searchClearButton = findViewById(R.id.searchClearButton)
        searchScrim = findViewById(R.id.searchScrim)
        suggestionsCard = findViewById(R.id.searchSuggestionsCard)
        suggestionsHeader = findViewById(R.id.searchSuggestionsHeader)
        suggestionsRecycler = findViewById(R.id.searchSuggestionsRecycler)

        suggestionsAdapter = SuggestionAdapter(emptyList()) { code ->
            searchEditText.setText(code)
            searchEditText.setSelection(code.length)
            onSearchSubmitted(code)
        }
        suggestionsRecycler.layoutManager = LinearLayoutManager(this)
        suggestionsRecycler.adapter = suggestionsAdapter

        searchEditText.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) expandSearch() }
        searchEditText.setOnClickListener { expandSearch() }

        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearchSubmitted(searchEditText.text.toString().trim())
                true
            } else false
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchClearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        searchClearButton.setOnClickListener {
            searchEditText.setText("")
            showRecentOrRecommended()
        }

        searchScrim.setOnClickListener { collapseSearch() }
    }

    private fun expandSearch() {
        showRecentOrRecommended()
        TransitionManager.beginDelayedTransition(rootLayout)
        searchScrim.visibility = View.VISIBLE
        suggestionsCard.visibility = View.VISIBLE
    }

    private fun collapseSearch() {
        TransitionManager.beginDelayedTransition(rootLayout)
        searchScrim.visibility = View.GONE
        suggestionsCard.visibility = View.GONE
        searchEditText.clearFocus()
    }

    private fun showRecentOrRecommended() {
        val recent = getRecentSearches()
        if (recent.isEmpty()) {
            suggestionsHeader.text = getString(R.string.recommended_header)
            suggestionsAdapter.submit(recommendedCodes)
        } else {
            suggestionsHeader.text = getString(R.string.recent_header)
            suggestionsAdapter.submit(recent)
        }
    }

    private fun onSearchSubmitted(query: String) {
        if (query.isEmpty()) return
        saveRecentSearch(query)
        collapseSearch()
        // TODO: trigger the actual jeepney-code search / camera move on the map here
    }

    private fun getRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    private fun saveRecentSearch(query: String) {
        val updated = LinkedHashSet<String>()
        updated.add(query)
        updated.addAll(getRecentSearches())
        prefs.edit().putString(KEY_RECENT, updated.take(8).joinToString(SEPARATOR)).apply()
    }

    private fun setupBottomNav() {
        bottomNav = findViewById(R.id.bottomNavigationView)
        bottomNav.selectedItemId = R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true // already here
                R.id.nav_jeepneys -> {
                    startActivity(Intent(this, JeepneysActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_landmarks -> {
                    startActivity(Intent(this, LandmarksActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_menu -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
    companion object {
        private const val KEY_RECENT = "recent_searches"
        private const val SEPARATOR = "||"
    }
}