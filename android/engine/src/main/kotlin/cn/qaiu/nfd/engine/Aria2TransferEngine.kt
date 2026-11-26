package cn.qaiu.nfd.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aria2TransferEngine(
    private val client: Aria2Client,
    override val displayName: String
) : TransferEngine {
    private val jobs = ConcurrentHashMap<String, TransferSnapshot>()
    private val order = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val poller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nfd-aria2").apply { isDaemon = true }
    }

    init {
        poller.scheduleAtFixedRate({ refresh() }, 300, 400, TimeUnit.MILLISECONDS)
    }

    override fun start(request: TransferRequest): String {
        val directory = request.directory.apply { mkdirs() }
        val gid = client.addUri(
            request.url,
            directory.absolutePath,
            safeFileName(request.fileName),
            request.headers
        )
        jobs[gid] = TransferSnapshot(gid, safeFileName(request.fileName), TransferStatus.RUNNING, 0, -1, 0, "")
        order.add(gid)
        notifyListeners()
        return gid
    }

    override fun pause(id: String) {
        try {
            client.pause(id)
        } catch (ex: Exception) {
            update(id) { it.copy(status = TransferStatus.FAILED, message = ex.message ?: "暂停失败") }
            return
        }
        update(id) { it.copy(status = TransferStatus.PAUSED, speedBytesPerSec = 0, message = "已暂停") }
    }

    override fun resume(id: String) {
        try {
            client.unpause(id)
        } catch (ex: Exception) {
            update(id) { it.copy(status = TransferStatus.FAILED, message = ex.message ?: "继续失败") }
            return
        }
        update(id) { it.copy(status = TransferStatus.RUNNING, message = "") }
    }

    override fun snapshots(): List<TransferSnapshot> = order.mapNotNull { jobs[it] }

    override fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    override fun shutdown() {
        poller.shutdownNow()
        client.close()
    }

    private fun refresh() {
        for (id in order) {
            val current = jobs[id] ?: continue
            if (current.status == TransferStatus.COMPLETED || current.status == TransferStatus.FAILED) {
                continue
            }
            try {
                val status = client.tellStatus(id)
                val mapped = when (status.status) {
                    "active", "waiting" -> TransferStatus.RUNNING
                    "paused" -> TransferStatus.PAUSED
                    "complete" -> TransferStatus.COMPLETED
                    else -> TransferStatus.FAILED
                }
                jobs[id] = current.copy(
                    status = mapped,
                    downloaded = status.completed,
                    total = if (status.total > 0) status.total else -1,
                    speedBytesPerSec = status.speed,
                    message = status.error ?: if (mapped == TransferStatus.COMPLETED) "完成" else current.message
                )
            } catch (ex: Exception) {
                jobs[id] = current.copy(status = TransferStatus.FAILED, message = ex.message ?: "状态查询失败")
            }
        }
        notifyListeners()
    }

    private fun update(id: String, transform: (TransferSnapshot) -> TransferSnapshot) {
        val current = jobs[id] ?: return
        jobs[id] = transform(current)
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (_: RuntimeException) {
                // UI listeners must not break the poller.
            }
        }
    }
}
