package cn.qaiu.nfd.daemon;

import cn.qaiu.nfd.share.ShareSession;
import io.vertx.core.json.JsonObject;

/** Parser operations behind the HTTP API; tests swap in a fake that needs no network. */
interface ShareBackend {

    JsonObject inspect(String link, String password);

    ShareSession.Listing list(String link, String password, ShareSession.Entry folder);

    ShareSession.DirectLink resolve(String link, String password, ShareSession.Entry entry);
}
