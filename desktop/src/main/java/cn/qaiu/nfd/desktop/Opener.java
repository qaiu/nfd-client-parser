package cn.qaiu.nfd.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Opens files and folders with the OS shell. {@code java.awt.Desktop} is avoided because mixing
 * AWT into a JavaFX app deadlocks on macOS and is missing on some Linux desktops.
 */
final class Opener {

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private Opener() {
    }

    static void open(Path file) throws IOException {
        if (OS.contains("win")) {
            run(List.of("rundll32", "url.dll,FileProtocolHandler", file.toString()));
        } else if (OS.contains("mac")) {
            run(List.of("open", file.toString()));
        } else {
            run(List.of("xdg-open", file.toString()));
        }
    }

    /** Shows the file selected in its folder where the platform supports it. */
    static void reveal(Path file) throws IOException {
        if (!Files.exists(file)) {
            openFolder(file.getParent());
        } else if (OS.contains("win")) {
            run(List.of("explorer.exe", "/select," + file));
        } else if (OS.contains("mac")) {
            run(List.of("open", "-R", file.toString()));
        } else {
            openFolder(file.getParent());
        }
    }

    static void openFolder(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (OS.contains("win")) {
            run(List.of("explorer.exe", dir.toString()));
        } else if (OS.contains("mac")) {
            run(List.of("open", dir.toString()));
        } else {
            run(List.of("xdg-open", dir.toString()));
        }
    }

    private static void run(List<String> command) throws IOException {
        new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
