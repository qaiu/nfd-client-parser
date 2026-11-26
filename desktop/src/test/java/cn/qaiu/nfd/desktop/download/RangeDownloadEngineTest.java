package cn.qaiu.nfd.desktop.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeDownloadEngineTest {

    @TempDir
    Path directory;

    @Test
    void pausesAndResumesARangeDownload() throws Exception {
        byte[] payload = new byte[384 * 1024];
        new Random(7).nextBytes(payload);
        AtomicBoolean headerSeen = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file.bin", exchange -> {
            try {
                String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
                if ("NFDTest/1".equals(userAgent)) {
                    headerSeen.set(true);
                }
                int start = 0;
                String range = exchange.getRequestHeaders().getFirst("Range");
                boolean partial = range != null && range.startsWith("bytes=");
                if (partial) {
                    start = Integer.parseInt(range.substring("bytes=".length()).split("-")[0]);
                }
                if (start > payload.length) {
                    exchange.sendResponseHeaders(416, -1);
                    exchange.close();
                    return;
                }
                int length = payload.length - start;
                if (partial) {
                    exchange.getResponseHeaders().add("Content-Range",
                            "bytes " + start + "-" + (payload.length - 1) + "/" + payload.length);
                    exchange.sendResponseHeaders(206, length);
                } else {
                    exchange.sendResponseHeaders(200, payload.length);
                }
                try (OutputStream output = exchange.getResponseBody()) {
                    int offset = start;
                    while (offset < payload.length) {
                        int chunk = Math.min(4096, payload.length - offset);
                        output.write(payload, offset, chunk);
                        output.flush();
                        offset += chunk;
                        Thread.sleep(20);
                    }
                }
            } catch (Exception ignored) {
                // The client closes the socket when the user pauses.
            }
        });
        server.start();
        RangeDownloadEngine engine = new RangeDownloadEngine(directory);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file.bin";
            DownloadTask created = engine.enqueue("示例 文件.bin", url, Map.of("User-Agent", "NFDTest/1"));
            DownloadTask paused = waitUntil(engine, created.id(), task -> task.downloaded() > 12_000, 10_000);
            engine.pause(created.id());
            DownloadTask held = waitUntil(engine, created.id(),
                    task -> task.status() == DownloadTask.Status.PAUSED, 5_000);
            long pausedAt = held.downloaded();
            assertTrue(pausedAt > 0 && pausedAt < payload.length, "paused at " + pausedAt);
            Thread.sleep(250);
            DownloadTask still = engine.tasks().get(0);
            assertEquals(DownloadTask.Status.PAUSED, still.status());
            assertTrue(still.downloaded() - pausedAt < 64 * 1024, "bytes kept arriving after pause");

            engine.resume(created.id());
            DownloadTask done = waitUntil(engine, created.id(),
                    task -> task.status() == DownloadTask.Status.COMPLETED
                            || task.status() == DownloadTask.Status.FAILED, 20_000);
            assertEquals(DownloadTask.Status.COMPLETED, done.status(), done.message());
            assertArrayEquals(payload, Files.readAllBytes(done.destination()));
            assertTrue(headerSeen.get());
            assertTrue(done.fileName().startsWith("示例"));
            assertTrue(paused.downloaded() >= 0);
        } finally {
            engine.close();
            server.stop(0);
        }
    }

    private static DownloadTask waitUntil(
            RangeDownloadEngine engine,
            String id,
            java.util.function.Predicate<DownloadTask> predicate,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        DownloadTask latest = null;
        while (System.nanoTime() < deadline) {
            latest = engine.tasks().stream().filter(task -> task.id().equals(id)).findFirst().orElseThrow();
            if (predicate.test(latest)) {
                return latest;
            }
            if (latest.status() == DownloadTask.Status.FAILED) {
                return latest;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("timed out, last=" + latest + " tasks=" + Arrays.toString(engine.tasks().toArray()));
    }
}
