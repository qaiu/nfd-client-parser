package cn.qaiu.nfd.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/** {@code ~/.nfd/daemon.properties}: token, port and download settings. */
final class DaemonConfig {

    static final int DEFAULT_PORT = 17890;
    static final String FILE = "daemon.properties";

    final Path home;
    String bind = "127.0.0.1";
    int port = DEFAULT_PORT;
    String token;
    Path downloadDir;
    int maxConcurrent = 3;
    int connections = 4;

    private DaemonConfig(Path home) {
        this.home = home;
    }

    static DaemonConfig load(Path home) throws IOException {
        DaemonConfig config = new DaemonConfig(home);
        Path file = home.resolve(FILE);
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            config.bind = p.getProperty("bind", config.bind).trim();
            config.port = parseInt(p.getProperty("port"), config.port);
            config.token = blankToNull(p.getProperty("token"));
            String dir = blankToNull(p.getProperty("downloadDir"));
            config.downloadDir = dir == null ? null : Paths.get(dir);
            config.maxConcurrent = parseInt(p.getProperty("maxConcurrent"), config.maxConcurrent);
            config.connections = parseInt(p.getProperty("connections"), config.connections);
        }
        if (config.token == null) {
            config.token = newToken();
        }
        return config;
    }

    /** Termux storage symlink when {@code termux-setup-storage} has run, else a private folder. */
    Path effectiveDownloadDir() {
        if (downloadDir != null) {
            return downloadDir;
        }
        Path shared = Paths.get(System.getProperty("user.home"), "storage", "downloads");
        if (Files.isDirectory(shared)) {
            return shared.resolve("nfd");
        }
        return home.resolve("downloads");
    }

    boolean downloadDirShared() {
        String path = effectiveDownloadDir().toAbsolutePath().toString();
        return path.contains("/storage/") || path.startsWith("/sdcard");
    }

    void save() throws IOException {
        Files.createDirectories(home);
        Properties p = new Properties();
        p.setProperty("bind", bind);
        p.setProperty("port", Integer.toString(port));
        p.setProperty("token", token);
        if (downloadDir != null) {
            p.setProperty("downloadDir", downloadDir.toString());
        }
        p.setProperty("maxConcurrent", Integer.toString(maxConcurrent));
        p.setProperty("connections", Integer.toString(connections));
        Path file = home.resolve(FILE);
        Path tmp = home.resolve(FILE + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "nfd-termux daemon; keep the token private");
        }
        restrict(tmp);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file systems (Windows test runs) keep default permissions.
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
