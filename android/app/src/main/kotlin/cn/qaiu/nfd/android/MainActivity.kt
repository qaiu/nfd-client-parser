package cn.qaiu.nfd.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qaiu.nfd.android.termux.StoragePaths
import cn.qaiu.nfd.android.runtime.ParserService
import cn.qaiu.nfd.android.termux.TermuxBridge
import cn.qaiu.nfd.android.ui.BrowseScreen
import cn.qaiu.nfd.android.ui.ConnectionChip
import cn.qaiu.nfd.android.ui.HistoryScreen
import cn.qaiu.nfd.android.ui.SetupScreen
import cn.qaiu.nfd.android.ui.TasksScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val runCommandPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startDaemon() else viewModel.toast("未授权：请在 Termux 中运行 nfd-termux start")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (BuildConfig.EMBEDDED_RUNTIME) {
            viewModel.startDaemon()
        } else {
            viewModel.startBootstrap()
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = StoragePaths.fromTreeDocument(uri.authority, runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull(), root)
        if (path == null) {
            viewModel.toast("无法使用该位置：请选择手机内部存储中的文件夹，或直接填写路径")
        } else {
            viewModel.setDownloadDir(path)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { NfdTheme { App(viewModel, ::startDaemon, ::startBootstrap) { folderPicker.launch(null) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.EMBEDDED_RUNTIME) ParserService.start(this)
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(viewModel::onSharedText)
        }
    }

    private fun startDaemon() {
        if (BuildConfig.EMBEDDED_RUNTIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!BuildConfig.EMBEDDED_RUNTIME && TermuxBridge.installed(this) && !TermuxBridge.canRunCommands(this)) {
            runCommandPermission.launch(TermuxBridge.RUN_COMMAND_PERMISSION)
        } else {
            viewModel.startDaemon()
        }
    }

    private fun startBootstrap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startBootstrap()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(viewModel: MainViewModel, startDaemon: () -> Unit, startBootstrap: () -> Unit, pickFolder: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = state.tab == Tab.BROWSE && (state.levels.size > 1 || state.loading)) {
        viewModel.back()
    }
    BackHandler(enabled = state.tab != Tab.BROWSE) {
        viewModel.select(Tab.BROWSE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFD 网盘直链") },
                actions = { ConnectionChip(state.connection) { viewModel.select(Tab.SETUP) } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.tab == Tab.BROWSE,
                    onClick = { viewModel.select(Tab.BROWSE) },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("解析") },
                )
                NavigationBarItem(
                    selected = state.tab == Tab.TASKS,
                    onClick = { viewModel.select(Tab.TASKS) },
                    icon = {
                        BadgedBox(badge = { if (state.activeTasks > 0) Badge { Text("${state.activeTasks}") } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        }
                    },
                    label = { Text("下载") },
                )
                NavigationBarItem(
                    selected = state.tab == Tab.HISTORY,
                    onClick = { viewModel.select(Tab.HISTORY) },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("历史") },
                )
                NavigationBarItem(
                    selected = state.tab == Tab.SETUP,
                    onClick = { viewModel.select(Tab.SETUP) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (state.tab) {
                Tab.BROWSE -> BrowseScreen(state, viewModel)
                Tab.TASKS -> TasksScreen(state, viewModel)
                Tab.HISTORY -> HistoryScreen(state, viewModel)
                Tab.SETUP -> SetupScreen(state, viewModel, startDaemon, startBootstrap, pickFolder)
            }
        }
    }
}

@Composable
private fun NfdTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val brand = Color(0xFF1E6FD9)
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8AB8FF)) else lightColorScheme(primary = brand),
        content = content,
    )
}
