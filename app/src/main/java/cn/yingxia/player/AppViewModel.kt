package cn.yingxia.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.yingxia.player.core.*
import cn.yingxia.player.data.DeleteOutcome
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AppViewModel(val graph: AppGraph) : ViewModel() {
    val videos = graph.repository.videos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hidden = graph.repository.hiddenVideos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = graph.repository.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val messages = MutableSharedFlow<UserNotice>(extraBufferCapacity = 8)
    val folders = graph.settings.storageFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folderKeys = combine(folders, videos) { folders, _ -> graph.files.folderKeys(folders) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val fileOperation = MutableStateFlow(FileOperationState())
    private var fileItems = emptyList<VideoItem>()
    private var fileTarget: StorageFolder? = null
    private var fileIndex = 0
    private var fileSucceeded = 0
    private var copiedUri: String? = null
    private var consentSerial = 0L

    fun createFolder(parent: String, name: String, onCreated: (StorageFolder) -> Unit) {
        viewModelScope.launch {
            runCatching { graph.files.createFolder(parent, name).also { graph.settings.addFolder(it) } }
                .onSuccess { notify("文件夹已创建在手机存储中"); onCreated(it) }
                .onFailure { notify(it.message ?: "无法新建文件夹") }
        }
    }
    fun manageFiles(items: List<VideoItem>, target: StorageFolder? = null) {
        if (fileOperation.value.busy || items.isEmpty()) return
        fileItems = items.distinctBy { it.id }; fileTarget = target; fileIndex = 0; fileSucceeded = 0; copiedUri = null
        fileOperation.value = FileOperationState(busy = true)
        viewModelScope.launch {
            if (graph.player.state.value.video?.id in fileItems.map { it.id }) graph.player.execute(PlayerCommand.Close)
            continueFiles()
        }
    }
    private suspend fun continueFiles() {
        while (fileIndex < fileItems.size) {
            val video = fileItems[fileIndex]
            fileOperation.value = FileOperationState(true, "${if (fileTarget == null) "正在删除" else "正在复制并校验"} ${fileIndex + 1}/${fileItems.size}：${video.name}")
            try {
                if (fileTarget != null && copiedUri == null) copiedUri = graph.files.copyVerified(video, fileTarget!!)
                when (val result = graph.files.delete(video)) {
                    DeleteOutcome.Deleted -> finishFile()
                    is DeleteOutcome.Consent -> {
                        fileOperation.value = FileOperationState(true, "请在系统窗口中确认删除${if (fileTarget != null) "原件，完成移动" else "视频"}", result, ++consentSerial)
                        return
                    }
                }
            } catch (e: Exception) {
                finishFiles("${video.name}：${e.message ?: "操作失败"}${if (copiedUri != null) "；副本已校验并保留，原件删除未确认" else ""}")
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
            if (!approved) { finishFiles(if (copiedUri != null) "已取消删除原件；原视频和已校验的副本均保留，未完成移动" else "已取消，其余视频未删除"); return@launch }
            try {
                val video = fileItems[fileIndex]
                if (consent.retryDelete) check(graph.files.delete(video, true) == DeleteOutcome.Deleted)
                else graph.files.verifySystemDeletion(consent.mediaUri)
                finishFile(); continueFiles()
            } catch (e: Exception) { finishFiles(e.message ?: "未能完成文件操作") }
        }
    }
    private suspend fun finishFiles(detail: String? = null) {
        val done = "已${if (fileTarget == null) "删除" else "移动"} $fileSucceeded/${fileItems.size} 个视频"
        val refreshFailure = runCatching { graph.repository.refresh() }.exceptionOrNull()
        val report = listOfNotNull(done, detail, refreshFailure?.let { "列表刷新失败，请稍后重新扫描" }).joinToString("。")
        fileItems = emptyList(); copiedUri = null; fileTarget = null
        fileOperation.value = FileOperationState(result = report)
    }
    init {
        viewModelScope.launch { graph.player.events.collect { if (it is PlayerEvent.Error) notify(it.failure.message) } }
    }
    fun command(command: PlayerCommand) { viewModelScope.launch { graph.player.execute(command) } }
    fun open(video: VideoItem, queue: List<VideoItem>, position: Long? = null) {
        if (position == null) {
            val at = PlaybackRules.resume(video.positionMs, video.durationMs)
            if (at > 0) {
                val seconds = at / 1000
                viewModelScope.launch { notify("从 %02d:%02d 继续播放".format(seconds / 60, seconds % 60)) }
            }
        }
        command(PlayerCommand.Open(video.id, queue.map { it.id }, position))
    }
    fun hide(ids: Set<VideoId>, hidden: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            graph.repository.setHidden(ids, hidden)
            if (hidden) notify("已隐藏 ${ids.size} 个视频，没有删除文件", "撤销") {
                viewModelScope.launch {
                    graph.repository.setHidden(ids, false)
                    notify("已恢复显示")
                }
            } else notify("已恢复 ${ids.size} 个视频")
        }
    }
    fun favorite(ids: Set<VideoId>, favorite: Boolean) {
        viewModelScope.launch { graph.repository.setFavorite(ids, favorite); notify(if (favorite) "已加入收藏" else "已取消收藏") }
    }
    fun rename(id: VideoId, displayName: String) {
        viewModelScope.launch {
            runCatching { graph.repository.rename(id, displayName) }
                .onSuccess { notify("已更新显示名称") }
                .onFailure { notify(it.message ?: "无法重命名") }
        }
    }
    fun importVideo(uri: String) {
        viewModelScope.launch {
            runCatching { graph.repository.importVideo(uri) }.onSuccess { open(it, listOf(it)) }
                .onFailure { notify(it.message ?: "未能打开这个视频") }
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

data class FileOperationState(val busy: Boolean = false, val message: String = "正在准备文件操作",
    val consent: DeleteOutcome.Consent? = null, val token: Long = 0, val result: String? = null)

/** A short message. [actionLabel] is shown on the snackbar when the action can be undone. */
data class UserNotice(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)
