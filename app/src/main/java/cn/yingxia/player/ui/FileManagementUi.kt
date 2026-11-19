package cn.yingxia.player.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.R
import cn.yingxia.player.core.*
import cn.yingxia.player.data.LocalFileManager

fun belongsToFolder(video: VideoItem, folderUri: String): Boolean = runCatching {
    val videoUri = Uri.parse(video.uri); val target = Uri.parse(folderUri)
    videoUri.authority == target.authority && DocumentsContract.getDocumentId(videoUri).substringBeforeLast('/') == DocumentsContract.getDocumentId(target)
}.getOrDefault(false)

/** A delete/move request is scoped to the exact videos shown in the confirmation. */
@Composable fun FileActionDialogs(vm: AppViewModel, items: List<VideoItem>, moving: Boolean, onDismiss: () -> Unit) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var target by remember { mutableStateOf<StorageFolder?>(null) }
    var creating by remember { mutableStateOf(false) }
    val run = { vm.manageFiles(items, if (moving) target else null); onDismiss() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) run() else { vm.message(context.getString(R.string.file_perm_denied)); onDismiss() }
    }
    val confirm = {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else run()
    }
    if (creating) NewFolderDialog(vm, { creating = false }) { target = it; creating = false }
    val previewNames = items.take(3).joinToString("\n") { it.name } + if (items.size > 3) "\n…" else ""
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (moving) R.string.file_dialog_move_title else R.string.file_dialog_delete_title)) },
        text = { Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.file_dialog_selected_summary, items.size, previewNames))
            Spacer(Modifier.height(16.dp))
            if (moving) {
                Text(stringResource(R.string.file_dialog_move_desc), style = MaterialTheme.typography.bodySmall)
                folders.forEach { folder ->
                    TextButton(onClick = { target = folder }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        RadioButton(selected = target == folder, onClick = null); Spacer(Modifier.width(8.dp)); Text(folder.name)
                    }
                }
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.folder_new)) }
            } else Text(stringResource(R.string.file_dialog_delete_desc))
        } },
        confirmButton = { TextButton(onClick = confirm, enabled = !moving || target != null) { Text(stringResource(if (moving) R.string.file_dialog_move_confirm else R.string.file_dialog_delete_confirm), color = if (moving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable fun NewFolderDialog(vm: AppViewModel, onDismiss: () -> Unit, onCreated: (StorageFolder) -> Unit = {}) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var waiting by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data; val tree = data?.data
        if (result.resultCode == Activity.RESULT_OK && tree != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onSuccess { vm.createFolder(tree.toString(), name.trim()) { onCreated(it); onDismiss() } }
                .onFailure { vm.message(context.getString(R.string.folder_persist_perm_failed)) }
        }
        waiting = false
    }
    AlertDialog(onDismissRequest = { if (!waiting) onDismiss() }, title = { Text(stringResource(R.string.folder_dialog_title)) },
        text = { Column {
            Text(stringResource(R.string.folder_dialog_desc), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.folder_dialog_label)) }, singleLine = true,
                modifier = Modifier.padding(top = 12.dp), isError = name.isNotEmpty() && !LocalFileManager.validFolderName(name))
        } },
        confirmButton = { TextButton(enabled = LocalFileManager.validFolderName(name) && !waiting, onClick = {
            waiting = true
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        }) { Text(stringResource(R.string.folder_dialog_pick_parent)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !waiting) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable fun FileOperationHost(vm: AppViewModel) {
    val state by vm.fileOperation.collectAsStateWithLifecycle()
    var launched by rememberSaveable { mutableLongStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.fileConsentResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.token, state.consent) {
        if (state.consent != null && launched != state.token) {
            launched = state.token
            runCatching { launcher.launch(IntentSenderRequest.Builder(state.consent!!.sender).build()) }
                .onFailure { vm.fileConsentResult(false) }
        }
    }
    if (state.busy) AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.file_op_busy_title)) },
        text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.message.ifBlank { stringResource(R.string.file_op_preparing) }, Modifier.padding(top = 16.dp)) } }, confirmButton = {})
    state.result?.let { report ->
        val dismiss = { vm.fileOperation.value = vm.fileOperation.value.copy(result = null) }
        AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.file_op_result_title)) }, text = { Text(report) },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_got_it)) } })
    }
}
