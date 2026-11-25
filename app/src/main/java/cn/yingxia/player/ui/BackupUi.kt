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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.R
import cn.yingxia.player.data.BackupTooLargeException
import cn.yingxia.player.data.LibraryBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error(context.getString(R.string.backup_error_write))
                }
            }.onSuccess { vm.message(context.getString(R.string.backup_saved_notice)) }
                .onFailure { vm.message(it.message ?: context.getString(R.string.backup_error_save)) }
        }
    }
    val import = rememberLauncherForActivityResult(LocalBackupImport()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { LibraryBackup.readBoundedUtf8(it) }
                        ?: error(context.getString(R.string.backup_error_read))
                }
            }.onSuccess { vm.restoreBackup(it) }.onFailure { error ->
                val message = if (error is BackupTooLargeException) context.getString(R.string.backup_error_too_large)
                else error.message ?: context.getString(R.string.backup_error_read)
                vm.message(message)
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Download, size = 42.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(stringResource(R.string.backup_export_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.backup_export_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.backup_export_desc),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                Button(onClick = { export.launch("YingXia-backup.json") }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_save_button))
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            val json = LibraryBackup.export(vm.graph.repository.allVideos(), vm.settings.value)
                            val uri = withContext(Dispatchers.IO) {
                                val dir = File(context.cacheDir, "backup").apply { mkdirs() }
                                val file = File(dir, "YingXia-backup.json")
                                file.writeText(json)
                                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            }
                            val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.backup_share_chooser)))
                        }.onFailure { vm.message(it.message ?: context.getString(R.string.backup_error_share)) }
                    }
                }, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_share_button))
                }
            }
        }
        item {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Restore, size = 42.dp, container = MaterialTheme.colorScheme.tertiaryContainer, tint = MaterialTheme.colorScheme.tertiary)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.backup_restore_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.backup_restore_desc),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/*", "*/*")) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_restore_button))
                }
            }
        }
    }
}
