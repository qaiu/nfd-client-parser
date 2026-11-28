package cn.qaiu.nfd.android.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.qaiu.nfd.android.BuildConfig
import cn.qaiu.nfd.android.MainViewModel
import cn.qaiu.nfd.android.UiState
import cn.qaiu.nfd.android.daemon.Task
import cn.qaiu.nfd.android.daemon.TaskStatus

@Composable
fun TasksScreen(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var removing by remember { mutableStateOf<Task?>(null) }
    var exportTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val task = state.tasks.firstOrNull { it.id == exportTaskId }
        exportTaskId = null
        if (uri != null && task != null) viewModel.exportDownload(task, uri)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = viewModel::pauseAll) { Text("全部暂停") }
            TextButton(onClick = viewModel::resumeAll) { Text("全部继续") }
            TextButton(onClick = viewModel::clearFinished) { Text("清除已完成") }
            if (!BuildConfig.EMBEDDED_RUNTIME) {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (ex: ActivityNotFoundException) {
                        viewModel.toast("没有可以打开下载目录的应用")
                    }
                }) { Text("打开目录") }
            }
        }
        state.settings?.let {
            Text(
                "保存到：${it.downloadDir}" + if (it.downloadDirShared || BuildConfig.EMBEDDED_RUNTIME) ""
                else "（Termux 私有目录，运行 termux-setup-storage 后改为 Download/nfd）",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.tasks.isEmpty()) {
            EmptyHint("还没有下载任务。到「解析」页选择文件并加入下载。")
            return
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.tasks, key = { it.id }) { task ->
                TaskCard(task, viewModel, onRemove = { removing = task }, onExport = {
                    exportTaskId = task.id
                    exportFile.launch(task.fileName)
                })
            }
        }
    }
    removing?.let { task ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除任务") },
            text = { Text(task.fileName) },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(task, deleteFile = true); removing = null }) { Text("同时删除文件") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.remove(task, deleteFile = false); removing = null }) { Text("仅移除任务") }
            },
        )
    }
}

@Composable
private fun TaskCard(task: Task, viewModel: MainViewModel, onRemove: () -> Unit, onExport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (task.status == TaskStatus.COMPLETED) {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            } else if (task.progress >= 0) {
                LinearProgressIndicator(progress = { task.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else if (task.status == TaskStatus.RUNNING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${task.statusLabel}  ${task.progressText}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    task.status.active -> TextButton(onClick = { viewModel.pause(task) }) { Text("暂停") }
                    task.status == TaskStatus.FAILED -> TextButton(onClick = { viewModel.resume(task) }) { Text("重试") }
                    task.status.canResume -> TextButton(onClick = { viewModel.resume(task) }) { Text("继续") }
                    BuildConfig.EMBEDDED_RUNTIME && task.status == TaskStatus.COMPLETED && task.destination.isNotBlank() ->
                        TextButton(onClick = onExport) { Text("另存为") }
                }
                TextButton(onClick = onRemove) { Text("移除") }
            }
            val engine = when (task.downloadEngine) {
                "aria2" -> "aria2c"
                "java" -> "Java 下载器"
                else -> ""
            }
            if (engine.isNotEmpty()) {
                val detail = if (task.status == TaskStatus.RUNNING) {
                    val range = if (task.resumable) "支持分段" else "单连接模式"
                    "$engine · ${task.activeConnections} 个活动连接 · $range"
                } else engine
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (task.message.isNotBlank()) {
                Text(
                    task.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.status == TaskStatus.COMPLETED && task.destination.isNotBlank()) {
                Text(task.destination, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.status == TaskStatus.PAUSED && !task.resumable && task.downloaded > 0) {
                Text("该服务器不支持断点续传，继续时会从头下载", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
