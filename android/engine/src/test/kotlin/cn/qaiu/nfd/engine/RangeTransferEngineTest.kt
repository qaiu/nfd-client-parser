package cn.qaiu.nfd.engine

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.fail

class RangeTransferEngineTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun pausesAndResumes() {
        val payload = ByteArray(384 * 1024)
        Random(7).nextBytes(payload)
        val headerSeen = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.bin") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("User-Agent") == "NFDTest/1") {
                    headerSeen.set(true)
                }
                val range = exchange.requestHeaders.getFirst("Range")
                val partial = range != null && range.startsWith("bytes=")
                val start = if (partial) range!!.substringAfter("bytes=").substringBefore("-").toInt() else 0
                if (start > payload.size) {
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                    return@createContext
                }
                val length = payload.size - start
                if (partial) {
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
                    while (offset < payload.size) {
                        val chunk = minOf(4096, payload.size - offset)
                        output.write(payload, offset, chunk)
                        output.flush()
                        offset += chunk
                        Thread.sleep(20)
                    }
                }
            } catch (_: Exception) {
                // The client closes the socket on pause.
            }
        }
        server.start()
        val engine = RangeTransferEngine(directory)
        try {
            val url = "http://127.0.0.1:${server.address.port}/file.bin"
            val id = engine.start(TransferRequest(url, "示例 文件.bin", directory, mapOf("User-Agent" to "NFDTest/1")))
            val started = waitUntil(engine, id, 10_000) { it.downloaded > 12_000 }
            assertTrue(started.downloaded > 0)
            engine.pause(id)
            val held = waitUntil(engine, id, 5_000) { it.status == TransferStatus.PAUSED }
            val pausedAt = held.downloaded
            assertTrue(pausedAt in 1 until payload.size.toLong()) { "paused at $pausedAt" }
            Thread.sleep(250)
            val still = engine.snapshots().first { it.id == id }
            assertEquals(TransferStatus.PAUSED, still.status)
            assertTrue(still.downloaded - pausedAt < 64 * 1024)
            engine.resume(id)
            val done = waitUntil(engine, id, 20_000) {
                it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED
            }
            assertEquals(TransferStatus.COMPLETED, done.status, done.message)
            val saved = File(directory, done.fileName)
            assertArrayEquals(payload, saved.readBytes())
            assertTrue(headerSeen.get())
        } finally {
            engine.shutdown()
            server.stop(0)
        }
    }
}

internal fun waitUntil(
    engine: TransferEngine,
    id: String,
    timeoutMillis: Long,
    predicate: (TransferSnapshot) -> Boolean
): TransferSnapshot {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    var latest: TransferSnapshot? = null
    while (System.nanoTime() < deadline) {
        latest = engine.snapshots().firstOrNull { it.id == id }
        if (latest != null && (predicate(latest) || latest.status == TransferStatus.FAILED)) {
            return latest
        }
        Thread.sleep(30)
    }
    fail("timed out, last=$latest")
}
