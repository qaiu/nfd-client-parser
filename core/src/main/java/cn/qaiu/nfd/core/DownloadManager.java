package cn.qaiu.nfd.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queue of HTTP downloads shared by the desktop and Android clients.
 *
 * <ul>
 *   <li>Multi-connection Range downloads into a {@code .nfdpart} file, renamed when complete.</li>
 *   <li>Resume after pause or restart; {@code If-Range} and {@code Content-Range} checks detect a
 *       changed file and restart instead of corrupting it.</li>
 *   <li>Transient network errors retry with exponential backoff.</li>
 *   <li>Expired links (401/403/404/410, or an HTML page) are re-resolved via {@link LinkRefresher}.</li>
 *   <li>Tasks persist in {@link Options#stateDir} and come back paused after a restart.</li>
 * </ul>
 */
public final class DownloadManager implements AutoCloseable {

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    private final Object lock = new Object();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Options options;
    private final TaskStore store;
    private final ExecutorService runners;
    private final ExecutorService segmentPool;
    private final ScheduledExecutorService ticker;
    private volatile boolean closed;

    public DownloadManager(Options options) {
        this.options = options.copy();
        this.store = options.stateDir == null ? null : new TaskStore(options.stateDir);
        this.runners = Executors.newCachedThreadPool(threads("nfd-task"));
        this.segmentPool = Executors.newCachedThreadPool(threads("nfd-segment"));
        this.ticker = Executors.newSingleThreadScheduledExecutor(threads("nfd-ticker"));
        restore();
        ticker.scheduleAtFixedRate(this::tick, 500, 500, TimeUnit.MILLISECONDS);
        schedule();
    }

    public TaskSnapshot enqueue(DownloadRequest request) {
        Task task = new Task(UUID.randomUUID().toString(), request.directory(), request.source(), System.currentTimeMillis());
        task.fileName = request.fileName();
        task.url = request.url();
        task.headers = request.headers();
        synchronized (lock) {
            ensureOpen();
            tasks.put(task.id, task);
            persist(task);
        }
        notifyChanged(task);
        schedule();
        return task.snapshot();
    }

    public void pause(String id) {
        Task task;
        synchronized (lock) {
            task = tasks.get(id);
            if (task == null || !task.status.active()) {
                return;
            }
            task.resumeAfterStop = false;
            if (task.runner == null) {
                task.status = TaskStatus.PAUSED;
                persist(task);
            } else {
                task.pauseRequested = true;
            }
        }
        task.disconnectAll();
        notifyChanged(task);
    }

    public void resume(String id) {
        Task task;
        synchronized (lock) {
            task = tasks.get(id);
            if (task == null || !(task.status.canResume() || (task.pauseRequested && task.runner != null))) {
                return;
            }
            if (task.runner != null) {
                task.resumeAfterStop = true;
                return;
            }
            task.status = TaskStatus.QUEUED;
            task.message = "";
            persist(task);
        }
        notifyChanged(task);
        schedule();
    }

    public void pauseAll() {
        for (TaskSnapshot snapshot : snapshots()) {
            if (snapshot.status().active()) {
                pause(snapshot.id());
            }
        }
    }

    public void resumeAll() {
        for (TaskSnapshot snapshot : snapshots()) {
            if (snapshot.status().canResume()) {
                resume(snapshot.id());
            }
        }
    }

    /** Removes the task; {@code deleteFile} also deletes the partial or completed file. */
    public void remove(String id, boolean deleteFile) {
        Task task;
        synchronized (lock) {
            task = tasks.get(id);
            if (task == null) {
                return;
            }
            if (task.runner != null) {
                task.removeRequested = true;
                task.deleteFileOnRemove = deleteFile;
            } else {
                tasks.remove(id);
                cleanup(task, deleteFile);
            }
        }
        task.disconnectAll();
        TaskSnapshot gone = task.snapshot();
        for (Listener listener : listeners) {
            listener.onRemoved(gone.id());
        }
    }

    public void clearFinished() {
        for (TaskSnapshot snapshot : snapshots()) {
            if (snapshot.status() == TaskStatus.COMPLETED) {
                remove(snapshot.id(), false);
            }
        }
    }

    /** Records where the platform moved a completed file (for example a MediaStore URI). */
    public void setDestination(String id, String destination) {
        Task task;
        synchronized (lock) {
            task = tasks.get(id);
            if (task == null) {
                return;
            }
            task.destination = destination;
            persist(task);
        }
        notifyChanged(task);
    }

    public List<TaskSnapshot> snapshots() {
        synchronized (lock) {
            List<TaskSnapshot> list = new ArrayList<>(tasks.size());
            for (Task task : tasks.values()) {
                list.add(task.snapshot());
            }
            return list;
        }
    }

    public Optional<TaskSnapshot> get(String id) {
        synchronized (lock) {
            Task task = tasks.get(id);
            return task == null ? Optional.empty() : Optional.of(task.snapshot());
        }
    }

    public void setMaxConcurrent(int value) {
        options.maxConcurrent = Math.max(1, Math.min(value, 16));
        schedule();
    }

    /** Applies to tasks that start or resume after the change. */
    public void setConnections(int value) {
        options.connections = Math.max(1, Math.min(value, 16));
    }

    public int maxConcurrent() {
        return options.maxConcurrent;
    }

    public int connections() {
        return options.connections;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void close() {
        List<Task> running = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Task task : tasks.values()) {
                if (task.runner != null) {
                    task.pauseRequested = true;
                    running.add(task);
                }
            }
        }
        for (Task task : running) {
            task.disconnectAll();
        }
        ticker.shutdownNow();
        runners.shutdown();
        try {
            runners.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        runners.shutdownNow();
        segmentPool.shutdownNow();
        synchronized (lock) {
            for (Task task : tasks.values()) {
                // Tasks interrupted by shutdown stay QUEUED on disk, so restore() can apply resumeOnStart.
                if (task.status.active() || running.contains(task) && task.status == TaskStatus.PAUSED) {
                    task.status = TaskStatus.QUEUED;
                    task.message = "";
                }
                persist(task);
            }
        }
    }

    void update(Task task, String message) {
        task.message = message == null ? "" : message;
        synchronized (lock) {
            persist(task);
        }
        notifyChanged(task);
    }

    private void schedule() {
        List<Task> started = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            int running = 0;
            for (Task task : tasks.values()) {
                if (task.runner != null) {
                    running++;
                }
            }
            for (Task task : tasks.values()) {
                if (running >= options.maxConcurrent) {
                    break;
                }
                if (task.status == TaskStatus.QUEUED && task.runner == null) {
                    task.status = TaskStatus.RUNNING;
                    task.pauseRequested = false;
                    task.speed = 0;
                    task.lastSampleBytes = -1;
                    task.runner = runners.submit(() -> run(task));
                    persist(task);
                    started.add(task);
                    running++;
                }
            }
        }
        started.forEach(this::notifyChanged);
    }

    private void run(Task task) {
        TaskStatus result;
        try {
            result = new TransferRunner(task, options, segmentPool, this).run();
        } catch (RuntimeException ex) {
            task.message = "内部错误：" + TransferRunner.describe(ex);
            result = TaskStatus.FAILED;
        }
        boolean removed;
        synchronized (lock) {
            task.runner = null;
            removed = task.removeRequested;
            if (removed) {
                tasks.remove(task.id);
                cleanup(task, task.deleteFileOnRemove);
            } else {
                task.status = result;
                if (result == TaskStatus.PAUSED) {
                    task.message = "";
                    if (task.resumeAfterStop && !closed) {
                        task.status = TaskStatus.QUEUED;
                        task.message = "";
                    }
                }
                task.pauseRequested = false;
                task.resumeAfterStop = false;
                task.speed = 0;
                persist(task);
            }
        }
        if (!removed) {
            notifyChanged(task);
        }
        schedule();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        List<Task> running = new ArrayList<>();
        synchronized (lock) {
            for (Task task : tasks.values()) {
                if (task.status == TaskStatus.RUNNING) {
                    running.add(task);
                }
            }
        }
        for (Task task : running) {
            long bytes = task.downloaded();
            if (task.lastSampleBytes >= 0 && now > task.lastSampleAt) {
                long instant = (bytes - task.lastSampleBytes) * 1000 / (now - task.lastSampleAt);
                task.speed = task.speed == 0 ? instant : (long) (instant * 0.3 + task.speed * 0.7);
            }
            task.lastSampleBytes = bytes;
            task.lastSampleAt = now;
            if (now - task.lastSavedAt > 2000) {
                synchronized (lock) {
                    persist(task);
                }
            }
            notifyChanged(task);
        }
    }

    private void restore() {
        if (store == null) {
            return;
        }
        for (Task task : store.loadAll()) {
            if (task.status.active()) {
                task.status = options.resumeOnStart ? TaskStatus.QUEUED : TaskStatus.PAUSED;
                if (task.status == TaskStatus.PAUSED && task.message.isEmpty()) {
                    task.message = "上次退出时未完成";
                }
            }
            if (task.status != TaskStatus.COMPLETED && task.downloaded() > 0
                    && (task.partPath == null || !Files.exists(task.partPath))) {
                task.replaceSegments(List.of());
            }
            tasks.put(task.id, task);
        }
    }

    private void cleanup(Task task, boolean deleteFile) {
        if (store != null) {
            store.delete(task.id);
        }
        try {
            if (task.partPath != null && task.status != TaskStatus.COMPLETED) {
                Files.deleteIfExists(task.partPath);
            }
            if (deleteFile && task.status == TaskStatus.COMPLETED && !task.destination.isEmpty()
                    && !task.destination.contains("://")) {
                Files.deleteIfExists(Path.of(task.destination));
            }
        } catch (IOException | RuntimeException ignored) {
            // The task is gone either way; leftover files can be deleted by hand.
        }
    }

    private void persist(Task task) {
        task.lastSavedAt = System.currentTimeMillis();
        if (store != null) {
            store.save(task);
        }
    }

    private void notifyChanged(Task task) {
        if (listeners.isEmpty()) {
            return;
        }
        TaskSnapshot snapshot = task.snapshot();
        for (Listener listener : listeners) {
            try {
                listener.onChanged(snapshot);
            } catch (RuntimeException ignored) {
                // A broken UI listener must not stop downloads.
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("下载管理器已关闭");
        }
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public interface Listener {
        /** Called from download threads; UIs must hop to their own thread. */
        void onChanged(TaskSnapshot task);

        default void onRemoved(String id) {
        }
    }

    public static final class Options {
        Path stateDir;
        volatile int maxConcurrent = 3;
        volatile int connections = 4;
        long minSegmentSize = 4L * 1024 * 1024;
        int maxRetries = 6;
        long retryBaseMillis = 1000;
        int connectTimeoutMillis = 15_000;
        int readTimeoutMillis = 30_000;
        String userAgent = DEFAULT_USER_AGENT;
        LinkRefresher refresher;
        boolean resumeOnStart;

        /** Directory for task state; {@code null} keeps tasks in memory only. */
        public Options stateDir(Path value) {
            stateDir = value;
            return this;
        }

        public Options maxConcurrent(int value) {
            maxConcurrent = Math.max(1, value);
            return this;
        }

        public Options connections(int value) {
            connections = Math.max(1, value);
            return this;
        }

        public Options minSegmentSize(long value) {
            minSegmentSize = Math.max(1, value);
            return this;
        }

        public Options maxRetries(int value) {
            maxRetries = Math.max(0, value);
            return this;
        }

        public Options retryBaseMillis(long value) {
            retryBaseMillis = Math.max(1, value);
            return this;
        }

        public Options timeouts(int connectMillis, int readMillis) {
            connectTimeoutMillis = connectMillis;
            readTimeoutMillis = readMillis;
            return this;
        }

        public Options userAgent(String value) {
            userAgent = value == null || value.isBlank() ? DEFAULT_USER_AGENT : value;
            return this;
        }

        public Options refresher(LinkRefresher value) {
            refresher = value;
            return this;
        }

        public Options resumeOnStart(boolean value) {
            resumeOnStart = value;
            return this;
        }

        Options copy() {
            Options copy = new Options();
            copy.stateDir = stateDir;
            copy.maxConcurrent = maxConcurrent;
            copy.connections = connections;
            copy.minSegmentSize = minSegmentSize;
            copy.maxRetries = maxRetries;
            copy.retryBaseMillis = retryBaseMillis;
            copy.connectTimeoutMillis = connectTimeoutMillis;
            copy.readTimeoutMillis = readTimeoutMillis;
            copy.userAgent = userAgent;
            copy.refresher = refresher;
            copy.resumeOnStart = resumeOnStart;
            return copy;
        }
    }
}
