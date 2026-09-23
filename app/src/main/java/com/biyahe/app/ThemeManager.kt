package com.biyahe.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Persists the user's chosen theme (light / dark / follow system) and applies it.
 *
 * Usage:
 *  - Call [ThemeManager.applySavedTheme] as early as possible (ideally from a custom
 *    [android.app.Application.onCreate], and defensively again from [BaseActivity]) so
 *    every screen launches in the right mode without a flash of the wrong theme.
 *  - Call [ThemeManager.setMode] when the user picks a new option (e.g. from the
 *    Appearance row on the Profile screen); it saves the choice and switches immediately.
 */
object ThemeManager {

    const val MODE_LIGHT = 0
    const val MODE_DARK = 1
    const val MODE_SYSTEM = 2

    private const val PREFS_NAME = "app_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    /** Reads the saved preference (defaults to "follow system"). */
    fun getMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_MODE, MODE_SYSTEM)
    }

    /** Saves [mode] and applies it immediately app-wide. */
    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode)
            .apply()
        applyMode(mode)
    }

    /** Re-applies whatever mode was last saved. Safe to call from every activity's onCreate. */
    fun applySavedTheme(context: Context) {
        applyMode(getMode(context))
    }

    private fun applyMode(mode: Int) {
        val nightMode = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    fun labelFor(mode: Int): Int = when (mode) {
        MODE_LIGHT -> R.string.menu_appearance_desc_light
        MODE_DARK -> R.string.menu_appearance_desc_dark
        else -> R.string.menu_appearance_desc_system
    }
}
