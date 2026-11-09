package com.orbit.zero

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.URLDecoder

data class PhoneDownload(
    val filename: String,
    val path: String,
    val size: Long,
    val received: Long,
    val state: String,
    val message: String = "",
    val uri: String = "",
    val downloadId: Long = -1,
) {
    val percent: Int
        get() = if (size > 0) ((received * 100) / size).coerceIn(0, 100).toInt() else 0
    val running: Boolean
        get() = state == "running"
}

object PhoneDownloads {
    private const val FOLDER = "ZeroVox"

    fun query(context: Context): List<PhoneDownload> {
        val byName = LinkedHashMap<String, PhoneDownload>()

        // Authoritative: the files actually present in Download/ZeroVox.
        for (d in queryDirectFolder(context)) byName[d.filename] = d

        // MediaStore rows only while the underlying file still exists.
        for (d in queryMediaStore(context)) {
            if (byName.containsKey(d.filename)) continue
            if (exists(context, d.uri, d.filename)) byName[d.filename] = d
        }

        // DownloadManager: keep live/failed rows; keep completed rows only when
        // the file still exists (otherwise a stale card survives after the user
        // deletes the file from the phone's storage manager).
        for (d in queryDownloadManager(context)) {
            when (d.state) {
                "running" -> byName[d.filename] = d
                "failed" -> byName.putIfAbsent(d.filename, d)
                "complete" -> {
                    if (byName.containsKey(d.filename)) continue
                    if (exists(context, d.uri, d.filename)) byName[d.filename] = d
                }
            }
        }

        return byName.values.sortedWith(
            compareByDescending<PhoneDownload> { it.running }.thenBy { it.filename.lowercase() }
        )
    }

    /** True when the downloaded file is still present in Download/ZeroVox. */
    fun exists(context: Context, uri: String, filename: String): Boolean {
        if (filename.isNotBlank()) {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$FOLDER/$filename"
            )
            if (file.exists()) return true
        }
        if (uri.startsWith("content://")) {
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
            }.getOrDefault(false)
        }
        if (uri.startsWith("file://")) {
            val path = Uri.parse(uri).path
            return !path.isNullOrBlank() && File(path).exists()
        }
        return false
    }

    private fun queryDirectFolder(context: Context): List<PhoneDownload> {
        val result = ArrayList<PhoneDownload>()
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!folder.exists()) return result
        folder.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.startsWith(".")) return@forEach
            val size = file.length()
            result.add(
                PhoneDownload(
                    filename = name,
                    path = "Download/$FOLDER/$name",
                    size = size,
                    received = size,
                    state = "complete",
                    uri = Uri.fromFile(file).toString(),
                )
            )
        }
        return result
    }

    private fun queryDownloadManager(context: Context): List<PhoneDownload> {
        val result = ArrayList<PhoneDownload>()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return result
        val tracked = Prefs(context).webDownloadIds
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                if (c == null) return@use
                val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleIdx = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
                    val localUri = c.getString(uriIdx) ?: ""
                    val isTracked = id >= 0 && tracked.contains(id.toString())
                    if (!isTracked && !localUri.contains("/$FOLDER/")) continue
                    val status = if (statusIdx >= 0) c.getInt(statusIdx) else -1
                    val state = when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> "complete"
                        DownloadManager.STATUS_FAILED -> "failed"
                        else -> "running"
                    }
                    val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
                    val name = when {
                        localUri.contains("/$FOLDER/") -> decode(localUri.substringAfterLast('/'))
                        title.isNotBlank() -> decode(title)
                        else -> "download"
                    }.ifBlank { "download" }
                    val readableUri = if (id >= 0) {
                        runCatching { dm.getUriForDownloadedFile(id)?.toString() }.getOrNull()
                    } else null
                    val uri = readableUri ?: localUri
                    val total = if (totalIdx >= 0) c.getLong(totalIdx) else 0L
                    val soFar = if (soFarIdx >= 0) c.getLong(soFarIdx) else 0L
                    val message = if (state == "failed" && reasonIdx >= 0) {
                        "reason ${c.getInt(reasonIdx)}"
                    } else ""
                    result.add(
                        PhoneDownload(
                            filename = name,
                            path = "Download/$FOLDER/$name",
                            size = total,
                            received = soFar,
                            state = state,
                            message = message,
                            uri = uri,
                            downloadId = id,
                        )
                    )
                }
            }
        }
        return result
    }

    private fun queryMediaStore(context: Context): List<PhoneDownload> {
        val result = ArrayList<PhoneDownload>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return result
        runCatching {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.RELATIVE_PATH,
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("Download/$FOLDER/%")
            context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Downloads._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
                    val contentUri = if (id >= 0) {
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        ).toString()
                    } else ""
                    result.add(
                        PhoneDownload(
                            filename = name,
                            path = "Download/$FOLDER/$name",
                            size = size,
                            received = size,
                            state = "complete",
                            uri = contentUri,
                        )
                    )
                }
            }
        }
        return result
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
