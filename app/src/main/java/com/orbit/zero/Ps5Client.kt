package com.orbit.zero

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Resp(val code: Int, val body: String, val error: String? = null) {
    val ok: Boolean get() = code in 200..299
    fun json(): JSONObject? = try {
        if (body.isBlank()) null else JSONObject(body)
    } catch (_: Exception) {
        null
    }
    fun jsonArray(): JSONArray? = try {
        if (body.isBlank()) null else JSONArray(body)
    } catch (_: Exception) {
        null
    }
    fun message(): String {
        val j = json()
        return j?.optString("error").takeUnless { it.isNullOrBlank() }
            ?: error
            ?: body.take(300)
            ?: ("HTTP $code")
    }
}

class Ps5Client(private val prefs: Prefs) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val binType = "application/octet-stream".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun url(path: String) = prefs.baseUrl + path

    private fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        timeoutSeconds: Long = 60,
    ): Resp {
        return try {
            val builder = Request.Builder().url(url(path))
            if (auth && prefs.paired) builder.header("Authorization", "Bearer ${prefs.token}")
            when (method) {
                "GET" -> builder.get()
                else -> {
                    val payload = (body ?: JSONObject()).toString()
                    builder.post(payload.toRequestBody(jsonType))
                }
            }
            val client = if (timeoutSeconds > 60) {
                http.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
            } else http
            client.newCall(builder.build()).execute().use { r ->
                Resp(r.code, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Resp(0, "", e.message ?: "Network error")
        }
    }

    fun showPairingCode(): Resp = call("POST", "/api/v1/pair/show", JSONObject(), auth = false)

    fun pair(code: String): Resp =
        call("POST", "/api/v1/pair", JSONObject().put("code", code), auth = false)

    fun system(): Resp = call("GET", "/api/v1/system")

    fun storage(): Resp = call("GET", "/api/v1/storage")

    fun downloads(): Resp = call("GET", "/api/v1/downloads")

    fun installations(): Resp = call("GET", "/api/v1/installations")

    fun installPackage(downloadId: String, packageId: String): Resp =
        call(
            "POST", "/api/v1/installations",
            JSONObject()
                .put("action", "install")
                .put("downloadId", downloadId)
                .put("packageId", packageId)
        )

    fun startDownload(releaseId: String, storageId: String, delivery: String = "direct"): Resp =
        call(
            "POST", "/api/v1/downloads",
            JSONObject()
                .put("releaseId", releaseId)
                .put("storageId", storageId)
                .put("delivery", delivery)
        )

    fun jobAction(jobId: String, action: String, body: JSONObject = JSONObject()): Resp =
        call("POST", "/api/v1/downloads/$jobId/$action", body)

    fun receiveStatus(): Resp = call("GET", "/api/v1/receive")

    fun receiveBegin(body: JSONObject): Resp = call("POST", "/api/v1/receive", body)

    fun receiveCancel(id: String): Resp =
        call("POST", "/api/v1/receive/cancel", JSONObject().put("id", id))

    fun receiveFinish(id: String, sha256: String): Resp =
        call("POST", "/api/v1/receive/finish", JSONObject().put("id", id).put("sha256", sha256))

    fun receiveChunk(id: String, offset: Long, sha256: String, bytes: ByteArray): Resp {
        return try {
            val request = Request.Builder()
                .url(url("/api/v1/receive/chunk"))
                .header("Authorization", "Bearer ${prefs.token}")
                .header("X-Orbit-Transfer", id)
                .header("X-Orbit-Offset", offset.toString())
                .header("X-Orbit-SHA256", sha256)
                .post(bytes.toRequestBody(binType))
                .build()
            val client = http.newBuilder().writeTimeout(180, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { r -> Resp(r.code, r.body?.string() ?: "") }
        } catch (e: Exception) {
            Resp(0, "", e.message ?: "Network error")
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            return d.joinToString("") { "%02x".format(it) }
        }

        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun parseStorage(resp: Resp): List<Storage> {
            val a = resp.jsonArray() ?: return emptyList()
            val out = ArrayList<Storage>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(
                    Storage(
                        id = o.optString("id"),
                        label = o.optString("label", o.optString("id")),
                        path = o.optString("path"),
                        freeBytes = o.optLong("freeBytes"),
                        totalBytes = o.optLong("totalBytes"),
                        external = o.optBoolean("external"),
                    )
                )
            }
            return out
        }

        fun parsePackages(o: JSONObject?): List<PackageInfo> {
            val arr = o?.optJSONArray("packages") ?: return emptyList()
            val packages = ArrayList<PackageInfo>(arr.length())
            for (k in 0 until arr.length()) {
                val p = arr.optJSONObject(k) ?: continue
                packages.add(
                    PackageInfo(
                        id = p.optString("id"),
                        filename = p.optString("filename"),
                        state = p.optString("state"),
                        platform = p.optString("platform"),
                    )
                )
            }
            return packages
        }

        fun parseReceipts(resp: Resp): List<Receipt> {
            val root = resp.json() ?: return emptyList()
            val a = root.optJSONArray("downloads") ?: return emptyList()
            val out = ArrayList<Receipt>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(
                    Receipt(
                        id = o.optString("id"),
                        title = o.optString("title").ifBlank { o.optString("output") },
                        state = o.optString("state"),
                        message = o.optString("message"),
                        packages = parsePackages(o),
                    )
                )
            }
            return out
        }

        fun parseJobs(resp: Resp): List<Job> {
            val a = resp.jsonArray() ?: return emptyList()
            val out = ArrayList<Job>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val packages = parsePackages(o.optJSONObject("installation"))
                out.add(
                    Job(
                        id = o.optString("id"),
                        releaseId = o.optString("releaseId"),
                        title = o.optString("title", o.optString("filename")),
                        titleId = o.optString("titleId"),
                        format = o.optString("format"),
                        status = o.optString("status"),
                        error = o.optString("error"),
                        received = o.optLong("received"),
                        total = o.optLong("total"),
                        speed = o.optDouble("speed", 0.0),
                        filename = o.optString("filename"),
                        path = o.optString("path"),
                        packages = packages,
                    )
                )
            }
            return out
        }

        fun formatBytes(v: Long): String {
            if (v <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var n = v.toDouble()
            var i = 0
            while (n >= 1024 && i < units.size - 1) {
                n /= 1024.0
                i++
            }
            return if (n >= 100 || i == 0) "%.0f %s".format(n, units[i])
            else "%.1f %s".format(n, units[i])
        }

        fun packageStateLabel(context: Context, state: String): String = when (state) {
            "ready" -> context.getString(R.string.pkg_state_ready)
            "queued" -> context.getString(R.string.pkg_state_queued)
            "installing" -> context.getString(R.string.pkg_state_installing)
            "unconfirmed" -> context.getString(R.string.pkg_state_unconfirmed)
            "installed" -> context.getString(R.string.pkg_state_installed)
            "error" -> context.getString(R.string.pkg_state_error)
            "checking" -> context.getString(R.string.pkg_state_checking)
            else -> state
        }

        fun statusLabel(context: Context, status: String): String = when (status) {
            "queued" -> context.getString(R.string.job_status_queued)
            "downloading" -> context.getString(R.string.job_status_downloading)
            "retrying" -> context.getString(R.string.job_status_retrying)
            "verifying" -> context.getString(R.string.job_status_verifying)
            "waiting-extraction" -> context.getString(R.string.job_status_waiting_extraction)
            "inspecting" -> context.getString(R.string.job_status_inspecting)
            "extracting" -> context.getString(R.string.job_status_extracting)
            "paused" -> context.getString(R.string.job_status_paused)
            "error" -> context.getString(R.string.job_status_error)
            "complete" -> context.getString(R.string.job_status_complete)
            "cancelled" -> context.getString(R.string.job_status_cancelled)
            else -> status
        }
    }
}
