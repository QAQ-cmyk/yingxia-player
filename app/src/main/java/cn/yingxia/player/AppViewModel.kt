package cn.yingxia.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.yingxia.player.core.*
import cn.yingxia.player.data.DeleteOutcome
import cn.yingxia.player.data.InvalidBackupException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

class AppViewModel(val graph: AppGraph) : ViewModel() {
    private fun str(id: Int, vararg args: Any) = AppLocale.str(graph.appContext, id, *args)
    val videos = graph.repository.videos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hidden = graph.repository.hiddenVideos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = graph.repository.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val messages = MutableSharedFlow<UserNotice>(extraBufferCapacity = 8)
    internal val share = MutableStateFlow<GalleryShare?>(null)
    private var shareJob: Job? = null

    internal fun receiveShare(incoming: GalleryShare) {
        dismissShare()
        if (graph.vault.state.value.busy || fileOperation.value.busy) {
            share.value = GalleryShare(error = str(R.string.share_error_busy))
            return
        }
        if (graph.vault.state.value.visible) graph.vault.close()
        if (graph.player.state.value.isPlaying) command(PlayerCommand.Pause)
        share.value = incoming
    }

    internal fun pauseSharePreparation() {
        shareJob?.cancel()
        share.value = share.value?.copy(loading = false)
    }
    internal fun dismissShare() {
        shareJob?.cancel()
        share.value = null
    }
    internal fun useShare(forVault: Boolean) {
        val pending = share.value ?: return
        if (pending.loading || pending.uris.isEmpty() || !forVault && pending.uris.size != 1) return
        share.value = pending.copy(loading = true, error = null)
        shareJob = viewModelScope.launch {
            try {
                val items = pending.uris.map { graph.repository.sharedVideo(it) }.distinctBy { it.id }
                ensureActive()
                if (forVault) graph.vault.show(items)
                else graph.player.execute(PlayerCommand.OpenShared(items.single())).getOrThrow()
                share.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is PlayerFailure) e.message else str(R.string.share_error_unreadable)
                share.value = GalleryShare(error = message)
            }
        }
    }
    val folders = graph.settings.storageFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folderKeys = combine(folders, videos) { folders, _ -> graph.files.folderKeys(folders) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val fileOperation = MutableStateFlow(FileOperationState())
    val libraryScan = MutableStateFlow(LibraryScanState())
    private var scanJob: Job? = null
    private var fileItems = emptyList<VideoItem>()
    private var fileTarget: StorageFolder? = null
    private var fileIndex = 0
    private var fileSucceeded = 0
    private var copiedUri: String? = null
    private var consentSerial = 0L

    fun createFolder(parent: String, name: String, onCreated: (StorageFolder) -> Unit) {
        viewModelScope.launch {
            runCatching { graph.files.createFolder(parent, name).also { graph.settings.addFolder(it) } }
                .onSuccess { notify(str(R.string.file_op_folder_created)); onCreated(it) }
                .onFailure { notify(it.message ?: str(R.string.file_op_folder_create_failed)) }
        }
    }
    fun manageFiles(items: List<VideoItem>, target: StorageFolder? = null) {
        if (fileOperation.value.busy || items.isEmpty()) return
        fileItems = items.distinctBy { it.id }; fileTarget = target; fileIndex = 0; fileSucceeded = 0; copiedUri = null
        fileOperation.value = FileOperationState(busy = true, message = str(R.string.file_op_preparing))
        viewModelScope.launch {
            if (graph.player.state.value.video?.id in fileItems.map { it.id }) graph.player.execute(PlayerCommand.Close)
            continueFiles()
        }
    }
    private suspend fun continueFiles() {
        while (fileIndex < fileItems.size) {
            val video = fileItems[fileIndex]
            fileOperation.value = FileOperationState(true, str(if (fileTarget == null) R.string.file_op_deleting else R.string.file_op_copying, fileIndex + 1, fileItems.size, video.name))
            try {
                if (fileTarget != null && copiedUri == null) copiedUri = graph.files.copyVerified(video, fileTarget!!)
                when (val result = graph.files.delete(video)) {
                    DeleteOutcome.Deleted -> finishFile()
                    is DeleteOutcome.Consent -> {
                        fileOperation.value = FileOperationState(true, str(if (fileTarget != null) R.string.file_op_consent_move else R.string.file_op_consent_delete), result, ++consentSerial)
                        return
                    }
                }
            } catch (e: Exception) {
                val reason = e.message ?: str(R.string.file_op_failed_fallback)
                finishFiles(str(if (copiedUri != null) R.string.file_op_failed_copy_kept else R.string.file_op_failed_single, video.name, reason))
                return
            }
        }
        finishFiles()
    }
    private suspend fun finishFile() {
        val video = fileItems[fileIndex]
        if (copiedUri != null) graph.repository.relocated(video, copiedUri!!, fileTarget!!.name)
        else graph.database.videos().unavailable(video.id.value)
        fileSucceeded++; fileIndex++; copiedUri = null
    }
    fun fileConsentResult(approved: Boolean) {
        val consent = fileOperation.value.consent ?: return
        fileOperation.value = fileOperation.value.copy(consent = null)
        viewModelScope.launch {
            if (!approved) { finishFiles(str(if (copiedUri != null) R.string.file_op_cancelled_move else R.string.file_op_cancelled_delete)); return@launch }
            try {
                val video = fileItems[fileIndex]
                if (consent.retryDelete) check(graph.files.delete(video, true) == DeleteOutcome.Deleted)
                else graph.files.verifySystemDeletion(consent.mediaUri)
                finishFile(); continueFiles()
            } catch (e: Exception) { finishFiles(e.message ?: str(R.string.file_op_incomplete)) }
        }
    }
    private suspend fun finishFiles(detail: String? = null) {
        val done = str(if (fileTarget == null) R.string.file_op_summary_deleted else R.string.file_op_summary_moved, fileSucceeded, fileItems.size)
        val refreshFailure = runCatching { graph.repository.refresh() }.exceptionOrNull()
        val report = listOfNotNull(done, detail, refreshFailure?.let { str(R.string.file_op_refresh_failed) }).joinToString("\n")
        fileItems = emptyList(); copiedUri = null; fileTarget = null
        fileOperation.value = FileOperationState(result = report)
    }
    init {
        viewModelScope.launch { graph.player.events.collect { if (it is PlayerEvent.Error) notify(it.failure.message) } }
    }
    fun command(command: PlayerCommand) { viewModelScope.launch { graph.player.execute(command) } }
    fun open(video: VideoItem, queue: List<VideoItem>, position: Long? = null, recordHistory: Boolean = true) {
        if (recordHistory && position == null) {
            val at = PlaybackRules.resume(video.positionMs, video.durationMs)
            if (at > 0) {
                val seconds = at / 1000
                viewModelScope.launch { notify(str(R.string.notice_resume_from, "%02d:%02d".format(seconds / 60, seconds % 60))) }
            }
        }
        command(PlayerCommand.Open(video.id, queue.map { it.id }, position, recordHistory))
    }
    fun hide(ids: Set<VideoId>, hidden: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            graph.repository.setHidden(ids, hidden)
            if (hidden) notify(str(R.string.notice_hidden_count, ids.size), str(R.string.action_undo)) {
                viewModelScope.launch {
                    graph.repository.setHidden(ids, false)
                    notify(str(R.string.notice_restored_visible))
                }
            } else notify(str(R.string.notice_unhidden_count, ids.size))
        }
    }
    fun favorite(ids: Set<VideoId>, favorite: Boolean) {
        viewModelScope.launch { graph.repository.setFavorite(ids, favorite); notify(str(if (favorite) R.string.notice_favorited else R.string.notice_unfavorited)) }
    }
    fun savePicture(id: VideoId, cropBars: Boolean) {
        viewModelScope.launch {
            runCatching { graph.repository.savePicture(id, cropBars) }
                .onFailure { notify(it.message ?: str(R.string.notice_picture_save_failed)) }
        }
    }
    fun startLibraryScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            val videos = graph.repository.allVideos().filter { !it.hidden && it.accessible }
            val keys = mutableListOf<Pair<VideoItem, String>>()
            val unreadable = mutableListOf<Pair<VideoItem, String>>()
            videos.forEachIndexed { index, video ->
                ensureActive()
                libraryScan.value = LibraryScanState(true, index, videos.size, video.displayTitle())
                var bad: String? = null
                var key: String? = null
                graph.checks.forEach { check ->
                    val hit = runCatching { check.inspect(video) { graph.files.openStream(video) } }.getOrNull()
                    when (hit) {
                        is CheckHit.Unreadable -> bad = hit.reason
                        is CheckHit.ContentKey -> if (key == null) key = hit.key
                        null -> Unit
                    }
                }
                if (bad != null) unreadable += video to bad!! else if (key != null) keys += video to key!!
            }
            libraryScan.value = LibraryScanState(false, videos.size, videos.size, str(R.string.scan_done_label), duplicateGroups(keys), unreadable)
        }
    }
    fun cancelLibraryScan() {
        scanJob?.cancel()
        libraryScan.value = libraryScan.value.copy(running = false, label = str(R.string.scan_cancelled_label))
    }
    fun restoreBackup(text: String) {
        viewModelScope.launch {
            runCatching {
                val plan = cn.yingxia.player.data.LibraryBackup.plan(graph.repository.allVideos(), cn.yingxia.player.data.LibraryBackup.parse(text))
                graph.settings.replacePlayback(plan.settings)
                graph.repository.restoreRecords(plan.matched)
                plan
            }.onSuccess { notify(if (it.skipped == 0) str(R.string.backup_restored_all, it.matched.size) else str(R.string.backup_restored_partial, it.matched.size, it.skipped)) }
                .onFailure { notify(if (it is InvalidBackupException) str(R.string.backup_error_invalid_file) else it.message ?: str(R.string.backup_error_read)) }
        }
    }
    fun rename(id: VideoId, displayName: String) {
        viewModelScope.launch {
            runCatching { graph.repository.rename(id, displayName) }
                .onSuccess { notify(str(R.string.notice_renamed)) }
                .onFailure { notify(it.message ?: str(R.string.notice_rename_failed)) }
        }
    }
    fun importVideo(uri: String) {
        viewModelScope.launch {
            runCatching { graph.repository.importVideo(uri) }.onSuccess { open(it, listOf(it)) }
                .onFailure { notify(it.message ?: str(R.string.notice_import_failed)) }
        }
    }
    fun refresh() { viewModelScope.launch { graph.repository.refresh() } }
    fun message(message: String) { viewModelScope.launch { notify(message) } }
    private suspend fun notify(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        messages.emit(UserNotice(text, actionLabel, onAction))
    }
    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}

data class FileOperationState(val busy: Boolean = false, val message: String = "",
    val consent: DeleteOutcome.Consent? = null, val token: Long = 0, val result: String? = null)

/** A short message. [actionLabel] is shown on the snackbar when the action can be undone. */
data class UserNotice(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)
data class LibraryScanState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val label: String = "",
    val duplicates: List<List<VideoItem>> = emptyList(),
    val unreadable: List<Pair<VideoItem, String>> = emptyList(),
)
