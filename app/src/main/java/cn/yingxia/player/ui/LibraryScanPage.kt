package cn.yingxia.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.core.VideoId
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.core.displayTitle

@Composable fun LibraryScanPage(vm: AppViewModel) {
    val scan by vm.libraryScan.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf(setOf<VideoId>()) }
    var confirm by remember { mutableStateOf<List<VideoItem>?>(null) }
    confirm?.let { items -> FileActionDialogs(vm, items, false) { confirm = null; chosen = emptySet() } }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("只检查这台手机里的视频，不会上传。重复是指文件内容完全一样。打不开只说明读不了，不表示可以修复。检查不会自动删除。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            if (scan.running) {
                Text(if (scan.total > 0) "正在检查 ${scan.done + 1}/${scan.total}" else "正在准备", style = MaterialTheme.typography.titleSmall)
                Text(scan.label, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (scan.total == 0) 0f else scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedButton(onClick = { vm.cancelLibraryScan() }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
            } else Button(onClick = { chosen = emptySet(); vm.startLibraryScan() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (scan.total == 0) "开始检查" else "重新检查") }
        }
        if (!scan.running && scan.total > 0 && scan.duplicates.isEmpty() && scan.unreadable.isEmpty()) item {
            Text("没有发现内容完全相同或打不开的视频。", style = MaterialTheme.typography.bodyMedium)
        }
        scan.duplicates.forEachIndexed { index, group ->
            item { Text("重复 ${index + 1} · ${group.size} 个文件内容相同", style = MaterialTheme.typography.titleSmall) }
            items(group, key = { "dup-$index-${it.id.value}" }) { video ->
                ScanRow(video, video.displayTitle(), video.id in chosen) {
                    chosen = if (video.id in chosen) chosen - video.id else chosen + video.id
                }
            }
        }
        if (scan.unreadable.isNotEmpty()) item { Text("打不开", style = MaterialTheme.typography.titleSmall) }
        items(scan.unreadable, key = { "bad-${it.first.id.value}" }) { (video, reason) ->
            ScanRow(video, "${video.displayTitle()}\n$reason", video.id in chosen) {
                chosen = if (video.id in chosen) chosen - video.id else chosen + video.id
            }
        }
        if (chosen.isNotEmpty()) item {
            val picked = (scan.duplicates.flatten() + scan.unreadable.map { it.first }).filter { it.id in chosen }.distinctBy { it.id }
            Button(onClick = { confirm = picked }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("删除所选文件")
            }
            Text("会从手机存储删除原文件，系统也会要求确认。只是不想看见时，请回列表使用「隐藏」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ScanRow(video: VideoItem, label: String, selected: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
            Icon(if (selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, if (selected) "取消选择" else "选择")
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(fileSize(video.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
