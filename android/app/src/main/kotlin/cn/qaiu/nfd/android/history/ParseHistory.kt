package cn.qaiu.nfd.android.history

import cn.qaiu.nfd.android.daemon.Listing
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One parse attempt. Counts describe the root level only, and only the pages loaded so far. */
data class HistoryItem(
    val link: String,
    val password: String,
    val time: Long,
    val success: Boolean,
    val panName: String = "",
    val type: String = "",
    val title: String = "",
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val totalSize: Long = 0,
    val sizeComplete: Boolean = true,
    val hasMore: Boolean = false,
    val singleFile: Boolean = false,
    val error: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("link", link)
        .put("password", password)
        .put("time", time)
        .put("success", success)
        .put("panName", panName)
        .put("type", type)
        .put("title", title)
        .put("fileCount", fileCount)
        .put("folderCount", folderCount)
        .put("totalSize", totalSize)
        .put("sizeComplete", sizeComplete)
        .put("hasMore", hasMore)
        .put("singleFile", singleFile)
        .put("error", error)

    companion object {
        fun from(json: JSONObject) = HistoryItem(
            link = json.optString("link"),
            password = json.optString("password"),
            time = json.optLong("time"),
            success = json.optBoolean("success"),
            panName = json.optString("panName"),
            type = json.optString("type"),
            title = json.optString("title"),
            fileCount = json.optInt("fileCount"),
            folderCount = json.optInt("folderCount"),
            totalSize = json.optLong("totalSize"),
            sizeComplete = json.optBoolean("sizeComplete", true),
            hasMore = json.optBoolean("hasMore"),
            singleFile = json.optBoolean("singleFile"),
            error = json.optString("error"),
        )

        fun succeeded(link: String, password: String, listing: Listing, time: Long = System.currentTimeMillis()): HistoryItem {
            val names = listing.entries.map { it.fileName }.filter { it.isNotBlank() }
            val title = when {
                names.isEmpty() -> "空目录"
                names.size == 1 -> names[0]
                else -> "${names[0]} 等 ${listing.entries.size} 项"
            }
            return HistoryItem(
                link = link,
                password = password,
                time = time,
                success = true,
                panName = listing.panName,
                type = listing.type,
                title = title,
                fileCount = listing.files.size,
                folderCount = listing.folders,
                totalSize = listing.totalSize,
                sizeComplete = listing.sizeComplete,
                hasMore = listing.hasMore,
                singleFile = listing.singleFile,
            )
        }

        fun failed(link: String, password: String, error: String, time: Long = System.currentTimeMillis()) =
            HistoryItem(link = link, password = password, time = time, success = false, error = error)
    }
}

/**
 * Local parse history in the app's private files. A link appears once; parsing it again moves it
 * to the top with the latest result. Extraction codes are kept so an item can be re-parsed.
 */
class ParseHistory(private val file: File, private val limit: Int = 100) {

    private var cache: List<HistoryItem>? = null

    @Synchronized
    fun items(): List<HistoryItem> = cache ?: load().also { cache = it }

    @Synchronized
    fun record(item: HistoryItem): List<HistoryItem> {
        val key = normalize(item.link)
        val updated = (listOf(item) + items().filterNot { normalize(it.link) == key }).take(limit)
        save(updated)
        return updated
    }

    /** Refreshes counts after more pages of the same root listing were loaded. */
    @Synchronized
    fun update(link: String, password: String, listing: Listing): List<HistoryItem> {
        val key = normalize(link)
        val existing = items().firstOrNull { normalize(it.link) == key } ?: return items()
        val refreshed = HistoryItem.succeeded(link, password, listing, existing.time)
        val updated = items().map { if (normalize(it.link) == key) refreshed else it }
        save(updated)
        return updated
    }

    @Synchronized
    fun remove(item: HistoryItem): List<HistoryItem> {
        val updated = items().filterNot { it.link == item.link && it.time == item.time }
        save(updated)
        return updated
    }

    @Synchronized
    fun clear(): List<HistoryItem> {
        save(emptyList())
        return emptyList()
    }

    private fun load(): List<HistoryItem> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(HistoryItem::from) }
        } catch (ex: JSONException) {
            emptyList()
        } catch (ex: java.io.IOException) {
            emptyList()
        }
    }

    private fun save(items: List<HistoryItem>) {
        cache = items
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString())
            tmp.delete()
        }
    }

    private fun normalize(link: String): String = link.trim().trimEnd('/')
}
