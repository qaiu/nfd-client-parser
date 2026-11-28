package cn.qaiu.nfd.android.runtime

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, local diagnostics. Runtime output is only recorded after matching a known format. */
class RuntimeLog(file: File) {
    private val storage = AtomicFile(file)
    private val messages = MutableStateFlow(
        runCatching { storage.openRead().bufferedReader().use { it.readLines().takeLast(LIMIT) } }
            .getOrDefault(emptyList()),
    )
    val entries = messages.asStateFlow()

    @Synchronized
    fun record(message: String) {
        val entry = "${LocalDateTime.now().format(TIME)}  $message"
        val updated = (messages.value + entry).takeLast(LIMIT)
        messages.value = updated
        val output = try {
            storage.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            output.write(updated.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            storage.finishWrite(output)
        } catch (_: IOException) {
            storage.failWrite(output)
        }
    }

    companion object {
        private const val LIMIT = 200
        private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}

/** Turns launcher and HotSpot diagnostics into messages safe to persist and copy. */
internal fun runtimeDiagnostic(line: String): String? {
    if (line.startsWith("NFD_EVENT ")) return eventDiagnostic(line)
    return when (line.trim()) {
        "NFD_RUNTIME load" -> "加载 OpenJDK"
        "NFD_RUNTIME load-failed" -> "无法加载内置 OpenJDK 库"
        "NFD_RUNTIME heap-tagging-unavailable" -> "当前系统未提供 JVM 指针标签兼容设置"
        "NFD_RUNTIME init" -> "初始化 JVM"
        "NFD_RUNTIME init-failed" -> "JVM 初始化失败"
        "NFD_RUNTIME java-ready" -> "JVM 已启动"
        "NFD_RUNTIME daemon-init" -> "初始化解析器"
        "NFD_RUNTIME verify-start" -> "正在运行本地解析自检"
        "NFD_RUNTIME verify-passed" -> "本地解析自检通过"
        "NFD_RUNTIME verify-failed" -> "本地解析自检失败"
        "NFD_RUNTIME daemon-ready" -> "解析引擎已就绪"
        "NFD_RUNTIME java-failed" -> "启动解析服务时发生 Java 异常"
        else -> {
            val signal = JVM_SIGNAL.find(line)?.groupValues?.get(1)
            val frame = JVM_FRAME.matchEntire(line)?.groupValues?.get(1)
            val internal = JVM_INTERNAL.find(line)?.groupValues?.get(1)
            when {
                signal != null -> "JVM 信号 $signal"
                frame != null -> "故障位置 $frame"
                internal != null -> "JVM 内部错误 $internal"
                POINTER_TAG_ERROR.containsMatchIn(line) -> "Android 检测到 JVM 指针标签被截断"
                line.startsWith("Cannot create worker GC thread") -> "JVM 无法创建垃圾回收线程"
                line.contains("failed to map segment from shared object") -> "系统拒绝映射运行时库"
                else -> null
            }
        }
    }
}

private val JVM_SIGNAL = Regex("^#\\s+(SIGABRT|SIGBUS|SIGFPE|SIGILL|SIGSEGV|SIGSYS|SIGTRAP)\\b")
private val JVM_FRAME = Regex("^#\\s+[A-Za-z]\\s+\\[([A-Za-z0-9_.+-]+\\+0x[0-9a-fA-F]+)\\].*")
private val JVM_INTERNAL = Regex("^#\\s+Internal Error \\((?:[^\\s()]*/)?([A-Za-z0-9_.]+:[0-9]+)\\)")
private val POINTER_TAG_ERROR = Regex("^(?:libc: )?Pointer tag for 0x[0-9a-fA-F]+ was truncated\\b")


private val parseList = Regex("""NFD_EVENT parse list-ok ([a-z0-9_]{1,20}) (\d{1,10}) (\d{1,10}) (file|folder) (\d{1,12})""")
private val parseEvent = Regex("""NFD_EVENT parse (list-start|list-fail|resolve-start|resolve-ok|resolve-fail) ([a-z0-9_]{1,20})(?: (\d{1,12}))?""")
private val downloadEvent = Regex("""NFD_EVENT download ([a-f0-9-]{36}) (.+)""")
private val downloadNumber = Regex("""(range|aria2|aria2-exit) (\d{1,3})""")
private val downloadDone = Regex("""done (COMPLETED|FAILED|PAUSED) (\d{1,3})""")
private val fallbackReasons = mapOf(
    "unavailable" to "aria2c 未启用",
    "no-range" to "服务器未返回可用的分段响应",
    "existing-progress" to "继续已有下载进度",
    "single-connection" to "设置仅允许单连接",
    "small-file" to "文件小于分段门槛",
    "unsupported-input" to "地址或文件格式不适用",
    "special-headers" to "链接需要特殊请求头",
    "fallback" to "aria2c 未完成，本次回退",
)
private val statuses = mapOf("COMPLETED" to "完成", "FAILED" to "失败", "PAUSED" to "已暂停")

private fun eventDiagnostic(line: String): String? {
    parseList.matchEntire(line)?.destructured?.let { (type, page, count, kind, ms) ->
        val mode = if (kind == "file") "按单文件解析" else "目录"
        return "解析 [$type] $mode 第 $page 页返回 $count 项 · ${ms} ms"
    }
    parseEvent.matchEntire(line)?.destructured?.let { (operation, type, number) ->
        return when (operation) {
            "list-start" -> "解析 [$type] 开始读取第 $number 页"
            "list-fail" -> "解析 [$type] 列表失败 · ${number} ms"
            "resolve-start" -> "解析 [$type] 开始获取下载直链"
            "resolve-ok" -> "解析 [$type] 已取得下载直链 · ${number} ms"
            else -> "解析 [$type] 直链获取失败 · ${number} ms"
        }
    }
    val (id, event) = downloadEvent.matchEntire(line)?.destructured ?: return null
    val detail = downloadNumber.matchEntire(event)?.destructured?.let { (stage, value) ->
        when (stage) {
            "range" -> "分段探测 HTTP $value"
            "aria2" -> "启动 aria2c · 上限 $value 个连接"
            else -> "aria2c 退出码 $value"
        }
    } ?: downloadDone.matchEntire(event)?.destructured?.let { (state, count) ->
        "${statuses[state]} · 峰值 $count 个活动连接"
    } ?: when {
        event == "aria2-io" -> "aria2c 启动或读写失败"
        event.startsWith("java ") -> fallbackReasons[event.removePrefix("java ")]?.let { "Java 下载器 · $it" }
        else -> null
    } ?: return null
    return "下载 [${id.take(8)}] $detail"
}
