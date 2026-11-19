package cn.yingxia.player.ui

import cn.yingxia.player.AppViewModel
import cn.yingxia.player.core.*
import cn.yingxia.player.features.*
import cn.yingxia.player.playback.Media3Controller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/** Shared controls depend on this session, never on the ordinary library singleton. */
class PlayerUiSession(
    val player: Media3Controller,
    val repository: VideoRepository,
    val videos: StateFlow<List<VideoItem>>,
    val bookmarks: StateFlow<List<Bookmark>>,
    val loop: LoopFeature,
    val timer: SleepTimerFeature,
    val bookmarkFeature: BookmarkFeature,
    val tools: List<PlayerTool>,
    private val scope: CoroutineScope,
    private val commandSink: (PlayerCommand) -> Unit,
    private val notice: (String) -> Unit,
    private val hideAction: ((Set<VideoId>, Boolean) -> Unit)? = null,
    val privateSession: Boolean = false,
) {
    fun command(command: PlayerCommand) = commandSink(command)
    fun message(text: String) = notice(text)
    fun open(video: VideoItem, queue: List<VideoItem>, position: Long? = null) =
        command(PlayerCommand.Open(video.id, queue.map { it.id }, position, player.state.value.recordHistory))
    fun favorite(ids: Set<VideoId>, value: Boolean) = action { repository.setFavorite(ids, value) }
    fun rename(id: VideoId, value: String) = action { repository.rename(id, value) }
    fun savePicture(id: VideoId, value: Boolean) = action { repository.savePicture(id, value) }
    fun hide(ids: Set<VideoId>, value: Boolean) { hideAction?.invoke(ids, value) }
    private fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message("未能保存本次操作，请重试") }
    } }
    companion object {
        fun ordinary(vm: AppViewModel) = PlayerUiSession(vm.graph.player, vm.graph.repository,
            vm.videos, vm.bookmarks, vm.graph.loop, vm.graph.timer, vm.graph.bookmarks,
            vm.graph.tools, vm.graph.scope, vm::command, vm::message, vm::hide)
    }
}
