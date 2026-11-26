package cn.qaiu.nfd.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class Formats {

    private static final int MAX_NAME_BYTES = 200;

    private Formats() {
    }

    public static String size(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public static String speed(long bytesPerSecond) {
        return size(Math.max(0, bytesPerSecond)) + "/s";
    }

    public static String eta(long seconds) {
        if (seconds < 0) {
            return "";
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 99) {
            return ">99h";
        }
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    /** Strips path separators and characters Windows, Android and FAT32 reject. */
    public static String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            cleaned = "download";
        }
        String upper = cleaned.toUpperCase(Locale.ROOT);
        String stem = upper.contains(".") ? upper.substring(0, upper.indexOf('.')) : upper;
        if (stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) {
            cleaned = "_" + cleaned;
        }
        return truncateUtf8(cleaned);
    }

    /** Returns {@code dir/name}, or {@code dir/name (n).ext} when that file already exists. */
    public static Path uniqueTarget(Path dir, String name) {
        String safe = safeFileName(name);
        Path candidate = dir.resolve(safe);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            candidate = dir.resolve(stem + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return dir.resolve(stem + " (" + System.currentTimeMillis() + ")" + ext);
    }

    private static String truncateUtf8(String name) {
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_NAME_BYTES) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
        String stem = ext.isEmpty() ? name : name.substring(0, dot);
        int budget = MAX_NAME_BYTES - ext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < stem.length(); ) {
            int cp = stem.codePointAt(i);
            int bytes = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (used + bytes > budget) {
                break;
            }
            out.appendCodePoint(cp);
            used += bytes;
            i += Character.charCount(cp);
        }
        return out + ext;
    }
}
