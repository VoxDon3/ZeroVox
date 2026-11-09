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
            sp.edit().putString("host", clean).putBoolean("hostConfigured", true).apply()
        }

    var hostConfigured: Boolean
        get() = sp.getBoolean("hostConfigured", false)
        set(value) = sp.edit().putBoolean("hostConfigured", value).apply()

    val port: Int = 33333

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

    var autoLoadAgent: Boolean
        get() = sp.getBoolean("autoLoadAgent", true)
        set(value) = sp.edit().putBoolean("autoLoadAgent", value).apply()

    var webDownloadIds: Set<String>
        get() = sp.getStringSet("webDownloadIds", emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet("webDownloadIds", value).apply()

    fun addWebDownloadId(id: Long) {
        if (id < 0) return
        val ids = webDownloadIds.toMutableSet()
        ids.add(id.toString())
        webDownloadIds = if (ids.size > 50) ids.toList().takeLast(50).toSet() else ids
    }

    fun clearPairing() {
        sp.edit().remove("token").apply()
    }
}
