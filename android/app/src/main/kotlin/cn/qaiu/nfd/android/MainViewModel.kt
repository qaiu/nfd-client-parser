package cn.qaiu.nfd.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.qaiu.nfd.android.daemon.AuthProvider
import cn.qaiu.nfd.android.daemon.DaemonException
import cn.qaiu.nfd.android.daemon.DaemonSettings
import cn.qaiu.nfd.android.daemon.Entry
import cn.qaiu.nfd.android.daemon.Health
import cn.qaiu.nfd.android.daemon.Listing
import cn.qaiu.nfd.android.daemon.Task
import cn.qaiu.nfd.android.history.HistoryItem
import cn.qaiu.nfd.android.termux.BootstrapService
import cn.qaiu.nfd.android.termux.TermuxBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Connection {
    data object Checking : Connection
    data object TermuxMissing : Connection
    data class Offline(val reason: String) : Connection
    data object Unauthorized : Connection
    data class Online(val health: Health) : Connection
}

enum class Tab { BROWSE, TASKS, HISTORY, SETUP }

/** One level of the folder stack; listings are kept so going back is instant. */
data class Level(val title: String, val folder: Entry?, val listing: Listing)

data class UiState(
    val tab: Tab = Tab.BROWSE,
    val connection: Connection = Connection.Checking,
    val link: String = "",
    val password: String = "",
    val levels: List<Level> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val selected: Set<String> = emptySet(),
    val tasks: List<Task> = emptyList(),
    val settings: DaemonSettings? = null,
    val message: String? = null,
    val allowAppControl: Boolean = true,
    val useChinaMirror: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    /** Null until loaded from the daemon, which is where login data lives. */
    val auths: List<AuthProvider>? = null,
) {
    val current: Level? get() = levels.lastOrNull()
    val activeTasks: Int get() = tasks.count { it.status.active }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NfdApp
    private val daemon = app.daemon
    private val mutable = MutableStateFlow(
        UiState(link = app.prefs.lastLink, useChinaMirror = app.prefs.useChinaMirror, history = app.history.items()),
    )
    val state: StateFlow<UiState> = mutable.asStateFlow()

    /** Bumped on every new parse so late results from an older link are dropped. */
    private var generation = 0
    private var browseJob: Job? = null
    private var pollJob: Job? = null
    /** The setup tab opens by itself once, the first time the daemon is unreachable. */
    private var setupShown = false

    fun onForeground() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val online = mutable.value.connection is Connection.Online
                if (!online || tick % 10 == 0) {
                    checkConnection()
                }
                if (mutable.value.connection is Connection.Online) {
                    refreshTasks()
                }
                tick++
                val busy = mutable.value.activeTasks > 0 || mutable.value.tab == Tab.TASKS
                delay(if (mutable.value.connection is Connection.Online && busy) 1000 else 3000)
            }
        }
    }

    fun onBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    fun select(tab: Tab) {
        mutable.update { it.copy(tab = tab) }
        if (tab == Tab.SETUP && mutable.value.connection is Connection.Online) loadAuths()
    }

    fun setLink(value: String) {
        mutable.update { it.copy(link = value) }
        app.prefs.lastLink = value
    }

    fun setPassword(value: String) = mutable.update { it.copy(password = value) }

    fun consumeMessage() = mutable.update { it.copy(message = null) }

    fun toast(message: String) = mutable.update { it.copy(message = message) }

    /** Text shared from another app: pick the URL out of it and parse right away. */
    fun onSharedText(text: String) {
        val url = Regex("https?://[^\\s，。）】]+").find(text)?.value ?: text.trim()
        val password = Regex("(?:提取码|密码|访问码|pwd)[:：\\s]*([A-Za-z0-9]{3,8})").find(text)?.groupValues?.get(1)
        setLink(url)
        mutable.update { it.copy(tab = Tab.BROWSE, password = password ?: it.password) }
        parse()
    }

    fun parse() {
        val link = mutable.value.link.trim()
        if (link.isEmpty()) {
            toast("请先粘贴分享链接")
            return
        }
        val password = mutable.value.password.trim()
        val token = ++generation
        browseJob?.cancel()
        mutable.update { it.copy(levels = emptyList(), selected = emptySet(), loading = true) }
        browseJob = viewModelScope.launch {
            try {
                val listing = daemon.list(link, password, null)
                if (token != generation) return@launch
                record(HistoryItem.succeeded(link, password, listing))
                if (listing.entries.isEmpty()) {
                    mutable.update { it.copy(loading = false, message = "没有列出文件，可尝试「直接下载」单文件分享") }
                } else {
                    mutable.update { it.copy(loading = false, levels = listOf(Level(listing.panName.ifBlank { "根目录" }, null, listing))) }
                }
            } catch (ex: DaemonException) {
                if (token != generation) return@launch
                if (ex.kind == DaemonException.Kind.REJECTED) {
                    record(HistoryItem.failed(link, password, ex.message ?: "解析失败"))
                }
                fail(ex)
            }
        }
    }

    /** Next page of the current folder (Lanzou and a few others return folders 50 rows at a time). */
    fun loadMore() {
        val state = mutable.value
        val level = state.current ?: return
        if (!level.listing.hasMore || state.loading || state.loadingMore) return
        val token = generation
        val link = state.link.trim()
        val password = state.password.trim()
        mutable.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val next = daemon.list(link, password, level.folder, level.listing.page + 1, level.listing.nextCursor)
                if (token != generation) return@launch
                val merged = level.listing.append(next)
                mutable.update { s ->
                    s.copy(loadingMore = false, levels = s.levels.map { if (it === level) it.copy(listing = merged) else it })
                }
                if (level.folder == null) {
                    val history = app.history.update(link, password, merged)
                    mutable.update { it.copy(history = history) }
                }
            } catch (ex: DaemonException) {
                mutable.update { it.copy(loadingMore = false) }
                if (token == generation) fail(ex)
            }
        }
    }

    fun reopen(item: HistoryItem) {
        setLink(item.link)
        mutable.update { it.copy(password = item.password, tab = Tab.BROWSE) }
        if (mutable.value.connection is Connection.Online) {
            parse()
        } else {
            toast("尚未连接 Termux 守护进程，连接后点「解析」")
        }
    }

    fun removeHistory(item: HistoryItem) = mutable.update { it.copy(history = app.history.remove(item)) }

    fun clearHistory() = mutable.update { it.copy(history = app.history.clear()) }

    private fun record(item: HistoryItem) = mutable.update { it.copy(history = app.history.record(item)) }

    fun openFolder(entry: Entry) {
        val state = mutable.value
        if (!entry.folder || state.loading) return
        val token = generation
        mutable.update { it.copy(loading = true) }
        browseJob = viewModelScope.launch {
            try {
                val listing = daemon.list(state.link.trim(), state.password, entry)
                if (token != generation) return@launch
                mutable.update { it.copy(loading = false, selected = emptySet(), levels = it.levels + Level(entry.fileName, entry, listing)) }
            } catch (ex: DaemonException) {
                if (token == generation) fail(ex)
            }
        }
    }

    /** Returns false when already at the root so the activity can handle system back. */
    fun back(): Boolean {
        val state = mutable.value
        if (state.loading) {
            browseJob?.cancel()
            generation++
            mutable.update { it.copy(loading = false) }
            return true
        }
        if (state.levels.size <= 1) return false
        mutable.update { it.copy(levels = it.levels.dropLast(1), selected = emptySet()) }
        return true
    }

    fun toggle(entry: Entry) = mutable.update {
        it.copy(selected = if (entry.key in it.selected) it.selected - entry.key else it.selected + entry.key)
    }

    fun selectAll() = mutable.update { state ->
        val files = state.current?.listing?.entries?.filterNot { it.folder }?.map { it.key }?.toSet().orEmpty()
        state.copy(selected = if (state.selected.containsAll(files)) emptySet() else files)
    }

    fun download(entries: List<Entry>) {
        val state = mutable.value
        val files = entries.filterNot { it.folder }
        if (files.isEmpty()) return
        launchAction {
            val tasks = daemon.downloadEntries(state.link.trim(), state.password, files)
            mutable.update { it.copy(tasks = tasks, selected = emptySet(), message = "已加入 ${files.size} 个下载任务") }
        }
    }

    fun downloadSelected() {
        val state = mutable.value
        download(state.current?.listing?.entries.orEmpty().filter { it.key in state.selected })
    }

    fun downloadDirect() {
        val state = mutable.value
        if (state.link.isBlank()) {
            toast("请先粘贴分享链接")
            return
        }
        val link = state.link.trim()
        val password = state.password.trim()
        mutable.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val tasks = daemon.downloadDirect(link, password)
                val name = tasks.firstOrNull()?.fileName.orEmpty()
                record(
                    HistoryItem(
                        link = link, password = password, time = System.currentTimeMillis(), success = true,
                        title = name, fileCount = 1, sizeComplete = false, singleFile = true,
                    ),
                )
                mutable.update { it.copy(tasks = tasks, tab = Tab.TASKS, message = "已加入下载") }
            } catch (ex: DaemonException) {
                if (ex.kind == DaemonException.Kind.REJECTED) {
                    record(HistoryItem.failed(link, password, ex.message ?: "解析失败"))
                }
                fail(ex)
            } finally {
                mutable.update { it.copy(loading = false) }
            }
        }
    }

    fun pause(task: Task) = launchAction { mutable.update { it.copy(tasks = daemon.pause(task.id)) } }

    fun resume(task: Task) = launchAction { mutable.update { it.copy(tasks = daemon.resume(task.id)) } }

    fun remove(task: Task, deleteFile: Boolean) = launchAction {
        mutable.update { it.copy(tasks = daemon.remove(task.id, deleteFile)) }
    }

    fun pauseAll() = launchAction { mutable.update { it.copy(tasks = daemon.pauseAll()) } }

    fun resumeAll() = launchAction { mutable.update { it.copy(tasks = daemon.resumeAll()) } }

    fun clearFinished() = launchAction { mutable.update { it.copy(tasks = daemon.clearFinished()) } }

    fun updateSettings(maxConcurrent: Int, connections: Int) = launchAction {
        mutable.update { it.copy(settings = daemon.updateSettings(maxConcurrent, connections), message = "已保存") }
    }

    /** [path] blank restores the default (Download/nfd once Termux has storage access). */
    fun setDownloadDir(path: String) = launchAction {
        val settings = daemon.setDownloadDir(path.trim())
        mutable.update {
            it.copy(settings = settings, message = if (path.isBlank()) "已恢复默认下载位置" else "新任务将保存到 ${settings.downloadDir}")
        }
    }

    fun loadAuths() = viewModelScope.launch {
        try {
            val auths = daemon.auths()
            mutable.update { it.copy(auths = auths) }
        } catch (ex: DaemonException) {
            // An older daemon has no /api/auths; the settings card explains that an update is needed.
            mutable.update { it.copy(auths = emptyList()) }
        }
    }

    fun saveAuth(type: String, fields: Map<String, String>) = launchAction {
        mutable.update { it.copy(auths = daemon.saveAuth(type, fields), message = "已保存登录信息") }
    }

    fun clearAuth(type: String) = launchAction {
        mutable.update { it.copy(auths = daemon.clearAuth(type), message = "已清除登录信息") }
    }

    fun setUseChinaMirror(value: Boolean) {
        app.prefs.useChinaMirror = value
        mutable.update { it.copy(useChinaMirror = value) }
    }

    fun saveEndpoint(host: String, port: Int, token: String) {
        app.prefs.host = host
        app.prefs.port = port
        if (token.isNotBlank()) app.prefs.token = token
        recheck()
    }

    fun regenerateToken() {
        app.prefs.regenerateToken()
        toast("已生成新令牌，请重新在 Termux 中运行安装命令或 nfd-termux token")
        recheck()
    }

    fun setAllowAppControl(value: Boolean) = mutable.update { it.copy(allowAppControl = value) }

    fun startBootstrap() = BootstrapService.start(getApplication())

    fun stopBootstrap() = BootstrapService.stop(getApplication())

    fun installCommand(): String? = BootstrapService.state.value?.let {
        BootstrapService.installCommand(it, app.prefs.token, app.prefs.port, mutable.value.allowAppControl, mutable.value.useChinaMirror)
    }

    fun tokenCommand(): String = "nfd-termux token ${app.prefs.token}"

    fun currentHost(): String = app.prefs.host

    fun currentPort(): Int = app.prefs.port

    fun currentToken(): String = app.prefs.token

    fun startDaemon() {
        val error = TermuxBridge.runDaemonCommand(getApplication(), "start")
        if (error != null) {
            toast(error + "。也可以在 Termux 中手动运行 nfd-termux start")
        } else {
            toast("已请求 Termux 启动守护进程…")
            viewModelScope.launch {
                repeat(20) {
                    delay(1500)
                    checkConnection()
                    if (mutable.value.connection is Connection.Online) return@launch
                }
            }
        }
    }

    fun stopDaemon() = launchAction {
        daemon.shutdown()
        mutable.update { it.copy(connection = Connection.Offline("守护进程已停止")) }
    }

    fun recheck() {
        mutable.update { it.copy(connection = Connection.Checking) }
        viewModelScope.launch { checkConnection() }
    }

    private suspend fun checkConnection() {
        val connection = try {
            val health = daemon.health()
            if (health.authorized) Connection.Online(health) else Connection.Unauthorized
        } catch (ex: DaemonException) {
            when {
                app.prefs.host == Prefs.DEFAULT_HOST && !TermuxBridge.installed(app) -> Connection.TermuxMissing
                ex.kind == DaemonException.Kind.UNAUTHORIZED -> Connection.Unauthorized
                else -> Connection.Offline(ex.message ?: "未连接")
            }
        }
        val wasOnline = mutable.value.connection is Connection.Online
        mutable.update { it.copy(connection = connection) }
        if (connection is Connection.Online) {
            if (!wasOnline) {
                runCatching { daemon.settings() }.onSuccess { s -> mutable.update { it.copy(settings = s) } }
                if (mutable.value.tab == Tab.SETUP) loadAuths()
                if (BootstrapService.state.value?.bundleServed == true) {
                    BootstrapService.stop(getApplication())
                }
            }
        } else if (!setupShown && connection !is Connection.Checking) {
            setupShown = true
            mutable.update { it.copy(tab = Tab.SETUP) }
        }
    }

    private suspend fun refreshTasks() {
        try {
            val tasks = daemon.tasks()
            mutable.update { it.copy(tasks = tasks) }
        } catch (ex: DaemonException) {
            if (ex.kind != DaemonException.Kind.REJECTED) checkConnection()
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (ex: DaemonException) {
                fail(ex)
            }
        }
    }

    private fun fail(ex: DaemonException) {
        mutable.update { it.copy(loading = false, message = ex.message) }
        if (ex.kind != DaemonException.Kind.REJECTED) {
            viewModelScope.launch { checkConnection() }
        }
    }
}
