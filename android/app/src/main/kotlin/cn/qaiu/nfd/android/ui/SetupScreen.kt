package cn.qaiu.nfd.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qaiu.nfd.android.BuildConfig
import cn.qaiu.nfd.android.Connection
import cn.qaiu.nfd.android.MainViewModel
import cn.qaiu.nfd.android.Prefs
import cn.qaiu.nfd.android.UiState
import cn.qaiu.nfd.android.termux.BootstrapService
import cn.qaiu.nfd.android.termux.TermuxBridge
import kotlin.math.roundToInt

@Composable
fun ConnectionChip(connection: Connection, onClick: () -> Unit) {
    val label = when (connection) {
        Connection.Checking -> "检测中"
        Connection.TermuxMissing -> "未安装 Termux"
        is Connection.Offline -> "未连接"
        Connection.Unauthorized -> "令牌不匹配"
        is Connection.Online -> "已连接"
    }
    AssistChip(onClick = onClick, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
}

@Composable
fun SetupScreen(state: UiState, viewModel: MainViewModel, startDaemon: () -> Unit, startBootstrap: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bootstrap by BootstrapService.state.collectAsStateWithLifecycle()
    val termuxInstalled = TermuxBridge.installed(context)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, viewModel)

        Step("1. 安装 Termux", done = termuxInstalled) {
            Text(
                "解析使用最新的 cn.qaiu:parser，它运行在 Termux 自带的 OpenJDK 中。请从 F-Droid 或 GitHub 安装 Termux（Google Play 上的旧版无法使用）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (termuxInstalled) {
                    OutlinedButton(onClick = { TermuxBridge.open(context) }) { Text("打开 Termux") }
                } else {
                    Button(onClick = { TermuxBridge.openStore(context) }) { Text("前往 F-Droid") }
                }
            }
        }

        Step("2. 在 Termux 中安装守护进程", done = state.connection is Connection.Online) {
            Text(
                "App 会在本机 127.0.0.1 临时提供安装包。复制命令后粘贴到 Termux 执行，它会安装 openjdk-21、解压守护进程并申请存储权限。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许 App 一键启动/停止（开启 allow-external-apps）", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = state.allowAppControl, onCheckedChange = viewModel::setAllowAppControl)
            }
            val info = bootstrap
            if (info == null) {
                Button(onClick = startBootstrap) { Text("生成安装命令") }
            } else {
                val command = BootstrapService.installCommand(info, viewModel.currentToken(), viewModel.currentPort(), state.allowAppControl)
                SelectionContainer {
                    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(command))
                        if (!TermuxBridge.open(context)) viewModel.toast("已复制命令")
                    }) { Text("复制并打开 Termux") }
                    TextButton(onClick = viewModel::stopBootstrap) { Text("停止") }
                }
                val progress = when {
                    info.bundleServed -> "Termux 已下载安装包，安装完成后点「重新检测」"
                    info.installerServed -> "Termux 正在下载安装包…"
                    else -> "等待 Termux 执行命令（15 分钟内有效）"
                }
                Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        Step("3. 启动守护进程", done = state.connection is Connection.Online) {
            Text("安装命令执行完会自动启动。手机重启或 Termux 被关闭后，在 Termux 中运行：", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text("nfd-termux start", fontFamily = FontFamily.Monospace) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = startDaemon, enabled = termuxInstalled) { Text("一键启动") }
                OutlinedButton(onClick = viewModel::recheck) { Text("重新检测") }
                if (state.connection is Connection.Online) {
                    TextButton(onClick = viewModel::stopDaemon) { Text("停止") }
                }
            }
        }

        if (state.connection is Connection.Unauthorized) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("令牌不匹配", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text("守护进程在运行，但令牌与本 App 不一致（例如重装过 App）。在 Termux 中执行：", style = MaterialTheme.typography.bodySmall)
                    val command = viewModel.tokenCommand()
                    SelectionContainer { Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(command))
                        TermuxBridge.open(context)
                    }) { Text("复制并打开 Termux") }
                }
            }
        }

        if (state.connection is Connection.Online) {
            DownloadSettings(state, viewModel)
        }
        EndpointSettings(viewModel)
        Text(
            "NFD ${BuildConfig.VERSION_NAME} · MIT License · 不内置任何网盘账号或 Cookie",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(state: UiState, viewModel: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (val connection = state.connection) {
                is Connection.Online -> {
                    val h = connection.health
                    Text("已连接 Termux 守护进程 ${h.version}", fontWeight = FontWeight.Bold)
                    Text("parser ${h.parserVersion} · ${h.java}", style = MaterialTheme.typography.bodySmall)
                    Text("下载目录：${h.downloadDir}", style = MaterialTheme.typography.bodySmall)
                    if (!h.downloadDirShared) {
                        Text(
                            "文件保存在 Termux 私有目录。在 Termux 中运行 termux-setup-storage 并重启守护进程后，会改存到 Download/nfd。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Connection.Checking -> Text("正在检测守护进程…")
                Connection.TermuxMissing -> Text("未检测到 Termux，请按下面的步骤安装。", fontWeight = FontWeight.Bold)
                Connection.Unauthorized -> Text("守护进程拒绝了本 App 的令牌。", fontWeight = FontWeight.Bold)
                is Connection.Offline -> {
                    Text("未连接：${connection.reason}", fontWeight = FontWeight.Bold)
                    Text("如已安装，请在 Termux 中运行 nfd-termux start，或点「一键启动」。", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = viewModel::recheck) { Text("重新检测") }
        }
    }
}

@Composable
private fun DownloadSettings(state: UiState, viewModel: MainViewModel) {
    val settings = state.settings ?: return
    var concurrent by remember(settings) { mutableFloatStateOf(settings.maxConcurrent.toFloat()) }
    var connections by remember(settings) { mutableFloatStateOf(settings.connections.toFloat()) }
    Step("下载设置", done = null) {
        Text("同时下载 ${concurrent.roundToInt()} 个任务", style = MaterialTheme.typography.bodySmall)
        Slider(value = concurrent, onValueChange = { concurrent = it }, valueRange = 1f..5f, steps = 3)
        Text("每个任务 ${connections.roundToInt()} 个连接（部分网盘限制并发，失败时可调低）", style = MaterialTheme.typography.bodySmall)
        Slider(value = connections, onValueChange = { connections = it }, valueRange = 1f..8f, steps = 6)
        Button(onClick = { viewModel.updateSettings(concurrent.roundToInt(), connections.roundToInt()) }) { Text("保存") }
    }
}

@Composable
private fun EndpointSettings(viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(viewModel.currentHost()) }
    var port by remember { mutableStateOf(viewModel.currentPort().toString()) }
    var token by remember { mutableStateOf(viewModel.currentToken()) }
    LaunchedEffect(expanded) {
        if (expanded) {
            host = viewModel.currentHost()
            port = viewModel.currentPort().toString()
            token = viewModel.currentToken()
        }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起高级设置" else "高级设置（地址 / 端口 / 令牌）") }
    if (!expanded) return
    Step("高级设置", done = null) {
        Text(
            "默认连接本机 127.0.0.1:${Prefs.DEFAULT_PORT}。也可以连接局域网电脑上用 --bind 0.0.0.0 启动的守护进程（令牌必须一致）。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(host, { host = it }, label = { Text("地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("令牌") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.saveEndpoint(host, port.toIntOrNull() ?: Prefs.DEFAULT_PORT, token) }) { Text("保存") }
            OutlinedButton(onClick = {
                viewModel.regenerateToken()
                token = viewModel.currentToken()
            }) { Text("重新生成令牌") }
        }
    }
}

@Composable
private fun Step(title: String, done: Boolean?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (done == true) Text("已完成", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            content()
        }
    }
}
