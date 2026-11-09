package com.orbit.zero

import org.json.JSONObject

data class Storage(
    val id: String,
    val label: String,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long,
    val external: Boolean,
)

data class GameOption(
    val id: String,
    val releaseId: String,
    val title: String,
    val titleId: String,
    val platform: String,
    val format: String,
    val source: String,
    val provider: String,
    val sizeBytes: Long,
    val filename: String,
    val url: String,
    val browserUrl: String,
    val sha256: String,
    val content: JSONObject,
) {
    val password: String = content.optString("archivePassword", "")
    val hasLink: Boolean get() = url.startsWith("http")

    val directFile: Boolean get() = hasLink &&
        (url.contains("archive.org", ignoreCase = true) ||
            (DIRECT_EXT.containsMatchIn(url) && BLOCKED_HOSTS.none { url.contains(it, ignoreCase = true) }))

    val phoneAvailable: Boolean get() = directFile

    private companion object {
        val DIRECT_EXT = Regex(
            "\\.(pkg|ffpfsc|ffp|exfat|zip|rar|7z|bin|iso|tar|gz|001|002|003)(\\?|$|#)",
            RegexOption.IGNORE_CASE
        )
        val BLOCKED_HOSTS = listOf(
            "akirabox.", "vikingfile.", "vik1ngfile.", "datanodes.", "filekeeper.",
            "datavaults.", "rootz.so", "1fichier.", "mediafire.", "buzzheavier.",
            "fileditch", "link-vault.", "gofile.", "pixeldrain.", "mega.nz",
        )
    }
}

data class Game(
    val key: String,
    val gameId: String,
    val title: String,
    val titleId: String,
    val platform: String,
    val genre: String,
    val region: String,
    val cover: String,
    val options: List<GameOption>,
) {
    val bestSize: Long get() = options.minOfOrNull { it.sizeBytes } ?: 0L
    val formats: String get() = options.map { it.format }.distinct().joinToString(" · ")
}

data class PackageInfo(
    val id: String,
    val filename: String,
    val state: String,
    val platform: String,
)

data class Receipt(
    val id: String,
    val title: String,
    val state: String,
    val message: String,
    val packages: List<PackageInfo>,
)

data class Job(
    val id: String,
    val releaseId: String,
    val title: String,
    val titleId: String,
    val format: String,
    val status: String,
    val error: String,
    val received: Long,
    val total: Long,
    val speed: Double,
    val filename: String,
    val path: String,
    val packages: List<PackageInfo>,
    val extractedPath: String = "",
) {
    val active: Boolean
        get() = status in setOf(
            "queued", "downloading", "retrying", "verifying",
            "waiting-extraction", "inspecting", "extracting"
        )
    val complete: Boolean get() = status == "complete"
    val percent: Int
        get() = if (total > 0) ((received * 100) / total).coerceIn(0, 100).toInt() else 0
    val installable: PackageInfo?
        get() = packages.firstOrNull { it.state == "ready" || it.state == "error" }
}
