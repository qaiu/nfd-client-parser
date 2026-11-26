package cn.qaiu.nfd.android.download

import android.content.Context
import android.os.Environment
import cn.qaiu.nfd.engine.Aria2Client
import cn.qaiu.nfd.engine.Aria2TransferEngine
import cn.qaiu.nfd.engine.RangeTransferEngine
import cn.qaiu.nfd.engine.TransferEngine
import cn.qaiu.nfd.engine.TransferSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.ServerSocket
import java.util.UUID

/**
 * Picks an aria2 core when one is available.
 *
 * Termux is not required. A packaged `libaria2c.so` is executed from the
 * app's native library directory, which stays executable on current Android.
 * An external JSON-RPC endpoint (for example aria2 inside Termux) is optional.
 * Without either, transfers use the in-process range downloader so pause and
 * resume still work.
 */
class DownloadController(private val context: Context) {
    val directory: File = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "downloads")

    private val prefs = context.getSharedPreferences("nfd", Context.MODE_PRIVATE)
    private var process: Process? = null
    private var engine: TransferEngine = RangeTransferEngine(directory)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val _engineName = MutableStateFlow(engine.displayName)
    val engineName: StateFlow<String> = _engineName

    init {
        directory.mkdirs()
        if (prefs.getBoolean(KEY_EXTERNAL, false)) {
            applyExternal(
                prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1",
                prefs.getInt(KEY_PORT, 6800),
                prefs.getString(KEY_SECRET, "") ?: ""
            )
        } else {
            selectEmbeddedOrRange()
        }
    }

    fun snapshots(): List<TransferSnapshot> = engine.snapshots()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        engine.addListener(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
        engine.removeListener(listener)
    }

    fun start(fileName: String, url: String, headers: Map<String, String>): String {
        return engine.start(
            cn.qaiu.nfd.engine.TransferRequest(url, fileName, directory, headers)
        )
    }

    fun pause(id: String) = engine.pause(id)

    fun resume(id: String) = engine.resume(id)

    fun useEmbedded() {
        prefs.edit().putBoolean(KEY_EXTERNAL, false).apply()
        selectEmbeddedOrRange()
    }

    fun useExternal(host: String, port: Int, secret: String): String? {
        prefs.edit()
            .putBoolean(KEY_EXTERNAL, true)
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_SECRET, secret)
            .apply()
        return applyExternal(host, port, secret)
    }

    fun externalEnabled(): Boolean = prefs.getBoolean(KEY_EXTERNAL, false)

    fun externalHost(): String = prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1"

    fun externalPort(): Int = prefs.getInt(KEY_PORT, 6800)

    fun externalSecret(): String = prefs.getString(KEY_SECRET, "") ?: ""

    private fun selectEmbeddedOrRange() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
        if (binary.exists()) {
            startEmbedded(binary)
            return
        }
        replaceProcess(null)
        switchEngine(RangeTransferEngine(directory))
    }

    private fun startEmbedded(binary: File): String? {
        val port = freePort()
        val secret = UUID.randomUUID().toString()
        var started: Process? = null
        return try {
            started = ProcessBuilder(
                binary.absolutePath,
                "--enable-rpc=true",
                "--rpc-listen-all=false",
                "--rpc-listen-port=$port",
                "--rpc-secret=$secret",
                "--dir=${directory.absolutePath}",
                "--continue=true",
                "--max-connection-per-server=8",
                "--split=8",
                "--file-allocation=none",
                "--console-log-level=warn",
                "--summary-interval=0",
                "--disable-ipv6=true"
            ).redirectErrorStream(true).directory(directory).start()
            val client = Aria2Client("http://127.0.0.1:$port/jsonrpc", secret)
            awaitVersion(client)
            replaceProcess(started)
            started = null
            switchEngine(Aria2TransferEngine(client, "aria2"))
            null
        } catch (ex: Exception) {
            started?.destroy()
            replaceProcess(null)
            switchEngine(RangeTransferEngine(directory, "内置传输（aria2 启动失败）"))
            ex.message
        }
    }

    private fun applyExternal(host: String, port: Int, secret: String): String? {
        val client = Aria2Client("http://$host:$port/jsonrpc", secret)
        return try {
            awaitVersion(client)
            replaceProcess(null)
            switchEngine(Aria2TransferEngine(client, "外部 aria2"))
            null
        } catch (ex: Exception) {
            client.close()
            switchEngine(RangeTransferEngine(directory, "内置传输（外部 aria2 不可用）"))
            ex.message ?: "无法连接 aria2"
        }
    }

    private fun switchEngine(next: TransferEngine) {
        val previous = engine
        listeners.forEach { previous.removeListener(it) }
        engine = next
        listeners.forEach { next.addListener(it) }
        _engineName.value = next.displayName
        if (previous !== next) {
            previous.shutdown()
        }
        listeners.forEach { it.invoke() }
    }

    private fun replaceProcess(next: Process?) {
        process?.destroy()
        process = next
    }

    private fun awaitVersion(client: Aria2Client) {
        var last: Exception? = null
        repeat(20) {
            try {
                if (client.version().isNotBlank()) return
            } catch (ex: Exception) {
                last = ex
            }
            Thread.sleep(150)
        }
        throw last ?: IllegalStateException("aria2 没有响应")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val KEY_EXTERNAL = "external"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SECRET = "secret"
    }
}
