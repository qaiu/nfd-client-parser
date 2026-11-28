package cn.qaiu.nfd.android.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RuntimeLogTest {
    @Test
    fun recognizesStartupAndCrashWithoutKeepingRuntimeArguments() {
        assertEquals("JVM 已启动", runtimeDiagnostic("NFD_RUNTIME java-ready"))
        assertEquals("解析引擎已就绪", runtimeDiagnostic("NFD_RUNTIME daemon-ready"))
        assertEquals("JVM 信号 SIGABRT", runtimeDiagnostic("#  SIGABRT (0x6) at pc=0x12"))
        assertEquals("故障位置 libjvm.so+0x123", runtimeDiagnostic("# V  [libjvm.so+0x123]"))
        assertEquals("JVM 内部错误 os_linux.cpp:222", runtimeDiagnostic("#  Internal Error (/build/os_linux.cpp:222)"))
        assertEquals("Android 检测到 JVM 指针标签被截断", runtimeDiagnostic("libc: Pointer tag for 0x7fff54033750 was truncated"))
    }

    @Test
    fun rejectsArbitraryRuntimeOutput() {
        assertNull(runtimeDiagnostic("NFD_RUNTIME daemon-ready https://example.test/s/share"))
        assertNull(runtimeDiagnostic("NFD_LOCAL_TOKEN=test-fixture-token"))
        assertNull(runtimeDiagnostic("WARN parser test https://example.test/s/share"))
        assertNull(runtimeDiagnostic("Cookie: sample=value"))
    }

    @Test
    fun showsParserAndDownloaderEventsWithOnlyStructuredData() {
        val task = "NFD_EVENT download 12345678-1234-1234-1234-123456789abc"
        assertEquals("解析 [lz] 目录 第 2 页返回 50 项 · 20 ms", runtimeDiagnostic("NFD_EVENT parse list-ok lz 2 50 folder 20"))
        assertEquals("下载 [12345678] 分段探测 HTTP 206", runtimeDiagnostic("$task range 206"))
        assertEquals("下载 [12345678] Java 下载器 · 链接需要特殊请求头", runtimeDiagnostic("$task java special-headers"))
        assertEquals("下载 [12345678] 完成 · 峰值 4 个活动连接", runtimeDiagnostic("$task done COMPLETED 4"))
        assertNull(runtimeDiagnostic("$task aria2 4 Cookie=secret"))
        assertNull(runtimeDiagnostic("NFD_EVENT parse resolve-ok lz 20 https://example.test/private"))
    }
}
