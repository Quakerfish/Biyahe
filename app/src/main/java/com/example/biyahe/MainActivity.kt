package com.example.biyahe

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //MapLibre Initialization
        MapLibre.getInstance(this)

        setContentView(R.layout.activity_main)

        //Map API Setup
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            //Map UI Configuration
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false

            //Starti    ng Camera Point
            val cebuCityCenter = LatLng(10.3156, 123.8854)
            val initialPosition = CameraPosition.Builder()
                .target(cebuCityCenter)
                .zoom(12.0)
                .build()
            map.cameraPosition = initialPosition

            //Map Boundary
            val wideCebuBounds = LatLngBounds.Builder()
                .include(LatLng(9.4000, 123.2000))
                .include(LatLng(11.3000, 124.2000))
                .build()
            map.setLatLngBoundsForCameraTarget(wideCebuBounds)
            map.setMinZoomPreference(10.0)
            map.setMaxZoomPreference(18.0)

            // Biyahe Map API KEY
            val apiKey = "5hqAX6ehvk13Ic2HPmia"
            val styleUrl = "https://api.maptiler.com/maps/01a06643-7cd4-7150-869a-610c3182da14/style.json?key=5hqAX6ehvk13Ic2HPmia"

            map.setStyle(Style.Builder().fromUri(styleUrl))
        }
    }

    // Forward MapView lifecycle events
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
}