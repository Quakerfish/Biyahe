package com.biyahe.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Helper function to completely strip window transitions in both modern and legacy Android. */
fun Activity.disableActivityTransitions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** Brings an existing instance of [target] to the front without stacking duplicates. */
fun Activity.navigateToTab(target: Class<out Activity>) {
    if (this::class.java == target) return
    val intent = Intent(this, target).apply {
        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    startActivity(intent)
    disableActivityTransitions()
}

/** Synchronizes the highlighted bottom bar tab without re-triggering navigation listeners. */
fun Activity.setupBottomNav(nav: BottomNavigationView, currentTabId: Int) {
    nav.setOnItemSelectedListener(null)
    nav.selectedItemId = currentTabId

    nav.setOnItemSelectedListener { item ->
        if (item.itemId == currentTabId) return@setOnItemSelectedListener true

        when (item.itemId) {
            R.id.nav_home -> {
                navigateToTab(MainActivity::class.java)
                false
            }
            R.id.nav_routes -> {
                navigateToTab(RoutesActivity::class.java)
                false
            }
            R.id.nav_profile -> {
                navigateToTab(ProfileActivity::class.java)
                false
            }
            else -> false
        }
    }
}
