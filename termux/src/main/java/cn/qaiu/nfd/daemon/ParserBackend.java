package cn.qaiu.nfd.daemon;

import cn.qaiu.nfd.share.ShareSession;
import io.vertx.core.json.JsonObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ShareBackend} on {@code cn.qaiu:parser}. Sessions are cached per link and password so
 * folder navigation keeps the share state (cookies, uuid, stoken) the root listing produced.
 */
final class ParserBackend implements ShareBackend {

    private static final int MAX_SESSIONS = 32;
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Cached> sessions = new LinkedHashMap<>(16, 0.75f, true);

    @Override
    public JsonObject inspect(String link, String password) {
        ShareSession session = ShareSession.open(link, password);
        return new JsonObject()
                .put("link", ShareSession.extractUrl(link))
                .put("type", session.type())
                .put("panName", session.panName())
                .put("password", session.password());
    }

    @Override
    public ShareSession.Listing list(String link, String password, ShareSession.Entry folder, int page, String cursor) {
        ShareSession session = session(link, password);
        String type = diagnosticType(session);
        long started = System.nanoTime();
        event("list-start " + type + " " + page);
        try {
            ShareSession.Listing listing = folder != null ? session.openFolder(folder, page, cursor)
                    : page <= 1 ? session.browse() : session.list(null, page, cursor);
            event("list-ok " + type + " " + listing.page() + " " + listing.entries().size() + " "
                    + (listing.singleFile() ? "file" : "folder") + " " + millis(started));
            return listing;
        } catch (RuntimeException ex) {
            event("list-fail " + type + " " + millis(started));
            throw ex;
        }
    }

    @Override
    public void invalidate() {
        synchronized (sessions) {
            sessions.clear();
        }
    }

    @Override
    public ShareSession.DirectLink resolve(String link, String password, ShareSession.Entry entry) {
        ShareSession session = session(link, password);
        String type = diagnosticType(session);
        long started = System.nanoTime();
        event("resolve-start " + type);
        try {
            ShareSession.DirectLink result = entry == null ? session.parseDirect() : session.resolve(entry);
            event("resolve-ok " + type + " " + millis(started));
            return result;
        } catch (RuntimeException ex) {
            event("resolve-fail " + type + " " + millis(started));
            throw ex;
        }
    }

    private static String diagnosticType(ShareSession session) {
        String type = session.type();
        return type.matches("[a-z0-9_]{1,20}") ? type : "other";
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static void event(String value) {
        System.out.println("NFD_EVENT parse " + value);
    }

    private ShareSession session(String link, String password) {
        String key = ShareSession.extractUrl(link) + "\n" + (password == null ? "" : password.trim());
        long now = System.currentTimeMillis();
        synchronized (sessions) {
            Iterator<Map.Entry<String, Cached>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().createdAt > TTL_MILLIS) {
                    it.remove();
                }
            }
            Cached cached = sessions.get(key);
            if (cached == null) {
                cached = new Cached(ShareSession.open(link, password), now);
                sessions.put(key, cached);
                if (sessions.size() > MAX_SESSIONS) {
                    sessions.remove(sessions.keySet().iterator().next());
                }
            }
            return cached.session;
        }
    }

    private record Cached(ShareSession session, long createdAt) {
    }
}
