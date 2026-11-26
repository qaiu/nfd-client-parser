package cn.qaiu.nfd.share;

import cn.qaiu.entity.FileInfo;
import cn.qaiu.entity.ShareLinkInfo;
import cn.qaiu.parser.IPanTool;
import cn.qaiu.parser.ParserCreate;
import cn.qaiu.util.CommonUtils;
import io.vertx.core.json.JsonObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local share-link session on top of {@code cn.qaiu:parser}.
 *
 * <p>Listing uses {@code parseFileList}. A file row's {@code parserUrl} carries the same
 * {@code /v2/redirectUrl/{type}/{param}} token the parser itself emits. Resolving a file
 * decodes that token into {@code otherParam.paramJson} and calls {@code parseById}.
 * Single-file shares fall back to {@code parse}. Direct URLs and {@code downloadHeaders}
 * come back on the share link, so the downloader does not call a hosted NFD service.
 */
public final class ShareSession {

    /** Keys of the source map stored with a download so an expired link can be re-resolved. */
    public static final String SOURCE_LINK = "link";
    public static final String SOURCE_PASSWORD = "password";
    public static final String SOURCE_ENTRY = "entry";

    private static final String REDIRECT_MARKER = "/v2/redirectUrl/";
    private static final String NOT_IMPLEMENTED = "Not implemented yet";

    private final ParserCreate created;

    private ShareSession(ParserCreate created) {
        this.created = created;
    }

    public static ShareSession open(String shareUrl, String password) {
        String url = extractUrl(shareUrl);
        if (url.isEmpty()) {
            throw new IllegalArgumentException("分享链接为空");
        }
        ParserCreate created = ParserCreate.fromShareUrl(url);
        if (password != null && !password.trim().isEmpty()) {
            created.setShareLinkInfoPwd(password.trim());
        }
        return new ShareSession(created);
    }

    public String type() {
        return created.getShareLinkInfo().getType();
    }

    public String panName() {
        return created.getShareLinkInfo().getPanName();
    }

    public String password() {
        return created.getShareLinkInfo().getSharePassword();
    }

    public synchronized Listing list(String dirId) {
        ParserRuntime.start();
        Map<String, Object> other = otherParam();
        if (dirId == null || dirId.isBlank()) {
            other.remove("dirId");
        } else {
            other.put("dirId", dirId);
        }
        IPanTool tool = created.createTool();
        try {
            List<FileInfo> files;
            try {
                files = tool.parseFileListSync();
            } catch (RuntimeException ex) {
                if (notImplemented(ex)) {
                    files = List.of();
                } else {
                    throw ex;
                }
            }
            List<Entry> entries = new ArrayList<>();
            if (files != null) {
                for (FileInfo file : files) {
                    if (file != null) {
                        entries.add(toEntry(file));
                    }
                }
            }
            return new Listing(panName(), type(), List.copyOf(entries));
        } finally {
            closeQuietly(tool);
        }
    }

    public synchronized Listing openFolder(Entry entry) {
        if (entry == null || !entry.folder()) {
            throw new IllegalArgumentException("不是文件夹");
        }
        Map<String, String> query = queryParams(entry.parserUrl());
        Map<String, Object> other = otherParam();
        for (String key : List.of("uuid", "stoken", "zml", "pwd")) {
            String value = query.get(key);
            if (value != null && !value.isBlank()) {
                other.put(key, value);
            }
        }
        String dirId = query.get("dirId");
        if (dirId == null || dirId.isBlank()) {
            dirId = entry.fileId();
        }
        return list(dirId);
    }

