@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.yingxia.player.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.R
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.core.displayTitle
import cn.yingxia.player.vault.*

@Composable fun VaultHost(vm: AppViewModel, onLockedToHome: () -> Unit) {
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
    var recoveryAction by remember(state.epoch) { mutableStateOf<RecoveryAction?>(null) }
    LaunchedEffect(state.recoveryEnabled) {
        if (state.recoveryEnabled && recoveryAction == RecoveryAction.ENROLL) recoveryAction = null
    }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    BackHandler { if (recoveryAction != null) recoveryAction = null else if (state.playing != null) vault.stopPlaying() else vault.close() }
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
    var cleaningDuplicates by remember(state.epoch) { mutableStateOf(false) }
    var restoreAfterPermission by remember(state.epoch) { mutableStateOf<Pair<String, Boolean>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = restoreAfterPermission; restoreAfterPermission = null
        if (granted && target != null) vault.restore(target.first, target.second)
        else vm.message(context.getString(R.string.vault_restore_perm_needed))
    }
    Surface(Modifier.fillMaxSize().testTag("vault-screen"), color = MaterialTheme.colorScheme.background) {
        val session = vault.playback
        if (state.unlocked && state.playing != null && session != null) {
            val playback by session.player.state.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize()) {
                PlayerPage(session.ui, playback, settings, onLockVault = {
                    vault.close()
                    onLockedToHome()
                })
                SnackbarHost(playbackNotice, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        } else Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { backDispatcher?.onBackPressed() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(if (recoveryAction != null) R.string.action_back else R.string.vault_exit)) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (state.unlocked) R.string.vault_subtitle_unlocked else R.string.vault_subtitle_locked), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.unlocked) FilledTonalIconButton(onClick = { vault.lock() }, modifier = Modifier.padding(end = 8.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.tertiary)) {
                    Icon(Icons.Outlined.Lock, stringResource(R.string.vault_lock_now))
                }
            }
            val action = recoveryAction
            if (action != null) {
                RecoveryForm(action, vault::recoveryPrompts, state.busy, Modifier.weight(1f), onCancel = { recoveryAction = null }) { password, prompts, answers ->
                    if (action != RecoveryAction.ENROLL) recoveryAction = null
                    vault.updateRecovery(action, password, prompts, answers)
                }
            } else if (!state.unlocked) {
                VaultPassword(state, vault::unlock, { recoveryAction = RecoveryAction.RESET }, Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        SectionCard(padding = 18.dp) {
                            if (state.damagedRecords > 0) Text(stringResource(R.string.vault_damaged_records, state.damagedRecords), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                            Text(stringResource(R.string.vault_intro_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { choose = true }, enabled = !state.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.vault_encrypt_local))
                            }
                        }
                    }
                    if (state.pendingRemovalCount > 1) item {
                        SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.vault_pending_batch_desc, state.pendingRemovalCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = vault::retryAllRemovals, enabled = !state.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                Text(stringResource(R.string.vault_action_retry_all_removals, state.pendingRemovalCount))
                            }
                        }
                    }
                    if (state.redundantCopies > 0) item {
                        SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.vault_duplicates_banner_desc, state.redundantCopies), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            OutlinedButton(onClick = { cleaningDuplicates = true }, enabled = !state.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                Text(stringResource(R.string.vault_action_clean_duplicates))
                            }
                        }
                    }
                    item {
                        TextButton(onClick = { recoveryAction = if (state.recoveryEnabled) RecoveryAction.DISABLE else RecoveryAction.ENROLL }, enabled = !state.busy) {
                            Text(stringResource(if (state.recoveryEnabled) R.string.vault_disable_recovery else R.string.vault_setup_recovery))
                        }
                    }
                    if (state.entries.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            IconBadge(Icons.Outlined.Lock, size = 72.dp, container = MaterialTheme.colorScheme.tertiaryContainer, tint = MaterialTheme.colorScheme.tertiary)
                            Text(stringResource(R.string.vault_empty_title), Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.vault_empty_desc), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(state.entries, key = { it.id }) { entry ->
                        SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Outlined.Lock, size = 44.dp, container = MaterialTheme.colorScheme.tertiaryContainer, tint = MaterialTheme.colorScheme.tertiary)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${durationText(entry.duration)} · ${fileSize(entry.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            Text(stringResource(if (entry.manageOriginalInGallery) R.string.vault_status_manage_in_gallery else if (!entry.sourceRemoved) R.string.vault_status_pending_removal else if (entry.restoredAt > 0) R.string.vault_status_restored else R.string.vault_status_encrypted),
                                color = if (!entry.sourceRemoved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton(onClick = { vault.play(entry) }, enabled = !state.busy, shape = CircleShape) {
                                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.vault_action_play))
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { restore = entry }, enabled = !state.busy) { Text(stringResource(R.string.vault_action_restore)) }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { deleting = entry }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.vault_action_delete_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (!entry.sourceRemoved && !entry.manageOriginalInGallery) OutlinedButton(onClick = { vault.retryRemoval(entry.id) }, enabled = !state.busy, shape = CircleShape, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.vault_action_retry_removal)) }
                        }
                    }
                    item { Text(stringResource(R.string.vault_uninstall_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }
            if (state.busy) Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.ifBlank { stringResource(R.string.vault_busy_default) }, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.vault_busy_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    state.message?.takeIf { state.playing == null }?.let { text ->
        AlertDialog(onDismissRequest = vault::dismissMessage, title = { Text(stringResource(R.string.vault_notice_title)) }, text = { Text(text) },
            confirmButton = { TextButton(onClick = vault::dismissMessage) { Text(stringResource(R.string.action_got_it)) } })
    }
    if (state.unlocked && state.importItems.isNotEmpty() && !state.busy) {
        val shared = state.importItems.all { it.source == "share" }
        val shareCanRemove = shared && state.importItems.any { it.id.value.startsWith("ms:") }
        AlertDialog(
            onDismissRequest = vault::dismissImport,
            title = { Text(stringResource(R.string.vault_confirm_encrypt_title, state.importItems.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            when {
                                shareCanRemove -> R.string.vault_confirm_share_move_desc
                                shared -> R.string.vault_confirm_share_desc
                                else -> R.string.vault_confirm_encrypt_desc
                            },
                        ),
                    )
                    if (shareCanRemove) {
                        Button(
                            onClick = { vault.importConfirmed(removeOriginals = true) },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            Text(stringResource(R.string.vault_confirm_encrypt_button))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vault.importConfirmed() }) {
                    Text(stringResource(if (shared) R.string.vault_confirm_share_button else R.string.vault_confirm_encrypt_button))
                }
            },
            dismissButton = { TextButton(onClick = vault::dismissImport) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (cleaningDuplicates && state.unlocked && state.redundantCopies > 0) {
        AlertDialog(
            onDismissRequest = { cleaningDuplicates = false },
            title = { Text(stringResource(R.string.vault_dedup_dialog_title, state.redundantCopies)) },
            text = { Text(stringResource(R.string.vault_dedup_dialog_desc)) },
            confirmButton = {
                TextButton(onClick = { cleaningDuplicates = false; vault.deduplicateEntries() }) {
                    Text(stringResource(R.string.vault_dedup_dialog_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { cleaningDuplicates = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    restore?.let { entry ->
        var removeVaultCopy by remember(entry.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { restore = null },
            title = { Text(stringResource(R.string.vault_restore_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.vault_restore_dialog_desc))
                    if (entry.restoredAt > 0) {
                        Text(
                            stringResource(R.string.vault_restore_already_restored_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { removeVaultCopy = !removeVaultCopy }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(removeVaultCopy, { removeVaultCopy = it })
                        Text(stringResource(R.string.vault_restore_also_delete_vault), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleteCopy = removeVaultCopy
                    restore = null
                    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        restoreAfterPermission = entry.id to deleteCopy
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else vault.restore(entry.id, deleteCopy)
                }) { Text(stringResource(R.string.vault_restore_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { restore = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.vault_delete_dialog_title)) },
            text = { Text(stringResource(R.string.vault_delete_dialog_desc, entry.name)) },
            confirmButton = { TextButton(onClick = { deleting = null; vault.deleteEncrypted(entry.id) }) { Text(stringResource(R.string.vault_delete_dialog_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_keep)) } })
    }
    if (choose && state.unlocked) {
        val videos by vm.videos.collectAsStateWithLifecycle()
        VaultPicker(videos.filter { it.accessible }, state.entries, { choose = false }) { selected -> choose = false; vault.requestImport(selected) }
    }
}

@Composable private fun VaultPassword(state: VaultUiState, unlock: (CharArray) -> Unit, recover: () -> Unit, modifier: Modifier = Modifier) {
    var password by remember(state.epoch) { mutableStateOf("") }
    var confirmation by remember(state.epoch) { mutableStateOf("") }
    var acknowledged by remember(state.epoch) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(padding = 22.dp, modifier = Modifier.fillMaxWidth()) {
            IconBadge(Icons.Outlined.Lock, size = 56.dp, container = MaterialTheme.colorScheme.tertiaryContainer, tint = MaterialTheme.colorScheme.tertiary)
            Text(stringResource(if (state.configured) R.string.vault_unlock_title else R.string.vault_create_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Text(stringResource(if (state.configured) R.string.vault_unlock_desc else R.string.vault_create_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
            OutlinedTextField(password, { password = it.take(128) }, enabled = !state.busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("vault-password"), label = { Text(stringResource(R.string.vault_password_label)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            if (!state.configured) {
                OutlinedTextField(confirmation, { confirmation = it.take(128) }, enabled = !state.busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("vault-password-confirm"), label = { Text(stringResource(R.string.vault_password_confirm_label)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("vault-acknowledge").clickable { acknowledged = !acknowledged }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, { acknowledged = it })
                    Text(stringResource(R.string.vault_acknowledge_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = { val chars = password.toCharArray(); password = ""; confirmation = ""; unlock(chars) },
                enabled = !state.busy && password.length >= 8 && (state.configured || password == confirmation && acknowledged),
                shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 48.dp).testTag("vault-unlock")) {
                Text(stringResource(if (state.configured) R.string.vault_unlock_button else R.string.vault_create_button), fontWeight = FontWeight.SemiBold)
            }
            if (state.recoveryEnabled) TextButton(onClick = recover, enabled = !state.busy) { Text(stringResource(R.string.vault_forgot_password)) }
            else if (state.configured) Text(stringResource(R.string.vault_recovery_not_enabled), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable private fun VaultPicker(
    videos: List<VideoItem>,
    vaultEntries: List<VaultEntry>,
    dismiss: () -> Unit,
    selected: (List<VideoItem>) -> Unit,
) {
    var ids by remember { mutableStateOf(setOf<String>()) }
    val inVaultIds = remember(videos, vaultEntries) {
        videos.filter { VaultRules.matchesVaultEntry(vaultEntries, it) }.map { it.id.value }.toSet()
    }
    val newIds = remember(videos, inVaultIds) {
        videos.map { it.id.value }.filterNot { it in inVaultIds }.toSet()
    }
    val allIds = remember(videos) { videos.map { it.id.value }.toSet() }
    val allSelected = allIds.isNotEmpty() && ids.containsAll(allIds)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.vault_picker_title)) },
        text = {
            Column {
                if (videos.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (newIds.isNotEmpty() && newIds.size < allIds.size) {
                            TextButton(onClick = { ids = newIds }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(stringResource(R.string.vault_picker_select_unencrypted))
                            }
                        }
                        TextButton(
                            onClick = { ids = if (allSelected) emptySet() else allIds },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all))
                        }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (videos.isEmpty()) item { Text(stringResource(R.string.vault_picker_empty)) }
                    items(videos, key = { it.id.value }) { video ->
                        val alreadyInVault = video.id.value in inVaultIds
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { ids = if (video.id.value in ids) ids - video.id.value else ids + video.id.value }
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(video.id.value in ids, null)
                            Box(Modifier.padding(start = 6.dp).size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(6.dp))) {
                                Thumbnail(video)
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(video.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${video.folder} · ${durationText(video.durationMs)} · ${fileSize(video.sizeBytes)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (alreadyInVault) {
                                    Text(
                                        stringResource(R.string.vault_picker_in_vault_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected(videos.filter { it.id.value in ids }) }, enabled = ids.isNotEmpty()) {
                Text(stringResource(R.string.vault_picker_confirm, ids.size))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
