package cn.qaiu.nfd.core;

import java.util.Map;

/**
 * Re-resolves a share link when the direct URL expires (HTTP 401/403/404/410 or an HTML page
 * instead of the file). Called on a download thread; blocking is fine.
 */
@FunctionalInterface
public interface LinkRefresher {

    ResolvedLink refresh(TaskSnapshot task) throws Exception;

    record ResolvedLink(String url, Map<String, String> headers) {
        public ResolvedLink {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }
}
