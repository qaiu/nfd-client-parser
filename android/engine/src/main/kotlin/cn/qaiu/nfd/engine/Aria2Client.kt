package cn.qaiu.nfd.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * aria2 JSON-RPC client. The process itself is started by the caller
 * (embedded `libaria2c.so`, a desktop `aria2c`, or an external RPC such as Termux).
 */
class Aria2Client(
    private val endpoint: String,
    private val secret: String = ""
) : AutoCloseable {
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    fun version(): String {
        val result = rpc("aria2.getVersion", JSONArray()) as JSONObject
        return result.optString("version", "")
    }

    fun addUri(url: String, directory: String, fileName: String, headers: Map<String, String>): String {
        val options = JSONObject()
        options.put("dir", directory)
        options.put("out", fileName)
        options.put("continue", "true")
        if (headers.isNotEmpty()) {
            val headerList = JSONArray()
            headers.forEach { (name, value) -> headerList.put("$name: $value") }
            options.put("header", headerList)
        }
        val params = JSONArray()
        params.put(JSONArray().put(url))
        params.put(options)
        val result = rpc("aria2.addUri", params)
        return result.toString()
    }

    fun pause(gid: String) {
        rpc("aria2.pause", JSONArray().put(gid))
    }

    fun unpause(gid: String) {
        rpc("aria2.unpause", JSONArray().put(gid))
    }

    fun tellStatus(gid: String): AriaStatus {
        val keys = JSONArray()
            .put("gid")
            .put("status")
            .put("totalLength")
            .put("completedLength")
            .put("downloadSpeed")
            .put("errorMessage")
        val result = rpc("aria2.tellStatus", JSONArray().put(gid).put(keys)) as JSONObject
        return AriaStatus(
            gid = result.optString("gid", gid),
            status = result.optString("status", "error"),
            total = result.optString("totalLength", "0").toLongOrNull() ?: 0L,
            completed = result.optString("completedLength", "0").toLongOrNull() ?: 0L,
            speed = result.optString("downloadSpeed", "0").toLongOrNull() ?: 0L,
            error = result.optString("errorMessage").ifBlank { null }
        )
    }

    private fun rpc(method: String, params: JSONArray): Any {
        val withToken = JSONArray()
        if (secret.isNotEmpty()) {
            withToken.put("token:$secret")
        }
        for (index in 0 until params.length()) {
            withToken.put(params.get(index))
        }
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "nfd")
            .put("method", method)
            .put("params", withToken)
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("aria2 HTTP ${response.code}: $text")
            }
            val json = JSONObject(text)
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                throw IOException(error.optString("message", "aria2 error ${error.optInt("code")}"))
            }
            return json.get("result")
        }
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

data class AriaStatus(
    val gid: String,
    val status: String,
    val total: Long,
    val completed: Long,
    val speed: Long,
    val error: String?
)
