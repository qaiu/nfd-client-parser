package cn.qaiu.nfd.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Runs one task until it completes, fails or is paused.
 *
 * <p>Uses {@code new URL(String)} on purpose: {@code URI.create} rejects the unescaped characters
 * some CDNs put into signed download URLs.
 */
@SuppressWarnings("deprecation")
final class TransferRunner {

    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_REFRESHES = 3;
    private static final int MAX_RESTARTS = 3;
    private static final int BUFFER = 64 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    private final Task task;
    private final DownloadManager.Options options;
    private final ExecutorService segmentPool;
    private final DownloadManager manager;

    private volatile boolean abort;

    TransferRunner(Task task, DownloadManager.Options options, ExecutorService segmentPool, DownloadManager manager) {
        this.task = task;
        this.options = options;
        this.segmentPool = segmentPool;
        this.manager = manager;
    }

    /** Returns the terminal status; the manager applies it under its lock. */
    TaskStatus run() {
        int failures = 0;
        int refreshes = 0;
        int restarts = 0;
        while (true) {
            long before = task.downloaded();
            abort = false;
            try {
                checkPaused();
                if (task.url == null || task.url.isBlank()) {
                    TaskStatus resolved = resolveInitial();
                    if (resolved != null) {
                        return resolved;
                    }
                }
                if (needsProbe()) {
                    probe();
                }
                transfer();
                finish();
                return TaskStatus.COMPLETED;
            } catch (Paused paused) {
                return TaskStatus.PAUSED;
            } catch (Failure failure) {
                if (stopRequested()) {
                    return TaskStatus.PAUSED;
                }
                boolean progressed = task.downloaded() > before;
                if (progressed) {
                    failures = 0;
                    refreshes = 0;
                }
                switch (failure.kind) {
                    case EXPIRED -> {
                        LinkRefresher refresher = options.refresher;
                        if (refresher == null || refreshes >= MAX_REFRESHES) {
                            return fail(failure.getMessage());
                        }
                        refreshes++;
                        manager.update(task, "链接已失效，正在重新解析…");
                        try {
                            LinkRefresher.ResolvedLink link = refresher.refresh(task.snapshot());
                            if (link == null || link.url() == null || link.url().isBlank()) {
                                return fail("重新解析没有返回下载地址");
                            }
                            task.url = link.url();
                            task.headers = link.headers();
                            manager.update(task, "已重新解析，继续下载");
                        } catch (Exception ex) {
                            if (stopRequested()) {
                                return TaskStatus.PAUSED;
                            }
                            return fail("重新解析失败：" + describe(ex));
                        }
                    }
                    case RESTART -> {
                        if (++restarts > MAX_RESTARTS) {
                            return fail(failure.getMessage());
                        }
                        resetProgress();
                        manager.update(task, failure.getMessage() + "，从头下载");
                    }
                    case TRANSIENT -> {
                        if (++failures > options.maxRetries) {
                            return fail(failure.getMessage() + "（已重试 " + options.maxRetries + " 次）");
                        }
                        long delay = Math.min(options.retryBaseMillis << Math.min(failures - 1, 5), 30_000L);
                        manager.update(task, String.format(Locale.ROOT, "%s，%d 秒后第 %d 次重试",
                                failure.getMessage(), Math.max(1, delay / 1000), failures));
                        try {
                            sleep(delay);
                        } catch (Paused paused) {
                            return TaskStatus.PAUSED;
                        }
                    }
                    default -> {
                        return fail(failure.getMessage());
                    }
                }
            }
        }
    }

    /** Tasks queued without a URL are resolved when they start, so "download all" stays cheap. */
    private TaskStatus resolveInitial() {
        LinkRefresher refresher = options.refresher;
        if (refresher == null) {
            return fail("缺少下载地址");
        }
        manager.update(task, "正在解析下载地址…");
        try {
            LinkRefresher.ResolvedLink link = refresher.refresh(task.snapshot());
            if (link == null || link.url() == null || link.url().isBlank()) {
                return fail("没有解析到下载地址");
            }
            task.url = link.url();
            task.headers = link.headers();
        } catch (Exception ex) {
            return stopRequested() ? TaskStatus.PAUSED : fail(describe(ex));
        }
        manager.update(task, "");
        return null;
    }

    private TaskStatus fail(String message) {
        task.message = message;
        return TaskStatus.FAILED;
    }

    private boolean needsProbe() {
        if (task.partPath == null || !task.rangeSupported || task.total <= 0) {
            return true;
        }
        List<Task.Segment> segments = task.segmentsCopy();
        if (segments.isEmpty()) {
            return true;
        }
        return task.downloaded() > 0 && !Files.exists(task.partPath);
    }

