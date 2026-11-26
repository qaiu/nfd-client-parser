package cn.qaiu.nfd.android.daemon

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class DaemonException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { OFFLINE, UNAUTHORIZED, REJECTED }
}

data class Endpoint(val host: String, val port: Int, val token: String) {
    val baseUrl: String get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"
}

/** HTTP client for the Termux daemon API (see termux/src/main/java/.../DaemonServer.java). */
class DaemonClient(
    private val endpoint: () -> Endpoint,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        // Parsing a folder share can take a while on slow providers.
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
) {

    suspend fun health(): Health = Health.from(get("/api/health"))

    suspend fun list(link: String, password: String, folder: Entry?, page: Int = 1, cursor: String? = null): Listing =
        Listing.from(
            post(
                "/api/list",
                share(link, password)
                    .put("folder", folder?.toJson() ?: JSONObject.NULL)
                    .put("page", page)
                    .put("cursor", cursor ?: JSONObject.NULL),
            ),
        )

    suspend fun tasks(): List<Task> = Task.listFrom(get("/api/tasks"))

    suspend fun downloadEntries(link: String, password: String, entries: List<Entry>): List<Task> {
        val array = JSONArray()
        entries.filterNot { it.folder }.forEach { array.put(it.toJson()) }
        return Task.listFrom(post("/api/tasks", share(link, password).put("entries", array)))
    }

    /** Single-file shares: the daemon resolves immediately so errors surface right away. */
    suspend fun downloadDirect(link: String, password: String): List<Task> =
        Task.listFrom(post("/api/tasks", share(link, password)))

    suspend fun pause(id: String) = Task.listFrom(post("/api/tasks/$id/pause", JSONObject()))

    suspend fun resume(id: String) = Task.listFrom(post("/api/tasks/$id/resume", JSONObject()))

    suspend fun remove(id: String, deleteFile: Boolean) =
        Task.listFrom(post("/api/tasks/$id/remove", JSONObject().put("deleteFile", deleteFile)))

    suspend fun pauseAll() = Task.listFrom(post("/api/tasks/pause-all", JSONObject()))

    suspend fun resumeAll() = Task.listFrom(post("/api/tasks/resume-all", JSONObject()))

    suspend fun clearFinished() = Task.listFrom(post("/api/tasks/clear-finished", JSONObject()))

    suspend fun settings(): DaemonSettings = DaemonSettings.from(get("/api/settings"))

    suspend fun updateSettings(maxConcurrent: Int, connections: Int): DaemonSettings =
        DaemonSettings.from(post("/api/settings", JSONObject().put("maxConcurrent", maxConcurrent).put("connections", connections)))

    /** Blank restores the default folder; the daemon rejects folders Termux cannot write to. */
    suspend fun setDownloadDir(path: String): DaemonSettings =
        DaemonSettings.from(post("/api/settings", JSONObject().put("downloadDir", path)))

    suspend fun auths(): List<AuthProvider> = AuthProvider.listFrom(get("/api/auths"))

    suspend fun saveAuth(type: String, fields: Map<String, String>): List<AuthProvider> =
        AuthProvider.listFrom(post("/api/auths", JSONObject().put("type", type).put("fields", JSONObject(fields))))

    suspend fun clearAuth(type: String): List<AuthProvider> =
        AuthProvider.listFrom(post("/api/auths/clear", JSONObject().put("type", type)))

    suspend fun shutdown() {
        post("/api/shutdown", JSONObject())
    }

    private fun share(link: String, password: String) = JSONObject()
        .put("link", link)
        .put("password", password.trim().ifEmpty { JSONObject.NULL })

    private suspend fun get(path: String): JSONObject = execute(request(path).get().build())

    private suspend fun post(path: String, body: JSONObject): JSONObject =
        execute(request(path).post(body.toString().toRequestBody(JSON)).build())

    private fun request(path: String): Request.Builder {
        val target = endpoint()
        return Request.Builder()
            .url(target.baseUrl + path)
            .header("X-NFD-Token", target.token)
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (ex: IOException) {
            throw DaemonException(DaemonException.Kind.OFFLINE, "未连接到 Termux 守护进程")
        }
        response.use {
            val text = it.body.string()
            val json = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (ex: JSONException) {
                throw DaemonException(DaemonException.Kind.REJECTED, "守护进程返回了无法识别的数据 (HTTP ${it.code})")
            }
            when {
                it.code == 401 -> throw DaemonException(DaemonException.Kind.UNAUTHORIZED, json.optString("error", "令牌无效"))
                !it.isSuccessful -> throw DaemonException(DaemonException.Kind.REJECTED, json.optString("error", "HTTP ${it.code}"))
                else -> json
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
