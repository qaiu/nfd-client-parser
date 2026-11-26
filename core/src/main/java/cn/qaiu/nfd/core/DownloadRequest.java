package cn.qaiu.nfd.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One file to download.
 *
 * @param fileName  preferred file name; a {@code Content-Disposition} name wins when this is blank
 * @param url       direct HTTP(S) URL; empty means "ask the {@link LinkRefresher} when the task starts"
 * @param headers   request headers the share provider requires (Referer, Cookie, User-Agent ...)
 * @param directory target directory
 * @param source    opaque data handed back to {@link LinkRefresher} when the URL expires
 */
public record DownloadRequest(
        String fileName,
        String url,
        Map<String, String> headers,
        Path directory,
        Map<String, String> source) {

    public DownloadRequest {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(directory, "directory");
        fileName = fileName == null ? "" : fileName.trim();
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        source = source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source));
    }
}
