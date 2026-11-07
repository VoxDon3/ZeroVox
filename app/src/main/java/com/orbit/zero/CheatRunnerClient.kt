package com.orbit.zero

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CheatGame(
    val titleId: String,
    val titleName: String,
    val platform: String,
    val version: String,
    val iconUrl: String,
    val hasIcon: Boolean,
    val hasCheat: Boolean,
    val cheatFormat: String,
    val running: Boolean,
    val playTime: Long,
)

data class CheatMod(
    val index: Int,
    val name: String,
    val visualState: String,
    val stateLabel: String,
    val canToggle: Boolean,
    val blockReason: String,
)

data class CheatState(
    val mods: List<CheatMod>,
    val cheatFormat: String,
    val detectedVersion: String,
    val gameRunning: Boolean,
    val summaryOn: Int,
    val summaryOff: Int,
    val summaryNotReady: Int,
)

class CheatRunnerClient(private val prefs: Prefs) {
    companion object {
        const val PORT = 9999

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun parseGames(resp: Resp): List<CheatGame> {
            val root = resp.json() ?: return emptyList()
            val arr = root.optJSONArray("games") ?: return emptyList()
            val out = ArrayList<CheatGame>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val platform = o.optString("platform").uppercase()
                if (platform != "PS5" && platform != "PS4") continue
                if (o.optBoolean("isApp")) continue
                val titleId = o.optString("titleId")
                if (titleId.isBlank()) continue
                out.add(
                    CheatGame(
                        titleId = titleId,
                        titleName = o.optString("titleName").ifBlank { titleId },
                        platform = platform,
                        version = o.optString("version").ifBlank { "unknown" },
                        iconUrl = o.optString("iconUrl").ifBlank { "/appdb/icon?id=$titleId" },
                        hasIcon = o.optBoolean("icon"),
                        hasCheat = o.optBoolean("hasCheat"),
                        cheatFormat = o.optString("cheatFormat"),
                        running = o.optBoolean("running"),
                        playTime = o.optLong("playTime"),
                    )
                )
            }
            return out
        }

        fun parseState(resp: Resp): CheatState? {
            val root = resp.json() ?: return null
            if (!root.optBoolean("ok")) return null
            val modsArr = root.optJSONArray("mods") ?: return null
            val mods = ArrayList<CheatMod>(modsArr.length())
            for (i in 0 until modsArr.length()) {
                val o = modsArr.optJSONObject(i) ?: continue
                mods.add(
                    CheatMod(
                        index = o.optInt("index", i),
                        name = o.optString("name").ifBlank { "Cheat ${i + 1}" },
                        visualState = o.optString("visualState"),
                        stateLabel = o.optString("label"),
                        canToggle = o.optBoolean("canToggle"),
                        blockReason = o.optString("blockReason"),
                    )
                )
            }
            val summary = root.optJSONObject("summary")
            return CheatState(
                mods = mods,
                cheatFormat = root.optString("cheatFormat"),
                detectedVersion = root.optString("detectedVersion"),
                gameRunning = root.optJSONObject("game")?.optBoolean("running") ?: false,
                summaryOn = summary?.optInt("on") ?: 0,
                summaryOff = summary?.optInt("off") ?: 0,
                summaryNotReady = summary?.optInt("notReady") ?: 0,
            )
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val baseUrl: String get() = "http://${prefs.host}:$PORT"

    fun iconUrl(game: CheatGame): String =
        if (game.iconUrl.startsWith("http")) game.iconUrl else baseUrl + game.iconUrl

    private fun get(path: String): Resp {
        return try {
            val request = Request.Builder().url(baseUrl + path).get().build()
            http.newCall(request).execute().use { r ->
                Resp(r.code, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Resp(0, "", e.message ?: "Network error")
        }
    }

    fun games(force: Boolean = false): Resp =
        get("/api/games" + if (force) "?refresh=1" else "")

    fun state(titleId: String): Resp =
        get("/api/cheats/state?titleId=${enc(titleId)}&debug=1")

    fun toggle(titleId: String, index: Int, on: Boolean): Resp =
        get("/api/cheats/toggle?titleId=${enc(titleId)}&index=$index&on=${if (on) 1 else 0}")

    fun launch(titleId: String): Resp =
        get("/api/launch?titleId=${enc(titleId)}")
}
