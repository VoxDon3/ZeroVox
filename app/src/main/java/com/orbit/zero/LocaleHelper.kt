package com.orbit.zero

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    val languages = listOf("en", "fr", "ar", "es", "ko")

    val languageNames = linkedMapOf(
        "en" to "English",
        "fr" to "Français",
        "ar" to "العربية",
        "es" to "Español",
        "ko" to "한국어",
    )

    fun currentTag(context: Context): String {
        val saved = context.getSharedPreferences("orbit_zero", Context.MODE_PRIVATE)
            .getString("language", "en")
        return if (saved in languages) saved.orEmpty() else "en"
    }

    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(currentTag(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
