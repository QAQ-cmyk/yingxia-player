package cn.yingxia.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.core.*
import kotlinx.coroutines.launch

/** UI contributions are compiled into the app; adding one does not change playback code. */
data class PlayerFeatureUi(val icon: ImageVector, val panel: @Composable (AppViewModel, PlaybackState, () -> Unit) -> Unit)
val BuiltInFeaturePanels = mapOf(
    "subtitles" to PlayerFeatureUi(Icons.Outlined.Subtitles) { vm, state, _ -> SubtitlePanel(vm, state) },
    "loop" to PlayerFeatureUi(Icons.Outlined.Repeat) { vm, state, _ -> LoopPanel(vm, state) },
    "bookmarks" to PlayerFeatureUi(Icons.Outlined.BookmarkBorder) { vm, state, _ -> BookmarkPanel(vm, state) },
    "sleep" to PlayerFeatureUi(Icons.Outlined.Bedtime) { vm, _, _ -> SleepPanel(vm) },
)

@Composable private fun SubtitlePanel(vm: AppViewModel, state: PlaybackState) {
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(false) }
    val select = rememberLauncherForActivityResult(LocalDocumentContract("*/*")) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try {
                runCatching { vm.graph.subtitles.load(uri.toString()) }
                    .onSuccess { vm.command(PlayerCommand.AttachSubtitle(it)); vm.message("字幕已加载") }
                    .onFailure { vm.message(it.message ?: "字幕读取失败") }
            } finally { loading = false }
        }
    }
    PanelTitle("字幕", "支持内嵌轨道与 UTF-8 编码的 SRT、VTT 字幕")
    TextButton(onClick = { vm.command(PlayerCommand.SelectSubtitle(null)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(if (state.subtitles.none { it.selected }) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(22.dp)); Text("  关闭字幕")
    }
    state.subtitles.forEach { track ->
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { vm.command(PlayerCommand.SelectSubtitle(track.id)) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(track.selected, onClick = { vm.command(PlayerCommand.SelectSubtitle(track.id)) })
            Text(track.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (state.subtitles.isEmpty()) Text("这个视频暂无内嵌字幕，可以从文件添加。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
    OutlinedButton(onClick = { select.launch(Unit) }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Add, null); Text(if (loading) "  正在读取…" else "  选择本地字幕")
    }
    Text("字幕字号", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(.75f, 1f, 1.25f, 1.5f).forEach { scale -> FilterChip(settings.subtitleScale == scale, { scope.launch { vm.graph.settings.setSubtitleScale(scale) } },
            label = { Text(when (scale) { .75f -> "小"; 1f -> "标准"; 1.25f -> "大"; else -> "特大" }) }) }
    }
}

@Composable private fun LoopPanel(vm: AppViewModel, state: PlaybackState) {
    val scope = rememberCoroutineScope()
    val a by vm.graph.loop.pointA.collectAsStateWithLifecycle()
    PanelTitle("A-B 区间循环", "在需要反复观看的片段，分别标记起点与终点")
    Text("当前 ${durationText(state.positionMs)} / ${durationText(state.durationMs)}", style = MaterialTheme.typography.titleMedium)
    Text("A  ${a?.let(::durationText) ?: "未设置"}     B  ${state.loop?.endMs?.let(::durationText) ?: "未设置"}", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { scope.launch { vm.graph.loop.markA() } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("设置 A 点") }
        Button(onClick = { scope.launch { vm.graph.loop.markB() } }, enabled = a != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("设置 B 点") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { vm.command(PlayerCommand.Seek(state.positionMs + 5000)) }) { Text("前进 5 秒") }
        TextButton(onClick = { vm.command(PlayerCommand.Toggle) }) { Text(if (state.isPlaying) "暂停" else "继续播放") }
        TextButton(onClick = { scope.launch { vm.graph.loop.clear() } }) { Text("清除区间") }
    }
    Text(if (state.loop == null) "B 点需要比 A 点晚至少 0.5 秒。" else "正在循环 ${durationText(state.loop.startMs)} — ${durationText(state.loop.endMs)}。\n跳到区间外或切换视频会关闭循环，睡眠定时仍然生效。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

@Composable private fun BookmarkPanel(vm: AppViewModel, state: PlaybackState) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var editNote by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    PanelTitle("时间书签", "给值得重看的瞬间，留一句备注")
    OutlinedTextField(note, { if (it.length <= 200) note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("备注（可选）") }, maxLines = 3)
    Button(onClick = { scope.launch {
        runCatching { vm.graph.bookmarks.add(note) }.onSuccess { note = ""; vm.message("书签已保存") }.onFailure { vm.message(it.message ?: "保存失败") }
    } }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) { Text("在 ${durationText(state.positionMs)} 添加书签") }
    val current = bookmarks.filter { it.videoId == state.video?.id }
    if (current.isEmpty()) Text("还没有书签，试着记下第一个片段吧。", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    current.forEach { bookmark ->
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { vm.command(PlayerCommand.Seek(bookmark.positionMs)) }, verticalAlignment = Alignment.CenterVertically) {
            Text(durationText(bookmark.positionMs), color = MaterialTheme.colorScheme.primary)
            Text(bookmark.note.ifBlank { "未命名书签" }, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { editing = bookmark; editNote = bookmark.note }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Edit, "编辑书签", Modifier.size(20.dp)) }
            IconButton(onClick = { pendingDelete = bookmark }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除书签", Modifier.size(20.dp)) }
        }
    }
    editing?.let { bookmark -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("编辑书签") },
        text = { OutlinedTextField(editNote, { if (it.length <= 200) editNote = it }, label = { Text("备注") }) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { vm.graph.repository.saveBookmark(bookmark.videoId, bookmark.positionMs, editNote, bookmark.id) }.onFailure { vm.message(it.message ?: "保存失败") } }; editing = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    pendingDelete?.let { bookmark ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除这本书签？") },
            text = { Text("「${bookmark.note.ifBlank { "未命名书签" }}」（${durationText(bookmark.positionMs)}）会从本机记录中移除。") },
            confirmButton = { TextButton(onClick = { scope.launch { vm.graph.repository.deleteBookmark(bookmark.id) }; pendingDelete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}

@Composable private fun SleepPanel(vm: AppViewModel) {
    val remaining by vm.graph.timer.remainingMs.collectAsStateWithLifecycle()
    PanelTitle("睡眠定时", "到时暂停播放，A-B 循环和列表循环也适用")
    if (remaining > 0) Text("${durationText(remaining)} 后暂停", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 20.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(15, 30, 60, 90).forEach { minutes -> OutlinedButton(onClick = { vm.graph.timer.start(minutes) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("$minutes 分钟") } }
    }
    if (remaining > 0) TextButton(onClick = { vm.graph.timer.cancel() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消定时") }
}
