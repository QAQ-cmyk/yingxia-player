package cn.yingxia.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.core.*
import cn.yingxia.player.features.BookmarkFeature
import cn.yingxia.player.features.LoopFeature
import cn.yingxia.player.features.SleepTimerFeature
import kotlinx.coroutines.launch

/**
 * 一个播放工具的界面。在 [yingxiaTools] 里登记后，播放工具列表会显示入口。
 * One player tool. Register it in [yingxiaTools] and the tools sheet shows it.
 */
data class PlayerTool(
    val feature: PlayerFeature,
    val icon: ImageVector,
    val panel: @Composable (PlayerUiSession, PlaybackState, () -> Unit) -> Unit,
)

/** 内置工具。要加功能，在这个列表追加一项即可，不必改播放器核心。 */
fun yingxiaTools(loop: LoopFeature, bookmarks: BookmarkFeature, timer: SleepTimerFeature) = listOf(
    PlayerTool(loop, Icons.Outlined.Repeat) { vm, state, _ -> LoopPanel(vm, state) },
    PlayerTool(bookmarks, Icons.Outlined.BookmarkBorder) { vm, state, _ -> BookmarkPanel(vm, state) },
    PlayerTool(timer, Icons.Outlined.Bedtime) { vm, _, _ -> SleepPanel(vm) },
)

@Composable private fun LoopPanel(vm: PlayerUiSession, state: PlaybackState) {
    val scope = rememberCoroutineScope()
    val a by vm.loop.pointA.collectAsStateWithLifecycle()
    PanelTitle("A-B 区间循环", "在需要反复观看的片段，分别标记起点与终点")
    SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text("当前 ${durationText(state.positionMs)} / ${durationText(state.durationMs)}", style = MaterialTheme.typography.titleMedium)
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("A  ${a?.let(::durationText) ?: "未设置"}     B  ${state.loop?.endMs?.let(::durationText) ?: "未设置"}",
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { scope.launch { vm.loop.markA() } }, shape = CircleShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("设置 A 点") }
        Button(onClick = { scope.launch { vm.loop.markB() } }, enabled = a != null, shape = CircleShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("设置 B 点") }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { vm.command(PlayerCommand.Seek(state.positionMs + 5000)) }) { Text("前进 5 秒") }
        TextButton(onClick = { vm.command(PlayerCommand.Toggle) }) { Text(if (state.isPlaying) "暂停" else "继续播放") }
        TextButton(onClick = { scope.launch { vm.loop.clear() } }) { Text("清除区间") }
    }
    Text(if (state.loop == null) "B 点需要比 A 点晚至少 0.5 秒。" else "正在循环 ${durationText(state.loop.startMs)} — ${durationText(state.loop.endMs)}。\n跳到区间外或切换视频会关闭循环，睡眠定时仍然生效。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

@Composable private fun BookmarkPanel(vm: PlayerUiSession, state: PlaybackState) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var editNote by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    PanelTitle("时间书签", if (vm.privateSession) "备注仅在本机加密保存，不出现在普通书签里" else "给值得重看的瞬间，留一句备注")
    OutlinedTextField(note, { if (it.length <= 200) note = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("备注（可选）") }, maxLines = 3)
    Button(onClick = { scope.launch {
        runCatching { vm.bookmarkFeature.add(note) }.onSuccess { note = ""; vm.message("书签已保存") }.onFailure { vm.message(it.message ?: "保存失败") }
    } }, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("在 ${durationText(state.positionMs)} 添加书签")
    }
    val current = bookmarks.filter { it.videoId == state.video?.id }
    if (current.isEmpty()) Text("还没有书签，试着记下第一个片段吧。", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    current.forEach { bookmark ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { vm.command(PlayerCommand.Seek(bookmark.positionMs)) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(durationText(bookmark.positionMs), Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                }
                Text(bookmark.note.ifBlank { "未命名书签" }, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { editing = bookmark; editNote = bookmark.note }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Edit, "编辑书签", Modifier.size(20.dp)) }
                IconButton(onClick = { pendingDelete = bookmark }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除书签", Modifier.size(20.dp)) }
            }
        }
    }
    editing?.let { bookmark -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("编辑书签") },
        text = { OutlinedTextField(editNote, { if (it.length <= 200) editNote = it }, label = { Text("备注") }) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { vm.repository.saveBookmark(bookmark.videoId, bookmark.positionMs, editNote, bookmark.id) }.onFailure { vm.message(it.message ?: "保存失败") } }; editing = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    pendingDelete?.let { bookmark ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除这本书签？") },
            text = { Text("「${bookmark.note.ifBlank { "未命名书签" }}」（${durationText(bookmark.positionMs)}）会从本机记录中移除。") },
            confirmButton = { TextButton(onClick = { scope.launch { vm.repository.deleteBookmark(bookmark.id) }; pendingDelete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}

@Composable private fun SleepPanel(vm: PlayerUiSession) {
    val remaining by vm.timer.remainingMs.collectAsStateWithLifecycle()
    PanelTitle("睡眠定时", "到时暂停播放，A-B 循环和列表循环也适用")
    val untilEnd by vm.timer.untilEnd.collectAsStateWithLifecycle()
    if (remaining > 0) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(if (untilEnd) "播完当前后暂停 · ${durationText(remaining)}" else "${durationText(remaining)} 后暂停",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60, 90).forEach { minutes -> OutlinedButton(onClick = { vm.timer.start(minutes) }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text("$minutes 分钟") } }
        OutlinedButton(onClick = { vm.timer.stopWhenFinished() }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text("播完当前") }
    }
    if (remaining > 0) TextButton(onClick = { vm.timer.cancel() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Text("取消定时") }
}
