package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.core.DownloadManager;
import cn.qaiu.nfd.core.DownloadRequest;
import cn.qaiu.nfd.core.TaskSnapshot;
import cn.qaiu.nfd.core.TaskStatus;
import cn.qaiu.nfd.share.SelfTest;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/** {@code --self-test}: run by CI against the packaged app before it is uploaded. */
final class DesktopSelfTest {

    private DesktopSelfTest() {
    }

    static boolean run(PrintStream out, boolean online) {
        out.println("NFD Client " + Main.version() + " self-test");
        SelfTest test = new SelfTest(out);
        test.check("JavaFX runtime " + javafxVersion(), () -> ModuleLayer.boot().findModule("javafx.controls").isPresent()
                || Class.forName("javafx.scene.control.Button") != null);
        test.runtimeChecks();
        test.check("download engine (local HTTP)", DesktopSelfTest::localDownload);
        test.check("data directory " + AppPaths.dataDir(), () -> {
            Files.createDirectories(AppPaths.dataDir());
            return Files.isWritable(AppPaths.dataDir());
        });
        if (online) {
            test.onlineCheck();
        }
        return test.finish();
    }

    private static String javafxVersion() {
        try {
            return ModuleLayer.boot().findModule("javafx.controls")
                    .flatMap(module -> module.getDescriptor().rawVersion())
                    .orElse("(class path)");
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    private static boolean localDownload() throws Exception {
        byte[] body = new byte[3 * 1024 * 1024 + 123];
        new Random(7).nextBytes(body);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/file.bin", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        Path dir = Files.createTempDirectory("nfd-selftest");
        try (DownloadManager manager = new DownloadManager(new DownloadManager.Options().retryBaseMillis(100))) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file.bin";
            TaskSnapshot task = manager.enqueue(new DownloadRequest("file.bin", url, Map.of(), dir, Map.of()));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                TaskSnapshot now = manager.get(task.id()).orElseThrow();
                if (now.status() == TaskStatus.COMPLETED) {
                    return Arrays.equals(body, Files.readAllBytes(Path.of(now.destination())));
                }
                if (now.status() == TaskStatus.FAILED) {
                    throw new IllegalStateException(now.message());
                }
                Thread.sleep(50);
            }
            return false;
        } finally {
            server.stop(0);
        }
    }
}
