package cn.qaiu.nfd.core;

import java.util.Map;

/**
 * Immutable view of a download task.
 *
 * @param speed       bytes per second, smoothed
 * @param etaSeconds  -1 when unknown
 * @param resumable   true when the server honours Range requests and the size is known
 * @param destination final file path once completed (or a platform URI after publishing)
 */
public record TaskSnapshot(
        String id,
        String fileName,
        TaskStatus status,
        long downloaded,
        long total,
        long speed,
        long etaSeconds,
        String message,
        String destination,
        int connections,
        boolean resumable,
        long createdAt,
        Map<String, String> source) {

    public double progress() {
        if (status == TaskStatus.COMPLETED) {
            return 1.0;
        }
        return total > 0 ? Math.min(1.0, downloaded / (double) total) : -1.0;
    }

    public String progressText() {
        String size = total > 0
                ? Formats.size(downloaded) + " / " + Formats.size(total)
                : Formats.size(downloaded);
        if (status == TaskStatus.RUNNING) {
            String eta = Formats.eta(etaSeconds);
            return size + "  " + Formats.speed(speed) + (eta.isEmpty() ? "" : "  剩余 " + eta);
        }
        return size;
    }
}
