package com.hyntix.android.usbflasher.util

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration

/**
 * Simple theme preference store using SharedPreferences.
 * Values: "system", "light", "dark"
 */
object ThemePreference {
    private const val PREFS_NAME = "usbflasher_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    fun getThemeMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
    }

    fun setThemeMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode)
            .apply()
    }

    fun isDark(context: Context, isSystemDark: Boolean): Boolean {
        return when (getThemeMode(context)) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> isSystemDark
        }
    }

    fun nextMode(context: Context): String {
        return when (getThemeMode(context)) {
            MODE_SYSTEM -> MODE_LIGHT
            MODE_LIGHT -> MODE_DARK
            else -> MODE_SYSTEM
        }
    }

    fun modeLabel(context: Context): String {
        return when (getThemeMode(context)) {
            MODE_SYSTEM -> "System"
            MODE_LIGHT -> "Light"
            MODE_DARK -> "Dark"
            else -> "System"
        }
    }
}