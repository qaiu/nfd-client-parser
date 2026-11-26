package cn.qaiu.nfd.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.qaiu.nfd.core.DownloadManager;
import cn.qaiu.nfd.share.AuthStore;
import cn.qaiu.nfd.share.ShareSession;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonServerTest {

    private static final byte[] PAYLOAD = "hello from the share".repeat(5000).getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path home;

    private HttpServer files;
    private DaemonServer server;
    private DownloadManager downloads;
    private DaemonConfig config;
    private final AtomicInteger resolves = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        files = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        files.createContext("/f", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, PAYLOAD.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(PAYLOAD);
            }
        });
        files.start();
        config = DaemonConfig.load(home);
        config.port = 0;
        config.downloadDir = home.resolve("dl");
        ShareBackend backend = new FakeBackend();
        downloads = new DownloadManager(new DownloadManager.Options().refresher(DaemonServer.refresher(backend)));
        server = new DaemonServer(config, backend, downloads, AuthStore.load(home.resolve("auths.json")), "test");
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
        downloads.close();
        files.stop(0);
    }

    @Test
    void healthIsPublicButMinimalWithoutToken() throws Exception {
        Response open = call("GET", "/api/health", null, null);
        assertEquals(200, open.code);
        assertFalse(open.json.getBoolean("authorized"));
        assertFalse(open.json.containsKey("downloadDir"));

        Response full = call("GET", "/api/health", null, config.token);
        assertTrue(full.json.getBoolean("authorized"));
        assertEquals(home.resolve("dl").toString(), full.json.getString("downloadDir"));
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        assertEquals(401, call("POST", "/api/inspect", "{\"link\":\"x\"}", null).code);
        assertEquals(401, call("POST", "/api/inspect", "{\"link\":\"x\"}", "nope").code);
    }

    @Test
    void rejectsForeignHostHeader() throws Exception {
        assertTrue(rawGet("/api/tasks", "evil.example").startsWith("HTTP/1.1 403"));
        assertTrue(rawGet("/api/tasks", "localhost:" + server.port()).startsWith("HTTP/1.1 200"));
    }

    @Test
    void validatesRequests() throws Exception {
        Response empty = call("POST", "/api/list", "{}", config.token);
        assertEquals(400, empty.code);
        assertEquals("请先粘贴分享链接", empty.json.getString("error"));
        assertEquals(400, call("POST", "/api/list", "not json", config.token).code);
        assertEquals(404, call("POST", "/api/tasks/nope/pause", "{}", config.token).code);
    }

    @Test
    void parserErrorsBecomeFriendlyMessages() throws Exception {
        Response failed = call("POST", "/api/list", "{\"link\":\"https://gone.example/s/1\"}", config.token);
        assertEquals(422, failed.code);
        assertEquals("分享已失效：分享不存在", failed.json.getString("error"));
    }

    @Test
    void listsFoldersAndDownloadsEntries() throws Exception {
        Response listing = call("POST", "/api/list", "{\"link\":\"https://fake.example/s/1\",\"password\":\"1234\"}", config.token);
        assertEquals(200, listing.code);
        JsonArray entries = listing.json.getJsonArray("entries");
        assertEquals(2, entries.size());
        JsonObject folder = entries.getJsonObject(0);
        assertTrue(folder.getBoolean("folder"));

        Response inside = call("POST", "/api/list",
                new JsonObject().put("link", "https://fake.example/s/1").put("folder", folder).encode(), config.token);
        assertEquals("inner.bin", inside.json.getJsonArray("entries").getJsonObject(0).getString("fileName"));

        Response queued = call("POST", "/api/tasks",
                new JsonObject().put("link", "https://fake.example/s/1").put("password", "1234").put("entries", entries).encode(),
                config.token);
        assertEquals(200, queued.code);
        assertEquals(1, queued.json.getJsonArray("created").size(), "folders are skipped");
        String id = queued.json.getJsonArray("created").getJsonObject(0).getString("id");

        JsonObject task = awaitTask(id, "COMPLETED");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(Path.of(task.getString("destination"))));
        assertEquals(home.resolve("dl").resolve("a.bin").toString(), task.getString("destination"));
        assertEquals("https://fake.example/s/1", task.getString("link"));
        assertFalse(task.encode().contains("1234"), "password must not leak into task listings");
        assertEquals(1, resolves.get());

        Response removed = call("POST", "/api/tasks/" + id + "/remove", "{\"deleteFile\":true}", config.token);
        assertTrue(removed.json.getJsonArray("tasks").isEmpty());
        assertFalse(Files.exists(Path.of(task.getString("destination"))));
    }

    @Test
    void directShareResolvesImmediately() throws Exception {
        Response queued = call("POST", "/api/tasks", "{\"link\":\"https://fake.example/s/direct\"}", config.token);
        assertEquals(200, queued.code);
        assertEquals("direct.bin", queued.json.getJsonArray("created").getJsonObject(0).getString("fileName"));
        awaitTask(queued.json.getJsonArray("created").getJsonObject(0).getString("id"), "COMPLETED");
    }

    @Test
    void settingsPersist() throws Exception {
        Response settings = call("POST", "/api/settings", "{\"maxConcurrent\":2,\"connections\":8}", config.token);
        assertEquals(2, settings.json.getInteger("maxConcurrent"));
        assertEquals(8, settings.json.getInteger("connections"));
        DaemonConfig reloaded = DaemonConfig.load(home);
        assertEquals(2, reloaded.maxConcurrent);
        assertEquals(config.token, reloaded.token);
    }

    @Test
    void downloadDirIsValidatedAndUsedForNewTasks() throws Exception {
        Path chosen = home.resolve("chosen").resolve("sub");
        Response changed = call("POST", "/api/settings", new JsonObject().put("downloadDir", chosen.toString()).encode(), config.token);
        assertEquals(200, changed.code);
        assertEquals(chosen.toString(), changed.json.getString("downloadDir"));
        assertTrue(changed.json.getBoolean("downloadDirCustom"));
        assertEquals(chosen, DaemonConfig.load(home).downloadDir);

        Response queued = call("POST", "/api/tasks", "{\"link\":\"https://fake.example/s/direct\"}", config.token);
        JsonObject task = awaitTask(queued.json.getJsonArray("created").getJsonObject(0).getString("id"), "COMPLETED");
        assertEquals(chosen.resolve("direct.bin").toString(), task.getString("destination"));

        Response relative = call("POST", "/api/settings", "{\"downloadDir\":\"relative/dir\"}", config.token);
        assertEquals(400, relative.code);
        Path blocker = Files.writeString(home.resolve("not-a-dir"), "x");
        Response blocked = call("POST", "/api/settings", new JsonObject().put("downloadDir", blocker.resolve("x").toString()).encode(), config.token);
        assertEquals(400, blocked.code);
        assertTrue(blocked.json.getString("error").startsWith("无法写入"));
        assertEquals(chosen.toString(), call("GET", "/api/settings", null, config.token).json.getString("downloadDir"));

        Response reset = call("POST", "/api/settings", "{\"downloadDir\":\"\"}", config.token);
        assertFalse(reset.json.getBoolean("downloadDirCustom"));
        assertEquals(reset.json.getString("defaultDownloadDir"), reset.json.getString("downloadDir"));
    }

    @Test
    void loginDataIsStoredButNeverReturned() throws Exception {
        Response saved = call("POST", "/api/auths", new JsonObject().put("type", "qk")
                .put("fields", new JsonObject().put("cookie", "kps=secret-cookie-value")).encode(), config.token);
        assertEquals(200, saved.code);
        assertFalse(saved.json.encode().contains("secret-cookie-value"));
        JsonObject quark = provider(call("GET", "/api/auths", null, config.token).json, "qk");
        assertTrue(quark.getBoolean("configured"));
        assertTrue(quark.getJsonArray("fields").getJsonObject(0).getBoolean("set"));
        assertEquals("kps=secret-cookie-value", AuthStore.load(home.resolve("auths.json")).get("qk").get("cookie"));

        call("POST", "/api/auths", "{\"type\":\"qk\",\"fields\":{\"cookie\":\"\"}}", config.token);
        assertEquals("kps=secret-cookie-value", AuthStore.load(home.resolve("auths.json")).get("qk").get("cookie"),
                "a blank secret keeps the stored value");
        Response cleared = call("POST", "/api/auths/clear", "{\"type\":\"qk\"}", config.token);
        assertFalse(provider(cleared.json, "qk").getBoolean("configured"));
        assertEquals(400, call("POST", "/api/auths", "{\"type\":\"nope\"}", config.token).code);
        assertEquals(401, call("GET", "/api/auths", null, null).code);
    }

    @Test
    void listingReportsPagingAndSingleFileFallback() throws Exception {
        Response first = call("POST", "/api/list", "{\"link\":\"https://fake.example/s/paged\"}", config.token);
        assertTrue(first.json.getBoolean("hasMore"));
        assertEquals("c2", first.json.getString("nextCursor"));
        Response second = call("POST", "/api/list", "{\"link\":\"https://fake.example/s/paged\",\"page\":2,\"cursor\":\"c2\"}", config.token);
        assertEquals(2, second.json.getInteger("page"));
        assertFalse(second.json.getBoolean("hasMore"));

        Response single = call("POST", "/api/list", "{\"link\":\"https://fake.example/s/file\"}", config.token);
        assertTrue(single.json.getBoolean("singleFile"));
        assertEquals("目录解析失败，已按单文件分享解析", single.json.getString("note"));
    }

    private static JsonObject provider(JsonObject describe, String type) {
        for (Object item : describe.getJsonArray("providers")) {
            if (((JsonObject) item).getString("type").equals(type)) {
                return (JsonObject) item;
            }
        }
        throw new AssertionError("missing provider " + type);
    }

    private JsonObject awaitTask(String id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            for (Object item : call("GET", "/api/tasks", null, config.token).json.getJsonArray("tasks")) {
                JsonObject task = (JsonObject) item;
                if (task.getString("id").equals(id)) {
                    last = task;
                    if (task.getString("status").equals(status)) {
                        return task;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("task did not reach " + status + ": " + last);
    }

    private Response call(String method, String path, String body, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + path).openConnection();
        connection.setRequestMethod(method);
        if (token != null) {
            connection.setRequestProperty(DaemonServer.TOKEN_HEADER, token);
        }
        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = in == null ? "{}" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(code, new JsonObject(text));
    }

    private String rawGet(String path, String host) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.port())) {
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + DaemonServer.TOKEN_HEADER + ": "
                    + config.token + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Response(int code, JsonObject json) {
    }

    private final class FakeBackend implements ShareBackend {
        private final ShareSession.Entry folder = new ShareSession.Entry("docs", "d1", "folder", -1, "", "/v2/getFileList?dirId=d1", true);
        private final ShareSession.Entry file = new ShareSession.Entry("a.bin", "f1", "bin", PAYLOAD.length, "", "/v2/redirectUrl/fake/x", false);

        @Override
        public JsonObject inspect(String link, String password) {
            return new JsonObject().put("type", "fake");
        }

        @Override
        public ShareSession.Listing list(String link, String password, ShareSession.Entry dir, int page, String cursor) {
            if (link.contains("gone")) {
                throw new RuntimeException("fake - 解析异常: {\"status\":404,\"message\":\"分享不存在\"}");
            }
            if (dir != null) {
                return listing(List.of(new ShareSession.Entry("inner.bin", "f2", "bin", 1, "", "/v2/redirectUrl/fake/y", false)), 1, false, null);
            }
            if (link.contains("paged")) {
                return page == 1 ? listing(List.of(file), 1, true, "c2") : listing(List.of(folder), page, false, null);
            }
            if (link.contains("file")) {
                return new ShareSession.Listing("Fake", "fake", List.of(new ShareSession.Entry("one.bin", "", "file", 3, "3 B", null, false)),
                        1, false, null, true, "目录解析失败，已按单文件分享解析");
            }
            return listing(List.of(folder, file), 1, false, null);
        }

        private ShareSession.Listing listing(List<ShareSession.Entry> entries, int page, boolean hasMore, String cursor) {
            return new ShareSession.Listing("Fake", "fake", entries, page, hasMore, cursor, false, "");
        }

        @Override
        public ShareSession.DirectLink resolve(String link, String password, ShareSession.Entry entry) {
            resolves.incrementAndGet();
            String url = "http://127.0.0.1:" + files.getAddress().getPort() + "/f";
            return new ShareSession.DirectLink(url, entry == null ? "direct.bin" : entry.fileName(), Map.of("Referer", "https://fake.example/"));
        }
    }
}
