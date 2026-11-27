package cn.qaiu.nfd.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import cn.qaiu.nfd.android.BuildConfig
import cn.qaiu.nfd.android.MainActivity
import cn.qaiu.nfd.android.NfdApp
import cn.qaiu.nfd.android.R
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tukaani.xz.XZInputStream

/** Owns the embedded JVM for the lifetime of the Android foreground service. */
class ParserService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val processLock = Any()
    private var stopping = false
    private var process: Process? = null
    private val log get() = (application as NfdApp).runtimeLog

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.EMBEDDED_RUNTIME) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        runtimeError.value = null
        worker = scope.launch {
            log.record("启动内置解析 · NFD ${BuildConfig.VERSION_NAME} · ${BuildConfig.RUNTIME_ABI} · " +
                "Android API ${Build.VERSION.SDK_INT} · 页大小 ${Os.sysconf(OsConstants._SC_PAGESIZE)}")
            try {
                val exit = runParser()
                if (!synchronized(processLock) { stopping }) {
                    log.record("解析运行时已退出（$exit）")
                    runtimeError.value = "解析运行时已退出（$exit）"
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: IOException) {
                log.record("无法启动内置解析运行时（${ex.javaClass.simpleName}）")
                runtimeError.value = "无法启动内置解析运行时（${ex.javaClass.simpleName}）"
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runtimeError.value = "已达到系统后台运行时限，重新打开应用后可继续下载"
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(processLock) {
            stopping = true
            process?.destroy()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun runParser(): Int {
        val app = application as NfdApp
        val packageTimestamp = File(applicationInfo.sourceDir).lastModified()
        val installation = installRuntime(packageTimestamp.toString())
        val compatibility = File(installation, ".interpreter")
        val interpreter = compatibility.isFile
        val initial = launchParser(app, installation, interpreter)
        if (!interpreter && initial in JVM_CRASH_EXIT_CODES
            && !synchronized(processLock) { stopping }) {
            log.record("JVM 异常退出（$initial），自动尝试兼容模式")
            return launchParser(app, installation, interpreter = true, onReady = {
                runCatching { compatibility.createNewFile() }
            })
        }
        return initial
    }

    private fun launchParser(
        app: NfdApp,
        installation: File,
        interpreter: Boolean,
        onReady: () -> Unit = {},
    ): Int {
        val image = File(installation, "jre")
        val daemon = File(installation, "daemon")
        val data = File(filesDir, "embedded-daemon").apply { mkdirs() }
        val temp = File(cacheDir, "embedded-temp").apply { mkdirs() }
        val downloads = downloadRoot(this).apply { mkdirs() }
        val launcher = File(applicationInfo.nativeLibraryDir, "libnfd_runtime.so")
        val aria2 = File(applicationInfo.nativeLibraryDir, "libnfd_aria2c.so")
        val classpath = File(daemon, "lib").listFiles { f -> f.isFile && f.extension == "jar" }
            ?.sortedBy { it.name }?.joinToString(File.pathSeparator) { it.absolutePath }
            ?.takeIf { it.isNotEmpty() } ?: throw IOException("missing daemon classpath")
        val endpoint = app.prefs.endpoint
        val builder = ProcessBuilder(
            launcher.absolutePath, image.absolutePath, classpath,
            "--home", data.absolutePath, "--bind", endpoint.host, "--port", endpoint.port.toString(),
        ).directory(temp).redirectErrorStream(true)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", File(image, "lib").absolutePath + ":" + File(image, "lib/server").absolutePath)
            put("TMPDIR", temp.absolutePath)
            put("NFD_LOCAL_TOKEN", endpoint.token)
            put("NFD_LOCAL_DOWNLOADS", downloads.absolutePath)
            if (aria2.canExecute()) {
                put("NFD_ARIA2C", aria2.absolutePath)
                put("NFD_ARIA2C_CA", File(image, "lib/security/aria2-ca.pem").absolutePath)
            }
            if (BuildConfig.DEBUG) put("NFD_VERIFY_STARTUP", "1")
            if (interpreter) put("NFD_JVM_COMPAT", "1")
            put("JAVA_TOOL_OPTIONS", "-Dlogback.configurationFile=\"${File(daemon, "conf/logback.xml")}\"")
        }
        if (interpreter) log.record("使用 JVM 兼容模式")
        val child = synchronized(processLock) {
            if (stopping) throw CancellationException()
            builder.start().also { process = it }
        }
        try {
            var ready = false
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    runtimeDiagnostic(line)?.let(log::record)
                    if (!ready && line == "NFD_RUNTIME daemon-ready") {
                        ready = true
                        onReady()
                    }
                }
            }
            return child.waitFor()
        } finally {
            child.destroy()
            synchronized(processLock) {
                if (process === child) process = null
            }
        }
    }

    private fun installRuntime(version: String): File {
        val target = File(filesDir, "embedded-runtime")
        val stamp = File(target, ".installed")
        if (stamp.isFile && stamp.readText() == version) return target
        log.record("安装内置运行时")

        // A new APK invalidates the image. A partial extraction is never marked as installed.
        val staging = File(filesDir, "embedded-runtime-install")
        if (!target.deleteRecursively() || !staging.deleteRecursively() || !staging.mkdirs()) {
            throw IOException("cannot replace runtime image")
        }
        try {
            unpack("runtime-${BuildConfig.RUNTIME_ABI}.zip.xz", File(staging, "jre"))
            unpack("daemon.zip", File(staging, "daemon"))
            File(staging, ".installed").writeText(version)
            if (!staging.renameTo(target)) throw IOException("cannot commit runtime image")
            log.record("内置运行时安装完成")
            return target
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unpack(asset: String, target: File) {
        val base = target.canonicalPath + File.separator
        assets.open(asset).buffered().use { raw ->
            val archive = if (asset.endsWith(".xz")) XZInputStream(raw, 32 * 1024) else raw
            ZipInputStream(archive).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = File(target, entry.name).canonicalFile
                    if (!output.path.startsWith(base)) throw IOException("invalid runtime archive entry")
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { zip.copyTo(it) }
                        if (!output.setReadOnly()) throw IOException("cannot lock runtime file")
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "内置解析", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NFD 网盘直链")
            .setContentText("本机解析和下载服务正在运行")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "embedded-parser"
        private const val NOTIFICATION_ID = 78
        private val JVM_CRASH_EXIT_CODES = setOf(132, 134, 135, 139, 159)
        private val runtimeError = MutableStateFlow<String?>(null)
        val error = runtimeError.asStateFlow()

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ParserService::class.java))
            } catch (ex: IllegalStateException) {
                runtimeError.value = "请在应用前台启动内置解析"
                (context.applicationContext as NfdApp).runtimeLog.record("系统拒绝在当前状态启动解析服务")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ParserService::class.java))
        }

        fun downloadRoot(context: Context): File = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "embedded-downloads")
    }
}
