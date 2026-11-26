package cn.qaiu.nfd.desktop.download;

import java.nio.file.Path;

public record DownloadTask(
        String id,
        String fileName,
        Status status,
        long downloaded,
        long total,
        String message,
        Path destination) {

    public enum Status {
        QUEUED, RUNNING, PAUSED, COMPLETED, FAILED
    }

    public double fraction() {
        if (total <= 0) {
            return status == Status.COMPLETED ? 1.0 : -1.0;
        }
        return Math.min(1.0, downloaded / (double) total);
    }
}
