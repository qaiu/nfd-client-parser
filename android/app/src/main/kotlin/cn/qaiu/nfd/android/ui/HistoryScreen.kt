package cn.qaiu.nfd.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.qaiu.nfd.android.MainViewModel
import cn.qaiu.nfd.android.UiState
import cn.qaiu.nfd.android.history.HistoryItem

@Composable
fun HistoryScreen(state: UiState, viewModel: MainViewModel) {
    if (state.history.isEmpty()) {
        EmptyHint("还没有解析记录。\n解析过的链接会保存在本机，点一条即可重新解析。")
        return
    }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${state.history.size} 条，点一条重新解析", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmClear = true }) { Text("清空") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(state.history, key = { it.link + "\n" + it.time }) { item ->
                HistoryRow(item, onOpen = { viewModel.reopen(item) }, onDelete = { viewModel.removeHistory(item) })
                HorizontalDivider()
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空解析历史？") },
            text = { Text("只删除本机的记录，不影响已下载的文件和下载任务。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(item.success)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.title.ifBlank { item.link },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (item.success) summary(item) else item.error.ifBlank { "解析失败" },
                style = MaterialTheme.typography.bodySmall,
                color = if (item.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatTime(item.time) + (if (item.password.isNotBlank()) " · 提取码 ${item.password}" else "") + " · " + item.link,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除这条记录") }
    }
}

private fun summary(item: HistoryItem): String {
    val parts = mutableListOf<String>()
    if (item.panName.isNotBlank()) parts += item.panName
    if (item.singleFile) parts += "单文件"
    if (item.folderCount > 0) parts += "${item.folderCount} 个文件夹"
    if (!item.singleFile || item.fileCount != 1) parts += "${item.fileCount} 个文件"
    when {
        item.totalSize > 0 -> parts += "共 " + formatSize(item.totalSize) + if (item.sizeComplete) "" else "（部分文件大小未知）"
        item.fileCount > 0 && !item.sizeComplete -> parts += "大小未知"
    }
    if (item.hasMore) parts += "已加载部分，还有更多"
    return parts.joinToString(" · ")
}

@Composable
private fun StatusBadge(success: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.size(width = 44.dp, height = 32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (success) "成功" else "失败",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
