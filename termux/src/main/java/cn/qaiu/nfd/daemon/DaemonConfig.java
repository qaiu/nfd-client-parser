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

    Path effectiveDownloadDir() {
        return downloadDir != null ? downloadDir : defaultDownloadDir();
    }

    /** App-owned storage for embedded runtime, or the shared Termux folder if available. */
    Path defaultDownloadDir() {
        String embedded = System.getenv("NFD_LOCAL_DOWNLOADS");
        if (embedded != null && !embedded.isBlank()) {
            return Paths.get(embedded);
        }
        Path shared = Paths.get(System.getProperty("user.home"), "storage", "downloads");
        if (Files.isDirectory(shared)) {
            return shared.resolve("nfd");
        }
        return home.resolve("downloads");
    }

    /**
     * Switches the folder new downloads go to; blank restores the default. The folder must exist or be
     * creatable and accept a test file, so a choice Termux cannot write to fails here, not mid-download.
     */
    void useDownloadDir(String value) throws IOException {
        if (value == null || value.isBlank()) {
            downloadDir = null;
            return;
        }
        String raw = value.trim();
        if (raw.equals("~") || raw.startsWith("~/")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        Path dir = Paths.get(raw);
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("请填写完整路径，例如 /storage/emulated/0/Download/nfd");
        }
        dir = dir.normalize();
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".nfd-write-test", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException ex) {
            String hint = System.getenv("NFD_LOCAL_DOWNLOADS") != null
                    ? "。请选择应用可写入的目录"
                    : "。请确认已在 Termux 中运行 termux-setup-storage 并授权，且目录不在 Android/data 或外置 SD 卡上";
            throw new IOException("无法写入 " + dir + hint, ex);
        }
        downloadDir = dir;
    }

    boolean downloadDirShared() {
        String path = effectiveDownloadDir().toAbsolutePath().toString();
        if (System.getenv("NFD_LOCAL_DOWNLOADS") != null && path.contains("/Android/data/")) {
            return false;
        }
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
