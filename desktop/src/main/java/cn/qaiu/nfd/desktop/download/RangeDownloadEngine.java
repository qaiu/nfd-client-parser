package cn.qaiu.nfd.desktop.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP downloader with pause and resume via {@code Range}.
 * Partial bytes stay in a {@code .nfdpart} file next to the destination.
 */
public final class RangeDownloadEngine implements AutoCloseable {

    private final HttpClient httpClient;
    private final ExecutorService workers = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "nfd-download");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Path downloadDirectory;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RangeDownloadEngine(Path downloadDirectory) {
        this.downloadDirectory = downloadDirectory;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public void setDownloadDirectory(Path downloadDirectory) {
        this.downloadDirectory = downloadDirectory;
    }

    public Path downloadDirectory() {
        return downloadDirectory;
    }

    public DownloadTask enqueue(String fileName, String url, Map<String, String> headers) {
        if (closed.get()) {
            throw new IllegalStateException("下载器已关闭");
        }
        Task task = new Task(UUID.randomUUID().toString(), safeName(fileName), url, copyHeaders(headers));
        tasks.add(task);
        workers.submit(() -> run(task));
        notifyListeners();
        return task.snapshot();
    }

    public void pause(String id) {
        Task task = find(id);
        if (task == null) {
            return;
        }
        task.requestPause();
    }

    public void resume(String id) {
        Task task = find(id);
        if (task == null || closed.get()) {
            return;
        }
        if (task.status != DownloadTask.Status.PAUSED && task.status != DownloadTask.Status.FAILED) {
            return;
        }
        task.pauseRequested.set(false);
        task.message = "";
        workers.submit(() -> run(task));
        notifyListeners();
    }

    public List<DownloadTask> tasks() {
        List<DownloadTask> snapshots = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            snapshots.add(task.snapshot());
        }
        return List.copyOf(snapshots);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Task task : tasks) {
            task.requestPause();
        }
        workers.shutdownNow();
    }

    private void run(Task task) {
        if (task.pauseRequested.get() || closed.get()) {
            task.status = DownloadTask.Status.PAUSED;
            notifyListeners();
            return;
        }
        task.status = DownloadTask.Status.RUNNING;
        notifyListeners();
        Path directory = downloadDirectory;
        try {
            Files.createDirectories(directory);
            if (task.partial == null) {
                task.partial = directory.resolve(task.fileName + ".nfdpart");
                task.destination = directory.resolve(uniqueFinalName(directory, task.fileName));
            }
            long existing = Files.exists(task.partial) ? Files.size(task.partial) : 0L;
            if (task.total > 0 && existing >= task.total) {
                finish(task);
                return;
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(task.url)).GET();
            boolean hasUserAgent = false;
            for (Map.Entry<String, String> header : task.headers.entrySet()) {
                if (header.getKey() == null || header.getValue() == null) {
                    continue;
                }
                if (header.getKey().equalsIgnoreCase("range") || header.getKey().equalsIgnoreCase("host")) {
                    continue;
                }
                if (header.getKey().equalsIgnoreCase("user-agent")) {
                    hasUserAgent = true;
                }
                try {
                    builder.header(header.getKey(), header.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Restricted headers are skipped; the parser-supplied ones that matter are not restricted.
                }
            }
            if (!hasUserAgent) {
                builder.header("User-Agent", "NFDClient/0.1");
            }
            if (existing > 0) {
                builder.header("Range", "bytes=" + existing + "-");
            }
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            if (code == 416) {
                finish(task);
                return;
            }
            boolean restart = code == 200 && existing > 0;
            if (restart) {
                existing = 0L;
                Files.deleteIfExists(task.partial);
            }
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            task.total = totalLength(response, code, existing);
            task.downloaded = existing;
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(
                         task.partial,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE,
                         existing == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                task.stream = input;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (task.pauseRequested.get() || closed.get()) {
                        task.status = DownloadTask.Status.PAUSED;
                        task.message = "已暂停";
                        return;
                    }
                    output.write(buffer, 0, read);
                    task.downloaded += read;
                    notifyListeners();
                }
            } finally {
                task.stream = null;
            }
            if (task.pauseRequested.get() || closed.get()) {
                task.status = DownloadTask.Status.PAUSED;
                task.message = "已暂停";
                return;
            }
            finish(task);
        } catch (Exception ex) {
            if (task.pauseRequested.get() || closed.get()) {
                task.status = DownloadTask.Status.PAUSED;
                task.message = "已暂停";
            } else {
                task.status = DownloadTask.Status.FAILED;
                task.message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
        } finally {
            notifyListeners();
        }
    }

    private void finish(Task task) throws IOException {
        if (task.partial != null && Files.exists(task.partial)) {
            try {
                Files.move(task.partial, task.destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(task.partial, task.destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (task.destination != null && Files.exists(task.destination)) {
            task.downloaded = Files.size(task.destination);
            if (task.total < task.downloaded) {
                task.total = task.downloaded;
            }
        }
        task.status = DownloadTask.Status.COMPLETED;
        task.message = "完成";
    }

    private static long totalLength(HttpResponse<?> response, int code, long existing) {
        String contentRange = response.headers().firstValue("Content-Range").orElse("");
        int slash = contentRange.lastIndexOf('/');
        if (slash >= 0) {
            String tail = contentRange.substring(slash + 1).trim();
            if (!tail.equals("*") && !tail.isEmpty()) {
                try {
                    return Long.parseLong(tail);
                } catch (NumberFormatException ignored) {
                    // Fall through to Content-Length.
                }
            }
        }
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength >= 0 && code == 200) {
            return contentLength;
        }
        if (contentLength >= 0 && code == 206) {
            return existing + contentLength;
        }
        return -1L;
    }

    private static String uniqueFinalName(Path directory, String fileName) throws IOException {
        if (!Files.exists(directory.resolve(fileName))) {
            return fileName;
        }
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!Files.exists(directory.resolve(candidate))) {
                return candidate;
            }
        }
        throw new IOException("无法分配文件名");
    }

    static String safeName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "download" : fileName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.equals(".") || name.equals("..") || name.isBlank()) {
            name = "download";
        }
        if (name.length() > 180) {
            name = name.substring(0, 180);
        }
        return name;
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        return Map.copyOf(copy);
    }

    private Task find(String id) {
        for (Task task : tasks) {
            if (task.id.equals(id)) {
                return task;
            }
        }
        return null;
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A listener must not stop the transfer.
            }
        }
    }

    private static final class Task {
        final String id;
        final String fileName;
        final String url;
        final Map<String, String> headers;
        final AtomicBoolean pauseRequested = new AtomicBoolean();
        volatile DownloadTask.Status status = DownloadTask.Status.QUEUED;
        volatile long downloaded;
        volatile long total = -1L;
        volatile String message = "";
        volatile Path partial;
        volatile Path destination;
        volatile InputStream stream;

        Task(String id, String fileName, String url, Map<String, String> headers) {
            this.id = id;
            this.fileName = fileName;
            this.url = url;
            this.headers = headers;
        }

        void requestPause() {
            pauseRequested.set(true);
            InputStream current = stream;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Closing unblocks the reader.
                }
            }
        }

        DownloadTask snapshot() {
            return new DownloadTask(id, fileName, status, downloaded, total, message, destination);
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }
}
