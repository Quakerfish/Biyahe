package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        disableActivityTransitions()
    }

    // Handles FLAG_ACTIVITY_REORDER_TO_FRONT bringing an existing screen forward
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        disableActivityTransitions()
    }

    override fun finish() {
        super.finish()
        disableActivityTransitions()
    }

    override fun onPause() {
        super.onPause()
        disableActivityTransitions()
    }

    protected fun showNetworkError(detail: String? = null) {
        val message = getString(R.string.error_network)
        Toast.makeText(this, if (detail.isNullOrBlank()) message else "$message ($detail)", Toast.LENGTH_LONG).show()
    }
}