    public synchronized DirectLink resolve(Entry entry) {
        ParserRuntime.start();
        String fallbackName = entry == null || entry.fileName() == null || entry.fileName().isBlank()
                ? "download"
                : entry.fileName();
        DownloadRef ref = entry == null ? null : decodeParserUrl(entry.parserUrl());
        String url = null;
        if (ref != null) {
            otherParam().put("paramJson", new JsonObject(ref.paramJson()));
            if (ref.paramJson().contains("fileName") && fallbackName.equals("download")) {
                String fromParam = new JsonObject(ref.paramJson()).getString("fileName");
                if (fromParam != null && !fromParam.isBlank()) {
                    fallbackName = fromParam;
                }
            }
            IPanTool tool = created.createTool();
            try {
                url = tool.parseByIdSync();
            } finally {
                closeQuietly(tool);
            }
            if (!isHttp(url)) {
                url = null;
            }
        }
        if (url == null) {
            IPanTool tool = created.createTool();
            try {
                url = tool.parseSync();
            } finally {
                closeQuietly(tool);
            }
        }
        if (!isHttp(url)) {
            throw new IllegalStateException(url == null || url.isBlank() ? "没有解析到下载地址" : url);
        }
        return new DirectLink(url, preferredName(fallbackName), headers());
    }

    public synchronized DirectLink parseDirect() {
        ParserRuntime.start();
        IPanTool tool = created.createTool();
        try {
            String url = tool.parseSync();
            if (!isHttp(url)) {
                throw new IllegalStateException(url == null || url.isBlank() ? "没有解析到下载地址" : url);
            }
            return new DirectLink(url, preferredName("download"), headers());
        } finally {
            closeQuietly(tool);
        }
    }

