package cn.qaiu.nfd.share;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Local parse history. A link appears once; parsing it again moves it to the top with the latest
 * result. Extraction codes are kept so an item can be re-parsed. Same format as the Android app.
 */
public final class ParseHistory {

    /** One parse attempt. Counts describe the root level only, and only the pages loaded so far. */
    public record Item(String link, String password, long time, boolean success, String panName, String type,
                       String title, int fileCount, int folderCount, long totalSize, boolean sizeComplete,
                       boolean hasMore, boolean singleFile, String error) {

        public static Item succeeded(String link, String password, ShareSession.Listing listing, long time) {
            List<ShareSession.Entry> entries = listing.entries();
            int files = 0;
            int folders = 0;
            long total = 0;
            boolean complete = true;
            String first = null;
            for (ShareSession.Entry entry : entries) {
                if (first == null && entry.fileName() != null && !entry.fileName().isBlank()) {
                    first = entry.fileName();
                }
                if (entry.folder()) {
                    folders++;
                } else {
                    files++;
                    if (entry.size() >= 0) {
                        total += entry.size();
                    } else {
                        complete = false;
                    }
                }
            }
            String title = first == null ? "空目录" : entries.size() == 1 ? first : first + " 等 " + entries.size() + " 项";
            return new Item(link, password, time, true, nz(listing.panName()), nz(listing.type()), title, files, folders,
                    total, complete, listing.hasMore(), listing.singleFile(), "");
        }

        public static Item failed(String link, String password, String error, long time) {
            return new Item(link, password, time, false, "", "", "", 0, 0, 0, true, false, false, nz(error));
        }

        JsonObject toJson() {
            return new JsonObject()
                    .put("link", link).put("password", password).put("time", time).put("success", success)
                    .put("panName", panName).put("type", type).put("title", title)
                    .put("fileCount", fileCount).put("folderCount", folderCount).put("totalSize", totalSize)
                    .put("sizeComplete", sizeComplete).put("hasMore", hasMore).put("singleFile", singleFile)
                    .put("error", error);
        }

        static Item fromJson(JsonObject json) {
            return new Item(json.getString("link", ""), json.getString("password", ""), json.getLong("time", 0L),
                    json.getBoolean("success", false), json.getString("panName", ""), json.getString("type", ""),
                    json.getString("title", ""), json.getInteger("fileCount", 0), json.getInteger("folderCount", 0),
                    json.getLong("totalSize", 0L), json.getBoolean("sizeComplete", true),
                    json.getBoolean("hasMore", false), json.getBoolean("singleFile", false),
                    json.getString("error", ""));
        }
    }

    private final Path file;
    private final int limit;
    private List<Item> items;

    public ParseHistory(Path file) {
        this(file, 100);
    }

    public ParseHistory(Path file, int limit) {
        this.file = file;
        this.limit = limit;
    }

    public synchronized List<Item> items() {
        if (items == null) {
            items = load();
        }
        return List.copyOf(items);
    }

    public synchronized void record(Item item) throws IOException {
        String key = normalize(item.link());
        List<Item> updated = new ArrayList<>();
        updated.add(item);
        for (Item existing : items()) {
            if (updated.size() >= limit) {
                break;
            }
            if (!normalize(existing.link()).equals(key)) {
                updated.add(existing);
            }
        }
        save(updated);
    }

    /** Refreshes counts after more pages of the same root listing were loaded. */
    public synchronized void update(String link, String password, ShareSession.Listing listing) throws IOException {
        String key = normalize(link);
        List<Item> updated = new ArrayList<>();
        boolean found = false;
        for (Item existing : items()) {
            if (!found && normalize(existing.link()).equals(key)) {
                updated.add(Item.succeeded(existing.link(), existing.password(), listing, existing.time()));
                found = true;
            } else {
                updated.add(existing);
            }
        }
        if (found) {
            save(updated);
        }
    }

    public synchronized void remove(Item item) throws IOException {
        List<Item> updated = new ArrayList<>(items());
        updated.removeIf(existing -> existing.link().equals(item.link()) && existing.time() == item.time());
        save(updated);
    }

    public synchronized void clear() throws IOException {
        save(List.of());
    }

    private List<Item> load() {
        List<Item> loaded = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return loaded;
        }
        try {
            JsonArray array = new JsonArray(Files.readString(file, StandardCharsets.UTF_8));
            for (int i = 0; i < array.size(); i++) {
                Object value = array.getValue(i);
                if (value instanceof JsonObject json) {
                    loaded.add(Item.fromJson(json));
                }
            }
        } catch (IOException | RuntimeException ex) {
            loaded.clear();
        }
        return loaded;
    }

    private void save(List<Item> updated) throws IOException {
        items = new ArrayList<>(updated);
        JsonArray array = new JsonArray();
        updated.forEach(item -> array.add(item.toJson()));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, array.encode(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String normalize(String link) {
        String trimmed = link == null ? "" : link.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
