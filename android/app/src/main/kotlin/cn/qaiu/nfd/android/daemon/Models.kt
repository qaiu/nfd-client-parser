package cn.qaiu.nfd.android.daemon

import org.json.JSONArray
import org.json.JSONObject

data class Health(
    val version: String,
    val authorized: Boolean,
    val parserVersion: String,
    val java: String,
    val termux: Boolean,
    val downloadDir: String,
    val downloadDirShared: Boolean,
) {
    companion object {
        fun from(json: JSONObject) = Health(
            version = json.optString("version"),
            authorized = json.optBoolean("authorized"),
            parserVersion = json.optString("parserVersion"),
            java = json.optString("java"),
            termux = json.optBoolean("termux"),
            downloadDir = json.optString("downloadDir"),
            downloadDirShared = json.optBoolean("downloadDirShared"),
        )
    }
}

/** A row of a share listing; serialised back unchanged when listing a folder or downloading. */
data class Entry(
    val fileName: String,
    val fileId: String,
    val fileType: String,
    val size: Long,
    val sizeText: String,
    val parserUrl: String?,
    val folder: Boolean,
) {
    val key: String get() = parserUrl?.takeIf { it.isNotBlank() } ?: "$fileId/$fileName"

    fun toJson(): JSONObject = JSONObject()
        .put("fileName", fileName)
        .put("fileId", fileId)
        .put("fileType", fileType)
        .put("size", size)
        .put("sizeText", sizeText)
        .put("parserUrl", parserUrl ?: JSONObject.NULL)
        .put("folder", folder)

    companion object {
        fun from(json: JSONObject) = Entry(
            fileName = json.optString("fileName"),
            fileId = json.optString("fileId"),
            fileType = json.optString("fileType"),
            size = json.optLong("size", -1),
            sizeText = json.optString("sizeText"),
            parserUrl = if (json.isNull("parserUrl")) null else json.optString("parserUrl"),
            folder = json.optBoolean("folder"),
        )
    }
}

data class Listing(val panName: String, val type: String, val entries: List<Entry>) {
    companion object {
        fun from(json: JSONObject) = Listing(
            panName = json.optString("panName"),
            type = json.optString("type"),
            entries = json.optJSONArray("entries").objects().map(Entry::from),
        )
    }
}

enum class TaskStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED;

    val active get() = this == QUEUED || this == RUNNING
    val canResume get() = this == PAUSED || this == FAILED
}

data class Task(
    val id: String,
    val fileName: String,
    val status: TaskStatus,
    val statusLabel: String,
    val downloaded: Long,
    val total: Long,
    val progress: Double,
    val progressText: String,
    val message: String,
    val destination: String,
    val resumable: Boolean,
    val link: String,
) {
    companion object {
        fun from(json: JSONObject) = Task(
            id = json.optString("id"),
            fileName = json.optString("fileName"),
            status = runCatching { TaskStatus.valueOf(json.optString("status")) }.getOrDefault(TaskStatus.FAILED),
            statusLabel = json.optString("statusLabel"),
            downloaded = json.optLong("downloaded"),
            total = json.optLong("total", -1),
            progress = json.optDouble("progress", -1.0),
            progressText = json.optString("progressText"),
            message = json.optString("message"),
            destination = json.optString("destination"),
            resumable = json.optBoolean("resumable"),
            link = json.optString("link"),
        )

        fun listFrom(json: JSONObject): List<Task> = json.optJSONArray("tasks").objects().map(::from)
    }
}

data class DaemonSettings(val maxConcurrent: Int, val connections: Int, val downloadDir: String, val downloadDirShared: Boolean) {
    companion object {
        fun from(json: JSONObject) = DaemonSettings(
            maxConcurrent = json.optInt("maxConcurrent", 3),
            connections = json.optInt("connections", 4),
            downloadDir = json.optString("downloadDir"),
            downloadDirShared = json.optBoolean("downloadDirShared"),
        )
    }
}

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
