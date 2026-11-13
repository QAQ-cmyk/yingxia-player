package cn.yingxia.player.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.data.LibraryBackup
import kotlinx.coroutines.launch
import java.io.File

@Composable fun BackupPage(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = LibraryBackup.export(vm.graph.repository.allVideos(), vm.settings.value)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("无法写入备份")
            }.onSuccess { vm.message("备份已保存。里面没有视频文件。") }
                .onFailure { vm.message(it.message ?: "无法保存备份") }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("无法读取备份")
            }.onSuccess { vm.restoreBackup(it) }.onFailure { vm.message(it.message ?: "无法读取备份") }
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("备份里有收藏、播放进度、显示名称、画面旋转，以及倍速、排序、手势这些设置。没有视频文件，也不含网盘密码。不需要账号。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Button(onClick = { export.launch("YingXia-backup.json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存备份文件") } }
        item {
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
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("分享备份") }
        }
        item { OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/*", "*/*")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("从备份恢复") } }
        item { Text("恢复时按视频地址匹配。手机里已经没有的视频会跳过，不会新建空记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
