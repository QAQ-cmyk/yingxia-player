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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
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
        if (it) run() else { vm.message("未允许写入存储，原视频未删除或移动"); onDismiss() }
    }
    val confirm = {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else run()
    }
    if (creating) NewFolderDialog(vm, { creating = false }) { target = it; creating = false }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (moving) "移动到文件夹" else "删除相册原文件？") },
        text = { Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
            Text("已选择 ${items.size} 个视频\n" + items.take(3).joinToString("\n") { it.name } + if (items.size > 3) "\n……" else "")
            Spacer(Modifier.height(16.dp))
            if (moving) {
                Text("文件会真正移动。复制并校验后才删除原件；同名文件不会被覆盖。请保持应用在前台。", style = MaterialTheme.typography.bodySmall)
                folders.forEach { folder ->
                    TextButton(onClick = { target = folder }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        RadioButton(selected = target == folder, onClick = null); Spacer(Modifier.width(8.dp)); Text(folder.name)
                    }
                }
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("新建文件夹") }
            } else Text("这会从手机存储中删除原视频，系统相册也会失去该文件，无法在映匣中撤销。仅想不显示时，请使用「隐藏」。")
        } },
        confirmButton = { TextButton(onClick = confirm, enabled = !moving || target != null) { Text(if (moving) "开始移动" else "删除原文件", color = if (moving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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
                .onFailure { vm.message("无法保留文件夹授权，请重新选择") }
        }
        waiting = false
    }
    AlertDialog(onDismissRequest = { if (!waiting) onDismiss() }, title = { Text("新建手机文件夹") },
        text = { Column {
            Text("输入名称，再选择它的上级位置。例如选择 Movies 后，将在其中创建这个文件夹。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("文件夹名称") }, singleLine = true,
                modifier = Modifier.padding(top = 12.dp), isError = name.isNotEmpty() && !LocalFileManager.validFolderName(name))
        } },
        confirmButton = { TextButton(enabled = LocalFileManager.validFolderName(name) && !waiting, onClick = {
            waiting = true
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        }) { Text("选择存放位置") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !waiting) { Text("取消") } })
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
    if (state.busy) AlertDialog(onDismissRequest = {}, title = { Text("正在处理本地文件") },
        text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.message, Modifier.padding(top = 16.dp)) } }, confirmButton = {})
    state.result?.let { report ->
        val dismiss = { vm.fileOperation.value = vm.fileOperation.value.copy(result = null) }
        AlertDialog(onDismissRequest = dismiss, title = { Text("文件操作结果") }, text = { Text(report) },
            confirmButton = { TextButton(onClick = dismiss) { Text("知道了") } })
    }
}
