package cn.qaiu.nfd.share;

import cn.qaiu.WebClientVertxInit;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

/**
 * Owns the Vert.x instance {@code cn.qaiu:parser} needs. {@code createTool()} fails with
 * "无法创建工具实例" until {@link WebClientVertxInit#init} has been called.
 */
public final class ParserRuntime {

    private static Vertx vertx;

    private ParserRuntime() {
    }

    public static synchronized void start() {
        if (vertx != null) {
            return;
        }
        VertxOptions options = new VertxOptions()
                .setEventLoopPoolSize(2)
                .setWorkerPoolSize(8)
                .setBlockedThreadCheckInterval(60_000);
        vertx = Vertx.vertx(options);
        WebClientVertxInit.init(vertx);
    }

    public static synchronized boolean started() {
        return vertx != null;
    }

    public static synchronized void stop() {
        if (vertx == null) {
            return;
        }
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Shutting down; nothing useful to do with a close failure.
        }
        vertx = null;
    }

    /** Version of the bundled {@code cn.qaiu:parser} jar, from its Maven metadata. */
    public static String parserVersion() {
        try (var in = WebClientVertxInit.class.getResourceAsStream("/META-INF/maven/cn.qaiu/parser/pom.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                return p.getProperty("version", "unknown");
            }
        } catch (java.io.IOException ignored) {
            // Fall through to the manifest.
        }
        String version = WebClientVertxInit.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }
}
