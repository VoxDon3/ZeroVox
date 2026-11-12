package com.zerovox

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

data class TransferState(
    val id: String = "",
    val title: String = "",
    val filename: String = "",
    val state: String = "idle",
    val received: Long = 0,
    val total: Long = 0,
    val speed: Double = 0.0,
    val message: String = "",
    val uri: String = "",
    val path: String = "",
    val url: String = "",
    val cookie: String = "",
    val userAgent: String = "",
    val origin: String = "",
    val sha256: String = "",
    val mode: String = "",
) {
    val percent: Int
        get() = if (total > 0) ((received * 100) / total).coerceIn(0, 100).toInt() else 0
    val running: Boolean
        get() = state in setOf("preparing", "downloading", "verifying")
    val paused: Boolean
        get() = state == "paused"
}

object TransferStore {
    @Volatile
    var current: TransferState = TransferState()

    @Volatile
    var cancelRequested: Boolean = false

    @Volatile
    var pauseRequested: Boolean = false

    @Volatile
    private var muted: Boolean = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastPersistAt: Long = 0L

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            restore()
        }
    }

    fun reset(title: String, filename: String, total: Long) {
        muted = false
        cancelRequested = false
        pauseRequested = false
        current = TransferState(
            title = title,
            filename = filename,
            state = "preparing",
            total = total,
        )
        persist(force = true)
    }

    fun update(
        state: String? = null,
        received: Long? = null,
        speed: Double? = null,
        message: String? = null,
        uri: String? = null,
        path: String? = null,
        url: String? = null,
        cookie: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        sha256: String? = null,
        total: Long? = null,
        title: String? = null,
        filename: String? = null,
        mode: String? = null,
    ) {
        if (muted) return
        val c = current
        val next = c.copy(
            state = state ?: c.state,
            received = received ?: c.received,
            speed = speed ?: c.speed,
            message = message ?: c.message,
            uri = uri ?: c.uri,
            path = path ?: c.path,
            url = url ?: c.url,
            cookie = cookie ?: c.cookie,
            userAgent = userAgent ?: c.userAgent,
            origin = origin ?: c.origin,
            sha256 = sha256 ?: c.sha256,
            total = total ?: c.total,
            title = title ?: c.title,
            filename = filename ?: c.filename,
            mode = mode ?: c.mode,
        )
        current = next
        val important = state != null && state in setOf(
            "downloading", "paused", "complete", "error", "cancelled", "sent"
        )
        val now = System.currentTimeMillis()
        if (important || now - lastPersistAt >= 4000) {
            persist(force = important)
        }
    }

    /** Force an immediate synchronous write of the current transfer state. */
    fun persistNow() {
        persist(force = true)
    }

    /** Persist the paused state immediately so it survives the app being closed. */
    fun pause() {
        pauseRequested = true
        val c = current
        if (c.state in setOf("preparing", "downloading")) {
            current = c.copy(state = "paused", speed = 0.0)
            persist(force = true)
        }
    }

    /** Discard the current transfer card and ignore any in-flight service updates. */
    fun clear() {
        muted = true
        cancelRequested = true
        pauseRequested = false
        current = TransferState()
        persist(force = true)
    }

    private fun persist(force: Boolean) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < 4000) return
        lastPersistAt = now
        val c = current
        val shouldKeep = when {
            c.state == "idle" -> false
            c.state == "paused" -> true
            c.running -> c.mode == "local" && c.url.isNotBlank()
            c.state in setOf("complete", "error", "cancelled", "sent") -> true
            else -> false
        }
        val json = if (!shouldKeep) "" else JSONObject().apply {
            put("title", c.title)
            put("filename", c.filename)
            put("state", c.state)
            put("received", c.received)
            put("total", c.total)
            put("message", c.message)
            put("uri", c.uri)
            put("path", c.path)
            put("url", c.url)
            put("cookie", c.cookie)
            put("userAgent", c.userAgent)
            put("origin", c.origin)
            put("sha256", c.sha256)
            put("mode", c.mode)
        }.toString()
        Prefs(ctx).transferStateJson = json
    }

    private fun restore() {
        val ctx = appContext ?: return
        val raw = Prefs(ctx).transferStateJson
        if (raw.isBlank()) return
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        var state = obj.optString("state", "idle")
        val mode = obj.optString("mode", "")
        val url = obj.optString("url", "")
        val uri = obj.optString("uri", "")
        val path = obj.optString("path", "")
        var received = obj.optLong("received", 0L)
        val total = obj.optLong("total", 0L)

        if (state in setOf("preparing", "downloading", "verifying")) {
            state = if (mode == "local" && url.isNotBlank()) "paused" else "idle"
        }
        if (state == "idle") {
            Prefs(ctx).transferStateJson = ""
            return
        }
        if (mode == "local" && (state == "paused" || state == "error")) {
            val actual = actualSize(ctx, uri, path)
            if (actual > received) received = actual
            if (total > 0 && received > total) received = total
        }
        current = TransferState(
            title = obj.optString("title", ""),
            filename = obj.optString("filename", ""),
            state = state,
            received = received,
            total = total,
            speed = 0.0,
            message = if (state == "paused") "" else obj.optString("message", ""),
            uri = uri,
            path = path,
            url = url,
            cookie = obj.optString("cookie", ""),
            userAgent = obj.optString("userAgent", ""),
            origin = obj.optString("origin", ""),
            sha256 = obj.optString("sha256", ""),
            mode = mode,
        )
        cancelRequested = false
        pauseRequested = false
        muted = false
    }

    private fun actualSize(ctx: Context, uriString: String, path: String): Long {
        if (uriString.startsWith("content:")) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return 0L
            return runCatching {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L)
        }
        val file = when {
            uriString.startsWith("file:") -> runCatching { Uri.parse(uriString).path?.let { File(it) } }.getOrNull()
            path.isNotBlank() -> File(path)
            else -> null
        } ?: return 0L
        return if (file.exists()) file.length() else 0L
    }
}
