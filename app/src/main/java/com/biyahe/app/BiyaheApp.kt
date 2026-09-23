package com.biyahe.app

import android.app.Application

class BiyaheApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySavedTheme(this)
    }
}
