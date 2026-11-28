package cn.qaiu.nfd.daemon;

import cn.qaiu.nfd.core.DownloadManager;
import cn.qaiu.nfd.share.AuthStore;
import cn.qaiu.nfd.share.ParserRuntime;
import cn.qaiu.nfd.share.SelfTest;
import cn.qaiu.nfd.share.ShareSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Entry point of the Termux daemon: {@code cn.qaiu:parser} on Termux's OpenJDK, plus the shared
 * download core writing into {@code ~/storage/downloads/nfd}.
 */
public final class DaemonMain {

    private DaemonMain() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Paths.get(System.getProperty("user.home"), ".nfd");
        Integer port = null;
        String bind = null;
        String token = System.getenv("NFD_LOCAL_TOKEN");
        String downloadDir = null;
        boolean selfTest = false;
        boolean online = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--home" -> home = Paths.get(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--bind" -> bind = args[++i];
                case "--token" -> token = args[++i];
                case "--download-dir" -> downloadDir = args[++i];
                case "--self-test" -> selfTest = true;
                case "--online" -> online = true;
                case "--version" -> {
                    System.out.println("nfd-termux " + version() + " (parser " + ParserRuntime.parserVersion() + ")");
                    return;
                }
                case "--help", "-h" -> {
                    System.out.println("usage: DaemonMain [--home DIR] [--port N] [--bind ADDR] [--token T] "
                            + "[--download-dir DIR] [--self-test [--online]] [--version]");
                    return;
                }
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        configureJvmForTermux();
        if (selfTest) {
            System.exit(selfTest(online) ? 0 : 1);
            return;
        }
        DaemonConfig config = DaemonConfig.load(home);
        if (port != null) {
            config.port = port;
        }
        if (bind != null) {
            config.bind = bind;
        }
        if (token != null && !token.isBlank()) {
            config.token = token.trim();
        }
        if (downloadDir != null) {
            config.downloadDir = downloadDir.isBlank() ? null : Paths.get(downloadDir);
        }
        config.save();
        run(config);
    }

    static void run(DaemonConfig config) throws Exception {
        AuthStore auths = AuthStore.load(config.home.resolve("auths.json"));
        ShareSession.useCredentials(auths::get);
        ParserBackend backend = new ParserBackend();
        boolean embedded = System.getenv("NFD_LOCAL_DOWNLOADS") != null;
        DownloadManager.Options options = new DownloadManager.Options()
                .stateDir(config.home.resolve("tasks"))
                .maxConcurrent(config.maxConcurrent)
                .connections(config.connections)
                .resumeOnStart(true)
                .refresher(DaemonServer.refresher(backend));
        String aria2Path = System.getenv("NFD_ARIA2C");
        String certificates = System.getenv("NFD_ARIA2C_CA");
        if (aria2Path == null && isTermux()) {
            aria2Path = Path.of(System.getenv("PREFIX"), "bin", "aria2c").toString();
        }
        if (aria2Path != null && Files.isExecutable(Path.of(aria2Path))) {
            options.aria2(Path.of(aria2Path), certificates == null ? null : Path.of(certificates));
        }
        options.diagnostics(System.out::println);
        DownloadManager downloads = new DownloadManager(options);
        DaemonServer server = new DaemonServer(config, backend, downloads, auths, version());
        if (embedded) {
            System.out.println("NFD_RUNTIME daemon-init");
            if ("1".equals(System.getenv("NFD_VERIFY_STARTUP"))) {
                System.out.println("NFD_RUNTIME verify-start");
                if (!new SelfTest(System.out).runtimeChecks().passed()) {
                    System.out.println("NFD_RUNTIME verify-failed");
                    throw new IllegalStateException("Embedded parser startup self-test failed");
                }
                System.out.println("NFD_RUNTIME verify-passed");
            }
            ParserRuntime.start();
        }
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            downloads.close();
            ParserRuntime.stop();
        }, "nfd-shutdown-hook"));
        System.out.println("nfd-termux " + version() + " (parser " + ParserRuntime.parserVersion() + ") listening on "
                + config.bind + ":" + server.port() + ", downloads -> " + config.effectiveDownloadDir());
        if (embedded) {
            System.out.println("NFD_RUNTIME daemon-ready");
        } else {
            Thread warmup = new Thread(ParserRuntime::start, "nfd-warmup");
            warmup.setDaemon(true);
            warmup.start();
        }
        server.awaitStop();
        downloads.close();
        ParserRuntime.stop();
        System.exit(0);
    }

    static boolean isTermux() {
        String prefix = System.getenv("PREFIX");
        return prefix != null && prefix.contains("com.termux");
    }

    /**
     * Termux has no {@code /etc/resolv.conf} unless the {@code resolv-conf} package is installed,
     * and Netty's resolver then falls back to public DNS servers that are often blocked. The JDK
     * resolver goes through Android's own resolver instead.
     */
    private static void configureJvmForTermux() {
        if (isTermux() && System.getProperty("vertx.disableDnsResolver") == null) {
            System.setProperty("vertx.disableDnsResolver", "true");
        }
    }

    static String version() {
        String version = DaemonMain.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static boolean selfTest(boolean online) {
        SelfTest test = new SelfTest(System.out).runtimeChecks()
                .check("daemon HTTP API", DaemonMain::apiRoundTrip);
        if (online) {
            test.onlineCheck();
        }
        return test.finish();
    }

    private static boolean apiRoundTrip() throws Exception {
        Path home = Files.createTempDirectory("nfd-selftest");
        DaemonConfig config = DaemonConfig.load(home);
        config.port = 0;
        try (DownloadManager downloads = new DownloadManager(new DownloadManager.Options())) {
            DaemonServer server = new DaemonServer(config, new ParserBackend(), downloads,
                    AuthStore.load(home.resolve("auths.json")), version());
            server.start();
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port() + "/api/inspect"))
                        .header(DaemonServer.TOKEN_HEADER, config.token)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"link\":\"https://pan.quark.cn/s/abc\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 && response.body().contains("\"type\":\"qk\"");
            } finally {
                server.close();
            }
        }
    }
}
