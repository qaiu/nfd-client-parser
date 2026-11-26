package cn.qaiu.nfd.android

import cn.qaiu.nfd.android.daemon.DaemonClient
import cn.qaiu.nfd.android.daemon.DaemonException
import cn.qaiu.nfd.android.daemon.Endpoint
import cn.qaiu.nfd.android.daemon.Entry
import cn.qaiu.nfd.android.daemon.TaskStatus
import cn.qaiu.nfd.android.termux.BootstrapServer
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DaemonClientTest {

    private val server = MockWebServer()
    private lateinit var client: DaemonClient

    @BeforeEach
    fun start() {
        server.start()
        client = DaemonClient({ Endpoint(server.hostName, server.port, "secret-token") })
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun listsAndSendsTheToken() = runBlocking {
        server.enqueue(json("""{"panName":"蓝奏云","type":"lz","entries":[
            {"fileName":"docs","fileId":"1","fileType":"folder","size":-1,"sizeText":"","parserUrl":"/v2/getFileList?dirId=1","folder":true},
            {"fileName":"a.zip","fileId":"2","fileType":"zip","size":10,"sizeText":"10 B","parserUrl":"/v2/redirectUrl/lz/x","folder":false}]}"""))
        val listing = client.list("https://pan.example/s/1", " 1234 ", null)
        assertEquals("蓝奏云", listing.panName)
        assertEquals(2, listing.entries.size)
        assertTrue(listing.entries[0].folder)
        val request = server.takeRequest()
        assertEquals("secret-token", request.headers["X-NFD-Token"])
        val body = JSONObject(request.body!!.utf8())
        assertEquals("1234", body.getString("password"))
        assertTrue(body.isNull("folder"))
    }

    @Test
    fun downloadsSkipFoldersAndParseTasks() = runBlocking {
        server.enqueue(json("""{"tasks":[{"id":"t1","fileName":"a.zip","status":"RUNNING","statusLabel":"下载中",
            "downloaded":5,"total":10,"progress":0.5,"progressText":"5 B / 10 B","message":"","destination":"","resumable":true,"link":"x"}]}"""))
        val folder = Entry("docs", "1", "folder", -1, "", "/v2/getFileList?dirId=1", true)
        val file = Entry("a.zip", "2", "zip", 10, "10 B", "/v2/redirectUrl/lz/x", false)
        val tasks = client.downloadEntries("https://pan.example/s/1", "", listOf(folder, file))
        assertEquals(TaskStatus.RUNNING, tasks.single().status)
        assertEquals(0.5, tasks.single().progress)
        val sent = JSONObject(server.takeRequest().body!!.utf8()).getJSONArray("entries")
        assertEquals(1, sent.length())
        assertEquals(file, Entry.from(sent.getJSONObject(0)))
    }

    @Test
    fun mapsErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"令牌无效"}""").build())
        val unauthorized = assertThrows<DaemonException> { runBlocking { client.tasks() } }
        assertEquals(DaemonException.Kind.UNAUTHORIZED, unauthorized.kind)

        server.enqueue(MockResponse.Builder().code(422).body("""{"error":"分享已失效：分享不存在"}""").build())
        val rejected = assertThrows<DaemonException> { runBlocking { client.list("x", "", null) } }
        assertEquals(DaemonException.Kind.REJECTED, rejected.kind)
        assertEquals("分享已失效：分享不存在", rejected.message)

        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = DaemonClient({ Endpoint("127.0.0.1", closedPort, "t") })
        val error = assertThrows<DaemonException> { runBlocking { offline.health() } }
        assertEquals(DaemonException.Kind.OFFLINE, error.kind)
    }

    @Test
    fun bootstrapServerOnlyServesKnownFilesUnderItsKey() {
        val served = mutableListOf<String>()
        BootstrapServer(open = { name -> ByteArrayInputStream("content of $name".toByteArray()) }, onServed = { served += it }).start().use { bootstrap ->
            val ok = URL("${bootstrap.baseUrl}/install.sh").openConnection() as HttpURLConnection
            assertEquals(200, ok.responseCode)
            assertEquals("content of install.sh", ok.inputStream.readBytes().decodeToString())
            assertEquals(404, (URL("http://127.0.0.1:${bootstrap.port}/install.sh").openConnection() as HttpURLConnection).responseCode)
            assertEquals(404, (URL("${bootstrap.baseUrl}/../etc/passwd").openConnection() as HttpURLConnection).responseCode)
            assertEquals(listOf("install.sh"), served)
            assertFalse(bootstrap.key.isBlank())
        }
    }

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()
}
