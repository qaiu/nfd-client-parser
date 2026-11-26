package cn.qaiu.nfd.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.qaiu.nfd.engine.TransferSnapshot
import cn.qaiu.nfd.engine.TransferStatus
import cn.qaiu.nfd.engine.formatSize
import cn.qaiu.nfd.share.ShareSession

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val state by model.state.collectAsState()
            NfdTheme {
                NfdScreen(state, model)
            }
        }
    }
}

private val NfdColors = lightColorScheme(
    primary = Color(0xFF0B6BCB),
    onPrimary = Color.White,
    secondary = Color(0xFF1B2430),
    background = Color(0xFFEEF2F6),
    surface = Color.White
)

@Composable
private fun NfdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NfdColors, content = content)
}

@Composable
private fun NfdScreen(state: NfdUiState, model: MainViewModel) {
    Scaffold(containerColor = NfdColors.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("网盘直链下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "本机解析分享链接。下载优先使用 aria2，没有二进制时用内置传输。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5C6B7A)
                )
            }
            item {
                OutlinedTextField(
                    value = state.link,
                    onValueChange = model::updateLink,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分享链接") },
                    minLines = 2
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = model::updatePassword,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("提取码，可空") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = model::parse, enabled = !state.parsing) { Text("解析") }
                    OutlinedButton(onClick = model::parseDirect, enabled = !state.parsing) { Text("解析直链") }
                }
                if (state.panLabel.isNotBlank()) {
                    Text(state.panLabel, color = NfdColors.primary, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    state.message,
                    color = if (state.error) Color(0xFFB42318) else Color(0xFF1B2430)
                )
            }
            item { EngineCard(state, model) }
            if (state.crumbs.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.crumbs.joinToString(" / "), modifier = Modifier.weight(1f))
                        if (state.crumbs.size > 1) {
                            OutlinedButton(onClick = model::back) { Text("返回") }
                        }
                    }
                }
            }
            items(state.entries, key = { it.fileId() + it.fileName() + it.folder() }) { entry ->
                FileRow(entry) { model.open(entry) }
            }
            item {
                Text("下载", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("引擎：${state.engineName}", color = Color(0xFF5C6B7A))
            }
            items(state.downloads, key = { it.id }) { snapshot ->
                DownloadRow(snapshot) { model.toggle(snapshot) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EngineCard(state: NfdUiState, model: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连接本机 aria2 RPC", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.externalEnabled,
                    onCheckedChange = { enabled ->
                        model.updateExternal(enabled, state.externalHost, state.externalPort, state.externalSecret)
                    }
                )
            }
            Text(
                "不需要 Termux。打开后可填写 Termux 或其他 aria2 的地址；关闭则使用已打包的 aria2，没有二进制时用内置传输。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5C6B7A)
            )
            if (state.externalEnabled) {
                OutlinedTextField(
                    value = state.externalHost,
                    onValueChange = { model.updateExternal(true, it, state.externalPort, state.externalSecret) },
                    label = { Text("主机") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.externalPort,
                    onValueChange = { model.updateExternal(true, state.externalHost, it, state.externalSecret) },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.externalSecret,
                    onValueChange = { model.updateExternal(true, state.externalHost, state.externalPort, it) },
                    label = { Text("RPC 密钥，可空") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedButton(onClick = model::applyEngine) { Text("应用下载引擎") }
        }
    }
}

@Composable
private fun FileRow(entry: ShareSession.Entry, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(entry.fileName().ifBlank { "未命名" }, fontWeight = FontWeight.SemiBold)
            val kind = if (entry.folder()) "文件夹" else entry.fileType().ifBlank { "文件" }
            Text("$kind  ${entry.sizeText()}", color = Color(0xFF5C6B7A), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DownloadRow(snapshot: TransferSnapshot, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(snapshot.fileName, fontWeight = FontWeight.SemiBold)
            val fraction = snapshot.fraction()
            if (fraction < 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            val state = when (snapshot.status) {
                TransferStatus.RUNNING -> "下载中"
                TransferStatus.PAUSED -> "已暂停"
                TransferStatus.COMPLETED -> "完成"
                TransferStatus.FAILED -> "失败"
            }
            val speed = if (snapshot.speedBytesPerSec > 0) "  ${formatSize(snapshot.speedBytesPerSec)}/s" else ""
            Text(
                "$state  ${formatSize(snapshot.downloaded)} / ${formatSize(snapshot.total)}$speed ${snapshot.message}",
                style = MaterialTheme.typography.bodySmall
            )
            val action = when (snapshot.status) {
                TransferStatus.RUNNING -> "暂停"
                TransferStatus.PAUSED, TransferStatus.FAILED -> "继续"
                TransferStatus.COMPLETED -> ""
            }
            if (action.isNotEmpty()) {
                OutlinedButton(onClick = onToggle) { Text(action) }
            }
        }
    }
}
