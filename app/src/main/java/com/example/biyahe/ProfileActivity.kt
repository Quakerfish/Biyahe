package com.example.biyahe

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.widget.AppCompatButton // Use AppCompatButton

class ProfileActivity : AppCompatActivity() {

    private lateinit var btnLogout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val bottomNav: BottomNavigationView = findViewById(R.id.bottomNavigationView)
        bottomNav.selectedItemId = R.id.nav_menu

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_menu -> true
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
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
                else -> false
            }
        }

        btnLogout = findViewById(R.id.btnLogout)
        btnLogout.setOnClickListener {
            logout()
        }
    }

    private fun logout() {
        // Navigate to LoginActivity without force-clearing tasks first
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish() // Closes ProfileActivity so user can't press back into it
    }
}