package cn.qaiu.nfd.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qaiu.nfd.android.BuildConfig
import cn.qaiu.nfd.android.Connection
import cn.qaiu.nfd.android.MainViewModel
import cn.qaiu.nfd.android.NfdApp
import cn.qaiu.nfd.android.Prefs
import cn.qaiu.nfd.android.UiState
import cn.qaiu.nfd.android.daemon.AuthProvider
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
        is Connection.Online -> if (BuildConfig.EMBEDDED_RUNTIME) "本机解析" else "已连接"
    }
    AssistChip(onClick = onClick, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
}

@Composable
fun SetupScreen(
    state: UiState,
    viewModel: MainViewModel,
    startDaemon: () -> Unit,
    startBootstrap: () -> Unit,
    pickFolder: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bootstrap by BootstrapService.state.collectAsStateWithLifecycle()
    var showRuntimeLog by rememberSaveable { mutableStateOf(false) }
    if (showRuntimeLog && BuildConfig.EMBEDDED_RUNTIME) {
        val entries by (context.applicationContext as NfdApp).runtimeLog.entries.collectAsStateWithLifecycle()
        val logText = entries.joinToString("\n")
        AlertDialog(
            onDismissRequest = { showRuntimeLog = false },
            title = { Text("运行日志") },
            text = {
                SelectionContainer {
                    Text(
                        logText.ifBlank { "暂无运行日志" },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(logText))
                    viewModel.toast("已复制运行日志")
                }, enabled = entries.isNotEmpty()) { Text("复制日志") }
            },
            dismissButton = { TextButton(onClick = { showRuntimeLog = false }) { Text("关闭") } },
        )
    }
    val termuxInstalled = !BuildConfig.EMBEDDED_RUNTIME && TermuxBridge.installed(context)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, viewModel)
        if (BuildConfig.EMBEDDED_RUNTIME) {
            OutlinedButton(onClick = { showRuntimeLog = true }) { Text("查看运行日志") }
        }

        if (state.connection is Connection.Online) {
            DownloadSettings(state, viewModel, pickFolder)
        }
        AuthSettings(state, viewModel)

        if (BuildConfig.EMBEDDED_RUNTIME) {
            if (state.connection !is Connection.Online) {
                Button(onClick = startDaemon) { Text("启动内置解析") }
            }
        } else {
            Step("1. 安装 Termux", done = termuxInstalled) {
                Text(
                    "解析使用最新的 cn.qaiu:parser，它运行在 Termux 自带的 OpenJDK 中。请从下面的官方渠道或国内镜像安装 Termux（Google Play 上的旧版无法使用）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (termuxInstalled) {
                    OutlinedButton(onClick = { TermuxBridge.open(context) }) { Text("打开 Termux") }
                }
                TermuxLinks(expandedByDefault = !termuxInstalled)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "使用国内镜像下载 OpenJDK（Termux 官方中国大陆镜像组：清华、中科大、阿里云等）",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = state.useChinaMirror, onCheckedChange = viewModel::setUseChinaMirror)
                }
                val info = bootstrap
                if (info == null) {
                    Button(onClick = startBootstrap) { Text("生成安装命令") }
                } else {
                    val command = BootstrapService.installCommand(
                        info, viewModel.currentToken(), viewModel.currentPort(), state.allowAppControl, state.useChinaMirror,
                    )
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

            EndpointSettings(viewModel)
        }
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
                    val runtime = if (BuildConfig.EMBEDDED_RUNTIME) "内置解析已就绪" else "已连接 Termux 守护进程"
                    Text("$runtime ${h.version}", fontWeight = FontWeight.Bold)
                    Text("parser ${h.parserVersion} · ${h.java}", style = MaterialTheme.typography.bodySmall)
                    val settings = state.settings
                    Text("下载目录：${settings?.downloadDir ?: h.downloadDir}", style = MaterialTheme.typography.bodySmall)
                    if (!BuildConfig.EMBEDDED_RUNTIME && !h.downloadDirShared && settings?.downloadDirCustom != true) {
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
                    if (!BuildConfig.EMBEDDED_RUNTIME) {
                        Text("如已安装，请在 Termux 中运行 nfd-termux start，或点「一键启动」。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = viewModel::recheck) { Text("重新检测") }
        }
    }
}

@Composable
private fun TermuxLinks(expandedByDefault: Boolean) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(expandedByDefault) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起下载地址" else "Termux 下载地址（官方 / 国内镜像）") }
    if (!expanded) return
    Text("官方渠道", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    TermuxBridge.OFFICIAL_LINKS.forEach { LinkRow(it) }
    Text("国内镜像（F-Droid 版 Termux 0.118.3 安装包）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    TermuxBridge.CHINA_MIRROR_LINKS.forEach { LinkRow(it) }
    Text(
        "镜像中的安装包与 F-Droid 官方仓库的文件相同。F-Droid 版和 GitHub 版签名不同，不能互相覆盖安装，" +
            "已装其中一种时请继续用同一渠道更新。若镜像链接失效，请查看镜像站的 F-Droid 帮助页：",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { TermuxBridge.openUrl(context, TermuxBridge.MIRROR_HELP_URL) }) { Text(TermuxBridge.MIRROR_HELP_URL) }
}

@Composable
private fun LinkRow(link: TermuxBridge.DownloadLink) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { TermuxBridge.openUrl(context, link.url) }.padding(vertical = 4.dp)) {
            Text(link.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            Text(link.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(link.url)) }) { Text("复制") }
    }
}

