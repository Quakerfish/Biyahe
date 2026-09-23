package com.biyahe.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE calling super.onCreate()
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Check if user has a stored session cookie
        val prefs = getSharedPreferences("app_session", MODE_PRIVATE)
        val sessionCookie = prefs.getString("session_cookie", null)

        val targetActivity = if (!sessionCookie.isNullOrEmpty()) {
            // User is logged in -> route to main screen (e.g., MainActivity or ProfileActivity)
            MainActivity::class.java
        } else {
            // User is not logged in -> route to Login
            LoginActivity::class.java
        }

        startActivity(Intent(this, targetActivity))
        finish() // Prevent returning to SplashActivity on back button press
    }
}