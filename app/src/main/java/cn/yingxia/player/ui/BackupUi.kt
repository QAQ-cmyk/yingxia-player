package cn.yingxia.player.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.R
import cn.yingxia.player.data.BackupTooLargeException
import cn.yingxia.player.data.LibraryBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class LocalBackupExport(mime: String = "application/json") : ActivityResultContracts.CreateDocument(mime) {
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
    var askingEncryptPwd by remember { mutableStateOf(false) }
    var pendingEncryptPassword by remember { mutableStateOf<CharArray?>(null) }
    var pendingEncryptedBytes by remember { mutableStateOf<ByteArray?>(null) }

    val exportPlain = rememberLauncherForActivityResult(LocalBackupExport("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val settings = vm.settings.value
                withContext(Dispatchers.IO) {
                    val bookmarks = vm.graph.repository.bookmarks.first()
                    val json = LibraryBackup.export(vm.graph.repository.allVideos(), settings, bookmarks)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error(context.getString(R.string.backup_error_write))
                }
            }.onSuccess { vm.message(context.getString(R.string.backup_saved_notice)) }
                .onFailure { vm.message(it.message ?: context.getString(R.string.backup_error_save)) }
        }
    }

    val exportEncrypted = rememberLauncherForActivityResult(LocalBackupExport("application/octet-stream")) { uri ->
        val pwd = pendingEncryptPassword
        pendingEncryptPassword = null
        if (uri == null || pwd == null) {
            pwd?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                val settings = vm.settings.value
                withContext(Dispatchers.IO) {
                    val bookmarks = vm.graph.repository.bookmarks.first()
                    val encBytes = LibraryBackup.exportEncrypted(vm.graph.repository.allVideos(), settings, pwd, bookmarks)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(encBytes) }
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
                    context.contentResolver.openInputStream(uri)?.use { LibraryBackup.readBoundedBytes(it) }
                        ?: error(context.getString(R.string.backup_error_read))
                }
            }.onSuccess { bytes ->
                if (LibraryBackup.isEncryptedBackup(bytes)) {
                    pendingEncryptedBytes = bytes
                } else {
                    vm.restoreBackup(String(bytes, Charsets.UTF_8))
                }
            }.onFailure { error ->
                val message = if (error is BackupTooLargeException) context.getString(R.string.backup_error_too_large)
                else error.message ?: context.getString(R.string.backup_error_read)
                vm.message(message)
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "backup-export", contentType = "card") {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Lock, size = 40.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(stringResource(R.string.backup_export_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.backup_export_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(R.string.backup_export_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
                )
                Button(
                    onClick = { askingEncryptPwd = true },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_export_encrypted_button))
                }
                FilledTonalButton(
                    onClick = {
                        runCatching { exportPlain.launch("YingXia-backup.json") }
                            .onFailure { vm.message(context.getString(R.string.backup_error_save)) }
                    },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 46.dp),
                ) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_save_button))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val settings = vm.settings.value
                                val uri = withContext(Dispatchers.IO) {
                                    LibraryBackup.clearShareCache(context)
                                    val bookmarks = vm.graph.repository.bookmarks.first()
                                    val json = LibraryBackup.export(vm.graph.repository.allVideos(), settings, bookmarks)
                                    val dir = File(context.cacheDir, "backup").apply { mkdirs() }
                                    val file = File(dir, "YingXia-backup.json")
                                    file.writeText(json)
                                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                }
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("application/json")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, context.getString(R.string.backup_share_chooser)))
                            }.onFailure { vm.message(it.message ?: context.getString(R.string.backup_error_share)) }
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 46.dp),
                ) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_share_button))
                }
            }
        }
        item(key = "backup-restore", contentType = "card") {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Restore, size = 40.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.backup_restore_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(R.string.backup_restore_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
                )
                OutlinedButton(
                    onClick = {
                        runCatching { import.launch(arrayOf("application/octet-stream", "application/json", "text/*", "*/*")) }
                            .onFailure { vm.message(context.getString(R.string.backup_error_read)) }
                    },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_restore_button))
                }
            }
        }
    }

    if (askingEncryptPwd) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askingEncryptPwd = false },
            title = { Text(stringResource(R.string.backup_encrypt_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it.take(128) },
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pwd.length >= 8,
                    onClick = {
                        val chars = pwd.toCharArray()
                        pwd = ""
                        askingEncryptPwd = false
                        pendingEncryptPassword = chars
                        runCatching { exportEncrypted.launch("YingXia-backup.yxbackup.enc") }
                            .onFailure {
                                chars.fill('\u0000')
                                pendingEncryptPassword = null
                                vm.message(context.getString(R.string.backup_error_save))
                            }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { askingEncryptPwd = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    pendingEncryptedBytes?.let { encBytes ->
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingEncryptedBytes = null },
            title = { Text(stringResource(R.string.backup_decrypt_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it.take(128) },
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pwd.length >= 8,
                    onClick = {
                        val chars = pwd.toCharArray()
                        pwd = ""
                        pendingEncryptedBytes = null
                        vm.restoreEncryptedBackup(encBytes, chars)
                    },
                ) { Text(stringResource(R.string.backup_restore_button)) }
            },
            dismissButton = { TextButton(onClick = { pendingEncryptedBytes = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
