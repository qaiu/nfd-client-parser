package cn.qaiu.nfd.daemon;

import cn.qaiu.nfd.share.ShareSession;
import io.vertx.core.json.JsonObject;

/** Parser operations behind the HTTP API; tests swap in a fake that needs no network. */
interface ShareBackend {

    JsonObject inspect(String link, String password);

    /** {@code folder == null && page == 1} is a fresh parse and may fall back to a single file. */
    ShareSession.Listing list(String link, String password, ShareSession.Entry folder, int page, String cursor);

    ShareSession.DirectLink resolve(String link, String password, ShareSession.Entry entry);

    /** Drops cached sessions, e.g. after login data changed. */
    default void invalidate() {
    }
}