    private void probe() {
        HttpURLConnection connection = open("bytes=0-", false);
        long total;
        boolean ranges;
        try {
            int code = code(connection);
            if (code == 416) {
                total = 0;
                ranges = false;
            } else {
                if (code != 200 && code != 206) {
                    throw httpFailure(code);
                }
                rejectHtml(connection);
                String disposition = fileNameFromDisposition(connection.getHeaderField("Content-Disposition"));
                if (disposition != null && betterName(task.fileName, disposition)) {
                    task.fileName = disposition;
                }
                if (code == 206) {
                    Matcher range = CONTENT_RANGE.matcher(nz(connection.getHeaderField("Content-Range")));
                    total = range.find() && !range.group(3).equals("*") ? Long.parseLong(range.group(3)) : -1;
                    ranges = total > 0;
                } else {
                    total = connection.getContentLengthLong();
                    ranges = false;
                }
                task.etag = weakToNull(connection.getHeaderField("ETag"));
                task.lastModified = connection.getHeaderField("Last-Modified");
                task.validatorUrl = task.url;
            }
        } finally {
            release(connection);
        }
        task.total = total;
        task.rangeSupported = ranges;
        List<Task.Segment> plan = new ArrayList<>();
        if (ranges) {
            long segmentSize = Math.max(options.minSegmentSize, ceilDiv(total, (long) options.connections * 4));
            for (long start = 0; start < total; start += segmentSize) {
                plan.add(new Task.Segment(start, Math.min(total, start + segmentSize) - 1, 0));
            }
        } else {
            plan.add(new Task.Segment(0, total > 0 ? total - 1 : -1, 0));
        }
        task.replaceSegments(plan);
        try {
            Files.createDirectories(task.directory);
            if (task.partPath == null) {
                task.partPath = Formats.uniqueTarget(task.directory, displayName() + ".nfdpart");
            }
            try (FileChannel channel = FileChannel.open(task.partPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.truncate(0);
            }
        } catch (IOException ex) {
            throw new Failure(Kind.FATAL, "无法创建文件：" + describe(ex));
        }
        manager.update(task, ranges ? "" : (total == 0 ? "" : "服务器不支持断点续传，暂停后将从头下载"));
    }

    private void transfer() {
        List<Task.Segment> pending = new ArrayList<>();
        for (Task.Segment segment : task.segmentsCopy()) {
            if (!segment.complete()) {
                pending.add(segment);
            }
        }
        if (task.total == 0 || pending.isEmpty()) {
            return;
        }
        Queue<Task.Segment> queue = new ConcurrentLinkedQueue<>(pending);
        int workers = task.rangeSupported ? Math.max(1, Math.min(options.connections, pending.size())) : 1;
        AtomicReference<RuntimeException> firstError = new AtomicReference<>();
        abort = false;
        try (FileChannel channel = FileChannel.open(task.partPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(segmentPool.submit(() -> {
                    Task.Segment segment;
                    while (!abort && (segment = queue.poll()) != null) {
                        try {
                            download(segment, channel);
                        } catch (RuntimeException ex) {
                            if (firstError.compareAndSet(null, ex)) {
                                abort = true;
                                task.disconnectAll();
                            }
                            return;
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    abort = true;
                    task.disconnectAll();
                    throw new Paused();
                } catch (ExecutionException ex) {
                    firstError.compareAndSet(null, new Failure(Kind.FATAL, describe(ex.getCause())));
                }
            }
        } catch (IOException ex) {
            throw new Failure(Kind.FATAL, "无法写入文件：" + describe(ex));
        }
        RuntimeException error = firstError.get();
        if (error != null) {
            if (stopRequested()) {
                throw new Paused();
            }
            throw error;
        }
        checkPaused();
    }

    private void download(Task.Segment segment, FileChannel channel) {
        boolean untilEof = segment.end < 0;
        while (untilEof || !segment.complete()) {
            checkPaused();
            long position = segment.position();
            String range = null;
            if (task.rangeSupported) {
                range = "bytes=" + position + "-" + segment.end;
            }
            boolean ifRange = task.rangeSupported && task.url.equals(task.validatorUrl)
                    && (task.etag != null || task.lastModified != null);
            HttpURLConnection connection = open(range, ifRange);
            try {
                int code = code(connection);
                if (task.rangeSupported) {
                    if (code == 200 || code == 416) {
                        throw new Failure(Kind.RESTART, "服务器上的文件已变化或不再支持断点续传");
                    }
                    if (code != 206) {
                        throw httpFailure(code);
                    }
                    Matcher matcher = CONTENT_RANGE.matcher(nz(connection.getHeaderField("Content-Range")));
                    if (!matcher.find() || Long.parseLong(matcher.group(1)) != position
                            || (!matcher.group(3).equals("*") && Long.parseLong(matcher.group(3)) != task.total)) {
                        throw new Failure(Kind.RESTART, "服务器返回的分段与本地记录不一致");
                    }
                } else {
                    if (code != 200 && code != 206) {
                        throw httpFailure(code);
                    }
                    rejectHtml(connection);
                }
                long remaining = untilEof ? Long.MAX_VALUE : segment.end - position + 1;
                byte[] buffer = new byte[BUFFER];
                try (InputStream in = connection.getInputStream()) {
                    while (remaining > 0) {
                        checkPaused();
                        int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) {
                            break;
                        }
                        write(channel, buffer, read, position);
                        position += read;
                        remaining -= read;
                        segment.done.addAndGet(read);
                    }
                }
                if (untilEof) {
                    if (task.total > 0 && segment.done.get() < task.total) {
                        throw new Failure(Kind.TRANSIENT, "连接提前断开");
                    }
                    if (task.total < 0) {
                        task.total = segment.done.get();
                    }
                    return;
                }
                if (remaining > 0) {
                    throw new Failure(Kind.TRANSIENT, "连接提前断开");
                }
            } catch (IOException ex) {
                checkPaused();
                throw classify(ex);
            } finally {
                release(connection);
            }
        }
    }

    private void finish() {
        long done = task.downloaded();
        if (task.total > 0 && done != task.total) {
            throw new Failure(Kind.TRANSIENT, "下载大小与服务器不一致");
        }
        if (task.total < 0) {
            task.total = done;
        }
        Path target = Formats.uniqueTarget(task.directory, displayName());
        try {
            if (task.partPath == null || !Files.exists(task.partPath)) {
                Files.createDirectories(task.directory);
                Files.createFile(target);
            } else {
                try {
                    Files.move(task.partPath, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(task.partPath, target);
                }
            }
        } catch (IOException ex) {
            throw new Failure(Kind.FATAL, "无法保存文件：" + describe(ex));
        }
        task.destination = target.toString();
        task.message = "";
    }

    private void resetProgress() {
        task.total = -1;
        task.rangeSupported = false;
        task.etag = null;
        task.lastModified = null;
        task.validatorUrl = null;
        task.replaceSegments(List.of());
    }

    private HttpURLConnection open(String range, boolean ifRange) {
        String current = task.url;
        String originHost = host(current);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkPaused();
            HttpURLConnection connection;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
            } catch (MalformedURLException ex) {
                throw new Failure(Kind.EXPIRED, "下载地址无效：" + current);
            } catch (IOException ex) {
                throw classify(ex);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(options.connectTimeoutMillis);
            connection.setReadTimeout(options.readTimeoutMillis);
            connection.setUseCaches(false);
            boolean sameHost = host(current).equalsIgnoreCase(originHost);
            boolean hasAgent = false;
            for (Map.Entry<String, String> header : task.headers.entrySet()) {
                String name = header.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("range") || lower.equals("host") || lower.equals("accept-encoding")
                        || lower.equals("content-length") || lower.equals("if-range")
                        || (!sameHost && lower.equals("authorization"))) {
                    continue;
                }
                hasAgent |= lower.equals("user-agent");
                connection.setRequestProperty(name, header.getValue());
            }
            if (!hasAgent) {
                connection.setRequestProperty("User-Agent", options.userAgent);
            }
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) {
                connection.setRequestProperty("Range", range);
                if (ifRange) {
                    connection.setRequestProperty("If-Range", task.etag != null ? task.etag : task.lastModified);
                }
            }
            task.connections.add(connection);
            int code = code(connection);
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                release(connection);
                if (location == null || location.isBlank()) {
                    throw new Failure(Kind.EXPIRED, "服务器重定向缺少地址");
                }
                try {
                    current = new URL(new URL(current), location.trim()).toString();
                } catch (MalformedURLException ex) {
                    throw new Failure(Kind.EXPIRED, "服务器重定向地址无效");
                }
                continue;
            }
            return connection;
        }
        throw new Failure(Kind.EXPIRED, "重定向次数过多");
    }

    private int code(HttpURLConnection connection) {
        try {
            return connection.getResponseCode();
        } catch (IOException ex) {
            release(connection);
            checkPaused();
            throw classify(ex);
        }
    }

    private void release(HttpURLConnection connection) {
        task.connections.remove(connection);
        connection.disconnect();
    }

    private void rejectHtml(HttpURLConnection connection) {
        String type = nz(connection.getContentType()).toLowerCase(Locale.ROOT);
        String name = displayName().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/html") && !name.endsWith(".html") && !name.endsWith(".htm")) {
            throw new Failure(Kind.EXPIRED, "服务器返回了网页而不是文件，链接可能已失效");
        }
    }

