package cn.yingxia.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.R
import cn.yingxia.player.core.VideoId
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.core.displayTitle

@Composable fun LibraryScanPage(vm: AppViewModel) {
    val scan by vm.libraryScan.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf(setOf<VideoId>()) }
    var confirm by remember { mutableStateOf<List<VideoItem>?>(null) }
    confirm?.let { items -> FileActionDialogs(vm, items, false) { confirm = null; chosen = emptySet() } }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.AutoMirrored.Outlined.FactCheck, size = 42.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(stringResource(R.string.scan_card_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.scan_card_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.scan_card_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                if (scan.running) {
                    Text(if (scan.total > 0) stringResource(R.string.scan_progress, scan.done + 1, scan.total) else stringResource(R.string.scan_preparing), style = MaterialTheme.typography.titleSmall)
                    Text(scan.label.ifBlank { stringResource(R.string.scan_not_started_label) }, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (scan.total == 0) 0f else scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedButton(onClick = { vm.cancelLibraryScan() }, shape = CircleShape, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
                } else Button(onClick = { chosen = emptySet(); vm.startLibraryScan() }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(if (scan.total == 0) R.string.scan_start else R.string.scan_restart)) }
            }
        }
        if (!scan.running && scan.total > 0 && scan.duplicates.isEmpty() && scan.unreadable.isEmpty()) item {
            SectionCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_all_good), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        scan.duplicates.forEachIndexed { index, group ->
            item { Text(stringResource(R.string.scan_dup_group, index + 1, group.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
            items(group, key = { "dup-$index-${it.id.value}" }) { video ->
                ScanRow(video, video.displayTitle(), video.id in chosen) {
                    chosen = if (video.id in chosen) chosen - video.id else chosen + video.id
                }
            }
        }
        if (scan.unreadable.isNotEmpty()) item { Text(stringResource(R.string.scan_unreadable_header), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        items(scan.unreadable, key = { "bad-${it.first.id.value}" }) { (video, reason) ->
            ScanRow(video, "${video.displayTitle()}\n$reason", video.id in chosen) {
                chosen = if (video.id in chosen) chosen - video.id else chosen + video.id
            }
        }
        if (chosen.isNotEmpty()) item {
            val picked = (scan.duplicates.flatten() + scan.unreadable.map { it.first }).filter { it.id in chosen }.distinctBy { it.id }
            Button(onClick = { confirm = picked }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.scan_delete_selected))
            }
            Text(stringResource(R.string.scan_delete_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable private fun ScanRow(video: VideoItem, label: String, selected: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                Icon(if (selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, stringResource(if (selected) R.string.action_deselect else R.string.action_select),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(fileSize(video.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
