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
}