    private static void write(FileChannel channel, byte[] buffer, int length, long position) {
        ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, length);
        long at = position;
        try {
            while (bytes.hasRemaining()) {
                at += channel.write(bytes, at);
            }
        } catch (IOException ex) {
            throw new Failure(Kind.FATAL, "写入文件失败：" + describe(ex));
        }
    }

    private void sleep(long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (stopRequested()) {
                throw new Paused();
            }
            try {
                Thread.sleep(Math.min(100, Math.max(1, until - System.currentTimeMillis())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Paused();
            }
        }
    }

    private boolean stopRequested() {
        return task.pauseRequested || task.removeRequested || Thread.currentThread().isInterrupted();
    }

    private void checkPaused() {
        if (stopRequested()) {
            throw new Paused();
        }
        if (abort) {
            throw new Failure(Kind.TRANSIENT, "已中止");
        }
    }

    private String displayName() {
        return task.fileName == null || task.fileName.isBlank() ? "download" : task.fileName;
    }

    private static Failure httpFailure(int code) {
        if (code == 401 || code == 403 || code == 404 || code == 410) {
            return new Failure(Kind.EXPIRED, "下载链接已失效（HTTP " + code + "）");
        }
        if (code == 408 || code == 425 || code == 429 || code >= 500) {
            return new Failure(Kind.TRANSIENT, "服务器暂时不可用（HTTP " + code + "）");
        }
        return new Failure(Kind.FATAL, "服务器返回 HTTP " + code);
    }

    private static Failure classify(IOException ex) {
        if (ex instanceof UnknownHostException) {
            return new Failure(Kind.TRANSIENT, "无法解析域名，请检查网络");
        }
        if (ex instanceof ConnectException || ex instanceof NoRouteToHostException) {
            return new Failure(Kind.TRANSIENT, "无法连接服务器");
        }
        if (ex instanceof InterruptedIOException) {
            return new Failure(Kind.TRANSIENT, "网络超时");
        }
        if (ex instanceof SocketException || ex instanceof SSLException || ex instanceof java.io.EOFException) {
            return new Failure(Kind.TRANSIENT, "网络连接中断");
        }
        return new Failure(Kind.TRANSIENT, "网络错误：" + describe(ex));
    }

    /** Share listings often truncate long names and drop the extension; the server's name is then better. */
    static boolean betterName(String current, String fromServer) {
        if (current == null || current.isBlank() || current.equals("download")) {
            return true;
        }
        return !hasExtension(current) && hasExtension(fromServer);
    }

    private static boolean hasExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 && name.length() - dot <= 10 && !name.substring(dot + 1).contains(" ");
    }

    static String fileNameFromDisposition(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher extended = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE).matcher(header);
        if (extended.find()) {
            String charset = extended.group(1).isBlank() ? "UTF-8" : extended.group(1).trim();
            try {
                return clean(URLDecoder.decode(extended.group(2).trim().replace("+", "%2B"), charset));
            } catch (Exception ignored) {
                // Fall through to the plain filename parameter.
            }
        }
        Matcher plain = Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", Pattern.CASE_INSENSITIVE).matcher(header);
        if (plain.find()) {
            String value = plain.group(2) != null ? plain.group(2) : plain.group(1).trim();
            if (!value.isEmpty() && value.chars().allMatch(c -> c < 256) && !value.chars().allMatch(c -> c < 128)) {
                value = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            }
            if (value.contains("%")) {
                try {
                    value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    // Not percent-encoded after all.
                }
            }
            return clean(value);
        }
        return null;
    }

    private static String clean(String value) {
        String trimmed = value == null ? "" : value.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        trimmed = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String weakToNull(String etag) {
        return etag == null || etag.startsWith("W/") ? null : etag;
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (MalformedURLException ex) {
            return "";
        }
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    enum Kind { TRANSIENT, EXPIRED, RESTART, FATAL }

    static final class Failure extends RuntimeException {
        final Kind kind;

        Failure(Kind kind, String message) {
            super(message, null, false, false);
            this.kind = kind;
        }
    }

    static final class Paused extends RuntimeException {
        Paused() {
            super("paused", null, false, false);
        }
    }
}
