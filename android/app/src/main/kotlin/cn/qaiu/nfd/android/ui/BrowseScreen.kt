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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.qaiu.nfd.android.Connection
import cn.qaiu.nfd.android.BuildConfig
import cn.qaiu.nfd.android.MainViewModel
import cn.qaiu.nfd.android.UiState
import cn.qaiu.nfd.android.daemon.Entry

@Composable
fun BrowseScreen(state: UiState, viewModel: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val online = state.connection is Connection.Online
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.link,
                onValueChange = viewModel::setLink,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分享链接（可粘贴整段分享文字）") },
                singleLine = true,
                trailingIcon = {
                    if (state.link.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setLink("") }) { Icon(Icons.Filled.Clear, contentDescription = "清空") }
                    } else {
                        TextButton(onClick = {
                            clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let(viewModel::onSharedText)
                        }) { Text("粘贴") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); viewModel.parse() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::setPassword,
                    modifier = Modifier.weight(1f),
                    label = { Text("提取码") },
                    singleLine = true,
                )
                Button(onClick = { focus.clearFocus(); viewModel.parse() }, enabled = online && !state.loading) { Text("解析") }
                OutlinedButton(onClick = { focus.clearFocus(); viewModel.downloadDirect() }, enabled = online && !state.loading) {
                    Text("直接下载")
                }
            }
            if (!online) {
                Text(
                    if (BuildConfig.EMBEDDED_RUNTIME) "正在准备内置解析。可到「设置」查看运行状态。"
                    else "尚未连接 Termux 守护进程，请先到「设置」页完成连接。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val level = state.current
        if (level == null) {
            EmptyHint(
                if (state.loading) "正在解析…" else "支持蓝奏云、夸克、UC、123、小飞机等分享链接。\n也可以在其他 App 中「分享」链接到 NFD。",
            )
            return
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.back() }, enabled = state.levels.size > 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级")
            }
            Text(
                state.levels.joinToString(" / ") { it.title },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            val files = level.listing.entries.filterNot { it.folder }
            if (files.isNotEmpty()) {
                TextButton(onClick = viewModel::selectAll) { Text("全选") }
            }
        }
        if (level.listing.note.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    level.listing.note,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(level.listing.entries, key = { it.key }) { entry ->
                EntryRow(entry, entry.key in state.selected, viewModel)
                HorizontalDivider()
            }
            if (level.listing.hasMore) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        } else {
                            OutlinedButton(onClick = viewModel::loadMore, enabled = online) {
                                Text("加载更多（已加载 ${level.listing.entries.size} 项）")
                            }
                        }
                    }
                }
            }
        }
        val files = level.listing.entries.filterNot { it.folder }
        if (files.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 ${state.selected.size} / ${files.size}" + if (level.listing.hasMore) "+" else "",
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { viewModel.download(files) }, enabled = online) { Text(if (level.listing.hasMore) "下载已加载" else "全部下载") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = viewModel::downloadSelected, enabled = online && state.selected.isNotEmpty()) { Text("下载选中") }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, selected: Boolean, viewModel: MainViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (entry.folder) viewModel.openFolder(entry) else viewModel.toggle(entry) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeBadge(entry)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = if (entry.folder) "文件夹" else entry.sizeText.ifBlank { "大小未知" }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.folder) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "打开")
        } else {
            Checkbox(checked = selected, onCheckedChange = { viewModel.toggle(entry) })
            TextButton(onClick = { viewModel.download(listOf(entry)) }) { Text("下载") }
        }
    }
}

@Composable
private fun TypeBadge(entry: Entry) {
    val label = if (entry.folder) "DIR" else entry.fileName.substringAfterLast('.', "").take(4).uppercase().ifBlank { "FILE" }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (entry.folder) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(width = 44.dp, height = 32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
