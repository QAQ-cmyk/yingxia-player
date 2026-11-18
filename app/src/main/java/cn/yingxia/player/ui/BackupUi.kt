package cn.yingxia.player.ui

import android.content.Intent
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.data.LibraryBackup
import kotlinx.coroutines.launch
import java.io.File

private class LocalBackupExport : ActivityResultContracts.CreateDocument("application/json") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
private class LocalBackupImport : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable fun BackupPage(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(LocalBackupExport()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = LibraryBackup.export(vm.graph.repository.allVideos(), vm.settings.value)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("无法写入备份")
            }.onSuccess { vm.message("备份已保存。里面没有视频文件。") }
                .onFailure { vm.message(it.message ?: "无法保存备份") }
        }
    }
    val import = rememberLauncherForActivityResult(LocalBackupImport()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("无法读取备份")
            }.onSuccess { vm.restoreBackup(it) }.onFailure { vm.message(it.message ?: "无法读取备份") }
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Download, size = 42.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("导出与分享", style = MaterialTheme.typography.titleMedium)
                        Text("纯文本 JSON，仅保存在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("备份里有收藏、播放进度、显示名称、裁黑边，以及倍速、排序和防误触。这是本机上的文本文件，应用不会上传。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                Button(onClick = { export.launch("YingXia-backup.json") }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("保存备份文件")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            val json = LibraryBackup.export(vm.graph.repository.allVideos(), vm.settings.value)
                            val dir = File(context.cacheDir, "backup").apply { mkdirs() }
                            val file = File(dir, "YingXia-backup.json")
                            file.writeText(json)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "分享备份"))
                        }.onFailure { vm.message(it.message ?: "无法分享备份") }
                    }
                }, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("分享备份")
                }
            }
        }
        item {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Restore, size = 42.dp, container = MaterialTheme.colorScheme.tertiaryContainer, tint = MaterialTheme.colorScheme.tertiary)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("恢复记录", style = MaterialTheme.typography.titleMedium)
                        Text("按本机视频地址自动匹配", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("恢复时按视频地址匹配。手机里已经没有的视频会跳过，不会新建空记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/*", "*/*")) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("从备份恢复")
                }
            }
        }
    }
}
