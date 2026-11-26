package cn.qaiu.nfd.desktop;

import java.nio.file.Path;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** User settings in {@link Preferences} (registry on Windows, plist on macOS, ~/.java on Linux). */
final class Settings {

    private final Preferences prefs;

    Settings(Preferences prefs) {
        this.prefs = prefs;
    }

    static Settings load() {
        return new Settings(Preferences.userRoot().node("cn/qaiu/nfd-client"));
    }

    Path downloadDir() {
        String value = prefs.get("downloadDir", "");
        return value.isBlank() ? AppPaths.defaultDownloadDir() : Path.of(value);
    }

    void downloadDir(Path dir) {
        prefs.put("downloadDir", dir.toAbsolutePath().toString());
        flush();
    }

    int maxConcurrent() {
        return clamp(prefs.getInt("maxConcurrent", 3), 1, 8);
    }

    int connections() {
        return clamp(prefs.getInt("connections", 4), 1, 16);
    }

    void downloads(int maxConcurrent, int connections) {
        prefs.putInt("maxConcurrent", clamp(maxConcurrent, 1, 8));
        prefs.putInt("connections", clamp(connections, 1, 16));
        flush();
    }

    String lastLink() {
        return prefs.get("lastLink", "");
    }

    void lastLink(String link) {
        prefs.put("lastLink", link == null ? "" : link);
    }

    double[] window() {
        return new double[] {prefs.getDouble("width", 1180), prefs.getDouble("height", 720)};
    }

    void window(double width, double height) {
        prefs.putDouble("width", width);
        prefs.putDouble("height", height);
        flush();
    }

    private void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException ignored) {
            // Settings are a convenience; a read-only preference store must not break downloads.
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
