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

    @Test
    void entryAndSourceRoundTrip() {
        ShareSession.Entry entry = new ShareSession.Entry("a.zip", "1", "zip", 10, "10 B", "/v2/redirectUrl/lz/xyz", false);
        assertEquals(entry, ShareSession.Entry.fromJson(entry.toJson()));
        var source = ShareSession.source("https://pan.example/s/1", " 1234 ", entry);
        assertEquals("1234", source.get(ShareSession.SOURCE_PASSWORD));
        assertEquals(entry, ShareSession.Entry.fromJson(new io.vertx.core.json.JsonObject(source.get(ShareSession.SOURCE_ENTRY))));
        assertFalse(ShareSession.source("x", "", null).containsKey(ShareSession.SOURCE_PASSWORD));
    }

    @Test
    void extractsPasswordFromShareText() {
        assertEquals("abcd", ShareSession.extractPassword("链接：https://pan.baidu.com/s/1xyz 提取码：abcd 复制这段内容"));
        assertEquals("8k2p", ShareSession.extractPassword("https://www.123pan.com/s/abc?pwd=8k2p"));
        assertEquals("1234", ShareSession.extractPassword("https://pan.lanzoui.com/i1 密码:1234"));
        assertEquals("", ShareSession.extractPassword("https://pan.quark.cn/s/abcdef"));
        assertEquals("", ShareSession.extractPassword("pwd 123456789 is too long"));
    }

    @Test
    void friendlyErrors() {
        RuntimeException gone = new RuntimeException("夸克网盘-qk: url=x - 解析异常: token 返回异常: {\"status\":404,\"message\":\"分享不存在\"}");
        assertEquals("分享已失效：分享不存在", ShareErrors.friendly(new RuntimeException(gone)));
        assertEquals("暂不支持该分享链接", ShareErrors.friendly(new IllegalArgumentException("Unsupported share URL")));
        assertEquals("网络不可用或无法解析网盘域名",
                ShareErrors.friendly(new RuntimeException(new java.net.UnknownHostException("pan.quark.cn"))));
        assertEquals("解析超时，网盘服务器响应过慢，请稍后重试",
                ShareErrors.friendly(new RuntimeException(new java.util.concurrent.TimeoutException())));
        assertTrue(ShareErrors.friendly(new RuntimeException("密码错误")).startsWith("提取码错误"));
        assertEquals("解析失败：boom", ShareErrors.friendly(new IllegalStateException("boom")));
    }
}
