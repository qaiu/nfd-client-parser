package cn.qaiu.nfd.android

import cn.qaiu.nfd.android.daemon.Entry
import cn.qaiu.nfd.android.daemon.Listing
import cn.qaiu.nfd.android.history.HistoryItem
import cn.qaiu.nfd.android.history.ParseHistory
import cn.qaiu.nfd.android.termux.StoragePaths
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ParseHistoryTest {

    @TempDir
    lateinit var dir: Path

    private fun file(name: String, size: Long, id: String = name) = Entry(name, id, "file", size, "", "u:$id", false)
    private fun folder(name: String) = Entry(name, name, "folder", 0, "", null, true)

    @Test
    fun summarisesSuccessfulListings() {
        val listing = Listing("蓝奏云", "lz", listOf(folder("docs"), file("a.apk", 100), file("b.zip", 50)), hasMore = true)
        val item = HistoryItem.succeeded("https://x/abc", "1234", listing, time = 5)
        assertTrue(item.success)
        assertEquals("docs 等 3 项", item.title)
        assertEquals(2, item.fileCount)
        assertEquals(1, item.folderCount)
        assertEquals(150, item.totalSize)
        assertTrue(item.sizeComplete)
        assertTrue(item.hasMore)

        val single = HistoryItem.succeeded("l", "", Listing("蓝奏云", "lz", listOf(file("x.apk", -1)), singleFile = true))
        assertEquals("x.apk", single.title)
        assertFalse(single.sizeComplete)
        assertTrue(single.singleFile)
        assertEquals("空目录", HistoryItem.succeeded("l", "", Listing("p", "t", emptyList())).title)
    }

    @Test
    fun keepsOneEntryPerLinkNewestFirstAndSurvivesReload() {
        val f = dir.resolve("h.json").toFile()
        val history = ParseHistory(f, limit = 3)
        history.record(HistoryItem.failed("https://x/a", "", "分享已失效", time = 1))
        history.record(HistoryItem.succeeded("https://x/b", "", Listing("p", "t", listOf(file("b", 1))), time = 2))
        history.record(HistoryItem.succeeded("https://x/a/", "pw", Listing("p", "t", listOf(file("a", 1))), time = 3))

        val items = ParseHistory(f).items()
        assertEquals(listOf("https://x/a/", "https://x/b"), items.map { it.link })
        assertTrue(items[0].success)
        assertEquals("pw", items[0].password)

        history.record(HistoryItem.failed("c", "", "e", time = 4))
        history.record(HistoryItem.failed("d", "", "e", time = 5))
        assertEquals(listOf("d", "c", "https://x/a/"), history.items().map { it.link })

        history.remove(history.items()[1])
        assertEquals(listOf("d", "https://x/a/"), ParseHistory(f).items().map { it.link })
        history.clear()
        assertTrue(ParseHistory(f).items().isEmpty())
    }

    @Test
    fun updateRefreshesCountsAfterLoadingMorePages() {
        val history = ParseHistory(dir.resolve("h.json").toFile())
        val first = Listing("p", "t", listOf(file("a", 10)), hasMore = true, nextCursor = "c")
        history.record(HistoryItem.succeeded("l", "", first, time = 7))
        val merged = first.append(Listing("p", "t", listOf(file("a", 10), file("b", 5)), page = 2))
        val item = history.update("l", "", merged).single()
        assertEquals(2, item.fileCount)
        assertEquals(15, item.totalSize)
        assertFalse(item.hasMore)
        assertEquals(7, item.time)
    }

    @Test
    fun corruptFileStartsEmpty() {
        val f = File(dir.toFile(), "h.json").apply { writeText("{not json") }
        assertTrue(ParseHistory(f).items().isEmpty())
    }

    @Test
    fun mapsPickedFoldersToTermuxPaths() {
        val ext = "com.android.externalstorage.documents"
        val root = "/storage/emulated/0"
        assertEquals("/storage/emulated/0/Download/nfd", StoragePaths.fromTreeDocument(ext, "primary:Download/nfd", root))
        assertEquals("/storage/emulated/0", StoragePaths.fromTreeDocument(ext, "primary:", root))
        assertEquals("/storage/emulated/0/Documents", StoragePaths.fromTreeDocument(ext, "home:", root))
        assertEquals("/storage/1A2B-3C4D/Movies", StoragePaths.fromTreeDocument(ext, "1A2B-3C4D:Movies", root))
        assertEquals(
            "/storage/emulated/0/Download/x",
            StoragePaths.fromTreeDocument("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/x", root),
        )
        assertNull(StoragePaths.fromTreeDocument("com.android.providers.downloads.documents", "msd:12", root))
        assertNull(StoragePaths.fromTreeDocument("com.google.android.apps.docs.storage", "abc", root))
        assertNull(StoragePaths.fromTreeDocument(null, "primary:x", root))
    }
}
