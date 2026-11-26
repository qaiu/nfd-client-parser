package cn.qaiu.nfd.android

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.qaiu.nfd.android.download.DownloadService
import cn.qaiu.nfd.engine.TransferSnapshot
import cn.qaiu.nfd.engine.TransferStatus
import cn.qaiu.nfd.share.ShareSession
import kotlin.collections.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NfdUiState(
    val link: String = "",
    val password: String = "",
    val parsing: Boolean = false,
    val message: String = "粘贴分享链接后解析",
    val error: Boolean = false,
    val panLabel: String = "",
    val entries: List<ShareSession.Entry> = emptyList(),
    val crumbs: List<String> = emptyList(),
    val downloads: List<TransferSnapshot> = emptyList(),
    val engineName: String = "",
    val externalEnabled: Boolean = false,
    val externalHost: String = "127.0.0.1",
    val externalPort: String = "6800",
    val externalSecret: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val downloads = (application as NfdApp).downloads
    private val _state = MutableStateFlow(
        NfdUiState(
            engineName = downloads.engineName.value,
            externalEnabled = downloads.externalEnabled(),
            externalHost = downloads.externalHost(),
            externalPort = downloads.externalPort().toString(),
            externalSecret = downloads.externalSecret()
        )
    )
    val state: StateFlow<NfdUiState> = _state
    private var session: ShareSession? = null
    private val dirStack = ArrayDeque<String?>()
    private val listener: () -> Unit = {
        val snapshots = downloads.snapshots()
        _state.update { it.copy(downloads = snapshots, engineName = downloads.engineName.value) }
        syncService(snapshots)
    }

    init {
        downloads.addListener(listener)
    }

    fun updateLink(value: String) = _state.update { it.copy(link = value) }

    fun updatePassword(value: String) = _state.update { it.copy(password = value) }

    fun updateExternal(enabled: Boolean, host: String, port: String, secret: String) {
        _state.update {
            it.copy(externalEnabled = enabled, externalHost = host, externalPort = port, externalSecret = secret)
        }
    }

    fun applyEngine() {
        val current = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            val failure = if (current.externalEnabled) {
                val port = current.externalPort.toIntOrNull() ?: 6800
                downloads.useExternal(current.externalHost.trim(), port, current.externalSecret)
            } else {
                downloads.useEmbedded()
                null
            }
            _state.update {
                it.copy(
                    engineName = downloads.engineName.value,
                    message = failure ?: "下载引擎：${downloads.engineName.value}",
                    error = failure != null
                )
            }
        }
    }

    fun parse() {
        val link = _state.value.link
        val password = _state.value.password
        _state.update { it.copy(parsing = true, error = false, message = "正在解析…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val opened = ShareSession.open(link, password.ifBlank { null })
                    session = opened
                    dirStack.clear()
                    dirStack.add(null)
                    opened to opened.list(null)
                }
            }
            result.fold(
                onSuccess = { (opened, listing) ->
                    _state.update {
                        it.copy(
                            parsing = false,
                            panLabel = "${opened.panName()} · ${opened.type()}",
                            entries = listing.entries(),
                            crumbs = listOf("根目录"),
                            message = "共 ${listing.entries().size} 项",
                            error = false
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(parsing = false, error = true, message = error.message ?: "解析失败")
                    }
                }
            )
        }
    }

    fun open(entry: ShareSession.Entry) {
        val current = session ?: return
        if (entry.folder()) {
            _state.update { it.copy(parsing = true, error = false, message = "正在打开文件夹…") }
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { current.openFolder(entry) } }
                result.fold(
                    onSuccess = { listing ->
                        dirStack.add(entry.fileId())
                        val crumbs = _state.value.crumbs + (entry.fileName().ifBlank { "文件夹" })
                        _state.update {
                            it.copy(
                                parsing = false,
                                entries = listing.entries(),
                                crumbs = crumbs,
                                message = "共 ${listing.entries().size} 项"
                            )
                        }
                    },
                    onFailure = { error ->
                        _state.update { it.copy(parsing = false, error = true, message = error.message ?: "打开失败") }
                    }
                )
            }
            return
        }
        resolveAndDownload(entry)
    }

    fun back() {
        val current = session ?: return
        if (dirStack.size <= 1) return
        dirStack.removeLast()
        val dirId = dirStack.last()
        _state.update { it.copy(parsing = true, message = "正在返回…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { current.list(dirId) } }
            result.fold(
                onSuccess = { listing ->
                    val crumbs = _state.value.crumbs.dropLast(1).ifEmpty { listOf("根目录") }
                    _state.update {
                        it.copy(
                            parsing = false,
                            entries = listing.entries(),
                            crumbs = crumbs,
                            message = "共 ${listing.entries().size} 项",
                            error = false
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(parsing = false, error = true, message = error.message ?: "返回失败") }
                }
            )
        }
    }

    fun parseDirect() {
        val link = _state.value.link
        val password = _state.value.password
        _state.update { it.copy(parsing = true, message = "正在解析直链…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val opened = session ?: ShareSession.open(link, password.ifBlank { null }).also { session = it }
                    opened.parseDirect()
                }
            }
            result.fold(
                onSuccess = { direct ->
                    val name = direct.fileName().ifBlank { "download" }
                    downloads.start(name, direct.url(), direct.headers())
                    syncService(downloads.snapshots())
                    _state.update {
                        it.copy(
                            parsing = false,
                            downloads = downloads.snapshots(),
                            message = "已创建下载：$name",
                            error = false
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(parsing = false, error = true, message = error.message ?: "解析失败") }
                }
            )
        }
    }

    fun toggle(snapshot: TransferSnapshot) {
        when (snapshot.status) {
            TransferStatus.RUNNING -> downloads.pause(snapshot.id)
            TransferStatus.PAUSED, TransferStatus.FAILED -> downloads.resume(snapshot.id)
            TransferStatus.COMPLETED -> Unit
        }
        val snapshots = downloads.snapshots()
        _state.update { it.copy(downloads = snapshots) }
        syncService(snapshots)
    }

    private fun resolveAndDownload(entry: ShareSession.Entry) {
        val current = session ?: return
        _state.update { it.copy(message = "正在获取下载地址…", error = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { current.resolve(entry) } }
            result.fold(
                onSuccess = { direct ->
                    val name = direct.fileName().ifBlank { entry.fileName().ifBlank { "download" } }
                    downloads.start(name, direct.url(), direct.headers())
                    val snapshots = downloads.snapshots()
                    syncService(snapshots)
                    _state.update { it.copy(downloads = snapshots, message = "已创建下载：$name", error = false) }
                },
                onFailure = { error ->
                    _state.update { it.copy(error = true, message = error.message ?: "获取下载地址失败") }
                }
            )
        }
    }

    private fun syncService(snapshots: List<TransferSnapshot>) {
        val running = snapshots.any { it.status == TransferStatus.RUNNING }
        val app = getApplication<Application>()
        if (!running) {
            app.stopService(Intent(app, DownloadService::class.java))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (_: Exception) {
            // Notification permission or background limits. The in-app list still tracks progress.
        }
    }

    override fun onCleared() {
        downloads.removeListener(listener)
    }
}
