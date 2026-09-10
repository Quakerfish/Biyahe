package com.example.biyahe

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class JeepneysActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_jeepneys)

        rootLayout = findViewById(R.id.main)
        bottomNav = findViewById(R.id.bottomNavigationView)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 1. Pad top of rootLayout so content doesn't go under status bar
            rootLayout.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)

            // 2. Pad bottom of bottomNav so icons sit above gesture bar while background extends to edge
            bottomNav.setPadding(0, 0, 0, systemBars.bottom)

            insets
        }

        setupBottomNav()
    }

    private fun setupBottomNav() {
        bottomNav = findViewById(R.id.bottomNavigationView)
        bottomNav.selectedItemId = R.id.nav_jeepneys

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_jeepneys -> true // already here
                R.id.nav_landmarks -> {
                    startActivity(Intent(this, LandmarksActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_menu -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                else -> false
            }
        }
    }
}