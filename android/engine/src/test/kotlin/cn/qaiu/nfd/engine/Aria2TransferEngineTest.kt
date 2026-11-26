package cn.qaiu.nfd.engine

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.fail

class Aria2TransferEngineTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun aria2PausesAndResumes() {
        val aria2 = locateAria2()
        assumeTrue(aria2 != null) { "aria2c is not on PATH" }
        val payload = ByteArray(256 * 1024)
        Random(3).nextBytes(payload)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.bin") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                val start = if (range != null && range.startsWith("bytes=")) {
                    range.substringAfter("bytes=").substringBefore("-").toInt()
                } else {
                    0
                }
                if (start >= payload.size) {
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                    return@createContext
                }
                val length = payload.size - start
                if (start > 0) {
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes $start-${payload.size - 1}/${payload.size}"
                    )
                    exchange.sendResponseHeaders(206, length.toLong())
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                }
                exchange.responseBody.use { output ->
                    var offset = start
                    if (offset == 0) {
                        val first = minOf(16 * 1024, payload.size)
                        output.write(payload, 0, first)
                        output.flush()
                        offset = first
                        release.await(20, TimeUnit.SECONDS)
                    }
                    while (offset < payload.size) {
                        val chunk = minOf(16 * 1024, payload.size - offset)
                        output.write(payload, offset, chunk)
                        output.flush()
                        offset += chunk
                    }
                }
            } catch (_: Exception) {
                // aria2 closes the socket when the transfer is paused.
            }
        }
        server.start()
        val port = freePort()
        val secret = "nfd-test"
        val process = ProcessBuilder(
            aria2!!,
            "--enable-rpc=true",
            "--rpc-listen-all=false",
            "--rpc-listen-port=$port",
            "--rpc-secret=$secret",
            "--dir=${directory.absolutePath}",
            "--max-connection-per-server=1",
            "--split=1",
            "--min-split-size=1M",
            "--file-allocation=none",
            "--console-log-level=warn",
            "--summary-interval=0",
            "--disable-ipv6=true"
        ).redirectErrorStream(true).start()
        val client = Aria2Client("http://127.0.0.1:$port/jsonrpc", secret)
        val engine = Aria2TransferEngine(client, "aria2")
        try {
            waitUntilRpc(client)
            val url = "http://127.0.0.1:${server.address.port}/file.bin"
            val id = engine.start(TransferRequest(url, "aria.bin", directory))
            val started = waitUntil(engine, id, 15_000) { it.downloaded > 0 && it.status != TransferStatus.FAILED }
            assertTrue(started.downloaded in 1 until payload.size.toLong()) { "downloaded ${started.downloaded}" }
            engine.pause(id)
            val held = waitUntil(engine, id, 8_000) { it.status == TransferStatus.PAUSED }
            assertEquals(TransferStatus.PAUSED, held.status, held.message)
            val pausedAt = held.downloaded
            Thread.sleep(500)
            val still = engine.snapshots().first { it.id == id }
            assertEquals(TransferStatus.PAUSED, still.status, still.message)
            assertTrue(still.downloaded <= pausedAt + 64 * 1024)
            engine.resume(id)
            release.countDown()
            val done = waitUntil(engine, id, 30_000) {
                it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED
            }
            assertEquals(TransferStatus.COMPLETED, done.status, done.message)
            assertArrayEquals(payload, File(directory, "aria.bin").readBytes())
        } finally {
            engine.shutdown()
            process.destroy()
            process.waitFor()
            server.stop(0)
        }
    }

    private fun waitUntilRpc(client: Aria2Client) {
        val deadline = System.nanoTime() + 8_000_000_000L
        var last = "not started"
        while (System.nanoTime() < deadline) {
            try {
                val version = client.version()
                if (version.isNotBlank()) return
            } catch (ex: Exception) {
                last = ex.message ?: ex.javaClass.simpleName
            }
            Thread.sleep(100)
        }
        fail("aria2 RPC did not come up: $last")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun locateAria2(): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .map { File(it, "aria2c") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }
}
