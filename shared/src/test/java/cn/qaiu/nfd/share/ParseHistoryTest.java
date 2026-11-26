package cn.qaiu.nfd.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParseHistoryTest {

    @TempDir
    Path dir;

    private static ShareSession.Entry file(String name, long size) {
        return new ShareSession.Entry(name, name, "file", size, "", "u:" + name, false);
    }

    private static ShareSession.Listing listing(boolean hasMore, ShareSession.Entry... entries) {
        return new ShareSession.Listing("蓝奏云", "lz", List.of(entries), 1, hasMore, null, false, "");
    }

    @Test
    void summarisesListings() {
        ShareSession.Entry folder = new ShareSession.Entry("docs", "d", "folder", 0, "", null, true);
        ParseHistory.Item item = ParseHistory.Item.succeeded("l", "pw",
                listing(true, folder, file("a.apk", 100), file("b", -1)), 9);
        assertEquals("docs 等 3 项", item.title());
        assertEquals(2, item.fileCount());
        assertEquals(1, item.folderCount());
        assertEquals(100, item.totalSize());
        assertFalse(item.sizeComplete());
        assertTrue(item.hasMore());
        assertEquals("空目录", ParseHistory.Item.succeeded("l", "", listing(false), 1).title());
    }

    @Test
    void oneEntryPerLinkNewestFirstAndPersisted() throws Exception {
        Path path = dir.resolve("history.json");
        ParseHistory history = new ParseHistory(path, 2);
        history.record(ParseHistory.Item.failed("https://x/a", "", "分享已失效", 1));
        history.record(ParseHistory.Item.succeeded("https://x/b", "", listing(false, file("b", 1)), 2));
        history.record(ParseHistory.Item.succeeded("https://x/a/", "pw", listing(true, file("a", 5)), 3));
        assertEquals(List.of("https://x/a/", "https://x/b"),
                new ParseHistory(path).items().stream().map(ParseHistory.Item::link).toList());

        history.update("https://x/a", "pw", listing(false, file("a", 5), file("c", 5)));
        ParseHistory.Item top = new ParseHistory(path).items().get(0);
        assertEquals(2, top.fileCount());
        assertEquals(3, top.time());
        assertFalse(top.hasMore());

        history.record(ParseHistory.Item.failed("c", "", "e", 4));
        assertEquals(List.of("c", "https://x/a/"), history.items().stream().map(ParseHistory.Item::link).toList());
        history.remove(history.items().get(0));
        assertEquals(1, new ParseHistory(path).items().size());
        history.clear();
        assertTrue(new ParseHistory(path).items().isEmpty());
    }

    @Test
    void corruptFileStartsEmpty() throws Exception {
        Path path = dir.resolve("history.json");
        Files.writeString(path, "{oops");
        assertTrue(new ParseHistory(path).items().isEmpty());
    }
}
