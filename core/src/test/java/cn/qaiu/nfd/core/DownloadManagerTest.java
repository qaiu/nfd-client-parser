package cn.qaiu.nfd.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadManagerTest {

    @TempDir
    Path dir;

    private TestServer server;
    private DownloadManager manager;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer();
    }

    @AfterEach
    void stop() {
        if (manager != null) {
            manager.close();
        }
        server.close();
    }

    private DownloadManager.Options options() {
        return new DownloadManager.Options()
                .connections(4)
                .minSegmentSize(64 * 1024)
                .retryBaseMillis(10)
                .maxRetries(4)
                .timeouts(2000, 2000);
    }

    private TaskSnapshot enqueue(String path, String name, Map<String, String> source) {
        return manager.enqueue(new DownloadRequest(name, server.url(path), Map.of("Referer", "https://example.com/"), dir, source));
    }

    private TaskSnapshot await(String id, Predicate<TaskSnapshot> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        TaskSnapshot last = null;
        while (System.nanoTime() < deadline) {
            last = manager.get(id).orElseThrow();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out, last state: " + last);
    }

    private TaskSnapshot awaitStatus(String id, TaskStatus status) throws InterruptedException {
        return await(id, s -> s.status() == status);
    }

    private void assertContent(TaskSnapshot done, int version) throws Exception {
        assertEquals(TaskStatus.COMPLETED, done.status(), done.message());
        assertArrayEquals(TestServer.content(version), Files.readAllBytes(Path.of(done.destination())));
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".nfdpart")), "part file left behind");
        }
    }

    @Test
    void downloadsWithSeveralConnections() throws Exception {
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/file", "a.bin", Map.of());
        TaskSnapshot done = awaitStatus(task.id(), TaskStatus.COMPLETED);
        assertContent(done, 1);
        assertEquals(dir.resolve("a.bin").toString(), done.destination());
        long segmentRequests = server.ranges.stream().filter(r -> r.matches("bytes=\\d+-\\d+")).count();
        assertTrue(segmentRequests >= 4, "expected multiple segment requests: " + server.ranges);
    }

    @Test
    void pauseAndResumeKeepsPartialData() throws Exception {
        server.chunkDelayMillis = 20;
        manager = new DownloadManager(options().connections(2));
        TaskSnapshot task = enqueue("/file", "b.bin", Map.of());
        await(task.id(), s -> s.downloaded() > 200 * 1024);
        manager.pause(task.id());
        TaskSnapshot paused = awaitStatus(task.id(), TaskStatus.PAUSED);
        assertTrue(paused.downloaded() > 0);
        assertTrue(paused.resumable());
        server.ranges.clear();
        server.chunkDelayMillis = 0;
        manager.resume(task.id());
        TaskSnapshot done = awaitStatus(task.id(), TaskStatus.COMPLETED);
        assertContent(done, 1);
        assertTrue(server.ranges.stream().noneMatch(r -> r.equals("bytes=0-")), "resume must not re-probe: " + server.ranges);
    }

    @Test
    void tasksSurviveRestart() throws Exception {
        Path state = dir.resolve("state");
        server.chunkDelayMillis = 20;
        manager = new DownloadManager(options().stateDir(state).connections(2));
        TaskSnapshot task = enqueue("/file", "c.bin", Map.of("link", "https://pan.example/s/1"));
        await(task.id(), s -> s.downloaded() > 150 * 1024);
        manager.close();
        long before = manager.get(task.id()).orElseThrow().downloaded();

        server.chunkDelayMillis = 0;
        manager = new DownloadManager(options().stateDir(state));
        TaskSnapshot restored = manager.get(task.id()).orElseThrow();
        assertEquals(TaskStatus.PAUSED, restored.status());
        assertEquals(before, restored.downloaded());
        assertEquals("https://pan.example/s/1", restored.source().get("link"));
        manager.resume(task.id());
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 1);
    }

    @Test
    void interruptedTasksResumeOnStartWhenEnabled() throws Exception {
        Path state = dir.resolve("state");
        server.chunkDelayMillis = 20;
        manager = new DownloadManager(options().stateDir(state).connections(2));
        TaskSnapshot running = enqueue("/file", "d.bin", Map.of());
        await(running.id(), s -> s.downloaded() > 150 * 1024);
        TaskSnapshot paused = enqueue("/file", "e.bin", Map.of());
        manager.pause(paused.id());
        awaitStatus(paused.id(), TaskStatus.PAUSED);
        manager.close();

        server.chunkDelayMillis = 0;
        manager = new DownloadManager(options().stateDir(state).resumeOnStart(true));
        TaskSnapshot done = awaitStatus(running.id(), TaskStatus.COMPLETED);
        assertArrayEquals(TestServer.content(1), Files.readAllBytes(Path.of(done.destination())));
        assertEquals(TaskStatus.PAUSED, manager.get(paused.id()).orElseThrow().status(), "user pauses must survive restarts");
    }

    @Test
    void serverWithoutRangeRestartsFromZero() throws Exception {
        server.chunkDelayMillis = 10;
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/norange", "d.bin", Map.of());
        await(task.id(), s -> s.downloaded() > 100 * 1024);
        assertFalse(manager.get(task.id()).orElseThrow().resumable());
        manager.pause(task.id());
        awaitStatus(task.id(), TaskStatus.PAUSED);
        server.chunkDelayMillis = 0;
        manager.resume(task.id());
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 1);
    }

    @Test
    void expiredLinkIsRefreshed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        manager = new DownloadManager(options().refresher(task -> {
            calls.incrementAndGet();
            assertEquals("secret-share", task.source().get("link"));
            return new LinkRefresher.ResolvedLink(server.url("/file"), Map.of());
        }));
        TaskSnapshot task = enqueue("/expired", "e.bin", Map.of("link", "secret-share"));
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 1);
        assertEquals(1, calls.get());
    }

    @Test
    void unresolvedTasksAreResolvedWhenTheyStart() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        manager = new DownloadManager(options().refresher(task -> {
            calls.incrementAndGet();
            if (task.source().containsKey("broken")) {
                throw new IllegalStateException("分享已失效");
            }
            return new LinkRefresher.ResolvedLink(server.url("/file"), Map.of());
        }));
        TaskSnapshot lazy = manager.enqueue(new DownloadRequest("lazy.bin", "", Map.of(), dir, Map.of("link", "x")));
        assertContent(awaitStatus(lazy.id(), TaskStatus.COMPLETED), 1);
        TaskSnapshot broken = manager.enqueue(new DownloadRequest("broken.bin", "", Map.of(), dir, Map.of("broken", "1")));
        assertEquals("分享已失效", awaitStatus(broken.id(), TaskStatus.FAILED).message());
        assertEquals(2, calls.get());
    }

    @Test
    void expiredLinkWithoutRefresherFails() throws Exception {
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/gone", "f.bin", Map.of());
        TaskSnapshot failed = awaitStatus(task.id(), TaskStatus.FAILED);
        assertTrue(failed.message().contains("410"), failed.message());
        manager.resume(task.id());
        awaitStatus(task.id(), TaskStatus.FAILED);
    }

    @Test
    void htmlInsteadOfFileCountsAsExpired() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        manager = new DownloadManager(options().refresher(task -> {
            calls.incrementAndGet();
            return new LinkRefresher.ResolvedLink(server.url("/file"), Map.of());
        }));
        TaskSnapshot task = enqueue("/html", "g.zip", Map.of());
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 1);
        assertEquals(1, calls.get());
    }

    @Test
    void droppedConnectionsAreRetried() throws Exception {
        server.dropsLeft.set(3);
        manager = new DownloadManager(options().connections(1).minSegmentSize(16L * 1024 * 1024));
        TaskSnapshot task = enqueue("/file", "h.bin", Map.of());
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 1);
    }

    @Test
    void serverErrorsAreRetried() throws Exception {
        server.errorsLeft.set(2);
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/file", "i.bin", Map.of());
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 1);
    }

    @Test
    void persistentServerErrorsFailAfterRetries() throws Exception {
        server.errorsLeft.set(100);
        manager = new DownloadManager(options().maxRetries(2));
        TaskSnapshot task = enqueue("/file", "j.bin", Map.of());
        TaskSnapshot failed = awaitStatus(task.id(), TaskStatus.FAILED);
        assertTrue(failed.message().contains("503"), failed.message());
    }

    @Test
    void followsRedirects() throws Exception {
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/redirect", "k.bin", Map.of());
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 1);
    }

    @Test
    void changedFileRestartsInsteadOfMixingVersions() throws Exception {
        server.changeAfterFirst = true;
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/file", "l.bin", Map.of());
        assertContent(awaitStatus(task.id(), TaskStatus.COMPLETED), 2);
    }

    @Test
    void contentDispositionNamesUnnamedTasks() throws Exception {
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/file?name=1", "", Map.of());
        TaskSnapshot done = awaitStatus(task.id(), TaskStatus.COMPLETED);
        assertEquals("测试.bin", done.fileName());
        assertEquals(dir.resolve("测试.bin").toString(), done.destination());
    }

    @Test
    void existingFilesAreNotOverwritten() throws Exception {
        Files.writeString(dir.resolve("m.bin"), "keep");
        manager = new DownloadManager(options());
        TaskSnapshot done = awaitStatus(enqueue("/file", "m.bin", Map.of()).id(), TaskStatus.COMPLETED);
        assertEquals(dir.resolve("m (1).bin").toString(), done.destination());
        assertEquals("keep", Files.readString(dir.resolve("m.bin")));
    }

    @Test
    void respectsMaxConcurrent() throws Exception {
        server.chunkDelayMillis = 20;
        manager = new DownloadManager(options().maxConcurrent(1));
        TaskSnapshot first = enqueue("/file", "n1.bin", Map.of());
        TaskSnapshot second = enqueue("/file", "n2.bin", Map.of());
        awaitStatus(first.id(), TaskStatus.RUNNING);
        assertEquals(TaskStatus.QUEUED, manager.get(second.id()).orElseThrow().status());
        server.chunkDelayMillis = 0;
        awaitStatus(second.id(), TaskStatus.COMPLETED);
        assertEquals(TaskStatus.COMPLETED, manager.get(first.id()).orElseThrow().status());
    }

    @Test
    void removeDeletesPartialFile() throws Exception {
        server.chunkDelayMillis = 20;
        manager = new DownloadManager(options());
        TaskSnapshot task = enqueue("/file", "o.bin", Map.of());
        await(task.id(), s -> s.downloaded() > 0);
        manager.remove(task.id(), true);
        long deadline = System.currentTimeMillis() + 5000;
        while (manager.get(task.id()).isPresent() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(manager.get(task.id()).isEmpty());
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("o.bin")));
        }
    }
}
