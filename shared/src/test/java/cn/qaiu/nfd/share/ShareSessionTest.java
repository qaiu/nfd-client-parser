package cn.qaiu.nfd.share;

import cn.qaiu.entity.FileInfo;
import cn.qaiu.util.CommonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareSessionTest {

    @Test
    void recognizesShareHostsWithoutContactingThem() {
        ShareSession quark = ShareSession.open("请看 https://pan.quark.cn/s/abcdef 谢谢", null);
        assertEquals("qk", quark.type());
        assertFalse(quark.panName().isBlank());

        ShareSession lanzou = ShareSession.open("https://w1.lanzn.com/abc123", "8888");
        assertEquals("lz", lanzou.type());
        assertEquals("8888", lanzou.password());

        ShareSession feiji = ShareSession.open("https://share.feijipan.com/s/3pMsofZd", null);
        assertEquals("fj", feiji.type());
    }

    @Test
    void readsPasswordEmbeddedInTheLink() {
        ShareSession session = ShareSession.open("https://ctfile.com/file/abc123?p=9999", null);
        assertEquals("ct", session.type());
        assertEquals("9999", session.password());
    }

    @Test
    void explicitPasswordReplacesTheLinkPassword() {
        ShareSession session = ShareSession.open("https://ctfile.com/file/abc123?p=9999", "0000");
        assertEquals("0000", session.password());
    }

    @Test
    void decodesTheParserRedirectToken() {
        String json = "{\"id\":\"42\",\"fileName\":\"a.zip\"}";
        String parserUrl = "https://example.invalid/v2/redirectUrl/lz/" + CommonUtils.urlBase64Encode(json);
        ShareSession.DownloadRef ref = ShareSession.decodeParserUrl(parserUrl);
        assertNotNull(ref);
        assertEquals("lz", ref.type());
        assertTrue(ref.paramJson().contains("\"fileName\":\"a.zip\""));
        assertTrue(ref.paramJson().contains("\"id\":\"42\""));
    }

    @Test
    void classifiesFoldersAndFiles() {
        FileInfo folder = new FileInfo().setFileName("docs").setFileId("d1").setFileType("folder");
        assertTrue(ShareSession.toEntry(folder).folder());

        FileInfo nested = new FileInfo()
                .setFileName("sub")
                .setFileId("9")
                .setFileType("zip")
                .setParserUrl("/v2/getFileList?url=https%3A%2F%2Fexample.invalid&dirId=9&uuid=abc");
        ShareSession.Entry entry = ShareSession.toEntry(nested);
        assertTrue(entry.folder());
        assertEquals("9", ShareSession.queryParams(entry.parserUrl()).get("dirId"));
        assertEquals("abc", ShareSession.queryParams(entry.parserUrl()).get("uuid"));

        FileInfo file = new FileInfo().setFileName("a.zip").setFileId("2").setFileType("file").setSize(2048L);
        ShareSession.Entry fileEntry = ShareSession.toEntry(file);
        assertFalse(fileEntry.folder());
        assertEquals("2.0 KB", fileEntry.sizeText());
    }

    @Test
    void rejectsAnEmptyLink() {
        assertThrows(IllegalArgumentException.class, () -> ShareSession.open("  ", null));
    }
}
