package cn.yingxia.player.vault

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import cn.yingxia.player.core.LibraryAccess
import cn.yingxia.player.core.PlayerCommand
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.data.DeleteOutcome
import cn.yingxia.player.data.LocalFileManager
import cn.yingxia.player.data.LocalVideoRepository
import cn.yingxia.player.data.canonicalVideoUri
import cn.yingxia.player.data.libraryAccess
import cn.yingxia.player.playback.Media3Controller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

internal enum class RecoveryAction { ENROLL, DISABLE, RESET }

data class VaultUiState(
    val recoveryEnabled: Boolean = false,
    val visible: Boolean = false, val configured: Boolean = false, val unlocked: Boolean = false,
    val busy: Boolean = false, val progress: String = "", val message: String? = null,
    val entries: List<VaultEntry> = emptyList(), val importItems: List<VideoItem> = emptyList(),
    val consent: DeleteOutcome.Consent? = null, val consentToken: Long = 0,
    val playing: VaultEntry? = null, val epoch: Long = 0, val damagedRecords: Int = 0,
    val pendingRemovalCount: Int = 0, val redundantCopies: Int = 0,
)

private data class RemovalTarget(
    val entryId: String,
    val video: VideoItem,
    val removalUri: String,
    val markEntrySourceRemoved: Boolean,
)

private data class BatchImportStats(
    val newlyEncrypted: Int = 0,
    val reusedExisting: Int = 0,
    val shareUnremovable: Int = 0,
)

