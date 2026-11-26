package cn.qaiu.nfd.share;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Netdisk login data the parser reads from {@code otherParam.auths} (cookie, token, account).
 *
 * <p>Values stay in one JSON file readable only by the owner. {@link #describe()} never returns
 * secret values, only whether they are set, so the HTTP API and UI cannot leak them back.
 */
public final class AuthStore {

    public record Field(String key, String label, boolean secret, boolean multiline, String hint) {
    }

    public record Provider(String type, String name, String hint, List<Field> fields) {

        Field field(String key) {
            for (Field field : fields) {
                if (field.key.equals(key)) {
                    return field;
                }
            }
            return null;
        }
    }

    private static final Field COOKIE = new Field("cookie", "Cookie", true, true, "整段复制浏览器请求头里的 Cookie");
    private static final Field USERNAME = new Field("username", "账号", false, false, "");
    private static final Field PASSWORD = new Field("password", "密码", true, false, "");

    /** Keys are the ones {@code cn.qaiu:parser} 10.2.6 reads for each type. */
    public static final List<Provider> PROVIDERS = List.of(
            new Provider("qk", "夸克网盘",
                    "夸克要求登录后才能下载。在电脑浏览器登录 pan.quark.cn，打开开发者工具的「网络」面板，复制任一请求的 Cookie。",
                    List.of(COOKIE)),
            new Provider("uc", "UC网盘",
                    "UC 网盘要求登录后才能下载。在浏览器登录 drive.uc.cn 后，按同样方式复制 Cookie。",
                    List.of(COOKIE)),
            new Provider("ye", "123网盘",
                    "填写 Token（请求头 Authorization 的值，可带 Bearer 前缀），或填写账号和密码由解析库登录。",
                    List.of(new Field("token", "Token / Authorization", true, false, ""), USERNAME, PASSWORD)),
            new Provider("iz", "蓝奏云优享", "部分分享需要登录后才能下载，填写蓝奏云优享的账号和密码。",
                    List.of(USERNAME, PASSWORD)),
            new Provider("fj", "小飞机网盘", "部分分享需要登录后才能下载，填写小飞机网盘的账号和密码。",
                    List.of(USERNAME, PASSWORD)),
            new Provider("ct", "城通网盘",
                    "登录 pan.ctfile.com 后，从浏览器存储或请求中取得 refresh_token；access token 可选。",
                    List.of(new Field("refresh_token", "refresh_token", true, false, ""),
                            new Field("token", "access token（可选）", true, false, ""))));

    private static final Map<String, String> ALIASES = Map.of("ctd", "ct");

    private final Path file;
    private final Map<String, Map<String, String>> values = new TreeMap<>();

    private AuthStore(Path file) {
        this.file = file;
    }

    /** Loads the store; a missing or unreadable file starts empty rather than blocking startup. */
    public static AuthStore load(Path file) {
        AuthStore store = new AuthStore(file);
        try {
            if (Files.isRegularFile(file)) {
                JsonObject json = new JsonObject(Files.readString(file, StandardCharsets.UTF_8));
                for (String type : json.fieldNames()) {
                    Provider provider = provider(type);
                    JsonObject fields = json.getJsonObject(type);
                    if (provider == null || fields == null) {
                        continue;
                    }
                    Map<String, String> map = new LinkedHashMap<>();
                    for (Field field : provider.fields()) {
                        String value = fields.getString(field.key());
                        if (value != null && !value.isBlank()) {
                            map.put(field.key(), value.trim());
                        }
                    }
                    if (!map.isEmpty()) {
                        store.values.put(provider.type(), map);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            store.values.clear();
        }
        return store;
    }

    public static Provider provider(String type) {
        if (type == null) {
            return null;
        }
        String key = ALIASES.getOrDefault(type, type);
        for (Provider provider : PROVIDERS) {
            if (provider.type().equals(key)) {
                return provider;
            }
        }
        return null;
    }

    /** Values for a parser type such as {@code qk}; empty when nothing is configured. */
    public synchronized Map<String, String> get(String type) {
        Provider provider = provider(type);
        if (provider == null) {
            return Map.of();
        }
        Map<String, String> map = values.get(provider.type());
        return map == null ? Map.of() : Map.copyOf(map);
    }

    /**
     * Applies an edit from the settings UI. A blank secret means "keep the stored value", because
     * the UI never receives secrets to show them; a blank non-secret field clears it.
     */
    public synchronized void update(String type, Map<String, String> changes) throws IOException {
        Provider provider = provider(type);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的网盘类型：" + type);
        }
        Map<String, String> map = new LinkedHashMap<>(values.getOrDefault(provider.type(), Map.of()));
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Field field = provider.field(change.getKey());
            if (field == null) {
                continue;
            }
            String value = change.getValue() == null ? "" : change.getValue().trim();
            if (!value.isEmpty()) {
                map.put(field.key(), value);
            } else if (!field.secret()) {
                map.remove(field.key());
            }
        }
        if (map.isEmpty()) {
            values.remove(provider.type());
        } else {
            values.put(provider.type(), map);
        }
        save();
    }

    public synchronized void clear(String type) throws IOException {
        Provider provider = provider(type);
        if (provider != null && values.remove(provider.type()) != null) {
            save();
        }
    }

    public synchronized boolean configured(String type) {
        Provider provider = provider(type);
        return provider != null && values.containsKey(provider.type());
    }

    /** Schema plus which fields are set. Secret values are never included. */
    public synchronized JsonObject describe() {
        JsonArray providers = new JsonArray();
        for (Provider provider : PROVIDERS) {
            Map<String, String> map = values.getOrDefault(provider.type(), Map.of());
            JsonArray fields = new JsonArray();
            for (Field field : provider.fields()) {
                String value = map.get(field.key());
                JsonObject json = new JsonObject()
                        .put("key", field.key())
                        .put("label", field.label())
                        .put("secret", field.secret())
                        .put("multiline", field.multiline())
                        .put("hint", field.hint())
                        .put("set", value != null);
                if (!field.secret() && value != null) {
                    json.put("value", value);
                }
                fields.add(json);
            }
            providers.add(new JsonObject()
                    .put("type", provider.type())
                    .put("name", provider.name())
                    .put("hint", provider.hint())
                    .put("configured", !map.isEmpty())
                    .put("fields", fields));
        }
        return new JsonObject().put("providers", providers);
    }

    private void save() throws IOException {
        JsonObject json = new JsonObject();
        values.forEach((type, map) -> json.put(type, new JsonObject(new LinkedHashMap<>(map))));
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json.encodePrettily(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows keeps the profile directory's ACL, which is already per-user.
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
