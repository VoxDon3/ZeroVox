package com.zerovox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatRunnerClientTest {
    @Test
    fun parseGamesKeepsOnlyConsoleGamesWithTitleIds() {
        val root = JSONObject().put(
            "games",
            JSONArray()
                .put(
                    JSONObject()
                        .put("titleId", "PPSA00001")
                        .put("titleName", "PS5 Game")
                        .put("platform", "ps5")
                        .put("version", "01.002.000")
                        .put("icon", true)
                        .put("hasCheat", true)
                        .put("cheatFormat", "MC4")
                        .put("running", true)
                        .put("playTime", 120L)
                )
                .put(
                    JSONObject()
                        .put("titleId", "CUSA00002")
                        .put("platform", "PS4")
                )
                .put(
                    JSONObject()
                        .put("titleId", "PPSA00003")
                        .put("platform", "PS5")
                        .put("isApp", true)
                )
                .put(JSONObject().put("titleId", "").put("platform", "PS5"))
                .put(JSONObject().put("titleId", "XBOX0001").put("platform", "Xbox"))
        )

        val games = CheatRunnerClient.parseGames(Resp(200, root.toString()))

        assertEquals(2, games.size)
        assertEquals("PS5", games[0].platform)
        assertEquals("PS5 Game", games[0].titleName)
        assertEquals("unknown", games[1].version)
        assertEquals("/appdb/icon?id=CUSA00002", games[1].iconUrl)
        assertFalse(games[1].hasIcon)
        assertTrue(games[0].hasCheat)
        assertTrue(games[0].running)
        assertEquals(120L, games[0].playTime)
    }

    @Test
    fun parseStateMapsModsSummaryAndRunningGame() {
        val root = JSONObject()
            .put("ok", true)
            .put("cheatFormat", "MC4")
            .put("detectedVersion", "01.002.000")
            .put("game", JSONObject().put("running", true))
            .put(
                "mods",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("index", 2)
                            .put("name", "Infinite Health")
                            .put("visualState", "on")
                            .put("label", "Enabled")
                            .put("canToggle", true)
                            .put("blockReason", "")
                    )
                    .put(JSONObject().put("index", 3))
            )
            .put(
                "summary",
                JSONObject()
                    .put("on", 1)
                    .put("off", 2)
                    .put("notReady", 3)
            )

        val state = CheatRunnerClient.parseState(Resp(200, root.toString()))!!

        assertEquals(2, state.mods.size)
        assertEquals("Infinite Health", state.mods[0].name)
        assertEquals("on", state.mods[0].visualState)
        assertTrue(state.mods[0].canToggle)
        assertEquals("Cheat 2", state.mods[1].name)
        assertTrue(state.gameRunning)
        assertEquals("MC4", state.cheatFormat)
        assertEquals("01.002.000", state.detectedVersion)
        assertEquals(1, state.summaryOn)
        assertEquals(2, state.summaryOff)
        assertEquals(3, state.summaryNotReady)
    }

    @Test
    fun parseStateRejectsFailedOrIncompletePayloads() {
        assertNull(CheatRunnerClient.parseState(Resp(500, "{}")))
        assertNull(CheatRunnerClient.parseState(Resp(200, """{"ok":false,"mods":[]}""")))
        assertNull(CheatRunnerClient.parseState(Resp(200, """{"ok":true}""")))
        assertNull(CheatRunnerClient.parseState(Resp(200, "not json")))
    }

    @Test
    fun parseGamesRejectsInvalidPayloads() {
        assertTrue(CheatRunnerClient.parseGames(Resp(200, "{}")).isEmpty())
        assertTrue(CheatRunnerClient.parseGames(Resp(200, """{"games":"bad"}""")).isEmpty())
        assertTrue(CheatRunnerClient.parseGames(Resp(0, "", "offline")).isEmpty())
    }
}
