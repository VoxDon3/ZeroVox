package com.orbit.zero

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CatalogRepo {
    private const val FEED_URL =
        "https://raw.githubusercontent.com/saawant12/orbit-store-ps5/main/catalogue-v8.enc"
    private const val CACHE_NAME = "catalog-v8.json"
    private const val CACHE_MS = 6L * 60L * 60L * 1000L

    private val KEY = "2143263b716b06ec565d047a8c370da7e5d9690975748e836ed92c664341f6ac"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Volatile
    var games: List<Game> = emptyList()
        private set

    @Volatile
    var revision: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_NAME)

    fun load(context: Context, force: Boolean): Boolean {
        lastError = null
        val cache = cacheFile(context)
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MS

        if (!force && fresh) {
            return try {
                parse(context, cache.readText())
                true
            } catch (_: Exception) {
                false
            }
        }

        try {
            val request = Request.Builder().url(FEED_URL).get().build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                val encrypted = r.body!!.bytes()
                val plain = decrypt(context, encrypted)
                cache.writeText(plain)
                parse(context, plain)
            }
            return true
        } catch (e: Exception) {
            lastError = e.message ?: context.getString(R.string.catalog_err_update)
            if (cache.exists()) {
                return try {
                    parse(context, cache.readText())
                    true
                } catch (_: Exception) {
                    false
                }
            }
            return false
        }
    }

    private fun decrypt(context: Context, data: ByteArray): String {
        require(data.size > 40) { context.getString(R.string.catalog_err_invalid) }
        require(String(data, 0, 8, Charsets.US_ASCII) == "ORBITENC") { context.getString(R.string.catalog_err_unknown_format) }
        val header = data.copyOfRange(0, 24)
        val nonce = data.copyOfRange(12, 24)
        val cipherText = data.copyOfRange(24, data.size - 16)
        val tag = data.copyOfRange(data.size - 16, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY, "AES"),
            GCMParameterSpec(128, nonce)
        )
        cipher.updateAAD(header)
        return String(cipher.doFinal(cipherText + tag), Charsets.UTF_8)
    }

    private fun parse(context: Context, plain: String) {
        val root = JSONObject(plain)
        revision = root.optInt("revision", 0)
        val arr = root.optJSONArray("releases") ?: throw RuntimeException(context.getString(R.string.catalog_err_no_games))
        val grouped = LinkedHashMap<String, MutableList<GameOption>>()
        val meta = HashMap<String, JSONObject>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val titleId = o.optString("titleId").trim()
            if (title.isEmpty() || titleId.isEmpty()) continue

            val platform = if (titleId.startsWith("CUSA")) "ps4" else "ps5"
            val gameId = o.optString("gameId").ifBlank { titleId }
            val url = o.optString("url")
            val browserUrl = o.optString("browserUrl")
            val content = JSONObject()
                .put("title", title)
                .put("titleId", titleId)
                .put("platform", platform)
                .put("gameId", gameId)
                .put("format", o.optString("format", "Unknown"))
            if (o.has("contentFormat")) content.put("contentFormat", o.optString("contentFormat"))

            val option = GameOption(
                id = o.optString("id"),
                releaseId = o.optString("id"),
                title = title,
                titleId = titleId,
                platform = platform,
                format = o.optString("format", "Unknown"),
                source = o.optString("sourceId"),
                provider = o.optString("provider", o.optString("sourceId")),
                sizeBytes = o.optLong("sizeBytes"),
                filename = o.optString("filename"),
                url = url,
                browserUrl = browserUrl,
                sha256 = o.optString("sha256"),
                content = content,
            )
            val key = "$gameId|$platform"
            grouped.getOrPut(key) { ArrayList() }.add(option)
            meta.putIfAbsent(key, o)
        }

        val list = ArrayList<Game>(grouped.size)
        for ((key, options) in grouped) {
            val o = meta[key] ?: continue
            val sorted = options.sortedWith(
                compareByDescending<GameOption> { it.phoneAvailable }
                    .thenBy { it.format }
                    .thenBy { it.sizeBytes }
            )
            list.add(
                Game(
                    key = key,
                    gameId = o.optString("gameId").ifBlank { options.first().titleId },
                    title = options.first().title,
                    titleId = options.first().titleId,
                    platform = options.first().platform,
                    genre = o.optString("genre"),
                    region = o.optString("region"),
                    cover = o.optString("cover"),
                    options = sorted,
                )
            )
        }
        list.sortBy { it.title.lowercase() }
        games = list
    }
}
