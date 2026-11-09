package com.orbit.zero

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Catalog(
    val id: String,
    val name: String,
    val feedUrl: String,
    val keyHex: String,
) {
    private val key: ByteArray
        get() = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun decrypt(data: ByteArray): String {
        require(data.size > 40) { "invalid" }
        require(String(data, 0, 8, Charsets.US_ASCII) == "ORBITENC") { "unknown format" }
        val header = data.copyOfRange(0, 24)
        val nonce = data.copyOfRange(12, 24)
        val cipherText = data.copyOfRange(24, data.size - 16)
        val tag = data.copyOfRange(data.size - 16, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        cipher.updateAAD(header)
        return String(cipher.doFinal(cipherText + tag), Charsets.UTF_8)
    }
}

object CatalogRepo {
    private const val CACHE_MS = 6L * 60L * 60L * 1000L

    val catalogs: List<Catalog> = listOf(
        Catalog(
            id = "orbit",
            name = "Orbit",
            feedUrl = "https://raw.githubusercontent.com/saawant12/orbit-store-ps5/main/catalogue-v8.enc",
            keyHex = "2143263b716b06ec565d047a8c370da7e5d9690975748e836ed92c664341f6ac",
        ),
        Catalog(
            id = "dlpsgame",
            name = "DLPSGame",
            feedUrl = "https://raw.githubusercontent.com/OptiTronOffical/OptiStore/main/dlps.json",
            keyHex = "",
        ),
        Catalog(
            id = "pippo",
            name = "Pippo",
            feedUrl = "https://raw.githubusercontent.com/OptiTronOffical/OptiStore/main/pippo.json",
            keyHex = "",
        ),
    )

    @Volatile
    var currentCatalogId: String = "orbit"
        private set

    @Volatile
    var games: List<Game> = emptyList()
        private set

    @Volatile
    var revision: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var sizeIndex: Map<String, Long>? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun currentCatalog(): Catalog = catalogs.firstOrNull { it.id == currentCatalogId } ?: catalogs.first()

    fun setCurrentCatalog(id: String) {
        currentCatalogId = id
    }

    fun load(context: Context, force: Boolean, catalogId: String = currentCatalogId): Boolean {
        val catalog = catalogs.firstOrNull { it.id == catalogId } ?: return false
        lastError = null
        val cache = cacheFile(context, catalog.id)
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MS

        if (!force && fresh) {
            return try {
                parse(context, cache.readText(), catalog)
                true
            } catch (_: Exception) {
                false
            }
        }

        try {
            val request = Request.Builder().url(catalog.feedUrl).get().build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                val bytes = r.body!!.bytes()
                val plain = if (String(bytes, 0, 8, Charsets.US_ASCII) == "ORBITENC") {
                    catalog.decrypt(bytes)
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                cache.writeText(plain)
                parse(context, plain, catalog)
            }
            return true
        } catch (e: Exception) {
            lastError = e.message ?: context.getString(R.string.catalog_err_update)
            if (cache.exists()) {
                return try {
                    parse(context, cache.readText(), catalog)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            return false
        }
    }

    private fun cacheFile(context: Context, catalogId: String) = File(context.filesDir, "catalog-$catalogId.json")

    private fun sizeIndex(context: Context): Map<String, Long> {
        sizeIndex?.let { return it }
        val map = HashMap<String, Long>()
        runCatching {
            context.assets.open("dlps_sizes.json").bufferedReader().use { reader ->
                val obj = JSONObject(reader.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optLong(k, 0L)
                    if (v > 0) map[k] = v
                }
            }
        }
        sizeIndex = map
        return map
    }

    private fun parse(context: Context, plain: String, catalog: Catalog) {
        val root = JSONObject(plain)
        revision = if (root.has("releases")) root.optInt("revision", 0) else root.optInt("version", 1)
        when {
            root.has("releases") -> parseOrbitReleases(context, root)
            root.has("packages") -> parsePackages(context, root, catalog)
        }
    }

    private fun parseOrbitReleases(context: Context, root: JSONObject) {
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
                    cover = o.optString("cover").ifBlank {
                        o.optString("posterUrl").ifBlank { o.optString("originalPosterUrl") }
                    },
                    options = sorted,
                )
            )
        }
        list.sortBy { it.title.lowercase() }
        games = list
    }

    private fun parsePackages(context: Context, root: JSONObject, catalog: Catalog) {
        val arr = root.optJSONArray("packages") ?: throw RuntimeException(context.getString(R.string.catalog_err_no_games))
        val grouped = LinkedHashMap<String, MutableList<GameOption>>()
        val meta = HashMap<String, JSONObject>()
        val sizes = sizeIndex(context)

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val titleId = o.optString("titleId").trim()
            val title = o.optString("title").trim()
            if (titleId.isEmpty() || title.isEmpty()) continue

            val platform = if (titleId.startsWith("CUSA")) "ps4" else "ps5"
            val gameId = titleId
            val category = o.optString("category", "game")
            val rawSize = o.optLong("sizeBytes", 0L)
            val sizeBytes = if (rawSize > 0) rawSize else sizes[titleId] ?: 0L
            val links = o.optJSONArray("downloadLinks") ?: JSONArray()

            for (j in 0 until links.length()) {
                val l = links.optJSONObject(j) ?: continue
                val url = l.optString("url")
                if (url.isBlank() || !url.startsWith("http")) continue
                val linkName = l.optString("name", title)
                val content = JSONObject()
                    .put("title", title)
                    .put("titleId", titleId)
                    .put("platform", platform)
                    .put("gameId", gameId)
                    .put("format", category)
                val option = GameOption(
                    id = "${titleId}_$j",
                    releaseId = titleId,
                    title = title,
                    titleId = titleId,
                    platform = platform,
                    format = category,
                    source = o.optString("downloadSource"),
                    provider = o.optString("downloadSource"),
                    sizeBytes = sizeBytes,
                    filename = linkName,
                    url = url,
                    browserUrl = url,
                    sha256 = l.optString("sha256"),
                    content = content,
                )
                val key = "$gameId|$platform"
                grouped.getOrPut(key) { ArrayList() }.add(option)
                meta.putIfAbsent(key, o)
            }
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
                    cover = o.optString("cover").ifBlank {
                        o.optString("posterUrl").ifBlank { o.optString("originalPosterUrl") }
                    },
                    options = sorted,
                )
            )
        }
        list.sortBy { it.title.lowercase() }
        games = list
    }
}
