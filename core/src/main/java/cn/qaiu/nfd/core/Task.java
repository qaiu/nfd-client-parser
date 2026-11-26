package cn.qaiu.nfd.core;

import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable task state owned by {@link DownloadManager}. */
final class Task {

    final String id;
    final Path directory;
    final Map<String, String> source;
    final long createdAt;

    volatile String fileName;
    volatile String url;
    volatile Map<String, String> headers;
    volatile TaskStatus status = TaskStatus.QUEUED;
    volatile String message = "";
    volatile Path partPath;
    volatile String destination = "";

    /** -1 until the server reports a size. */
    volatile long total = -1;
    volatile boolean rangeSupported;
    volatile String etag;
    volatile String lastModified;
    /** URL the validators were captured from; If-Range is only sent to the same URL. */
    volatile String validatorUrl;

    final List<Segment> segments = new ArrayList<>();

    volatile Future<?> runner;
    volatile boolean pauseRequested;
    volatile boolean resumeAfterStop;
    volatile boolean removeRequested;
    volatile boolean deleteFileOnRemove;
    final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();

    volatile long speed;
    long lastSampleBytes = -1;
    long lastSampleAt;
    long lastSavedAt;

    Task(String id, Path directory, Map<String, String> source, long createdAt) {
        this.id = id;
        this.directory = directory;
        this.source = Map.copyOf(source);
        this.createdAt = createdAt;
    }

    long downloaded() {
        synchronized (segments) {
            long sum = 0;
            for (Segment segment : segments) {
                sum += segment.done.get();
            }
            return sum;
        }
    }

    List<Segment> segmentsCopy() {
        synchronized (segments) {
            return new ArrayList<>(segments);
        }
    }

    void replaceSegments(List<Segment> fresh) {
        synchronized (segments) {
            segments.clear();
            segments.addAll(fresh);
        }
    }

    void disconnectAll() {
        for (HttpURLConnection connection : connections) {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
                // Disconnecting only unblocks the reader thread.
            }
        }
    }

    TaskSnapshot snapshot() {
        long done = downloaded();
        long currentSpeed = status == TaskStatus.RUNNING ? speed : 0;
        long eta = -1;
        if (status == TaskStatus.RUNNING && total > 0 && currentSpeed > 0) {
            eta = Math.max(0, (total - done) / currentSpeed);
        }
        return new TaskSnapshot(
                id,
                fileName == null || fileName.isBlank() ? "download" : fileName,
                status,
                done,
                status == TaskStatus.COMPLETED && total < 0 ? done : total,
                currentSpeed,
                eta,
                message == null ? "" : message,
                destination == null ? "" : destination,
                connections.size(),
                rangeSupported && total > 0,
                createdAt,
                source);
    }

    static final class Segment {
        final long start;
        /** Inclusive end offset; -1 means "until EOF" (size unknown or no Range support). */
        final long end;
        final AtomicLong done;

        Segment(long start, long end, long done) {
            this.start = start;
            this.end = end;
            this.done = new AtomicLong(done);
        }

        long length() {
            return end < 0 ? -1 : end - start + 1;
        }

        boolean complete() {
            return end >= 0 && done.get() >= length();
        }

        long position() {
            return start + done.get();
        }
    }
}