@Composable
private fun DownloadSettings(state: UiState, viewModel: MainViewModel, pickFolder: () -> Unit) {
    val settings = state.settings ?: return
    var concurrent by remember(settings) { mutableFloatStateOf(settings.maxConcurrent.toFloat()) }
    var connections by remember(settings) { mutableFloatStateOf(settings.connections.toFloat()) }
    var path by remember(settings.downloadDir) { mutableStateOf(settings.downloadDir) }
    val focus = LocalFocusManager.current
    Step("下载设置", done = null) {
        Text("下载位置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(
            settings.downloadDir + if (settings.downloadDirCustom) "" else "（默认）",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            path,
            { path = it },
            label = { Text("保存到（完整路径）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                focus.clearFocus()
                viewModel.setDownloadDir(path)
            }, enabled = path.isNotBlank() && path.trim() != settings.downloadDir) {
                Text("应用")
            }
            if (!BuildConfig.EMBEDDED_RUNTIME) {
                OutlinedButton(onClick = pickFolder) { Text("选择文件夹") }
            }
            if (settings.downloadDirCustom) {
                TextButton(onClick = {
                    focus.clearFocus()
                    viewModel.setDownloadDir("")
                }) { Text("恢复默认") }
            }
        }
        Text(
            if (BuildConfig.EMBEDDED_RUNTIME) {
                "新任务可保存到应用可写的目录。下载完成后可在「下载」页选择「另存为」导出文件。"
            } else {
                "默认：${settings.defaultDownloadDir}。请在 Termux 中运行 termux-setup-storage 授权后选择内部存储目录。" +
                    "新位置只影响之后添加的任务。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("同时下载 ${concurrent.roundToInt()} 个任务", style = MaterialTheme.typography.bodySmall)
        Slider(value = concurrent, onValueChange = { concurrent = it }, valueRange = 1f..5f, steps = 3)
        Text("每个任务 ${connections.roundToInt()} 个连接（部分网盘限制并发，失败时可调低）", style = MaterialTheme.typography.bodySmall)
        Slider(value = connections, onValueChange = { connections = it }, valueRange = 1f..16f, steps = 14)
        Button(onClick = { viewModel.updateSettings(concurrent.roundToInt(), connections.roundToInt()) }) { Text("保存") }
    }
}

@Composable
private fun AuthSettings(state: UiState, viewModel: MainViewModel) {
    var editing by remember { mutableStateOf<AuthProvider?>(null) }
    val online = state.connection is Connection.Online
    LaunchedEffect(online) {
        if (online && state.auths == null) viewModel.loadAuths()
    }
    Step("网盘账号（Cookie / Token）", done = null) {
        Text(
            "夸克、UC 等网盘需要登录后才能解析或下载。登录信息保存在本机的私有数据目录。",
            style = MaterialTheme.typography.bodySmall,
        )
        val auths = state.auths
        when {
            !online -> Text(
                "解析运行时就绪后可以填写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            auths == null -> Text("正在读取…", style = MaterialTheme.typography.bodySmall)
            auths.isEmpty() -> Text(
                "当前守护进程版本不支持此功能，请在下面第 2 步重新生成并运行安装命令来更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> auths.forEach { provider ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = provider }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.name)
                        Text(
                            if (provider.configured) "已配置" else "未配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (provider.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editing = provider }) { Text(if (provider.configured) "修改" else "填写") }
                }
            }
        }
    }
    editing?.let { provider ->
        AuthDialog(
            provider,
            onDismiss = { editing = null },
            onSave = { fields ->
                viewModel.saveAuth(provider.type, fields)
                editing = null
            },
            onClear = {
                viewModel.clearAuth(provider.type)
                editing = null
            },
        )
    }
}

@Composable
private fun AuthDialog(provider: AuthProvider, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit, onClear: () -> Unit) {
    val values = remember(provider) { provider.fields.associate { it.key to mutableStateOf(if (it.secret) "" else it.value) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(provider.hint, style = MaterialTheme.typography.bodySmall)
                provider.fields.forEach { field ->
                    var value by values.getValue(field.key)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(field.label) },
                        placeholder = { if (field.hint.isNotBlank() && !(field.secret && field.set)) Text(field.hint) },
                        supportingText = if (field.secret && field.set) {
                            { Text("已填写，留空则保持不变") }
                        } else {
                            null
                        },
                        singleLine = !field.multiline,
                        minLines = if (field.multiline) 3 else 1,
                        maxLines = if (field.multiline) 6 else 1,
                        visualTransformation = if (field.secret && !field.multiline) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "请只填写自己的账号。Cookie 和 Token 相当于登录凭证，不要分享给他人。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(values.mapValues { it.value.value }) }) { Text("保存") } },
        dismissButton = {
            Row {
                if (provider.configured) TextButton(onClick = onClear) { Text("清除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
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
