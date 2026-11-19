@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.vault

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import cn.yingxia.player.core.*
import cn.yingxia.player.data.SettingsRepository
import cn.yingxia.player.features.*
import cn.yingxia.player.playback.Media3Controller
import cn.yingxia.player.ui.PlayerUiSession
import cn.yingxia.player.ui.yingxiaTools
import kotlinx.coroutines.*

/** Owned by the unlocked vault, survives rotation, destroyed before lock. Never uses public records. */
class VaultPlaybackSession(
    private val context: Context, store: VaultStore, val repository: VaultVideoRepository,
    settings: SettingsRepository, stop: () -> Unit, private val notice: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val player = Media3Controller(context, repository, settings, scope) { video ->
        ProgressiveMediaSource.Factory { VaultDataSource(store, video.id.value) }
            .createMediaSource(MediaItem.Builder().setMediaId(video.id.value).setUri(video.uri).build())
    }
    val loop = LoopFeature(player) { AppLocale.str(context, R.string.loop_error_set_a_first) }
    val timer = SleepTimerFeature(player, scope)
    private val bookmarkFeature = BookmarkFeature(player, repository)
    private val tools = yingxiaTools(loop, bookmarkFeature, timer)
    private val registry = FeatureRegistry(player, scope, tools.map { it.feature })
    private var closed = false
    val ui = PlayerUiSession(player, repository, repository.videos, repository.bookmarks,
        loop, timer, bookmarkFeature, tools, scope, { command ->
            if (command == PlayerCommand.Close) stop() else if (!closed) scope.launch { player.execute(command) }
        }, notice, privateSession = true, actionError = { AppLocale.str(context, R.string.error_action_not_saved) })
    init { scope.launch { player.events.collect { if (it is PlayerEvent.Error) notice(it.failure.message) } } }
    suspend fun open(id: String) = player.execute(PlayerCommand.Open(VideoId(id), repository.videos.value.map { it.id })).getOrThrow()
    fun close() {
        if (closed) return
        closed = true
        timer.cancel(); scope.cancel()
        val last = player.release()
        try { repository.finish(last.video?.id, last.positionMs) }
        catch (_: Exception) { notice(AppLocale.str(context, R.string.vault_error_playback_not_saved)) }
    }
}
