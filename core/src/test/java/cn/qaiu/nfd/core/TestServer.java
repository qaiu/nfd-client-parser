package cn.qaiu.nfd.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local HTTP server with the failure modes share-link CDNs exhibit. */
final class TestServer implements AutoCloseable {

    static final int SIZE = 1024 * 1024 + 123;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

    final HttpServer server;
    final List<String> ranges = new CopyOnWriteArrayList<>();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger dropsLeft = new AtomicInteger();
    final AtomicInteger errorsLeft = new AtomicInteger();
    final AtomicInteger version = new AtomicInteger(1);
    volatile boolean changeAfterFirst;
    volatile int chunkDelayMillis;

    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/file", ex -> serve(ex, true));
        server.createContext("/norange", ex -> serve(ex, false));
        server.createContext("/expired", ex -> status(ex, 403));
        server.createContext("/gone", ex -> status(ex, 410));
        server.createContext("/stuck", ex -> status(ex, 503));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/file");
            status(ex, 302);
        });
        server.createContext("/html", ex -> {
            byte[] body = "<html>expired</html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    static byte expected(int index, int version) {
        return (byte) ((index * 31 + version * 7) & 0xff);
    }

    static byte[] content(int version) {
        byte[] data = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            data[i] = expected(i, version);
        }
        return data;
    }

    private void serve(HttpExchange ex, boolean rangeSupport) throws IOException {
        requests.incrementAndGet();
        if (errorsLeft.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
            status(ex, 503);
            return;
        }
        int current = version.get();
        if (changeAfterFirst && requests.get() > 1) {
            current = 2;
            version.set(2);
        }
        String etag = "\"v" + current + "\"";
        String range = ex.getRequestHeaders().getFirst("Range");
        String ifRange = ex.getRequestHeaders().getFirst("If-Range");
        ranges.add(range == null ? "" : range);
        if (ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("name")) {
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.bin");
        }
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        long start = 0;
        long end = SIZE - 1;
        int code = 200;
        if (rangeSupport) {
            ex.getResponseHeaders().add("Accept-Ranges", "bytes");
            ex.getResponseHeaders().add("ETag", etag);
            Matcher m = range == null ? null : RANGE.matcher(range);
            boolean honour = m != null && m.matches() && (ifRange == null || ifRange.equals(etag));
            if (honour) {
                start = Long.parseLong(m.group(1));
                end = m.group(2).isEmpty() ? SIZE - 1 : Math.min(SIZE - 1, Long.parseLong(m.group(2)));
                if (start >= SIZE) {
                    ex.getResponseHeaders().add("Content-Range", "bytes */" + SIZE);
                    status(ex, 416);
                    return;
                }
                code = 206;
                ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + SIZE);
            }
        }
        long length = end - start + 1;
        ex.sendResponseHeaders(code, length);
        boolean drop = dropsLeft.getAndUpdate(v -> Math.max(0, v - 1)) > 0;
        byte[] chunk = new byte[16 * 1024];
        try (OutputStream out = ex.getResponseBody()) {
            long pos = start;
            long sent = 0;
            while (pos <= end) {
                int n = (int) Math.min(chunk.length, end - pos + 1);
                for (int i = 0; i < n; i++) {
                    chunk[i] = expected((int) (pos + i), current);
                }
                out.write(chunk, 0, n);
                out.flush();
                pos += n;
                sent += n;
                if (drop && sent >= 100 * 1024) {
                    throw new IOException("simulated drop");
                }
                if (chunkDelayMillis > 0) {
                    Thread.sleep(chunkDelayMillis);
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // Client went away or the drop was simulated; closing the exchange cuts the body short.
        } finally {
            ex.close();
        }
    }

    private static void status(HttpExchange ex, int code) throws IOException {
        ex.sendResponseHeaders(code, -1);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
