package cn.yingxia.player.features

import cn.yingxia.player.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.os.SystemClock

class LoopFeature(private val controller: PlaybackController) : PlayerFeature {
    override val id = "loop"
    override val title = "A-B 循环"
    override val order = 10
    val pointA = MutableStateFlow<Long?>(null)
    suspend fun markA() { pointA.value = controller.state.value.positionMs; controller.execute(PlayerCommand.SetLoop(null)) }
    suspend fun markB(): Result<Unit> {
        val a = pointA.value ?: return Result.failure(IllegalArgumentException("请先设置 A 点"))
        return controller.execute(PlayerCommand.SetLoop(LoopRange(a, controller.state.value.positionMs)))
    }
    suspend fun clear() { pointA.value = null; controller.execute(PlayerCommand.SetLoop(null)) }
    override suspend fun onEvent(event: PlayerEvent) { if (event is PlayerEvent.Opened || event is PlayerEvent.Closed) pointA.value = null }
}
class SleepTimerFeature(private val controller: PlaybackController, private val scope: CoroutineScope) : PlayerFeature {
    override val id = "sleep"
    override val title = "睡眠定时"
    override val order = 30
    val remainingMs = MutableStateFlow(0L)
    val untilEnd = MutableStateFlow(false)
    private var job: Job? = null
    fun start(minutes: Int) { startMilliseconds(minutes * 60_000L) }
    fun stopWhenFinished() {
        job?.cancel()
        if (controller.state.value.durationMs <= 0) return
        untilEnd.value = true
        job = scope.launch {
            while (isActive) {
                val snapshot = controller.state.value
                if (snapshot.video == null || snapshot.durationMs <= 0) break
                val left = (snapshot.durationMs - snapshot.positionMs).coerceAtLeast(0)
                remainingMs.value = left
                if (left <= 800) { controller.execute(PlayerCommand.Pause); break }
                delay(400)
            }
            remainingMs.value = 0
            untilEnd.value = false
            job = null
        }
    }
    internal fun startMilliseconds(duration: Long) {
        cancel()
        val deadline = SystemClock.elapsedRealtime() + duration
        job = scope.launch {
            while (true) {
                remainingMs.value = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                if (remainingMs.value == 0L) break
                delay(minOf(remainingMs.value, 500))
            }
            controller.execute(PlayerCommand.Pause)
        }
    }
    fun cancel() { job?.cancel(); job = null; remainingMs.value = 0; untilEnd.value = false }
    override suspend fun onEvent(event: PlayerEvent) { if (event is PlayerEvent.Closed) cancel() }
}
class BookmarkFeature(private val controller: PlaybackController, private val repository: VideoRepository) : PlayerFeature {
    override val id = "bookmarks"
    override val title = "时间书签"
    override val order = 20
    suspend fun add(note: String) {
        val s = controller.state.value
        s.video?.let { repository.saveBookmark(it.id, s.positionMs, note) }
    }
}
class SubtitleFeature : PlayerFeature {
    override val id = "subtitles"
    override val title = "字幕"
    override val order = 0
}
class FeatureRegistry(controller: PlaybackController, scope: CoroutineScope, contributions: List<PlayerFeature>) {
    val features = contributions.sortedBy { it.order }
    init {
        require(features.map { it.id }.distinct().size == features.size) { "Feature IDs must be unique" }
        scope.launch { controller.events.collect { event -> features.forEach { it.onEvent(event) } } }
    }
}
