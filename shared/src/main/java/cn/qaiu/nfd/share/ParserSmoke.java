package cn.qaiu.nfd.share;

import cn.qaiu.parser.ParserCreate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** Exercises the real parser over loopback HTTP without depending on public share availability. */
final class ParserSmoke {

    private static final String FOLDER = """
            <html><body><div id="infos"></div>
            <script type="text/javascript">
            var pwd;
            var pgs;
            var tokenT = '1700000000';
            var tokenK = 'test-k-token';
            pgs =1;
            function file(){
                $.ajax({
                    type : 'post',
                    url : '/filemoreajax.php?file=66477',
                    data : {
                        'lx':2,
                        'fid':66477,
                        'uid':'234978',
                        'pg':pgs,
                        'rep':'0',
                        't':tokenT,
                        'k':tokenK,
                        'up':1,
                        'ls':1,
                        'pwd':pwd
                    },
                    dataType : 'json'
                });
            }
            </script></body></html>
            """;

    private static final String FILE = """
            <html><head><title>page-one-0.txt - 蓝奏云</title></head><body>
            <div id="filenajax">page-one-0.txt</div>
            <iframe class="n_downlink" src="/fn?fixture"></iframe></body></html>
            """;

    private static final String FRAME = """
            <html><script>
            var wp_sign = 'test-wp-sign';
            var ajaxdata = 'test-ajaxdata';
            var kdns = 1;
            $.ajax({url: '/ajaxfiles.php?file=123', data: {
                'action':'downprocess','websignkey':ajaxdata,'signs':ajaxdata,'sign':wp_sign,
                'websign':'','kd':kdns,'ves':1
            }});
            </script></html>
            """;

    private ParserSmoke() {
    }

