package com.orbit.zero

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class TransferService : Service() {
    private val channelId = "orbit_transfer"
    private var worker: Thread? = null

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

        if (worker?.isAlive == true) return START_NOT_STICKY
        startForeground(77, notification(title, getString(R.string.notif_preparing), 0))
        TransferStore.reset(title, filename, size)

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
            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "ZeroVoxAndroid/1.0.1")
                .get()
                .build()

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
