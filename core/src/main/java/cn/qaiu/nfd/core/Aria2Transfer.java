package cn.qaiu.nfd.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** One HTTP transfer via the standalone aria2c command. aria2 owns its resume file. */
@SuppressWarnings("deprecation")
final class Aria2Transfer {
    // Use the Java downloader for arbitrary headers, in particular Cookie and Authorization.
    private static final Set<String> PUBLIC_HEADERS = Set.of("accept", "accept-language", "referer", "user-agent");
    private static final Pattern PROGRESS = Pattern.compile("\\[#\\p{XDigit}+ (\\d+)B/(\\d+)B\\(\\d+%\\) CN:(\\d+).*]");

    private Aria2Transfer() {}

    static String skipReason(Task task, DownloadManager.Options options) {
        if (options.aria2 == null) return "unavailable";
        if (!task.rangeSupported) return "no-range";
        if (!task.aria2 && task.downloaded() != 0) return "existing-progress";
        if (!task.aria2 && options.connections < 2) return "single-connection";
        if (!task.aria2 && task.total < 2 * splitSize(options) * 1024 * 1024) return "small-file";
        if (!safeLine(task.url) || !safeLine(task.partPath.getFileName().toString())) return "unsupported-input";
        try {
            URL url = new URL(task.url);
            if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https")) || url.getUserInfo() != null) {
                return "unsupported-input";
            }
        } catch (MalformedURLException ex) {
            return "unsupported-input";
        }
        return task.headers.entrySet().stream().allMatch(header ->
                PUBLIC_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT)) && safeLine(header.getValue()))
                ? null : "special-headers";
    }

    static Path controlFile(Path part) {
        return part.resolveSibling(part.getFileName() + ".aria2");
    }

    static boolean run(Task task, DownloadManager.Options options) {
        Path part = task.partPath;
        int connections = Math.min(16, options.connections);
        List<String> args = new ArrayList<>(List.of(options.aria2.toString(),
                "--no-conf", "--enable-rpc=false", "--no-netrc=true", "--continue=true",
                "--auto-file-renaming=false", "--allow-overwrite=false", "--file-allocation=none",
                "--http-accept-gzip=false",
                "--max-tries=1", "--connect-timeout=" + seconds(options.connectTimeoutMillis),
                "--timeout=" + seconds(options.readTimeoutMillis), "--check-certificate=true",
                "--max-connection-per-server=" + connections, "--split=" + connections,
                "--min-split-size=" + splitSize(options) + "M", "--auto-save-interval=1",
                "--stop-with-process=" + ProcessHandle.current().pid(), "--human-readable=false",
                "--truncate-console-readout=false", "--enable-color=false", "--console-log-level=error",
                "--show-console-readout=true", "--summary-interval=1", "--download-result=hide",
                "--dir=" + part.getParent(), "--input-file=-"));
        if (options.aria2Ca != null) {
            args.add("--ca-certificate=" + options.aria2Ca);
        }
        args.add("--user-agent=" + options.userAgent);
        task.headers.forEach((name, value) -> {
            if (name.equalsIgnoreCase("User-Agent")) {
                args.add("--user-agent=" + value);
            } else {
                args.add("--header=" + name + ": " + value);
            }
        });

        Process process = null;
        Thread output = null;
        Task.Segment progress = task.segmentsCopy().get(0);
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            Process child = process;
            output = new Thread(() -> readProgress(child, task, progress), "nfd-aria2-progress");
            output.setDaemon(true);
            output.start();
            try (var input = process.getOutputStream()) {
                // aria2 ignores the global --out for URLs coming from an input file.
                input.write((task.url + "\n  out=" + part.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            while (!task.pauseRequested && !task.removeRequested) {
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    boolean complete = process.exitValue() == 0 && !Files.exists(controlFile(part))
                            && Files.size(part) == task.total;
                    options.event(task, "aria2-exit " + process.exitValue());
                    if (complete) {
                        progress.done.set(task.total);
                    }
                    return complete;
                }
            }
            return false;
        } catch (IOException ex) {
            options.event(task, "aria2-io");
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor();
                    }
                } catch (InterruptedException ex) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            if (output != null) {
                try {
                    output.join(500);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            task.aria2Connections = 0;
        }
    }

    private static void readProgress(Process process, Task task, Task.Segment progress) {
        try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = lines.readLine()) != null; ) {
                var match = PROGRESS.matcher(line.strip());
                if (match.matches() && Long.parseLong(match.group(2)) == task.total) {
                    progress.done.accumulateAndGet(Long.parseLong(match.group(1)), Math::max);
                    task.aria2Connections = Integer.parseInt(match.group(3));
                    task.peakConnections.accumulateAndGet(task.aria2Connections, Math::max);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // Progress reports are optional. Completion is checked against the file and the exit code.
        }
    }

    private static boolean safeLine(String value) {
        return value != null && value.chars().noneMatch(c -> c <= 0x1f || c == 0x7f);
    }

    private static long splitSize(DownloadManager.Options options) {
        return Math.max(1, Math.min(1024, options.minSegmentSize / (1024 * 1024)));
    }

    private static int seconds(int millis) {
        return Math.max(1, (int) Math.ceil(millis / 1000.0));
    }
}
