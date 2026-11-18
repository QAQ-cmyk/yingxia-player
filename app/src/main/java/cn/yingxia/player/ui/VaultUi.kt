@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.yingxia.player.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.core.displayTitle
import cn.yingxia.player.vault.*

@Composable fun VaultHost(vm: AppViewModel) {
    val vault = vm.graph.vault
    val state by vault.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vault.consentResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.consentToken, state.consent) {
        state.consent?.let {
            vault.consentLaunched()
            try { consent.launch(IntentSenderRequest.Builder(it.sender).build()) }
            catch (_: Exception) { vault.consentResult(false) }
        }
    }
    if (!state.visible) return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    BackHandler { if (state.playing != null) vault.stopPlaying() else vault.close() }
    val playbackNotice = remember(state.epoch) { SnackbarHostState() }
    LaunchedEffect(state.playing != null, state.message, state.epoch) {
        if (state.playing != null) state.message?.let {
            playbackNotice.showSnackbar(it)
            vault.dismissMessage()
        }
    }
    var choose by remember(state.epoch) { mutableStateOf(false) }
    var restore by remember(state.epoch) { mutableStateOf<VaultEntry?>(null) }
    var deleting by remember(state.epoch) { mutableStateOf<VaultEntry?>(null) }
    var restoreAfterPermission by remember(state.epoch) { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = restoreAfterPermission; restoreAfterPermission = null
        if (granted && target != null) vault.restore(target)
        else vm.message("需要存储权限才能恢复到相册；加密视频仍保留")
    }
    Surface(Modifier.fillMaxSize().testTag("vault-screen"), color = MaterialTheme.colorScheme.background) {
        val session = vault.playback
        if (state.unlocked && state.playing != null && session != null) {
            val playback by session.player.state.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize()) {
                PlayerPage(session.ui, playback, settings)
                SnackbarHost(playbackNotice, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        } else Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (state.playing != null) vault.stopPlaying() else vault.close() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "退出保险箱") }
                Column(Modifier.weight(1f)) {
                    Text("加密保险箱", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.unlocked) "离开应用会自动锁定" else "只有你知道密码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.unlocked) IconButton(onClick = { vault.lock() }) { Icon(Icons.Outlined.Lock, "立即锁定") }
            }
            if (!state.unlocked) {
                VaultPassword(state, vault::unlock, Modifier.weight(1f))

            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        if (state.damagedRecords > 0) Text("有 ${state.damagedRecords} 条记录无法解密，相关文件已保留。其他视频仍可使用。", color = MaterialTheme.colorScheme.error)
                        Text("加密视频不参与普通搜索、收藏或记录备份。这里只显示名称，不生成明文封面。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { choose = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("加密本地视频")
                        }
                    }
                    if (state.entries.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Lock, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("保险箱还是空的", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                            Text("从本地视频中选择，或在卡片菜单中移入。", Modifier.padding(top = 8.dp))
                        }
                    }
                    items(state.entries, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                Text("${durationText(entry.duration)} · ${entry.bytes / 1048576} MB", style = MaterialTheme.typography.bodySmall)
                                Text(if (!entry.sourceRemoved) "原件移除尚未确认，相册可能仍可见" else if (entry.restoredAt > 0) "已恢复过相册副本；保险箱仍保留" else "已加密，相册原件已移除",
                                    color = if (!entry.sourceRemoved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { vault.play(entry) }, enabled = !state.busy) { Text("播放") }
                                    TextButton(onClick = { restore = entry }, enabled = !state.busy) { Text("恢复到相册") }
                                    IconButton(onClick = { deleting = entry }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, "删除保险箱副本") }
                                }
                                if (!entry.sourceRemoved) OutlinedButton(onClick = { vault.retryRemoval(entry.id) }, enabled = !state.busy) { Text("继续移除相册原件") }
                            }
                        }
                    }
                    item { Text("忘记密码无法找回。卸载应用或清空数据会删除保险箱视频；操作前请先恢复到相册。系统相册的云端备份、回收站和其他副本需要你另行处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (state.busy) Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.ifBlank { "正在处理，请保持应用在前台" }, style = MaterialTheme.typography.bodyMedium)
                Text("切到后台会锁定并中断当前步骤；未完成校验的原件不会被移除。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    state.message?.takeIf { state.playing == null }?.let { text ->
        AlertDialog(onDismissRequest = vault::dismissMessage, title = { Text("保险箱提示") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = vault::dismissMessage) { Text("知道了") } })
    }
    if (state.unlocked && state.importItems.isNotEmpty() && !state.busy) {
        AlertDialog(onDismissRequest = vault::dismissImport, title = { Text("加密 ${state.importItems.size} 个视频") },
            text = { Text("先加密并完整校验，再请求系统移除相册原件。取消系统授权时，相册原件仍可见。\n\n这些视频在普通列表中的收藏、进度和书签会被清除，以免留下明文记录。\n\n请记住保险箱密码；卸载应用或清空数据会丢失保险箱视频。") },
            confirmButton = { TextButton(onClick = vault::importConfirmed) { Text("加密并移除原件") } },
            dismissButton = { TextButton(onClick = vault::dismissImport) { Text("取消") } })
    }
    restore?.let { entry ->
        AlertDialog(onDismissRequest = { restore = null }, title = { Text("恢复到手机相册？") },
            text = { Text("解密后的视频将重新对相册及有权限的应用可见。完整校验后保存到 Movies/映匣恢复，保险箱中的加密副本仍保留。") },
            confirmButton = { TextButton(onClick = {
                restore = null
                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    restoreAfterPermission = entry.id; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else vault.restore(entry.id)
            }) { Text("解密恢复") } }, dismissButton = { TextButton(onClick = { restore = null }) { Text("取消") } })
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("永久删除加密视频？") },
            text = { Text("将从保险箱永久删除「${entry.name}」，无法撤销。如果相册原件已移除且没有另行恢复，这会删除仅剩的视频副本。") },
            confirmButton = { TextButton(onClick = { deleting = null; vault.deleteEncrypted(entry.id) }) { Text("永久删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
    if (choose && state.unlocked) {
        val videos by vm.videos.collectAsStateWithLifecycle()
        VaultPicker(videos.filter { it.accessible }, { choose = false }) { selected -> choose = false; vault.requestImport(selected) }
    }
}

@Composable private fun VaultPassword(state: VaultUiState, unlock: (CharArray) -> Unit, modifier: Modifier = Modifier) {
    var password by remember(state.epoch) { mutableStateOf("") }
    var confirmation by remember(state.epoch) { mutableStateOf("") }
    var acknowledged by remember(state.epoch) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (state.configured) "输入密码解锁" else "设置保险箱密码", style = MaterialTheme.typography.headlineSmall)
        Text(if (state.configured) "密码只用于本机解密，不会上传。没有忘记密码或重置入口。" else "至少 8 个字符，建议使用较长的独立密码。请保存在你能找回的安全位置。")
        OutlinedTextField(password, { password = it.take(128) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("vault-password"), label = { Text("保险箱密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
        if (!state.configured) {
            OutlinedTextField(confirmation, { confirmation = it.take(128) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("vault-password-confirm"), label = { Text("再次输入密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            Row(Modifier.fillMaxWidth().clickable { acknowledged = !acknowledged }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledged = it })
                Text("我已了解：忘记密码无法恢复；卸载应用、清空数据会丢失视频。普通记录备份不包含保险箱。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = { val chars = password.toCharArray(); password = ""; confirmation = ""; unlock(chars) },
            enabled = !state.busy && password.length >= 8 && (state.configured || password == confirmation && acknowledged),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vault-unlock")) { Text(if (state.configured) "解锁" else "创建保险箱") }
    }
}

@Composable private fun VaultPicker(videos: List<VideoItem>, dismiss: () -> Unit, selected: (List<VideoItem>) -> Unit) {
    var ids by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("选择要加密的视频") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (videos.isEmpty()) item { Text("还没有可访问的视频，请先在首页授权相册或打开本地文件。") }
            items(videos, key = { it.id.value }) { video ->
                Row(Modifier.fillMaxWidth().clickable { ids = if (video.id.value in ids) ids - video.id.value else ids + video.id.value }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(video.id.value in ids, null)
                    Text(video.displayTitle(), Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { selected(videos.filter { it.id.value in ids }) }, enabled = ids.isNotEmpty()) { Text("选择 ${ids.size} 项") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
