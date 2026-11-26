package cn.qaiu.nfd.daemon;

import cn.qaiu.nfd.core.DownloadManager;
import cn.qaiu.nfd.core.DownloadRequest;
import cn.qaiu.nfd.core.LinkRefresher;
import cn.qaiu.nfd.core.TaskSnapshot;
import cn.qaiu.nfd.share.ParserRuntime;
import cn.qaiu.nfd.share.ShareErrors;
import cn.qaiu.nfd.share.ShareSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Localhost HTTP API for the Android app.
 *
 * <p>Every endpoint except a bare {@code GET /api/health} needs the {@code X-NFD-Token} header.
 * When bound to loopback, requests whose {@code Host} is not a loopback name are rejected, which
 * stops DNS-rebinding pages in the phone's browser from reaching the API.
 */
final class DaemonServer implements AutoCloseable {

    static final String TOKEN_HEADER = "X-NFD-Token";
    private static final Logger LOG = Logger.getLogger("nfd-daemon");
    private static final int MAX_BODY = 256 * 1024;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    private final DaemonConfig config;
    private final ShareBackend backend;
    private final DownloadManager downloads;
    private final String version;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;

    DaemonServer(DaemonConfig config, ShareBackend backend, DownloadManager downloads, String version) {
        this.config = config;
        this.backend = backend;
        this.downloads = downloads;
        this.version = version;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(config.bind), config.port), 16);
        executor = Executors.newFixedThreadPool(6, runnable -> {
            Thread thread = new Thread(runnable, "nfd-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void awaitStop() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        stopped.countDown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!hostAllowed(exchange)) {
                send(exchange, 403, error("拒绝非本机访问"));
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();
            boolean authorized = authorized(exchange);
            if (method.equals("GET") && path.equals("/api/health")) {
                send(exchange, 200, health(authorized));
                return;
            }
            if (!authorized) {
                send(exchange, 401, error("令牌无效，请在 App 中重新复制安装命令或运行 nfd-termux token"));
                return;
            }
            route(exchange, method, path);
        } catch (BadRequest ex) {
            send(exchange, 400, error(ex.getMessage()));
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "request failed: " + exchange.getRequestURI().getPath() + ": " + ex);
            send(exchange, 422, error(ShareErrors.friendly(ex)));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String method, String path) throws IOException {
        if (method.equals("GET") && path.equals("/api/tasks")) {
            send(exchange, 200, tasks());
            return;
        }
        if (method.equals("GET") && path.equals("/api/settings")) {
            send(exchange, 200, settings());
            return;
        }
        if (!method.equals("POST")) {
            send(exchange, 404, error("未知接口"));
            return;
        }
        JsonObject body = body(exchange);
        switch (path) {
            case "/api/inspect" -> send(exchange, 200, backend.inspect(link(body), body.getString("password")));
            case "/api/list" -> {
                ShareSession.Listing listing = backend.list(link(body), body.getString("password"),
                        ShareSession.Entry.fromJson(body.getJsonObject("folder")));
                send(exchange, 200, listingJson(listing));
            }
            case "/api/resolve" -> {
                ShareSession.DirectLink direct = backend.resolve(link(body), body.getString("password"),
                        ShareSession.Entry.fromJson(body.getJsonObject("entry")));
                send(exchange, 200, new JsonObject()
                        .put("url", direct.url())
                        .put("fileName", direct.fileName())
                        .put("headers", new JsonObject(Map.copyOf(direct.headers()))));
            }
            case "/api/tasks" -> send(exchange, 200, enqueue(body));
            case "/api/tasks/pause-all" -> {
                downloads.pauseAll();
                send(exchange, 200, tasks());
            }
            case "/api/tasks/resume-all" -> {
                downloads.resumeAll();
                send(exchange, 200, tasks());
            }
            case "/api/tasks/clear-finished" -> {
                downloads.clearFinished();
                send(exchange, 200, tasks());
            }
            case "/api/settings" -> {
                if (body.containsKey("maxConcurrent")) {
                    config.maxConcurrent = clamp(body.getInteger("maxConcurrent"), 1, 8);
                    downloads.setMaxConcurrent(config.maxConcurrent);
                }
                if (body.containsKey("connections")) {
                    config.connections = clamp(body.getInteger("connections"), 1, 16);
                    downloads.setConnections(config.connections);
                }
                config.save();
                send(exchange, 200, settings());
            }
            case "/api/shutdown" -> {
                send(exchange, 200, new JsonObject().put("ok", true));
                Thread stopper = new Thread(() -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(200);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    close();
                }, "nfd-shutdown");
                stopper.start();
            }
            default -> taskAction(exchange, path, body);
        }
    }

    private void taskAction(HttpExchange exchange, String path, JsonObject body) throws IOException {
        String prefix = "/api/tasks/";
        int slash = path.lastIndexOf('/');
        if (!path.startsWith(prefix) || slash <= prefix.length()) {
            send(exchange, 404, error("未知接口"));
            return;
        }
        String id = path.substring(prefix.length(), slash);
        String action = path.substring(slash + 1);
        if (downloads.get(id).isEmpty()) {
            send(exchange, 404, error("任务不存在"));
            return;
        }
        switch (action) {
            case "pause" -> downloads.pause(id);
            case "resume" -> downloads.resume(id);
            case "remove" -> downloads.remove(id, body.getBoolean("deleteFile", false));
            default -> {
                send(exchange, 404, error("未知操作"));
                return;
            }
        }
        send(exchange, 200, tasks());
    }

    private JsonObject enqueue(JsonObject body) {
        String link = link(body);
        String password = body.getString("password");
        JsonArray entries = body.getJsonArray("entries");
        JsonArray created = new JsonArray();
        if (entries == null || entries.isEmpty()) {
            ShareSession.DirectLink direct = backend.resolve(link, password, null);
            String name = body.getString("fileName");
            TaskSnapshot task = downloads.enqueue(new DownloadRequest(
                    name == null || name.isBlank() ? direct.fileName() : name,
                    direct.url(),
                    direct.headers(),
                    config.effectiveDownloadDir(),
                    ShareSession.source(link, password, null)));
            created.add(taskJson(task));
        } else {
            for (int i = 0; i < entries.size(); i++) {
                ShareSession.Entry entry = ShareSession.Entry.fromJson(entries.getJsonObject(i));
                if (entry == null || entry.folder()) {
                    continue;
                }
                TaskSnapshot task = downloads.enqueue(new DownloadRequest(
                        entry.fileName(),
                        "",
                        Map.of(),
                        config.effectiveDownloadDir(),
                        ShareSession.source(link, password, entry)));
                created.add(taskJson(task));
            }
        }
        return tasks().put("created", created);
    }

    private JsonObject health(boolean authorized) {
        JsonObject health = new JsonObject()
                .put("ok", true)
                .put("app", "nfd-termux")
                .put("version", version)
                .put("authorized", authorized);
        if (authorized) {
            health.put("parserVersion", ParserRuntime.parserVersion())
                    .put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"))
                    .put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
                    .put("termux", DaemonMain.isTermux())
                    .put("downloadDir", config.effectiveDownloadDir().toString())
                    .put("downloadDirShared", config.downloadDirShared());
        }
        return health;
    }

    private JsonObject settings() {
        return new JsonObject()
                .put("maxConcurrent", downloads.maxConcurrent())
                .put("connections", downloads.connections())
                .put("downloadDir", config.effectiveDownloadDir().toString())
                .put("downloadDirShared", config.downloadDirShared());
    }

    private JsonObject tasks() {
        JsonArray list = new JsonArray();
        List<TaskSnapshot> snapshots = downloads.snapshots();
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            list.add(taskJson(snapshots.get(i)));
        }
        return new JsonObject().put("tasks", list);
    }

    /** Resolves queued and expired tasks through the same cached sessions the listing used. */
    static LinkRefresher refresher(ShareBackend backend) {
        return task -> {
            Map<String, String> source = task.source();
            String entry = source.get(ShareSession.SOURCE_ENTRY);
            try {
                ShareSession.DirectLink link = backend.resolve(
                        source.get(ShareSession.SOURCE_LINK),
                        source.get(ShareSession.SOURCE_PASSWORD),
                        entry == null || entry.isBlank() ? null : ShareSession.Entry.fromJson(new JsonObject(entry)));
                return new LinkRefresher.ResolvedLink(link.url(), link.headers());
            } catch (RuntimeException ex) {
                throw new IllegalStateException(ShareErrors.friendly(ex), ex);
            }
        };
    }

    static JsonObject taskJson(TaskSnapshot task) {
        return new JsonObject()
                .put("id", task.id())
                .put("fileName", task.fileName())
                .put("status", task.status().name())
                .put("statusLabel", task.status().label())
                .put("downloaded", task.downloaded())
                .put("total", task.total())
                .put("speed", task.speed())
                .put("etaSeconds", task.etaSeconds())
                .put("progress", task.progress())
                .put("progressText", task.progressText())
                .put("message", task.message())
                .put("destination", task.destination())
                .put("connections", task.connections())
                .put("resumable", task.resumable())
                .put("createdAt", task.createdAt())
                .put("link", task.source().getOrDefault(ShareSession.SOURCE_LINK, ""));
    }

    static JsonObject listingJson(ShareSession.Listing listing) {
        JsonArray entries = new JsonArray();
        for (ShareSession.Entry entry : listing.entries()) {
            entries.add(entry.toJson());
        }
        return new JsonObject()
                .put("panName", listing.panName())
                .put("type", listing.type())
                .put("entries", entries);
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (supplied == null || config.token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                supplied.trim().getBytes(StandardCharsets.UTF_8),
                config.token.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hostAllowed(HttpExchange exchange) {
        InetAddress local = exchange.getLocalAddress().getAddress();
        if (!local.isLoopbackAddress()) {
            return true;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return true;
        }
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            name = end > 0 ? name.substring(0, end + 1) : name;
        } else if (name.contains(":")) {
            name = name.substring(0, name.indexOf(':'));
        }
        return LOOPBACK_HOSTS.contains(name);
    }

    private static String link(JsonObject body) {
        String link = body.getString("link");
        if (link == null || link.isBlank()) {
            throw new BadRequest("请先粘贴分享链接");
        }
        return link;
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BODY) {
                    throw new BadRequest("请求过大");
                }
            }
            String text = out.toString(StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? new JsonObject() : new JsonObject(text);
        } catch (DecodeException | ClassCastException ex) {
            throw new BadRequest("请求不是合法的 JSON");
        }
    }

    private static void send(HttpExchange exchange, int code, JsonObject json) throws IOException {
        byte[] bytes = json.encode().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static JsonObject error(String message) {
        return new JsonObject().put("error", message);
    }

    private static int clamp(Integer value, int min, int max) {
        if (value == null) {
            throw new BadRequest("参数缺失");
        }
        return Math.max(min, Math.min(max, value));
    }

    static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }
}
