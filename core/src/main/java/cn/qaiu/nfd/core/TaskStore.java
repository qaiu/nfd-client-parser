package cn.qaiu.nfd.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * One {@code .properties} file per task. Headers can include share cookies, so the directory
 * should be private to the app (user profile on desktop, app-private storage on Android).
 */
final class TaskStore {

    private static final String SUFFIX = ".task";
    private final Path dir;

    TaskStore(Path dir) {
        this.dir = dir;
    }

    synchronized void save(Task task) {
        Properties p = new Properties();
        p.setProperty("id", task.id);
        p.setProperty("fileName", nz(task.fileName));
        p.setProperty("url", nz(task.url));
        p.setProperty("directory", task.directory.toString());
        p.setProperty("partPath", task.partPath == null ? "" : task.partPath.toString());
        p.setProperty("destination", nz(task.destination));
        p.setProperty("status", task.status.name());
        p.setProperty("message", nz(task.message));
        p.setProperty("total", Long.toString(task.total));
        p.setProperty("rangeSupported", Boolean.toString(task.rangeSupported));
        p.setProperty("etag", nz(task.etag));
        p.setProperty("lastModified", nz(task.lastModified));
        p.setProperty("validatorUrl", nz(task.validatorUrl));
        p.setProperty("createdAt", Long.toString(task.createdAt));
        StringBuilder segments = new StringBuilder();
        for (Task.Segment segment : task.segmentsCopy()) {
            if (segments.length() > 0) {
                segments.append(',');
            }
            segments.append(segment.start).append(':').append(segment.end).append(':').append(segment.done.get());
        }
        p.setProperty("segments", segments.toString());
        task.headers.forEach((k, v) -> p.setProperty("header." + k, v));
        task.source.forEach((k, v) -> p.setProperty("source." + k, v));
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(task.id + SUFFIX + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "nfd download task");
            }
            Path target = dir.resolve(task.id + SUFFIX);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Persistence is best effort; the download itself must not fail because of it.
        }
    }

    synchronized void delete(String id) {
        try {
            Files.deleteIfExists(dir.resolve(id + SUFFIX));
        } catch (IOException ignored) {
            // A stale task file is harmless; it is overwritten or deleted on the next run.
        }
    }

    synchronized List<Task> loadAll() {
        List<Task> tasks = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return tasks;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path file : stream) {
                Task task = load(file);
                if (task != null) {
                    tasks.add(task);
                }
            }
        } catch (IOException ignored) {
            return tasks;
        }
        tasks.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        return tasks;
    }

    private Task load(Path file) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
        String id = p.getProperty("id");
        String url = p.getProperty("url");
        String directory = p.getProperty("directory");
        if (id == null || url == null || directory == null || directory.isEmpty()) {
            return null;
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> source = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("header.")) {
                headers.put(key.substring(7), p.getProperty(key));
            } else if (key.startsWith("source.")) {
                source.put(key.substring(7), p.getProperty(key));
            }
        }
        try {
            Task task = new Task(id, Paths.get(directory), source, Long.parseLong(p.getProperty("createdAt", "0")));
            task.fileName = p.getProperty("fileName", "");
            task.url = url;
            task.headers = Map.copyOf(headers);
            String part = p.getProperty("partPath", "");
            task.partPath = part.isEmpty() ? null : Paths.get(part);
            task.destination = p.getProperty("destination", "");
            task.status = TaskStatus.valueOf(p.getProperty("status", TaskStatus.PAUSED.name()));
            task.message = p.getProperty("message", "");
            task.total = Long.parseLong(p.getProperty("total", "-1"));
            task.rangeSupported = Boolean.parseBoolean(p.getProperty("rangeSupported", "false"));
            task.etag = blankToNull(p.getProperty("etag"));
            task.lastModified = blankToNull(p.getProperty("lastModified"));
            task.validatorUrl = blankToNull(p.getProperty("validatorUrl"));
            List<Task.Segment> segments = new ArrayList<>();
            String raw = p.getProperty("segments", "");
            if (!raw.isBlank()) {
                for (String item : raw.split(",")) {
                    String[] parts = item.split(":");
                    segments.add(new Task.Segment(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])));
                }
            }
            task.replaceSegments(segments);
            return task;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
