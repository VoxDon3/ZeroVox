package com.orbit.zero

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class Ps5ClientTest {
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun messagePrefersApiErrorThenBodyThenHttpCode() {
        assertEquals("bad request", Resp(400, """{"error":"bad request"}""").message())
        assertEquals("gateway failed", Resp(502, "gateway failed").message())
        assertEquals("HTTP 500", Resp(500, "").message())
        assertEquals("socket closed", Resp(0, "", "socket closed").message())
    }

    @Test
    fun parseStorageMapsFieldsAndSkipsInvalidEntries() {
        val json = JSONArray()
            .put(
                JSONObject()
                    .put("id", "ssd")
                    .put("label", "Internal SSD")
                    .put("path", "/data")
                    .put("freeBytes", 1024L)
                    .put("totalBytes", 4096L)
                    .put("external", false)
            )
            .put(JSONObject().put("id", "ext").put("path", "/mnt/ext").put("external", true))
            .put("invalid")

        val storage = Ps5Client.parseStorage(Resp(200, json.toString()))

        assertEquals(2, storage.size)
        assertEquals("Internal SSD", storage[0].label)
        assertEquals(1024L, storage[0].freeBytes)
        assertFalse(storage[0].external)
        assertEquals("ext", storage[1].label)
        assertTrue(storage[1].external)
    }

    @Test
    fun parseJobsMapsDownloadsAndInstallablePackages() {
        val jobsJson = JSONArray().put(
            JSONObject()
                .put("id", "job-1")
                .put("releaseId", "release-1")
                .put("filename", "game.pkg")
                .put("status", "downloading")
                .put("received", 50L)
                .put("total", 200L)
                .put("speed", 12.5)
                .put(
                    "installation",
                    JSONObject().put(
                        "packages",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "pkg-1")
                                    .put("filename", "part1.pkg")
                                    .put("state", "ready")
                                    .put("platform", "PS5")
                            )
                            .put(
                                JSONObject()
                                    .put("id", "pkg-2")
                                    .put("state", "installed")
                            )
                    )
                )
        )

        val jobs = Ps5Client.parseJobs(Resp(200, jobsJson.toString()))

        assertEquals(1, jobs.size)
        val job = jobs[0]
        assertEquals("job-1", job.id)
        assertEquals("game.pkg", job.title)
        assertEquals(25, job.percent)
        assertTrue(job.active)
        assertFalse(job.complete)
        assertEquals("pkg-1", job.installable?.id)
        assertEquals(2, job.packages.size)
    }

    @Test
    fun parseReceiptsMapsDownloadsAndPackages() {
        val root = JSONObject().put(
            "downloads",
            JSONArray().put(
                JSONObject()
                    .put("id", "receipt-1")
                    .put("title", "Game")
                    .put("state", "complete")
                    .put("message", "done")
                    .put(
                        "packages",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "pkg-1")
                                .put("filename", "game.pkg")
                                .put("state", "installed")
                                .put("platform", "PS5")
                        )
                    )
            )
        )

        val receipts = Ps5Client.parseReceipts(Resp(200, root.toString()))

        assertEquals(1, receipts.size)
        assertEquals("Game", receipts[0].title)
        assertEquals("complete", receipts[0].state)
        assertEquals("pkg-1", receipts[0].packages.single().id)
    }

    @Test
    fun invalidPayloadsReturnEmptyCollectionsOrNull() {
        assertTrue(Ps5Client.parseStorage(Resp(200, "not json")).isEmpty())
        assertTrue(Ps5Client.parseJobs(Resp(200, "")).isEmpty())
        assertTrue(Ps5Client.parseReceipts(Resp(200, "{}")).isEmpty())
        assertNull(Resp(200, "not json").json())
    }

    @Test
    fun formatBytesUsesReadableUnits() {
        assertEquals("0 B", Ps5Client.formatBytes(0))
        assertEquals("1.0 KB", Ps5Client.formatBytes(1024))
        assertEquals("1.5 MB", Ps5Client.formatBytes(1536L * 1024L))
        assertEquals("100 MB", Ps5Client.formatBytes(100L * 1024L * 1024L))
    }
}
