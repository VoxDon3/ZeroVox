package com.zerovox

import android.content.Context
import android.util.Log
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
    val mirrors: List<String> = emptyList(),
    val assetName: String = "",
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
            mirrors = listOf(
                "https://cdn.jsdelivr.net/gh/saawant12/orbit-store-ps5@main/catalogue-v8.enc",
            ),
        ),
        Catalog(
            id = "dlpsgame",
            name = "DLPSGame",
            feedUrl = "https://raw.githubusercontent.com/OptiTronOffical/OptiStore/main/dlps.json",
            keyHex = "",
            mirrors = listOf(
                "https://cdn.jsdelivr.net/gh/OptiTronOffical/OptiStore@main/dlps.json",
            ),
        ),
        Catalog(
            id = "pippo",
            name = "Pippo",
            feedUrl = "https://raw.githubusercontent.com/OptiTronOffical/OptiStore/main/pippo.json",
            keyHex = "",
            mirrors = listOf(
                "https://cdn.jsdelivr.net/gh/OptiTronOffical/OptiStore@main/pippo.json",
            ),
        ),
        Catalog(
            id = "pfs",
            name = "PFS",
            feedUrl = "https://pegasus-dl.dev/catalogs/pfs.json",
            keyHex = "",
        ),
        Catalog(
            id = "zer0",
            name = "Zer0",
            feedUrl = "https://pegasus-dl.dev/catalogs/zer0.json",
            keyHex = "",
        ),
        Catalog(
            id = "evox",
            name = "evoX",
            feedUrl = "https://raw.githubusercontent.com/nexgen999/evoX-CoreOS/refs/heads/main/json/pegasus-dl/catalog.json",
            keyHex = "",
            mirrors = listOf(
                "https://cdn.jsdelivr.net/gh/nexgen999/evoX-CoreOS@main/json/pegasus-dl/catalog.json",
            ),
        ),
        Catalog(
            id = "blackbox",
            name = "Blackbox",
            feedUrl = "",
            keyHex = "",
            assetName = "blackbox.json",
        ),
    )

    @Volatile
    var currentCatalogId: String = "orbit"
        private set

    @Volatile
    var games: List<Game> = emptyList()
        private set

    @Volatile
    var ps4Supplement: List<Game> = emptyList()
        private set

    @Volatile
    var homebrewGames: List<Game> = emptyList()
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun currentCatalog(): Catalog = catalogs.firstOrNull { it.id == currentCatalogId } ?: catalogs.first()

    fun setCurrentCatalog(id: String) {
        if (catalogs.none { it.id == id }) return
        if (currentCatalogId == id) return
        currentCatalogId = id
        games = emptyList()
        revision = 0
        lastError = null
    }

    fun load(
        context: Context,
        force: Boolean,
        catalogId: String = currentCatalogId,
        onUpdate: (() -> Unit)? = null,
    ): Boolean = loadInternal(context, force, catalogId, homebrew = false, onUpdate)

    fun loadHomebrew(
        context: Context,
        force: Boolean = false,
        onUpdate: (() -> Unit)? = null,
    ): Boolean = loadInternal(context, force, "evox", homebrew = true, onUpdate)

    private fun loadInternal(
        context: Context,
        force: Boolean,
        catalogId: String,
        homebrew: Boolean,
        onUpdate: (() -> Unit)?,
    ): Boolean {
        val catalog = catalogs.firstOrNull { it.id == catalogId } ?: return false
        lastError = null
        val cache = cacheFile(context, catalog.id)

        if (catalog.assetName.isNotBlank()) {
            try {
                val plain = context.assets.open(catalog.assetName).bufferedReader().use { it.readText() }
                parse(context, plain, catalog, homebrew)
                cache.writeText(plain)
                onUpdate?.invoke()
                return true
            } catch (e: Exception) {
                lastError = e.message ?: context.getString(R.string.catalog_err_update)
                return false
            }
        }

        var cached = false
        if (cache.exists()) {
            try {
                parse(context, cache.readText(), catalog, homebrew)
                cached = true
                onUpdate?.invoke()
            } catch (_: Exception) {
            }
        }
        val fresh = cached &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MS
        if (!force && fresh) return true

        var lastException: Exception? = null
        for (url in listOf(catalog.feedUrl) + catalog.mirrors) {
            try {
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val bytes = r.body!!.bytes()
                    val plain = if (bytes.size >= 8 &&
                        String(bytes, 0, 8, Charsets.US_ASCII) == "ORBITENC"
                    ) {
                        catalog.decrypt(bytes)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                    val started = System.currentTimeMillis()
                    parse(context, plain, catalog, homebrew)
                    cache.writeText(plain)
                    onUpdate?.invoke()
                    val count = if (homebrew) homebrewGames.size else games.size
                    Log.i(
                        "CatalogRepo",
                        "${catalog.id} loaded from $url in ${System.currentTimeMillis() - started}ms " +
                            "(${bytes.size} bytes, $count games)"
                    )
                }
                return true
            } catch (e: Exception) {
                Log.w("CatalogRepo", "${catalog.id} feed failed: $url", e)
                lastException = e
            }
        }
        lastError = lastException?.message ?: context.getString(R.string.catalog_err_update)
        return cached
    }

    private fun cacheFile(context: Context, catalogId: String) = File(context.filesDir, "catalog-$catalogId.json")

    /**
     * Find a game cover in any cached catalogue, even when that catalogue is not
     * the currently selected one. Used as a fallback for transfer cards so the
     * real game icon survives an app restart or a catalogue switch.
     */
    fun findCachedCover(context: Context, title: String, filename: String): String {
        val wantedTitle = title.trim().lowercase()
        val wantedFile = filename.trim().lowercase()
        if (wantedTitle.isBlank() && wantedFile.isBlank()) return ""
        for (catalog in catalogs) {
            val file = cacheFile(context, catalog.id)
            if (!file.exists()) continue
            val raw = runCatching { file.readText() }.getOrNull() ?: continue
            if (wantedTitle.isNotBlank() && !raw.contains(wantedTitle, ignoreCase = true) &&
                wantedFile.isNotBlank() && !raw.contains(wantedFile, ignoreCase = true)
            ) continue
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val arr = root.optJSONArray("releases")
                ?: root.optJSONArray("packages")
                ?: root.optJSONArray("games")
                ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val oTitle = o.optString("title").trim().lowercase()
                val oFile = o.optString("filename").trim().lowercase()
                val matches = when {
                    wantedTitle.isNotBlank() && oTitle == wantedTitle -> true
                    wantedFile.isNotBlank() && oFile == wantedFile -> true
                    wantedTitle.isNotBlank() && oTitle.isNotBlank() &&
                        (oTitle.contains(wantedTitle) || wantedTitle.contains(oTitle)) -> true
                    wantedFile.isNotBlank() && oFile.isNotBlank() &&
                        (oFile.contains(wantedFile) || wantedFile.contains(oFile)) -> true
                    else -> false
                }
                if (!matches) continue
                val cover = o.optString("cover").ifBlank {
                    o.optString("posterUrl").ifBlank { o.optString("originalPosterUrl") }
                }
                if (cover.isNotBlank()) return safeUrl(cover)
            }
        }
        return ""
    }

    /**
     * Built-in PS5 feeds contain no PS4 titles. The Orbit feed is the only
     * built-in source that already carries PS4 games, so reuse its cached
     * releases as a supplement for the other catalogues.
     */
    fun loadPs4Supplement(context: Context) {
        val file = cacheFile(context, "orbit")
        if (!file.exists()) {
            ps4Supplement = emptyList()
            return
        }
        val raw = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val arr = root.optJSONArray("releases") ?: run {
            ps4Supplement = emptyList()
            return
        }
        val grouped = LinkedHashMap<String, MutableList<GameOption>>()
        val meta = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val titleId = o.optString("titleId").trim()
            if (title.isEmpty() || !titleId.startsWith("CUSA")) continue
            val platform = "ps4"
            val gameId = o.optString("gameId").ifBlank { titleId }
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
                url = o.optString("url"),
                browserUrl = o.optString("browserUrl"),
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
                    cover = safeUrl(
                        o.optString("cover").ifBlank {
                            o.optString("posterUrl").ifBlank { o.optString("originalPosterUrl") }
                        }
                    ),
                    options = sorted,
                )
            )
        }
        list.sortBy { it.title.lowercase() }
        ps4Supplement = list
    }

    private fun safeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.contains("evoX-CoreOS") && trimmed.contains("/assets/icon/")) {
            return trimmed.replace(" ", "")
        }
        return trimmed.replace(" ", "%20")
    }

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

    private fun parse(context: Context, plain: String, catalog: Catalog, homebrew: Boolean = false) {
        val root = JSONObject(plain)
        val rev = if (root.has("releases")) root.optInt("revision", 0) else root.optInt("version", 1)
        when {
            root.has("releases") -> parseReleases(context, root, catalog, rev, homebrew)
            root.has("packages") -> parsePackages(context, root, catalog, rev, homebrew)
        }
    }

    private fun parseReleases(context: Context, root: JSONObject, catalog: Catalog, rev: Int, homebrew: Boolean = false) {
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
                    cover = safeUrl(
                        o.optString("cover").ifBlank {
                            o.optString("posterUrl").ifBlank { o.optString("originalPosterUrl") }
                        }
                    ),
                    options = sorted,
                )
            )
        }
        list.sortBy { it.title.lowercase() }
        if (homebrew) {
            homebrewGames = list
        } else if (catalog.id == currentCatalogId) {
            revision = rev
            games = list
        }
    }

    private fun parsePackages(context: Context, root: JSONObject, catalog: Catalog, rev: Int, homebrew: Boolean = false) {
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
                    filename = o.optString("filename").ifBlank { linkName },
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
                    cover = safeUrl(
                        o.optString("cover").ifBlank {
                            o.optString("posterUrl").ifBlank { o.optString("originalPosterUrl") }
                        }
                    ),
                    options = sorted,
                )
            )
        }
        list.sortBy { it.title.lowercase() }
        if (homebrew) {
            homebrewGames = list
        } else if (catalog.id == currentCatalogId) {
            revision = rev
            games = list
        }
    }
}
