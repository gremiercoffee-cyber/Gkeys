package com.gremier.gkeys.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.gremier.gkeys.settings.GkeysSettings

object GkeysTheme {

    fun isDarkMode(mode: String): Boolean = mode != GkeysSettings.THEME_LIGHT

    fun wrap(base: Context, dark: Boolean): Context {
        val config = Configuration(base.resources.configuration)
        val nightMode = if (dark) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        return base.createConfigurationContext(config)
    }

    fun applyAppCompatNightMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
