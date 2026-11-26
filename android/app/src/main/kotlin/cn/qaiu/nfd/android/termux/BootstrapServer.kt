package cn.qaiu.nfd.android.termux

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Minimal loopback HTTP server that hands the daemon bundle to Termux, which can reach the
 * app's 127.0.0.1 listener but not its assets. Paths carry a random key so other apps on the
 * device cannot guess them.
 */
class BootstrapServer(
    private val open: (String) -> InputStream?,
    private val onServed: (String) -> Unit = {},
) : AutoCloseable {

    val key: String = randomKey()
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port/$key"

    @Volatile
    private var closed = false

    fun start(): BootstrapServer {
        thread(name = "nfd-bootstrap", isDaemon = true) {
            while (!closed) {
                val client = try {
                    socket.accept()
                } catch (ex: SocketException) {
                    break
                }
                thread(name = "nfd-bootstrap-client", isDaemon = true) { serve(client) }
            }
        }
        return this
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            val out = connection.getOutputStream()
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
                respond(out, 405, "Method Not Allowed", null)
                return
            }
            val path = parts[1].substringBefore('?')
            val prefix = "/$key/"
            val name = if (path.startsWith(prefix)) path.removePrefix(prefix) else ""
            val asset = if (name in FILES) open(name) else null
            if (asset == null) {
                respond(out, 404, "Not Found", null)
                return
            }
            asset.use { respond(out, 200, "OK", if (parts[0] == "HEAD") null else it, contentType(name)) }
            onServed(name)
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, body: InputStream?, type: String = "text/plain") {
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        body?.copyTo(out, 64 * 1024)
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        const val INSTALLER = "install.sh"
        const val BUNDLE = "nfd-termux-daemon.tar.gz"
        private val FILES = setOf(INSTALLER, BUNDLE)

        private fun contentType(name: String) =
            if (name.endsWith(".sh")) "text/x-shellscript; charset=utf-8" else "application/gzip"

        private fun randomKey(): String {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