    public static DownloadRef decodeParserUrl(String parserUrl) {
        if (parserUrl == null) {
            return null;
        }
        int marker = parserUrl.indexOf(REDIRECT_MARKER);
        if (marker < 0) {
            return null;
        }
        String rest = parserUrl.substring(marker + REDIRECT_MARKER.length());
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            return null;
        }
        String type = rest.substring(0, slash);
        String param = rest.substring(slash + 1);
        int extra = param.indexOf('/');
        if (extra >= 0) {
            param = param.substring(0, extra);
        }
        try {
            String json = CommonUtils.urlBase64Decode(param);
            if (json == null || json.isBlank() || !json.trim().startsWith("{")) {
                return null;
            }
            return new DownloadRef(type, json);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static Entry toEntry(FileInfo file) {
        String name = file.getFileName() == null ? "" : file.getFileName();
        String fileType = file.getFileType() == null ? "" : file.getFileType();
        boolean folder = isFolder(fileType, file.getParserUrl());
        long size = file.getSize() == null ? -1L : file.getSize();
        String sizeText = file.getSizeStr();
        if (sizeText == null || sizeText.isBlank()) {
            sizeText = size >= 0 && !folder ? formatSize(size) : "";
        }
        return new Entry(
                name,
                file.getFileId() == null ? "" : file.getFileId(),
                fileType,
                size,
                sizeText,
                file.getParserUrl(),
                folder);
    }

    public static Map<String, String> queryParams(String url) {
        if (url == null) {
            return Map.of();
        }
        int query = url.indexOf('?');
        if (query < 0 || query == url.length() - 1) {
            return Map.of();
        }
        String raw = url.substring(query + 1);
        int hash = raw.indexOf('#');
        if (hash >= 0) {
            raw = raw.substring(0, hash);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = eq >= 0 ? part.substring(0, eq) : part;
            String value = eq >= 0 ? part.substring(eq + 1) : "";
            params.put(urlDecode(key), urlDecode(value));
        }
        return params;
    }

    public static String extractUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int http = indexOfIgnoreCase(trimmed, "http://");
        int https = indexOfIgnoreCase(trimmed, "https://");
        int start = -1;
        if (http >= 0 && https >= 0) {
            start = Math.min(http, https);
        } else if (http >= 0) {
            start = http;
        } else if (https >= 0) {
            start = https;
        }
        if (start < 0) {
            return trimmed;
        }
        int end = trimmed.length();
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c == '）' || c == '】' || c == '，' || c == '。') {
                end = i;
                break;
            }
        }
        String url = trimmed.substring(start, end);
        while (url.endsWith(")") || url.endsWith(",") || url.endsWith(";") || url.endsWith(".")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private Map<String, Object> otherParam() {
        ShareLinkInfo info = created.getShareLinkInfo();
        Map<String, Object> other = info.getOtherParam();
        if (other == null) {
            other = new LinkedHashMap<>();
            info.setOtherParam(other);
        }
        return other;
    }

    private Map<String, String> headers() {
        Object raw = otherParam().get("downloadHeaders");
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    headers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        } else if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map.Entry<?, ?> entry && entry.getKey() != null && entry.getValue() != null) {
                    headers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return Map.copyOf(headers);
    }

    private String preferredName(String fallback) {
        Object fileInfo = otherParam().get("fileInfo");
        if (fileInfo instanceof FileInfo info && info.getFileName() != null && !info.getFileName().isBlank()) {
            return info.getFileName();
        }
        Object name = otherParam().get("fileName");
        if (name != null && !name.toString().isBlank()) {
            return name.toString();
        }
        return fallback;
    }

    private static boolean isFolder(String fileType, String parserUrl) {
        if (parserUrl != null && parserUrl.contains("/v2/getFileList")) {
            return true;
        }
        if (fileType == null) {
            return false;
        }
        String type = fileType.trim().toLowerCase(Locale.ROOT);
        return type.equals("folder") || type.equals("dir") || type.equals("directory");
    }

    private static boolean isHttp(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        return trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean notImplemented(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains(NOT_IMPLEMENTED) || message.toLowerCase(Locale.ROOT).contains("not implemented"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void closeQuietly(Object tool) {
        if (tool instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Closing only releases the per-call web client.
            }
        }
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).indexOf(needle);
    }

    private static String formatSize(long bytes) {
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
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    public record Listing(String panName, String type, List<Entry> entries) {
    }

    public record Entry(
            String fileName,
            String fileId,
            String fileType,
            long size,
            String sizeText,
            String parserUrl,
            boolean folder) {

        public JsonObject toJson() {
            return new JsonObject()
                    .put("fileName", fileName)
                    .put("fileId", fileId)
                    .put("fileType", fileType)
                    .put("size", size)
                    .put("sizeText", sizeText)
                    .put("parserUrl", parserUrl)
                    .put("folder", folder);
        }

        public static Entry fromJson(JsonObject json) {
            if (json == null) {
                return null;
            }
            Object size = json.getValue("size");
            return new Entry(
                    json.getString("fileName", ""),
                    json.getString("fileId", ""),
                    json.getString("fileType", ""),
                    size instanceof Number number ? number.longValue() : -1L,
                    json.getString("sizeText", ""),
                    json.getString("parserUrl"),
                    json.getBoolean("folder", false));
        }
    }

    /** Source map for {@code cn.qaiu.nfd.core.DownloadRequest#source}. */
    public static Map<String, String> source(String link, String password, Entry entry) {
        Map<String, String> source = new LinkedHashMap<>();
        source.put(SOURCE_LINK, link == null ? "" : link);
        if (password != null && !password.isBlank()) {
            source.put(SOURCE_PASSWORD, password.trim());
        }
        if (entry != null) {
            source.put(SOURCE_ENTRY, entry.toJson().encode());
        }
        return source;
    }

    /** Re-resolves a download from its {@link #source} map. */
    public static DirectLink resolveSource(Map<String, String> source) {
        ShareSession session = open(source.get(SOURCE_LINK), source.get(SOURCE_PASSWORD));
        String raw = source.get(SOURCE_ENTRY);
        Entry entry = raw == null || raw.isBlank() ? null : Entry.fromJson(new JsonObject(raw));
        return entry == null ? session.parseDirect() : session.resolve(entry);
    }

    public record DirectLink(String url, String fileName, Map<String, String> headers) {
    }

    public record DownloadRef(String type, String paramJson) {
    }
}
