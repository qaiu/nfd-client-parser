package cn.qaiu.nfd.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Per-user locations, following each OS's convention. */
final class AppPaths {

    private static final String NAME = "NFDClient";

    private AppPaths() {
    }

    static Path dataDir() {
        String override = System.getProperty("nfd.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            return Path.of(appData == null || appData.isBlank() ? home : appData, NAME);
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", NAME);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return Path.of(xdg == null || xdg.isBlank() ? Path.of(home, ".local", "share").toString() : xdg, "nfd-client");
    }

    static Path taskStateDir() {
        return dataDir().resolve("tasks");
    }

    static Path defaultDownloadDir() {
        String home = System.getProperty("user.home", ".");
        Path downloads = Path.of(home, "Downloads");
        return Files.isDirectory(downloads) ? downloads.resolve("nfd") : Path.of(home, "nfd-downloads");
    }
}
