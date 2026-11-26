package cn.qaiu.nfd.desktop;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Launcher entry point. It does not extend {@code Application}, so {@code --self-test} and
 * {@code --version} work without initialising the JavaFX toolkit (CI runners have no display,
 * and the Windows launcher has no console, hence {@code --report}).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        configureRuntime();
        boolean selfTest = false;
        boolean online = false;
        Path report = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--self-test" -> selfTest = true;
                case "--online" -> online = true;
                case "--report" -> report = Path.of(args[++i]);
                case "--version" -> {
                    System.out.println("NFD Client " + version());
                    return;
                }
                default -> {
                    // Unknown arguments (for example a share link from a file association) go to the UI.
                }
            }
        }
        if (selfTest) {
            System.exit(DesktopSelfTest.run(output(report), online) ? 0 : 1);
            return;
        }
        javafx.application.Application.launch(DesktopApp.class, args);
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static void configureRuntime() {
        // The parser jar ships its own logback.xml; point logback at ours to avoid the duplicate warning.
        setDefault("logback.configurationFile", "nfd-logback.xml");
        Path data = AppPaths.dataDir();
        setDefault("vertx.cacheDirBase", data.resolve("vertx-cache").toString());
        setDefault("vertx.disableFileCPResolving", "true");
    }

    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static PrintStream output(Path report) throws IOException {
        if (report == null) {
            return System.out;
        }
        if (report.toAbsolutePath().getParent() != null) {
            Files.createDirectories(report.toAbsolutePath().getParent());
        }
        OutputStream file = new FileOutputStream(report.toFile());
        return new PrintStream(new Tee(System.out, file), true, StandardCharsets.UTF_8);
    }

    private static final class Tee extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        Tee(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void write(int b) throws IOException {
            first.write(b);
            second.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            first.write(b, off, len);
            second.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            first.flush();
            second.flush();
        }
    }
}