    /** Traces the same fixture as upstream's LzToolFolderPagingTest through the app's wrapper. */
    static boolean lanzouPages() throws IOException {
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/b66477", request -> {
            byte[] body = FOLDER.getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            request.sendResponseHeaders(200, body.length);
            try (var out = request.getResponseBody()) {
                out.write(body);
            }
        });
        fixture.createContext("/page-one-0", request -> send(request, "text/html", FILE));
        fixture.createContext("/fn", request -> send(request, "text/html", FRAME));
        fixture.createContext("/ajaxfiles.php", request -> send(request, "application/json",
                new JsonObject().put("zt", 1).put("inf", 0).put("url", "?fixture")
                        .put("dom", "http://127.0.0.1:" + fixture.getAddress().getPort()).encode()));
        final String download = "http://cdn.test/2026/11/25/page-one-0.txt";
        fixture.createContext("/file/", request -> {
            request.getResponseHeaders().set("Location", download);
            request.sendResponseHeaders(302, -1);
            request.close();
        });
        fixture.createContext("/filemoreajax.php", request -> {
            String form = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean first = form.contains("pg=1");
            boolean second = form.contains("pg=2");
            if ((!first && !second) || !form.contains("pwd=1234")
                    || !form.contains("t=1700000000") || !form.contains("k=test-k-token")) {
                request.sendResponseHeaders(400, -1);
                request.close();
                return;
            }
            JsonArray files = new JsonArray();
            for (int i = 0; i < (first ? 50 : 2); i++) {
                String id = (first ? "page-one-" : "page-two-") + i;
                files.add(new JsonObject().put("icon", "txt").put("t", 0).put("id", id)
                        .put("name_all", id + ".txt").put("size", "1.0 K").put("time", "2023-03-08"));
            }
            byte[] body = new JsonObject().put("zt", 1).put("info", "success").put("text", files)
                    .encode().getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            request.sendResponseHeaders(200, body.length);
            try (var out = request.getResponseBody()) {
                out.write(body);
            }
        });
        fixture.start();
        try {
            String url = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/b66477";
            ShareSession session = fixtureSession(url);
            ShareSession.Listing first = session.browse();
            if (first.singleFile() || !first.hasMore() || first.nextCursor() == null || first.entries().size() != 50) {
                return false;
            }
            if (!download.equals(session.resolve(first.entries().get(0)).url())) {
                return false;
            }
            // A cached daemon session can list, download an entry, and list the same folder again.
            ShareSession.Listing reopened = session.browse();
            if (reopened.singleFile() || !reopened.hasMore() || reopened.nextCursor() == null
                    || !reopened.entries().equals(first.entries())) {
                return false;
            }
            ShareSession.Listing second = session.list(null, 2, reopened.nextCursor());
            Set<String> ids = new HashSet<>();
            first.entries().forEach(entry -> ids.add(entry.fileId()));
            second.entries().forEach(entry -> ids.add(entry.fileId()));
            ShareSession.DownloadRef ref = ShareSession.decodeParserUrl(second.entries().get(0).parserUrl());
            return second.page() == 2 && !second.hasMore() && second.entries().size() == 2
                    && ids.size() == 52 && ref != null && "lz".equals(ref.type());
        } finally {
            fixture.stop(0);
        }
    }

    private static void send(HttpExchange request, String type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        request.sendResponseHeaders(200, body.length);
        try (var out = request.getResponseBody()) {
            out.write(body);
        }
    }

    // Public samples from upstream LzFolderLiveTest, LzFolderPagingLiveTest and CtToolLiveTest.
    static boolean liveLanzouPagesAndFile() throws InterruptedException {
        ShareSession session = ShareSession.open(SelfTest.SAMPLE_LINK, null);
        ShareSession.Listing first = session.browse();
        if (first.singleFile() || !first.hasMore() || first.nextCursor() == null || first.entries().size() < 45) {
            return false;
        }
        Thread.sleep(2500);
        ShareSession.Listing second = ShareSession.open(SelfTest.SAMPLE_LINK, null).list(null, 2, first.nextCursor());
        Set<String> ids = new HashSet<>();
        for (ShareSession.Entry entry : first.entries()) {
            ids.add(entry.fileId());
        }
        if (second.entries().size() < 45 || !second.hasMore() || second.entries().stream()
                .anyMatch(entry -> !ids.add(entry.fileId()))) {
            return false;
        }
        ShareSession.Entry file = second.entries().stream().filter(entry -> !entry.folder() && !entry.fileId().isBlank())
                .findFirst().orElseThrow();
        ShareSession fileSession = ShareSession.open("https://pan.lanzoui.com/" + file.fileId(), null);
        ShareSession.Listing single = fileSession.browse();
        return single.singleFile() && single.entries().size() == 1 && !single.entries().get(0).fileName().isBlank()
                && isDirect(fileSession.resolve(single.entries().get(0)));
    }

    static boolean liveLanzouWithCode() {
        ShareSession.Listing listing = ShareSession.open("https://moteam.lanzoum.com/b00yaejvte", "1234").browse();
        return !listing.singleFile() && !listing.entries().isEmpty();
    }

    static boolean liveCtfileDirectoryAndFile() throws IOException, InterruptedException {
        ShareSession directory = ShareSession.open("https://url94.ctfile.com/d/64115194-164803691-48508c?p=7609", null);
        ShareSession.Listing listing = directory.browse();
        if (listing.singleFile() || listing.entries().isEmpty()) {
            return false;
        }
        for (ShareSession.Entry entry : listing.entries()) {
            if (entry.folder() && directory.openFolder(entry).entries().isEmpty()) {
                return false;
            }
        }
        ShareSession.DirectLink link = ShareSession.open("https://474b.com/file/4015376-131945810", null).parseDirect();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(link.url()))
                .timeout(Duration.ofSeconds(30)).header("Range", "bytes=0-15");
        link.headers().forEach(request::header);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<java.io.InputStream> response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            return (response.statusCode() == 206 || response.statusCode() == 200)
                    && "%PDF-".equals(new String(body.readNBytes(5), StandardCharsets.US_ASCII));
        }
    }

    static boolean liveQuarkGuest() {
        ShareSession.DirectLink link = ShareSession.open("https://pan.quark.cn/s/d34c18e9d62a", null).parseDirect();
        return isDirect(link) && !link.fileName().isBlank();
    }

    private static boolean isDirect(ShareSession.DirectLink link) {
        return "https".equalsIgnoreCase(URI.create(link.url()).getScheme())
                || "http".equalsIgnoreCase(URI.create(link.url()).getScheme());
    }

    private static ShareSession fixtureSession(String url) {
        ParserCreate parser = ParserCreate.fromType("lz");
        parser.getShareLinkInfo().setShareKey("b66477");
        parser.getShareLinkInfo().setShareUrl(url);
        parser.getShareLinkInfo().setStandardUrl(url);
        parser.setShareLinkInfoPwd("1234");
        return new ShareSession(parser);
    }
}
