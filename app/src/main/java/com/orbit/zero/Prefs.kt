package com.orbit.zero

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("orbit_zero", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "192.168.1.50") ?: "192.168.1.50"
        set(value) {
            val clean = value.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')
            sp.edit().putString("host", clean).apply()
        }

    var port: Int
        get() = sp.getInt("port", 34177)
        set(value) = sp.edit().putInt("port", value).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value).apply()

    var storageId: String
        get() = sp.getString("storageId", "") ?: ""
        set(value) = sp.edit().putString("storageId", value).apply()

    var lastCatalogFetch: Long
        get() = sp.getLong("lastCatalogFetch", 0L)
        set(value) = sp.edit().putLong("lastCatalogFetch", value).apply()

    var language: String
        get() = sp.getString("language", "en") ?: "en"
        set(value) = sp.edit().putString("language", value).apply()

    var lightTheme: Boolean
        get() = sp.getBoolean("lightTheme", false)
        set(value) = sp.edit().putBoolean("lightTheme", value).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(value) = sp.edit().putBoolean("haptics", value).apply()

    val baseUrl: String get() = "http://$host:$port"
    val paired: Boolean get() = token.length == 64

    fun clearPairing() {
        sp.edit().remove("token").apply()
    }
}
