package cn.qaiu.nfd.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pause/resume downloader used when an aria2 binary or RPC endpoint is not available.
 * aria2 remains the preferred engine; this one keeps the same progress controls.
 */
class RangeTransferEngine(
    private val directory: File,
    override val displayName: String = "内置传输（未找到 aria2）"
) : TransferEngine {
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val workers: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "nfd-range").apply { isDaemon = true }
    }
    private val tasks = CopyOnWriteArrayList<Job>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val closed = AtomicBoolean(false)

    override fun start(request: TransferRequest): String {
        check(!closed.get()) { "下载器已关闭" }
        val job = Job(
            id = UUID.randomUUID().toString(),
            fileName = safeFileName(request.fileName),
            url = request.url,
            headers = request.headers,
            directory = request.directory
        )
        tasks.add(job)
        workers.submit { run(job) }
        notifyListeners()
        return job.id
    }

    fun startInDefaultDirectory(fileName: String, url: String, headers: Map<String, String> = emptyMap()): String {
        return start(TransferRequest(url, fileName, directory, headers))
    }

    override fun pause(id: String) {
        tasks.firstOrNull { it.id == id }?.requestPause()
    }

    override fun resume(id: String) {
        val job = tasks.firstOrNull { it.id == id } ?: return
        if (closed.get()) return
        if (job.status != TransferStatus.PAUSED && job.status != TransferStatus.FAILED) return
        job.pauseRequested.set(false)
        job.message = ""
        workers.submit { run(job) }
        notifyListeners()
    }

    override fun snapshots(): List<TransferSnapshot> = tasks.map { it.snapshot() }

    override fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        tasks.forEach { it.requestPause() }
        workers.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun run(job: Job) {
        if (job.pauseRequested.get() || closed.get()) {
            job.status = TransferStatus.PAUSED
            notifyListeners()
            return
        }
        job.status = TransferStatus.RUNNING
        notifyListeners()
        try {
            job.directory.mkdirs()
            if (job.partial == null) {
                job.partial = File(job.directory, job.fileName + ".nfdpart")
                job.destination = File(job.directory, uniqueName(job.directory, job.fileName))
            }
            var existing = job.partial?.takeIf { it.exists() }?.length() ?: 0L
            if (job.total > 0 && existing >= job.total) {
                finish(job)
                return
            }
            val builder = Request.Builder().url(job.url).get()
            var hasUserAgent = false
            job.headers.forEach { (name, value) ->
                if (!name.equals("Range", true) && !name.equals("Host", true)) {
                    if (name.equals("User-Agent", true)) hasUserAgent = true
                    builder.header(name, value)
                }
            }
            if (!hasUserAgent) builder.header("User-Agent", "NFDClient/0.1")
            if (existing > 0) builder.header("Range", "bytes=$existing-")
            http.newCall(builder.build()).execute().use { response ->
                val code = response.code
                if (code == 416) {
                    finish(job)
                    return
                }
                if (code == 200 && existing > 0) {
                    existing = 0L
                    job.partial?.delete()
                }
                if (code != 200 && code != 206) {
                    throw java.io.IOException("HTTP $code")
                }
                job.total = totalLength(response.header("Content-Range"), response.header("Content-Length"), code, existing)
                job.downloaded = existing
                val partial = job.partial ?: error("partial file missing")
                response.body.byteStream().use { input ->
                    job.stream = input
                    java.io.FileOutputStream(partial, existing > 0).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (job.pauseRequested.get() || closed.get()) {
                                job.status = TransferStatus.PAUSED
                                job.message = "已暂停"
                                return
                            }
                            output.write(buffer, 0, read)
                            job.downloaded += read
                            notifyListeners()
                        }
                    }
                }
                job.stream = null
            }
            if (job.pauseRequested.get() || closed.get()) {
                job.status = TransferStatus.PAUSED
                job.message = "已暂停"
                return
            }
            finish(job)
        } catch (ex: Exception) {
            if (job.pauseRequested.get() || closed.get()) {
                job.status = TransferStatus.PAUSED
                job.message = "已暂停"
            } else {
                job.status = TransferStatus.FAILED
                job.message = ex.message ?: ex.javaClass.simpleName
            }
        } finally {
            job.stream = null
            notifyListeners()
        }
    }

    private fun finish(job: Job) {
        val partial = job.partial
        val destination = job.destination
        if (partial != null && partial.exists() && destination != null) {
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
        }
        if (destination != null && destination.exists()) {
            job.downloaded = destination.length()
            if (job.total < job.downloaded) job.total = job.downloaded
        }
        job.status = TransferStatus.COMPLETED
        job.message = "完成"
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (_: RuntimeException) {
            }
        }
    }

    private class Job(
        val id: String,
        val fileName: String,
        val url: String,
        val headers: Map<String, String>,
        val directory: File
    ) {
        val pauseRequested = AtomicBoolean(false)

        @Volatile var status = TransferStatus.RUNNING

        @Volatile var downloaded = 0L

        @Volatile var total = -1L

        @Volatile var message = ""

        @Volatile var partial: File? = null

        @Volatile var destination: File? = null

        @Volatile var stream: InputStream? = null

        fun requestPause() {
            pauseRequested.set(true)
            try {
                stream?.close()
            } catch (_: Exception) {
            }
        }

        fun snapshot() = TransferSnapshot(id, fileName, status, downloaded, total, 0, message)
    }
}

private fun totalLength(contentRange: String?, contentLength: String?, code: Int, existing: Long): Long {
    if (contentRange != null && contentRange.contains("/")) {
        val tail = contentRange.substringAfterLast("/").trim()
        if (tail != "*" && tail.isNotEmpty()) {
            tail.toLongOrNull()?.let { return it }
        }
    }
    val length = contentLength?.toLongOrNull() ?: -1L
    if (length >= 0 && code == 200) return length
    if (length >= 0 && code == 206) return existing + length
    return -1L
}

private fun uniqueName(directory: File, fileName: String): String {
    if (!File(directory, fileName).exists()) return fileName
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0) fileName.substring(dot) else ""
    for (index in 2 until 1000) {
        val candidate = "$base ($index)$ext"
        if (!File(directory, candidate).exists()) return candidate
    }
    return "$base-${System.currentTimeMillis()}$ext"
}
