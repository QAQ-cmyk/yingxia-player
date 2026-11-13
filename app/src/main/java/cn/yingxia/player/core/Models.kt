package cn.yingxia.player.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@JvmInline value class VideoId(val value: String)
data class VideoItem(
    val id: VideoId, val uri: String, val name: String, val folder: String,
    val folderKey: String, val durationMs: Long, val sizeBytes: Long,
    val width: Int = 0, val height: Int = 0, val addedAt: Long = 0,
    val source: String = "media-store", val accessible: Boolean = true,
    val hidden: Boolean = false, val favorite: Boolean = false,
    val positionMs: Long = 0, val lastPlayedAt: Long = 0,
    val subtitleUri: String? = null, val subtitleName: String? = null,
)
data class Bookmark(val id: Long, val videoId: VideoId, val positionMs: Long, val note: String)
data class SubtitleAttachment(val uri: String, val name: String, val mimeType: String)
data class SubtitleTrack(val id: String, val name: String, val selected: Boolean)
data class StorageFolder(val uri: String, val name: String)
enum class RepeatMode { SEQUENCE, ONE, ALL }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LibraryAccess { FULL, PARTIAL, NONE }
enum class SortOrder(val title: String) { NEWEST("最近添加"), NAME("名称"), DURATION("时长"), SIZE("文件大小") }
enum class FailureKind { ACCESS_DENIED, SOURCE_MISSING, UNSUPPORTED_FORMAT, DECODE_FAILED, INVALID_SUBTITLE, UNKNOWN }
class PlayerFailure(val kind: FailureKind, override val message: String) : Exception(message)
data class LoopRange(val startMs: Long, val endMs: Long)
data class PlaybackState(
    val video: VideoItem? = null, val queue: List<VideoId> = emptyList(),
    val positionMs: Long = 0, val durationMs: Long = 0,
    val isPlaying: Boolean = false, val buffering: Boolean = false,
    val baseSpeed: Float = 1f, val effectiveSpeed: Float = 1f,
    val boosting: Boolean = false, val repeatMode: RepeatMode = RepeatMode.SEQUENCE,
    val loop: LoopRange? = null, val subtitles: List<SubtitleTrack> = emptyList(),
    val failure: String? = null,
)
data class UserSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val speed: Float = 1f,
    val holdSpeed: Float = 2f, val subtitleScale: Float = 1f)

sealed interface PlayerCommand {
    data class Open(val id: VideoId, val queue: List<VideoId>, val positionMs: Long? = null) : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object Toggle : PlayerCommand
    data class Seek(val positionMs: Long) : PlayerCommand
    data class SetSpeed(val speed: Float) : PlayerCommand
    data class BeginBoost(val speed: Float) : PlayerCommand
    data object EndBoost : PlayerCommand
    data object Next : PlayerCommand
    data object Previous : PlayerCommand
    data class Enqueue(val ids: List<VideoId>) : PlayerCommand
    data class SetRepeat(val mode: RepeatMode) : PlayerCommand
    data class SetLoop(val range: LoopRange?) : PlayerCommand
    data class AttachSubtitle(val attachment: SubtitleAttachment) : PlayerCommand
    data class SelectSubtitle(val id: String?) : PlayerCommand
    data object Close : PlayerCommand
}
sealed interface PlayerEvent {
    data class Opened(val video: VideoItem) : PlayerEvent
    data class Position(val positionMs: Long) : PlayerEvent
    data class Error(val failure: PlayerFailure) : PlayerEvent
    data object Paused : PlayerEvent
    data object Closed : PlayerEvent
}
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    val events: Flow<PlayerEvent>
    suspend fun execute(command: PlayerCommand): Result<Unit>
}
interface MediaSource {
    val sourceId: String
    suspend fun query(): List<VideoItem>
    suspend fun resolve(video: VideoItem): String
}
interface VideoRepository {
    val videos: Flow<List<VideoItem>>
    val hiddenVideos: Flow<List<VideoItem>>
    val bookmarks: Flow<List<Bookmark>>
    val scanning: StateFlow<Boolean>
    val access: StateFlow<LibraryAccess>
    val scanError: StateFlow<String?>
    suspend fun refresh()
    suspend fun importVideo(uri: String): VideoItem
    suspend fun get(id: VideoId): VideoItem?
    suspend fun requirePlayable(id: VideoId): VideoItem
    suspend fun setHidden(ids: Set<VideoId>, hidden: Boolean)
    suspend fun setFavorite(ids: Set<VideoId>, favorite: Boolean)
    suspend fun saveProgress(id: VideoId, positionMs: Long)
    suspend fun saveSubtitle(id: VideoId, attachment: SubtitleAttachment)
    suspend fun saveBookmark(videoId: VideoId, positionMs: Long, note: String, id: Long = 0)
    suspend fun deleteBookmark(id: Long)
    suspend fun clearHistory()
}
interface SubtitleProvider { suspend fun load(uri: String): SubtitleAttachment }

/** Domain contribution; a UI panel is registered separately, never inside the playback engine. */
interface PlayerFeature {
    val id: String
    val title: String
    val order: Int
    fun available(state: PlaybackState): Boolean = state.video != null
    suspend fun onEvent(event: PlayerEvent) {}
}

object PlaybackRules {
    val speeds = listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f)
    fun speed(value: Float) = if (value in speeds) value else 1f
    fun temporarySpeed(base: Float, hold: Float) = maxOf(speed(base), if (hold == 3f) 3f else 2f)
    fun seek(positionMs: Long, durationMs: Long) = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
    fun resume(positionMs: Long, durationMs: Long): Long =
        if (positionMs < 3000 || durationMs <= 0 || durationMs - positionMs <= 5000) 0 else seek(positionMs, durationMs)
    fun validLoop(range: LoopRange, durationMs: Long) = range.startMs >= 0 &&
        range.endMs - range.startMs >= 500 && range.endMs <= durationMs
    fun visible(video: VideoItem) = !video.hidden && video.accessible
    fun next(queue: List<VideoId>, current: VideoId, mode: RepeatMode): VideoId? {
        if (mode == RepeatMode.ONE) return current
        val i = queue.indexOf(current)
        if (i < 0 || queue.isEmpty()) return null
        return queue.getOrNull(i + 1) ?: if (mode == RepeatMode.ALL) queue.firstOrNull() else null
    }
}

enum class GestureMode { PENDING, SEEK, BRIGHTNESS, VOLUME, BOOST, DOUBLE_TAP }
fun chooseDrag(dx: Float, dy: Float, slop: Float, fullscreen: Boolean, leftSide: Boolean): GestureMode {
    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) return GestureMode.SEEK
    if (fullscreen && kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f)
        return if (leftSide) GestureMode.BRIGHTNESS else GestureMode.VOLUME
    return GestureMode.PENDING
}
