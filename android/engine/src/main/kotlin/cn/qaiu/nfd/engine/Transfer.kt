package cn.qaiu.nfd.engine

import java.io.File

enum class TransferStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class TransferSnapshot(
    val id: String,
    val fileName: String,
    val status: TransferStatus,
    val downloaded: Long,
    val total: Long,
    val speedBytesPerSec: Long,
    val message: String
) {
    fun fraction(): Double {
        if (status == TransferStatus.COMPLETED) return 1.0
        if (total <= 0) return -1.0
        return (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }
}

data class TransferRequest(
    val url: String,
    val fileName: String,
    val directory: File,
    val headers: Map<String, String> = emptyMap()
)

interface TransferEngine {
    val displayName: String

    fun start(request: TransferRequest): String

    fun pause(id: String)

    fun resume(id: String)

    fun snapshots(): List<TransferSnapshot>

    fun addListener(listener: () -> Unit)

    fun removeListener(listener: () -> Unit)

    fun shutdown()
}

fun safeFileName(fileName: String?): String {
    val raw = fileName?.trim().orEmpty().ifBlank { "download" }
    val cleaned = raw.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .ifBlank { "download" }
    return if (cleaned.length > 180) cleaned.substring(0, 180) else cleaned
}

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "未知"
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    val units = arrayOf("KB", "MB", "GB", "TB")
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024 && unit < units.lastIndex)
    return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}
