package cn.qaiu.nfd.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FormatsTest {

    @Test
    void sanitizesFileNames() {
        assertEquals("a_b_c.txt", Formats.safeFileName("a/b\\c.txt"));
        assertEquals("download", Formats.safeFileName("  ..  "));
        assertEquals("_CON.txt", Formats.safeFileName("CON.txt"));
        assertEquals("x_y.zip", Formats.safeFileName("x:y.zip."));
        String longName = "长".repeat(200) + ".mp4";
        String safe = Formats.safeFileName(longName);
        assertTrue(safe.endsWith(".mp4"));
        assertTrue(safe.getBytes(StandardCharsets.UTF_8).length <= 200);
    }

    @Test
    void formatsSizesAndEta() {
        assertEquals("未知", Formats.size(-1));
        assertEquals("512 B", Formats.size(512));
        assertEquals("1.5 MB", Formats.size(1536 * 1024));
        assertEquals("01:05", Formats.eta(65));
        assertEquals("1:01:01", Formats.eta(3661));
        assertEquals("", Formats.eta(-1));
    }

    @Test
    void parsesContentDisposition() {
        assertEquals("测试 1.zip", TransferRunner.fileNameFromDisposition("attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%201.zip"));
        assertEquals("a b.txt", TransferRunner.fileNameFromDisposition("attachment; filename=\"a b.txt\""));
        assertEquals("plain.bin", TransferRunner.fileNameFromDisposition("inline; filename=plain.bin"));
        assertEquals("x.bin", TransferRunner.fileNameFromDisposition("attachment; filename=\"../../x.bin\""));
        assertEquals(null, TransferRunner.fileNameFromDisposition("attachment"));
    }

    @Test
    void prefersServerNameOnlyWhenTheListedNameIsTruncated() {
        assertTrue(TransferRunner.betterName("", "a.zip"));
        assertTrue(TransferRunner.betterName("download", "a.zip"));
        assertTrue(TransferRunner.betterName("漫畫迷  已更新!最快，最全的港漫", "漫畫迷  已更新!最快，最全的港漫，国漫.apk"));
        assertFalse(TransferRunner.betterName("my.zip", "server-name.zip"));
        assertFalse(TransferRunner.betterName("notes", "x"));
    }
}