/** One foreground operation at a time; backgrounding cancels IO and destroys the unlocked session. */
class VaultCoordinator(
    private val context: Context, private val repository: LocalVideoRepository, private val files: LocalFileManager,
    private val ordinaryPlayer: Media3Controller, private val scope: CoroutineScope,
    private val settings: cn.yingxia.player.data.SettingsRepository,
) {
    val store = VaultStore(context)
    val state = MutableStateFlow(VaultUiState(configured = store.configured, recoveryEnabled = store.recovery.enabled))
    private var job: Job? = null
    private var previousJob: Job? = null
    private val epoch = AtomicLong(0)
    private var serial = 0L
    private var pendingRemovals = emptyList<RemovalTarget>()
    private var batchStats: BatchImportStats? = null
    private var consentTargets = emptyList<RemovalTarget>()
    private var launchedConsent: DeleteOutcome.Consent? = null
    private var consentAnswer: Boolean? = null
    var releasePlayer: (() -> Unit)? = null
    var playback: VaultPlaybackSession? = null; private set

    fun show(items: List<VideoItem> = emptyList()) {
        if (state.value.busy) return
        val uniqueItems = items.filter { it.accessible }.distinctBy { it.id }
        state.update {
            it.copy(
                visible = true,
                configured = store.configured,
                recoveryEnabled = store.recovery.enabled,
                importItems = uniqueItems,
                message = null,
            )
        }
        scope.launch { ordinaryPlayer.execute(PlayerCommand.Close) }
    }

    fun close() {
        launchedConsent = null
        consentTargets = emptyList()
        consentAnswer = null
        pendingRemovals = emptyList()
        batchStats = null
        lock()
        state.update { it.copy(visible = false) }
    }

    fun lock() {
        epoch.incrementAndGet()
        job?.cancel(); previousJob = job ?: previousJob; job = null
        try { releasePlayer?.invoke() } finally {
            releasePlayer = null; playback = null
            store.lock()
            if (launchedConsent == null) {
                pendingRemovals = emptyList()
                batchStats = null
            }
            state.value = VaultUiState(
                visible = state.value.visible,
                configured = store.configured,
                recoveryEnabled = store.recovery.enabled,
                epoch = epoch.get(),
            )
        }
    }

    fun dismissMessage() { state.update { it.copy(message = null) } }
    fun dismissImport() { state.update { it.copy(importItems = emptyList()) } }
    fun requestImport(items: List<VideoItem>) {
        if (!state.value.busy) state.update { it.copy(importItems = items.filter { item -> item.accessible }.distinctBy { item -> item.id }) }
    }

    private fun str(id: Int, vararg args: Any) = AppLocale.str(context, id, *args)

    fun unlock(password: CharArray) {
        if (state.value.busy) { password.fill('\u0000'); return }
        run(str(R.string.vault_prog_unlocking)) {
            try {
                withContext(Dispatchers.IO) {
                    if (store.configured) store.unlock(password) else store.setup(password)
                }
                state.update { it.copy(configured = true, unlocked = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                store.lock()
                state.update { it.copy(unlocked = false) }
                error(str(R.string.vault_error_wrong_password))
            } finally { password.fill('\u0000') }
            refresh()
            if (consentAnswer != null) finishConsent()
            reconcileRemovedSources()
        }
    }

    internal suspend fun recoveryPrompts(): List<String> = withContext(Dispatchers.IO) {
        store.recovery.enrolledPrompts()
    }

    internal fun updateRecovery(action: RecoveryAction, password: CharArray, prompts: List<String>, answers: List<CharArray>) {
        fun clear() { password.fill('\u0000'); answers.forEach { it.fill('\u0000') } }
        val lockedAction = action == RecoveryAction.RESET
        if (!state.value.visible || !state.value.configured || state.value.busy || job?.isActive == true ||
            state.value.unlocked == lockedAction) { clear(); return }
        run(str(R.string.vault_prog_verifying_local)) {
            withContext(Dispatchers.IO) {
                val worker = currentCoroutineContext()
                val beforeCommit: () -> Unit = { worker.ensureActive(); if (!lockedAction) store.requireSession().requireOpen() }
                when (action) {
                    RecoveryAction.ENROLL -> store.recovery.enroll(password, prompts, answers, beforeCommit)
                    RecoveryAction.DISABLE -> store.recovery.disable(password, beforeCommit)
                    RecoveryAction.RESET -> store.recovery.reset(answers, password, beforeCommit)
                }
            }
            val message = when (action) {
                RecoveryAction.ENROLL -> str(R.string.vault_msg_recovery_enabled)
                RecoveryAction.DISABLE -> str(R.string.vault_msg_recovery_disabled)
                RecoveryAction.RESET -> str(R.string.vault_msg_password_reset)
            }
            state.update { it.copy(recoveryEnabled = store.recovery.enabled, message = message) }
        }
        job?.invokeOnCompletion { clear() }
    }

    fun importConfirmed() = importConfirmed(removeOriginals = null)

    fun importConfirmed(removeOriginals: Boolean?) {
        if (!state.value.unlocked || state.value.busy) return
        val rawItems = state.value.importItems
        if (rawItems.isEmpty()) return
        state.update { it.copy(importItems = emptyList()) }
        val keepOriginals = if (removeOriginals != null) !removeOriginals else rawItems.all { it.source == "share" }
        run(str(R.string.vault_prog_preparing_encrypt)) {
            ordinaryPlayer.execute(PlayerCommand.Close)
            val canonicalMap = withContext(Dispatchers.IO) {
                rawItems.associate { item -> item.uri to canonicalVideoUri(context, Uri.parse(item.uri))?.toString() }
            }
            val items = VaultRules.deduplicateBatch(rawItems) { canonicalMap[it.uri] }
            val known = state.value.entries.toMutableList()
            val targets = mutableListOf<RemovalTarget>()
            var newlyEncrypted = 0
            var reusedExisting = 0
            var shareUnremovable = 0
            var processed = 0
            try {
                items.forEachIndexed { index, video ->
                    val prefix = if (items.size > 1) "${index + 1}/${items.size} · " else ""
                    val canonical = canonicalMap[video.uri]
                    val existing = findReusableEntry(known, video, canonical, prefix)
                    if (existing != null) {
                        reusedExisting++
                        if (!keepOriginals) {
                            val resolvedUri = canonical ?: video.uri
                            val canRemove = video.source != "share" || isMediaStoreVideoUri(resolvedUri)
                            if (!canRemove) {
                                shareUnremovable++
                            } else {
                                var targetEntry = existing
                                val adoptedSharedOriginal = existing.manageOriginalInGallery && !existing.sourceRemoved
                                if (adoptedSharedOriginal) {
                                    targetEntry = existing.copy(
                                        sourceId = video.id.value,
                                        sourceUri = video.uri,
                                        removalUri = resolvedUri,
                                        manageOriginalInGallery = false,
                                    )
                                    withContext(Dispatchers.IO) { store.save(targetEntry) }
                                    val idx = known.indexOfFirst { it.id == targetEntry.id }
                                    if (idx >= 0) known[idx] = targetEntry
                                }
                                val sameOriginal = adoptedSharedOriginal ||
                                    targetEntry.sourceId == video.id.value ||
                                    targetEntry.sourceUri == video.uri ||
                                    (targetEntry.removalUri.isNotBlank() && targetEntry.removalUri == resolvedUri)
                                targets += RemovalTarget(
                                    entryId = targetEntry.id,
                                    video = video,
                                    removalUri = resolvedUri,
                                    markEntrySourceRemoved = !targetEntry.sourceRemoved && sameOriginal,
                                )
                            }
                        }
                    } else {
                        val entry = store.encrypt(video) { progress("$prefix$it") }
                        newlyEncrypted++
                        known.add(0, entry)
                        if (!keepOriginals) {
                            if (entry.manageOriginalInGallery) {
                                shareUnremovable++
                            } else {
                                targets += RemovalTarget(
                                    entryId = entry.id,
                                    video = video,
                                    removalUri = entry.removalUri.ifBlank { canonical ?: video.uri },
                                    markEntrySourceRemoved = true,
                                )
                            }
                        }
                    }
                    processed++
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (currentCoroutineContext().isActive) refresh()
                if (keepOriginals) error(str(R.string.vault_msg_share_encrypt_partial, processed, items.size))
                else throw e
            }
            refresh()
            if (keepOriginals) {
                val message = when {
                    newlyEncrypted == 0 && reusedExisting > 0 -> str(R.string.vault_msg_share_all_reused, reusedExisting)
                    reusedExisting > 0 -> str(R.string.vault_msg_share_mixed_reused, newlyEncrypted, reusedExisting)
                    else -> str(R.string.vault_msg_share_encrypt_done, newlyEncrypted)
                }
                state.update { it.copy(message = message) }
                return@run
            }
            batchStats = BatchImportStats(newlyEncrypted, reusedExisting, shareUnremovable)
            pendingRemovals = targets
            continueRemovals()
        }
    }

    private suspend fun findReusableEntry(
        known: List<VaultEntry>,
        video: VideoItem,
        canonicalUri: String?,
        progressPrefix: String,
    ): VaultEntry? {
        VaultRules.findDuplicateByIdentity(known, video, canonicalUri)?.let { candidate ->
            val verified = try {
                store.verifySourceMatches(candidate, video.uri)
                true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            if (verified) return candidate
        }
        val mayMatchBySize = known.any { entry ->
            entry.bytes > 0 && entry.digest.isNotBlank() && (video.sizeBytes <= 0 || entry.bytes == video.sizeBytes)
        }
        if (!mayMatchBySize) return null
        progress("$progressPrefix${str(R.string.vault_prog_checking_duplicate)}")
        val (actualSize, digestHex) = store.digestOf(video.uri)
        val candidate = VaultRules.findDuplicateByContent(known, video, actualSize, digestHex) ?: return null
        val payloadOk = try {
            store.verifyPayload(candidate)
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        return candidate.takeIf { payloadOk }
    }

    private fun isMediaStoreVideoUri(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        val parts = uri.pathSegments
        return uri.authority == MediaStore.AUTHORITY && parts.size == 4 &&
            parts[1] == "video" && parts[2] == "media" && parts.last().toLongOrNull() != null
    }

    fun retryRemoval(id: String) {
        if (!state.value.unlocked || state.value.busy) return
        run(str(R.string.vault_prog_verify_before_remove)) {
            val entry = withContext(Dispatchers.IO) { store.load(id) }
            check(!entry.manageOriginalInGallery) { str(R.string.vault_error_manage_in_gallery) }
            val removalUri = withContext(Dispatchers.IO) {
                canonicalVideoUri(context, Uri.parse(entry.sourceUri))?.toString()
                    ?: entry.removalUri.ifBlank { entry.sourceUri }
            }
            batchStats = null
            pendingRemovals = listOf(RemovalTarget(entry.id, entry.original(), removalUri, markEntrySourceRemoved = true))
            continueRemovals()
        }
    }

    fun retryAllRemovals() {
        if (!state.value.unlocked || state.value.busy) return
        run(str(R.string.vault_prog_verify_before_remove)) {
            reconcileRemovedSources()
            val pending = state.value.entries.filter { !it.sourceRemoved && !it.manageOriginalInGallery }
            if (pending.isEmpty()) {
                state.update { it.copy(message = str(R.string.vault_msg_removal_confirmed)) }
                return@run
            }
            val targets = withContext(Dispatchers.IO) {
                pending.map { entry ->
                    val removalUri = canonicalVideoUri(context, Uri.parse(entry.sourceUri))?.toString()
                        ?: entry.removalUri.ifBlank { entry.sourceUri }
                    RemovalTarget(entry.id, entry.original(), removalUri, markEntrySourceRemoved = true)
                }
            }
            batchStats = null
            pendingRemovals = targets
            continueRemovals()
        }
    }

    private suspend fun continueRemovals() {
        var deletedDirectly = false
        while (pendingRemovals.isNotEmpty()) {
            val nonBatchIndex = pendingRemovals.indexOfFirst { files.batchMediaDeleteUri(it.video) == null }
            if (nonBatchIndex >= 0) {
                val target = pendingRemovals[nonBatchIndex]
                val entry = withContext(Dispatchers.IO) { store.load(target.entryId) }
                progress(str(R.string.vault_prog_verify_request_remove))
                store.verifySourceMatches(entry, target.video.uri)
                val effectiveUri = withContext(Dispatchers.IO) {
                    canonicalVideoUri(context, Uri.parse(target.video.uri))?.toString() ?: target.video.uri
                }
                if (target.markEntrySourceRemoved && entry.removalUri != effectiveUri) {
                    withContext(Dispatchers.IO) { store.save(entry.copy(removalUri = effectiveUri)) }
                }
                val resolvedTarget = target.copy(removalUri = effectiveUri)
                when (val result = files.delete(target.video)) {
                    DeleteOutcome.Deleted -> {
                        finishTargetRemoval(resolvedTarget, refreshNow = false)
                        deletedDirectly = true
                        pendingRemovals = pendingRemovals.filterIndexed { index, _ -> index != nonBatchIndex }
                    }
                    is DeleteOutcome.Consent -> {
                        if (deletedDirectly) { refresh(); repository.refresh(); deletedDirectly = false }
                        pendingRemovals = pendingRemovals.filterIndexed { index, _ -> index != nonBatchIndex }
                        consentTargets = listOf(resolvedTarget)
                        launchedConsent = result
                        consentAnswer = null
                        state.update {
                            it.copy(
                                consent = result,
                                consentToken = ++serial,
                                busy = true,
                                progress = str(R.string.vault_prog_confirm_removal),
                            )
                        }
                        return
                    }
                }
                continue
            }

            if (deletedDirectly) { refresh(); repository.refresh(); deletedDirectly = false }
            val chunk = pendingRemovals.take(VAULT_DELETE_BATCH_LIMIT)
            val verified = mutableListOf<RemovalTarget>()
            val deleteUris = mutableListOf<Uri>()
            chunk.forEachIndexed { index, target ->
                progress(
                    if (chunk.size > 1) "${index + 1}/${chunk.size} · ${str(R.string.vault_prog_verify_request_remove)}"
                    else str(R.string.vault_prog_verify_request_remove),
                )
                val entry = withContext(Dispatchers.IO) { store.load(target.entryId) }
                store.verifySourceMatches(entry, target.video.uri)
                val mediaUri = files.batchMediaDeleteUri(target.video)
                    ?: error(str(R.string.vault_error_operation_incomplete))
                val mediaUriStr = mediaUri.toString()
                if (target.markEntrySourceRemoved && entry.removalUri != mediaUriStr) {
                    withContext(Dispatchers.IO) { store.save(entry.copy(removalUri = mediaUriStr)) }
                }
                verified += target.copy(removalUri = mediaUriStr)
                deleteUris += mediaUri
            }
            val consent = files.requestBatchDelete(deleteUris)
            pendingRemovals = pendingRemovals.drop(chunk.size)
            consentTargets = verified
            launchedConsent = consent
            consentAnswer = null
            state.update {
                it.copy(
                    consent = consent,
                    consentToken = ++serial,
                    busy = true,
                    progress = if (verified.size > 1) str(R.string.vault_prog_confirm_removal_batch, verified.size)
                    else str(R.string.vault_prog_confirm_removal),
                )
            }
            return
        }
        if (deletedDirectly) { refresh(); repository.refresh() }
        finishRemovalSummary()
    }

    fun consentLaunched() { state.update { it.copy(consent = null) } }

    fun consentResult(approved: Boolean) {
        consentAnswer = approved
        scope.launch {
            job?.join()
            if (consentAnswer == null) return@launch
            if (state.value.unlocked) run(str(R.string.vault_prog_checking_removal), allowWaiting = true) { finishConsent() }
            else state.update { it.copy(message = str(R.string.vault_msg_reunlock_to_check)) }
        }
    }

    private suspend fun finishConsent() {
        val approved = consentAnswer ?: return
        val targets = consentTargets
        val consent = launchedConsent ?: return
        consentAnswer = null; consentTargets = emptyList(); launchedConsent = null
        if (targets.isEmpty()) return
        if (!approved) {
            pendingRemovals = emptyList()
            batchStats = null
            state.update { it.copy(message = str(R.string.vault_msg_removal_cancelled)) }
            refresh()
            return
        }
        if (consent.retryDelete) {
            val target = targets.single()
            val entry = withContext(Dispatchers.IO) { store.load(target.entryId) }
            store.verifySourceMatches(entry, target.video.uri)
            check(files.delete(target.video, afterGrant = true) == DeleteOutcome.Deleted)
            finishTargetRemoval(target, refreshNow = false)
        } else {
            var failed = 0
            targets.forEachIndexed { index, target ->
                if (targets.size > 1) progress("${index + 1}/${targets.size} · ${str(R.string.vault_prog_checking_removal)}")
                val verified = try {
                    files.verifySystemDeletion(target.removalUri)
                    true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
                if (verified) finishTargetRemoval(target, refreshNow = false)
                else failed++
            }
            if (failed > 0) {
                refresh(); repository.refresh()
                pendingRemovals = emptyList()
                batchStats = null
                if (failed == targets.size) error(str(R.string.file_error_system_delete_unconfirmed))
                state.update { it.copy(message = str(R.string.vault_msg_removal_partial, targets.size - failed, targets.size)) }
                return
            }
        }
        refresh(); repository.refresh()
        if (pendingRemovals.isNotEmpty()) continueRemovals()
        else finishRemovalSummary()
    }

    private fun finishRemovalSummary() {
        val stats = batchStats
        batchStats = null
        val message = when {
            stats == null -> str(R.string.vault_msg_removal_confirmed)
            stats.shareUnremovable > 0 -> str(R.string.vault_msg_encrypt_done_partial_gallery, stats.shareUnremovable)
            stats.reusedExisting > 0 && stats.newlyEncrypted == 0 -> str(R.string.vault_msg_encrypt_done_reused_only, stats.reusedExisting)
            stats.reusedExisting > 0 -> str(R.string.vault_msg_encrypt_done_mixed, stats.newlyEncrypted, stats.reusedExisting)
            else -> str(R.string.vault_msg_encrypt_done)
        }
        state.update { it.copy(message = message) }
    }

    private suspend fun finishTargetRemoval(target: RemovalTarget, refreshNow: Boolean = true) {
        val extraUris = setOfNotNull(target.removalUri.takeIf { it.isNotBlank() })
        repository.forgetEncryptedSource(target.video, extraUris)
        if (target.markEntrySourceRemoved) {
            withContext(Dispatchers.IO) {
                val entry = store.load(target.entryId)
                if (!entry.sourceRemoved) store.save(entry.copy(sourceRemoved = true))
            }
        }
        if (refreshNow) { refresh(); repository.refresh() }
    }

    private suspend fun finishRemoval(entry: VaultEntry, refreshNow: Boolean = true) {
        val extraUris = setOfNotNull(entry.removalUri.takeIf { it.isNotBlank() })
        repository.forgetEncryptedSource(entry.original(), extraUris)
        withContext(Dispatchers.IO) { store.save(entry.copy(sourceRemoved = true)) }
        if (refreshNow) { refresh(); repository.refresh() }
    }

    /** A process may die after Android deletes the source but before our result callback. */
    private suspend fun reconcileRemovedSources() {
        if (libraryAccess(context) != LibraryAccess.FULL) return // Missing rows under partial access are ambiguous.
        var changed = false
        state.value.entries.filter { !it.sourceRemoved && it.removalUri.isNotBlank() && Uri.parse(it.removalUri).authority == MediaStore.AUTHORITY }.forEach { entry ->
            val removed = try { files.verifySystemDeletion(entry.removalUri); true }
            catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (removed) {
                finishRemoval(entry, refreshNow = false)
                changed = true
            }
        }
        if (changed) { refresh(); repository.refresh() }
    }

    fun deduplicateEntries() {
        if (!state.value.unlocked || state.value.busy) return
        stopPlaying()
        run(str(R.string.vault_prog_cleaning_duplicates)) {
            val groups = VaultRules.duplicateContentGroups(state.value.entries, store::hasPlaybackRecord)
            var removed = 0
            groups.forEachIndexed { index, group ->
                progress("${index + 1}/${groups.size} · ${str(R.string.vault_prog_cleaning_duplicates)}")
                val keeper = group.firstOrNull { candidate ->
                    try {
                        store.verifyPayload(candidate)
                        true
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                } ?: return@forEachIndexed
                group.filter { it.id != keeper.id }.forEach { duplicate ->
                    withContext(Dispatchers.IO) { store.remove(duplicate.id) }
                    removed++
                }
            }
            refresh()
            val message = if (removed > 0) str(R.string.vault_msg_duplicates_cleaned, removed)
            else str(R.string.vault_msg_no_duplicates_cleaned)
            state.update { it.copy(message = message) }
        }
    }

    fun deleteEncrypted(id: String) {
        if (!state.value.unlocked) return
        stopPlaying()
        run(str(R.string.vault_prog_removing_copy)) {
            withContext(Dispatchers.IO) { store.remove(id) }
            refresh(); state.update { it.copy(message = str(R.string.vault_msg_copy_removed)) }
        }
    }

    fun restore(id: String, removeVaultCopy: Boolean = false) {
        if (!state.value.unlocked) return
        stopPlaying()
        run(str(R.string.vault_prog_preparing_restore)) {
            val entry = withContext(Dispatchers.IO) { store.load(id) }
            val result = store.restore(entry, ::progress)
            val message = if (removeVaultCopy) {
                withContext(Dispatchers.IO) { store.remove(id) }
                str(R.string.vault_store_restore_and_removed_done)
            } else result
            refresh(); repository.refresh()
            state.update { it.copy(message = message) }
        }
    }

    fun play(entry: VaultEntry) {
        if (state.value.busy || !state.value.unlocked) return
        run(str(R.string.vault_prog_preparing_playback)) {
            stopPlaying()
            val records = VaultVideoRepository(store)
            records.initialize(state.value.entries)
            val session = VaultPlaybackSession(context, store, records, settings, ::stopPlaying) { text ->
                if (state.value.unlocked) state.update { it.copy(message = text) }
            }
            playback = session
            releasePlayer = session::close
            try { session.open(entry.id); state.update { it.copy(playing = entry) } }
            catch (e: Exception) { stopPlaying(); throw e }
        }
    }

    fun stopPlaying() {
        val names = playback?.repository?.videos?.value.orEmpty().associate { it.id.value to (it.displayName ?: it.name) }
        try { releasePlayer?.invoke() } finally {
            releasePlayer = null; playback = null
            state.update { current -> current.copy(playing = null, entries = current.entries.map { entry -> names[entry.id]?.let { entry.copy(name = it) } ?: entry }) }
        }
    }

    private suspend fun refresh() {
        val inventory = withContext(Dispatchers.IO) { store.inventory().let { it.copy(entries = it.entries.map(store::recoverRestore)) } }
        val pendingCount = inventory.entries.count { !it.sourceRemoved && !it.manageOriginalInGallery }
        val redundant = VaultRules.redundantCopyCount(inventory.entries)
        state.update {
            it.copy(
                entries = inventory.entries,
                damagedRecords = inventory.damagedRecords,
                pendingRemovalCount = pendingCount,
                redundantCopies = redundant,
            )
        }
    }

    private fun progress(text: String) { if (state.value.unlocked) state.update { it.copy(progress = text) } }

    private fun run(label: String, allowWaiting: Boolean = false, action: suspend () -> Unit) {
        if (job?.isActive == true || state.value.busy && !allowWaiting) return
        val token = epoch.get()
        state.update { it.copy(busy = true, message = null, progress = label) }
        job = scope.launch {
            try {
                previousJob?.join(); previousJob = null
                ensureActive(); action()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                pendingRemovals = emptyList()
                batchStats = null
                if (token == epoch.get()) state.update { it.copy(message = e.message ?: str(R.string.vault_error_operation_incomplete)) }
            } finally {
                if (token == epoch.get()) state.update { it.copy(busy = launchedConsent != null && consentAnswer == null, progress = "") }
                else store.lock() // A slow password derivation must not leave a session unlocked after backgrounding.
            }
        }
    }
}
