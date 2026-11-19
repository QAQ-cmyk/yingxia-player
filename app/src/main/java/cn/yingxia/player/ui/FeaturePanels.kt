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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.R
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

@Composable internal fun localizedFeatureTitle(feature: PlayerFeature): String = when (feature.id) {
    "loop" -> stringResource(R.string.feature_loop_title)
    "bookmarks" -> stringResource(R.string.feature_bookmarks_title)
    "sleep" -> stringResource(R.string.feature_sleep_title)
    else -> feature.title
}

/** 内置工具。要加功能，在这个列表追加一项即可，不必改播放器核心。 */
fun yingxiaTools(loop: LoopFeature, bookmarks: BookmarkFeature, timer: SleepTimerFeature) = listOf(
    PlayerTool(loop, Icons.Outlined.Repeat) { vm, state, _ -> LoopPanel(vm, state) },
    PlayerTool(bookmarks, Icons.Outlined.BookmarkBorder) { vm, state, _ -> BookmarkPanel(vm, state) },
    PlayerTool(timer, Icons.Outlined.Bedtime) { vm, _, _ -> SleepPanel(vm) },
)

@Composable private fun LoopPanel(vm: PlayerUiSession, state: PlaybackState) {
    val scope = rememberCoroutineScope()
    val a by vm.loop.pointA.collectAsStateWithLifecycle()
    val unset = stringResource(R.string.loop_unset)
    PanelTitle(stringResource(R.string.loop_panel_title), stringResource(R.string.loop_panel_desc))
    SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(stringResource(R.string.loop_current_time, durationText(state.positionMs), durationText(state.durationMs)), style = MaterialTheme.typography.titleMedium)
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(stringResource(R.string.loop_range_display, a?.let(::durationText) ?: unset, state.loop?.endMs?.let(::durationText) ?: unset),
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { scope.launch { vm.loop.markA() } }, shape = CircleShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.loop_set_a)) }
        Button(onClick = { scope.launch { vm.loop.markB() } }, enabled = a != null, shape = CircleShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.loop_set_b)) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { vm.command(PlayerCommand.Seek(state.positionMs + 5000)) }) { Text(stringResource(R.string.loop_forward_5s)) }
        TextButton(onClick = { vm.command(PlayerCommand.Toggle) }) { Text(stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_resume_play)) }
        TextButton(onClick = { scope.launch { vm.loop.clear() } }) { Text(stringResource(R.string.loop_clear)) }
    }
    Text(if (state.loop == null) stringResource(R.string.loop_hint_unset) else stringResource(R.string.loop_hint_active, durationText(state.loop.startMs), durationText(state.loop.endMs)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

@Composable private fun BookmarkPanel(vm: PlayerUiSession, state: PlaybackState) {
    val context = LocalContext.current
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var editNote by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    val untitled = stringResource(R.string.bookmark_untitled)
    PanelTitle(stringResource(R.string.title_bookmarks), stringResource(if (vm.privateSession) R.string.bookmarks_panel_desc_private else R.string.bookmarks_panel_desc_public))
    OutlinedTextField(note, { if (it.length <= 200) note = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text(stringResource(R.string.bookmark_note_optional)) }, maxLines = 3)
    Button(onClick = { scope.launch {
        runCatching { vm.bookmarkFeature.add(note) }.onSuccess { note = ""; vm.message(context.getString(R.string.bookmark_saved)) }.onFailure { vm.message(it.message ?: context.getString(R.string.error_save_failed)) }
    } }, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.bookmark_add_at, durationText(state.positionMs)))
    }
    val current = bookmarks.filter { it.videoId == state.video?.id }
    if (current.isEmpty()) Text(stringResource(R.string.bookmarks_panel_empty), Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    current.forEach { bookmark ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { vm.command(PlayerCommand.Seek(bookmark.positionMs)) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(durationText(bookmark.positionMs), Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                }
                Text(bookmark.note.ifBlank { untitled }, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { editing = bookmark; editNote = bookmark.note }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Edit, stringResource(R.string.bookmark_edit), Modifier.size(20.dp)) }
                IconButton(onClick = { pendingDelete = bookmark }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.bookmark_delete), Modifier.size(20.dp)) }
            }
        }
    }
    editing?.let { bookmark -> AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.bookmark_edit)) },
        text = { OutlinedTextField(editNote, { if (it.length <= 200) editNote = it }, label = { Text(stringResource(R.string.bookmark_note)) }) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { vm.repository.saveBookmark(bookmark.videoId, bookmark.positionMs, editNote, bookmark.id) }.onFailure { vm.message(it.message ?: context.getString(R.string.error_save_failed)) } }; editing = null }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    pendingDelete?.let { bookmark ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(stringResource(R.string.bookmark_delete_title)) },
            text = { Text(stringResource(R.string.bookmark_delete_desc, bookmark.note.ifBlank { untitled }, durationText(bookmark.positionMs))) },
            confirmButton = { TextButton(onClick = { scope.launch { vm.repository.deleteBookmark(bookmark.id) }; pendingDelete = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable private fun SleepPanel(vm: PlayerUiSession) {
    val remaining by vm.timer.remainingMs.collectAsStateWithLifecycle()
    PanelTitle(stringResource(R.string.feature_sleep_title), stringResource(R.string.sleep_panel_desc))
    val untilEnd by vm.timer.untilEnd.collectAsStateWithLifecycle()
    if (remaining > 0) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(if (untilEnd) stringResource(R.string.sleep_until_end_remaining, durationText(remaining)) else stringResource(R.string.player_sleep_remaining_chip, durationText(remaining)),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60, 90).forEach { minutes -> OutlinedButton(onClick = { vm.timer.start(minutes) }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sleep_minutes, minutes)) } }
        OutlinedButton(onClick = { vm.timer.stopWhenFinished() }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sleep_after_current)) }
    }
    if (remaining > 0) TextButton(onClick = { vm.timer.cancel() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.sleep_cancel)) }
}
