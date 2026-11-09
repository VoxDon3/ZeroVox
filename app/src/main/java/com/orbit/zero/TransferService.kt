package com.orbit.zero

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class TransferService : Service() {
    private val channelId = "orbit_transfer"
    private var worker: Thread? = null

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra("title") ?: "Zero Vox"
        val url = intent.getStringExtra("url") ?: ""
        val filename = intent.getStringExtra("filename") ?: "game.bin"
        val size = intent.getLongExtra("size", 0L)
        val storageId = intent.getStringExtra("storageId") ?: ""
        val content = intent.getStringExtra("content") ?: "{}"
        val mode = intent.getStringExtra("mode") ?: "ps5"
        val sha256 = intent.getStringExtra("sha256") ?: ""
        val cookie = intent.getStringExtra("cookie") ?: ""
        val userAgent = intent.getStringExtra("userAgent") ?: ""

        if (worker?.isAlive == true) return START_NOT_STICKY
        startForeground(77, notification(title, getString(R.string.notif_preparing), 0))
        TransferStore.reset(title, filename, size)

        if (mode == "local") {
            if (url.isBlank()) {
                fail(getString(R.string.err_missing_url_storage))
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }
            worker = Thread {
                runLocalDownload(title, url, filename, size, sha256, cookie, userAgent)
            }.also { it.start() }
            return START_NOT_STICKY
        }

        if (mode == "send") {
            val uri = intent.getStringExtra("uri") ?: ""
            if (uri.isBlank() || storageId.isBlank()) {
                fail(getString(R.string.err_missing_url_storage))
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }
            worker = Thread {
                runSendLocal(title, uri, filename, size, storageId)
            }.also { it.start() }
            return START_NOT_STICKY
        }

        if (url.isBlank() || storageId.isBlank()) {
            fail(getString(R.string.err_missing_url_storage))
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }

        worker = Thread {
            runTransfer(title, url, filename, size, storageId, content)
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun runTransfer(
        title: String,
        url: String,
        filename: String,
        size: Long,
        storageId: String,
        contentJson: String,
    ) {
        val prefs = Prefs(this)
        val api = Ps5Client(prefs)
        var transferId = ""
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            val origin = runCatching {
                val u = java.net.URI(url)
                "${u.scheme}://${u.host}/"
            }.getOrNull()
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "*/*")
            if (!origin.isNullOrBlank()) requestBuilder.header("Referer", origin)
            val request = requestBuilder.get().build()

            val digest = MessageDigest.getInstance("SHA-256")
            val chunkSize = 8 * 1024 * 1024
            var offset = 0L
            var total = size

            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw RuntimeException(getString(R.string.err_source_http, response.code))
                }
                val type = response.header("Content-Type") ?: ""
                if (type.contains("text/html", true) || type.contains("application/json", true)) {
                    throw RuntimeException(getString(R.string.err_html))
                }
                val body = response.body ?: throw RuntimeException(getString(R.string.err_empty_body))
                total = body.contentLength().takeIf { it > 0 } ?: size
                if (total <= 0) throw RuntimeException(getString(R.string.err_unknown_size))

                transferId = Ps5Client.randomHex(16)
                val content = JSONObject(contentJson)
                val begin = JSONObject()
                    .put("id", transferId)
                    .put("filename", filename)
                    .put("storageId", storageId)
                    .put("size", total)
                    .put("content", content)
                val beginResp = api.receiveBegin(begin)
                if (!beginResp.ok) throw RuntimeException(beginResp.message())
                TransferStore.reset(title, filename, total)
                TransferStore.update(state = "downloading")
                notify(title, getString(R.string.notif_downloading), 0)

                val input = body.byteStream()
                val chunk = ByteArray(chunkSize)
                var lastReport = System.currentTimeMillis()
                var lastBytes = 0L

                while (true) {
                    if (TransferStore.cancelRequested) throw CancelledException()
                    var filled = 0
                    while (filled < chunkSize) {
                        val n = input.read(chunk, filled, chunkSize - filled)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(chunk, filled, n)
                        filled += n
                    }
                    if (filled == 0) break

                    val chunkHash = Ps5Client.sha256(chunk.copyOfRange(0, filled))
                    sendChunkWithRetry(api, transferId, offset, chunkHash, chunk.copyOfRange(0, filled))
                    offset += filled

                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 700) {
                        val dt = (now - lastReport).coerceAtLeast(1) / 1000.0
                        val speed = (offset - lastBytes) / dt
                        TransferStore.update(received = offset, speed = speed)
                        notify(
                            title,
                            "${Ps5Client.formatBytes(offset)} / ${Ps5Client.formatBytes(total)}",
                            progress(offset, total)
                        )
                        lastReport = now
                        lastBytes = offset
                    }
                    if (filled < chunkSize) break
                }
            }

            if (offset != total) {
                throw RuntimeException(
                    getString(
                        R.string.err_incomplete_source,
                        Ps5Client.formatBytes(offset),
                        Ps5Client.formatBytes(total)
                    )
                )
            }

            waitDurable(api, transferId, offset)
            TransferStore.update(state = "verifying", received = offset, speed = 0.0)
            notify(title, getString(R.string.notif_verifying), 100)
            val fullHash = digest.digest().joinToString("") { "%02x".format(it) }
            val finish = api.receiveFinish(transferId, fullHash)
            if (!finish.ok) throw RuntimeException(finish.message())

            var waited = 0
            while (waited < 3600) {
                if (TransferStore.cancelRequested) throw CancelledException()
                val status = api.receiveStatus()
                val obj = status.json()
                val state = obj?.optString("state") ?: ""
                if (state == "complete") {
                    TransferStore.update(state = "complete", received = offset, speed = 0.0, message = "")
                    notify(title, getString(R.string.notif_complete), 100)
                    return
                }
                if (state == "error" || state == "cancelled") {
                    throw RuntimeException(
                        obj?.optString("error")?.ifBlank { getString(R.string.err_verify_failed) }
                            ?: getString(R.string.err_verify_failed)
                    )
                }
                Thread.sleep(1000)
                waited++
            }
            TransferStore.update(state = "sent", received = offset, speed = 0.0, message = "")
            notify(title, getString(R.string.notif_sent_unverified), 100)
        } catch (_: CancelledException) {
            if (transferId.isNotBlank()) runCatching { api.receiveCancel(transferId) }
            TransferStore.update(state = "cancelled", speed = 0.0, message = "")
            notify(title, getString(R.string.notif_cancelled), 0)
        } catch (e: Exception) {
            if (transferId.isNotBlank()) runCatching { api.receiveCancel(transferId) }
            fail(e.message ?: getString(R.string.err_transfer_failed))
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun runSendLocal(
        title: String,
        uriString: String,
        filename: String,
        size: Long,
        storageId: String,
    ) {
        val prefs = Prefs(this)
        val api = Ps5Client(prefs)
        var transferId = ""
        try {
            val uri = Uri.parse(uriString)
            val input = contentResolver.openInputStream(uri)
                ?: throw RuntimeException(getString(R.string.err_open_file))
            val safeName = safeFilename(filename, title)
            input.use { stream ->
                var total = size
                if (total <= 0) {
                    total = runCatching {
                        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    }.getOrNull() ?: 0L
                }
                if (total <= 0) throw RuntimeException(getString(R.string.err_unknown_size))

                transferId = Ps5Client.randomHex(16)
                val content = JSONObject()
                    .put("title", title)
                    .put("filename", safeName)
                    .put("format", safeName.substringAfterLast('.', "").uppercase())
                Regex("(CUSA|PPSA)\\d{5}", RegexOption.IGNORE_CASE)
                    .find(safeName)?.value?.let { content.put("titleId", it.uppercase()) }
                val begin = JSONObject()
                    .put("id", transferId)
                    .put("filename", safeName)
                    .put("storageId", storageId)
                    .put("size", total)
                    .put("content", content)
                val beginResp = api.receiveBegin(begin)
                if (!beginResp.ok) throw RuntimeException(beginResp.message())
                TransferStore.reset(title, safeName, total)
                TransferStore.update(state = "downloading")
                notify(title, getString(R.string.notif_downloading), 0)

                val digest = MessageDigest.getInstance("SHA-256")
                val chunkSize = 8 * 1024 * 1024
                val chunk = ByteArray(chunkSize)
                var offset = 0L
                var lastReport = System.currentTimeMillis()
                var lastBytes = 0L

                while (true) {
                    if (TransferStore.cancelRequested) throw CancelledException()
                    var filled = 0
                    while (filled < chunkSize) {
                        val n = stream.read(chunk, filled, chunkSize - filled)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(chunk, filled, n)
                        filled += n
                    }
                    if (filled == 0) break

                    val chunkHash = Ps5Client.sha256(chunk.copyOfRange(0, filled))
                    sendChunkWithRetry(api, transferId, offset, chunkHash, chunk.copyOfRange(0, filled))
                    offset += filled

                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 700) {
                        val dt = (now - lastReport).coerceAtLeast(1) / 1000.0
                        val speed = (offset - lastBytes) / dt
                        TransferStore.update(received = offset, speed = speed)
                        notify(
                            title,
                            "${Ps5Client.formatBytes(offset)} / ${Ps5Client.formatBytes(total)}",
                            progress(offset, total)
                        )
                        lastReport = now
                        lastBytes = offset
                    }
                    if (filled < chunkSize) break
                }

                if (offset != total) {
                    throw RuntimeException(
                        getString(
                            R.string.err_incomplete_source,
                            Ps5Client.formatBytes(offset),
                            Ps5Client.formatBytes(total)
                        )
                    )
                }

                waitDurable(api, transferId, offset)
                TransferStore.update(state = "verifying", received = offset, speed = 0.0)
                notify(title, getString(R.string.notif_verifying), 100)
                val fullHash = digest.digest().joinToString("") { "%02x".format(it) }
                val finish = api.receiveFinish(transferId, fullHash)
                if (!finish.ok) throw RuntimeException(finish.message())

                var waited = 0
                while (waited < 3600) {
                    if (TransferStore.cancelRequested) throw CancelledException()
                    val status = api.receiveStatus()
                    val obj = status.json()
                    val state = obj?.optString("state") ?: ""
                    if (state == "complete") {
                        TransferStore.update(
                            state = "complete",
                            received = offset,
                            speed = 0.0,
                            message = getString(R.string.sent_to_ps5, safeName)
                        )
                        notify(title, getString(R.string.notif_complete), 100)
                        autoInstallWhenReady(api, title, safeName)
                        return
                    }
                    if (state == "error" || state == "cancelled") {
                        throw RuntimeException(
                            obj?.optString("error")?.ifBlank { getString(R.string.err_verify_failed) }
                                ?: getString(R.string.err_verify_failed)
                        )
                    }
                    Thread.sleep(1000)
                    waited++
                }
                TransferStore.update(state = "sent", received = offset, speed = 0.0, message = "")
                notify(title, getString(R.string.notif_sent_unverified), 100)
            }
        } catch (_: CancelledException) {
            if (transferId.isNotBlank()) runCatching { api.receiveCancel(transferId) }
            TransferStore.update(state = "cancelled", speed = 0.0, message = "")
            notify(title, getString(R.string.notif_cancelled), 0)
        } catch (e: Exception) {
            if (transferId.isNotBlank()) runCatching { api.receiveCancel(transferId) }
            fail(e.message ?: getString(R.string.err_transfer_failed))
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private data class InstallCandidate(
        val id: String,
        val pkg: PackageInfo,
        val fromJob: Boolean,
    )

    private fun autoInstallWhenReady(api: Ps5Client, title: String, filename: String) {
        val deadline = System.currentTimeMillis() + 120_000L
        while (System.currentTimeMillis() < deadline) {
            if (TransferStore.cancelRequested) return
            val candidate = findInstallCandidate(api, title, filename)
            if (candidate != null) {
                val pkg = candidate.pkg
                if (pkg.state == "installed") {
                    TransferStore.update(
                        message = getString(R.string.already_installed, pkg.filename.ifBlank { pkg.id })
                    )
                    return
                }
                if (pkg.state == "ready" || pkg.state == "error") {
                    val result = if (candidate.fromJob) {
                        api.jobAction(candidate.id, "install-${pkg.id}")
                    } else {
                        api.installPackage(candidate.id, pkg.id)
                    }
                    if (result.ok) {
                        TransferStore.update(
                            message = getString(R.string.auto_install_started, pkg.filename.ifBlank { pkg.id })
                        )
                        notify(title, getString(R.string.notif_installing), 100)
                        return
                    }
                }
            }
            Thread.sleep(3000)
        }
        TransferStore.update(message = getString(R.string.sent_install_manual))
    }

    private fun findInstallCandidate(
        api: Ps5Client,
        title: String,
        filename: String,
    ): InstallCandidate? {
        val base = filename.substringBeforeLast('.').lowercase()
        val wantedTitle = title.lowercase()
        for (receipt in Ps5Client.parseReceipts(api.installations())) {
            val receiptTitle = receipt.title.lowercase()
            val receiptMatch = receiptTitle.isNotBlank() &&
                (receiptTitle == wantedTitle || receiptTitle.contains(base) || base.contains(receiptTitle))
            for (pkg in receipt.packages) {
                if (pkg.state !in setOf("ready", "error", "installed")) continue
                val pkgBase = pkg.filename.substringBeforeLast('.').lowercase()
                val nameMatch = pkgBase.isNotBlank() &&
                    (pkgBase == base || pkgBase.contains(base) || base.contains(pkgBase))
                if (receiptMatch || nameMatch) {
                    return InstallCandidate(receipt.id, pkg, fromJob = false)
                }
            }
        }
        for (job in Ps5Client.parseJobs(api.downloads())) {
            val jobTitle = job.title.lowercase()
            val titleMatch = jobTitle.isNotBlank() &&
                (jobTitle == wantedTitle || jobTitle.contains(base) || base.contains(jobTitle))
            val nameMatch = job.filename.equals(filename, ignoreCase = true)
            if (!titleMatch && !nameMatch) continue
            val pkg = job.installable ?: job.packages.firstOrNull { it.state == "installed" }
            if (pkg != null) return InstallCandidate(job.id, pkg, fromJob = true)
        }
        return null
    }

    private fun runLocalDownload(
        title: String,
        url: String,
        filename: String,
        size: Long,
        sha256: String,
        cookie: String,
        userAgent: String,
    ) {
        val safeName = safeFilename(filename, title)
        var dest: Destination? = null
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            val origin = runCatching {
                val u = java.net.URI(url)
                "${u.scheme}://${u.host}/"
            }.getOrNull()
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", userAgent.ifBlank { BROWSER_UA })
                .header("Accept", "*/*")
            if (!origin.isNullOrBlank()) requestBuilder.header("Referer", origin)
            if (cookie.isNotBlank()) requestBuilder.header("Cookie", cookie)
            val request = requestBuilder.get().build()

            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw RuntimeException(getString(R.string.err_source_http, response.code))
                }
                val type = response.header("Content-Type") ?: ""
                if (type.contains("text/html", true) || type.contains("application/json", true)) {
                    throw RuntimeException(getString(R.string.err_html))
                }
                val body = response.body ?: throw RuntimeException(getString(R.string.err_empty_body))
                var total = body.contentLength().takeIf { it > 0 } ?: size
                if (total < 0) total = 0L

                val created = openDestination(safeName)
                dest = created
                TransferStore.reset(title, safeName, total)
                TransferStore.update(state = "downloading")
                notify(title, getString(R.string.notif_downloading), 0)

                val digest = MessageDigest.getInstance("SHA-256")
                val input = body.byteStream()
                var offset = 0L
                val buffer = ByteArray(256 * 1024)
                var lastReport = System.currentTimeMillis()
                var lastBytes = 0L
                while (true) {
                    if (TransferStore.cancelRequested) throw CancelledException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    created.stream.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    offset += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 700) {
                        val dt = (now - lastReport).coerceAtLeast(1) / 1000.0
                        val speed = (offset - lastBytes) / dt
                        TransferStore.update(received = offset, speed = speed)
                        notify(
                            title,
                            "${Ps5Client.formatBytes(offset)} / " +
                                (if (total > 0) Ps5Client.formatBytes(total) else "?"),
                            if (total > 0) progress(offset, total) else 0
                        )
                        lastReport = now
                        lastBytes = offset
                    }
                }
                created.stream.flush()
                created.stream.close()

                if (total > 0 && offset != total) {
                    throw RuntimeException(
                        getString(
                            R.string.err_incomplete_source,
                            Ps5Client.formatBytes(offset),
                            Ps5Client.formatBytes(total)
                        )
                    )
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha256.isNotBlank() && !hash.equals(sha256, ignoreCase = true)) {
                    throw RuntimeException(getString(R.string.err_verify_failed))
                }
                finalizeDestination(created)
                dest = null
                val savedUri = created.uri?.toString()
                    ?: created.file?.let { Uri.fromFile(it).toString() }.orEmpty()
                TransferStore.update(
                    state = "complete",
                    received = offset,
                    speed = 0.0,
                    message = getString(R.string.saved_to, created.displayPath),
                    uri = savedUri,
                    path = created.displayPath,
                )
                notify(title, getString(R.string.notif_complete), 100)
            }
        } catch (_: CancelledException) {
            runCatching { dest?.stream?.close() }
            dest?.let { deleteDestination(it) }
            TransferStore.update(state = "cancelled", speed = 0.0, message = "")
            notify(title, getString(R.string.notif_cancelled), 0)
        } catch (e: Exception) {
            runCatching { dest?.stream?.close() }
            dest?.let { deleteDestination(it) }
            fail(e.message ?: getString(R.string.err_transfer_failed))
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private class Destination(
        val stream: OutputStream,
        val uri: Uri?,
        val file: File?,
        val displayPath: String,
    )

    private fun openDestination(name: String): Destination {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ZeroVox")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException(getString(R.string.err_transfer_failed))
            val stream = contentResolver.openOutputStream(uri)
                ?: throw RuntimeException(getString(R.string.err_transfer_failed))
            Destination(stream, uri, null, "Download/ZeroVox/$name")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ZeroVox"
            )
            if (!dir.exists() && !dir.mkdirs()) throw RuntimeException(getString(R.string.storage_error))
            val file = File(dir, name)
            Destination(FileOutputStream(file), null, file, file.absolutePath)
        }
    }

    private fun finalizeDestination(dest: Destination) {
        if (dest.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            runCatching { contentResolver.update(dest.uri, values, null, null) }
        }
    }

    private fun deleteDestination(dest: Destination) {
        if (dest.uri != null) runCatching { contentResolver.delete(dest.uri, null, null) }
        if (dest.file != null) runCatching { dest.file.delete() }
    }

    private fun safeFilename(name: String, title: String): String {
        val base = name.ifBlank { title }
        val cleaned = base
            .filter { it.code >= 32 }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .trim('.')
        return cleaned.ifBlank { "download.bin" }.take(180)
    }

    private fun sendChunkWithRetry(
        api: Ps5Client,
        id: String,
        offset: Long,
        hash: String,
        bytes: ByteArray,
    ) {
        var attempts = 0
        while (true) {
            if (TransferStore.cancelRequested) throw CancelledException()
            val r = api.receiveChunk(id, offset, hash, bytes)
            if (r.ok) return
            if (r.code == 429) {
                attempts++
                if (attempts > 2400) throw RuntimeException(r.message())
                Thread.sleep(250)
                continue
            }
            if (r.code == 409 || r.code == 0) {
                val status = api.receiveStatus().json()
                val accepted = status?.optLong("acceptedOffset") ?: 0L
                val durable = status?.optLong("offset") ?: 0L
                if (accepted >= offset + bytes.size || durable >= offset + bytes.size) return
                val state = status?.optString("state") ?: ""
                if (state == "error" || state == "cancelled") {
                    throw RuntimeException(
                        status?.optString("error")?.ifBlank { getString(R.string.err_receiver_stopped) }
                            ?: getString(R.string.err_receiver_stopped)
                    )
                }
                if (r.code == 409) throw RuntimeException(r.message())
                attempts++
                if (attempts > 120) throw RuntimeException(r.message())
                Thread.sleep(500)
                continue
            }
            throw RuntimeException(r.message())
        }
    }

    private fun waitDurable(api: Ps5Client, id: String, target: Long) {
        var attempts = 0
        while (attempts < 2400) {
            if (TransferStore.cancelRequested) throw CancelledException()
            val obj = api.receiveStatus().json()
            if (obj == null) {
                attempts++
                Thread.sleep(250)
                continue
            }
            val state = obj.optString("state")
            if (state == "error" || state == "cancelled") {
                throw RuntimeException(
                    obj.optString("error").ifBlank { getString(R.string.err_receiver_stopped) }
                )
            }
            if (obj.optLong("offset") >= target) return
            Thread.sleep(250)
            attempts++
        }
        throw RuntimeException(getString(R.string.err_durable_timeout))
    }

    private fun fail(message: String) {
        TransferStore.update(state = "error", speed = 0.0, message = message)
        notify(TransferStore.current.title, message, 0)
    }

    private fun progress(done: Long, total: Long): Int =
        if (total > 0) ((done * 100) / total).coerceIn(0, 100).toInt() else 0

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun notification(title: String, text: String, percent: Int): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(percent in 1..99)
            .setProgress(100, percent, percent <= 0)
            .build()
    }

    private fun notify(title: String, text: String, percent: Int) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(77, notification(title, text, percent))
    }

    override fun onDestroy() {
        TransferStore.cancelRequested = true
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class CancelledException : Exception()
}
