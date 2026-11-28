@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.playback

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import cn.yingxia.player.core.*
import cn.yingxia.player.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Media3Controller(private val context: Context, private val repository: VideoRepository,
    private val settings: SettingsRepository, private val scope: CoroutineScope,
    private val source: ((VideoItem) -> androidx.media3.exoplayer.source.MediaSource?)? = null) : PlaybackController {
    private fun str(id: Int) = AppLocale.str(context, id)
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 32)
    override val events = mutableEvents.asSharedFlow()
    internal val aspectRatio = MutableStateFlow(16f / 9f)
    internal val surfacePlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
        setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }
    private var autoAdvance = false
    private var lastSavedAt = 0L
    private var isClosing = false
    private var released = false
    private val openMutex = Mutex()

    init {
        surfacePlayer.addListener(object : Player.Listener {
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                    endBoost()
                    surfacePlayer.pause()
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspectRatio.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePosition()
                if (!isPlaying) {
                    endBoost()
                    saveProgress()
                    mutableEvents.tryEmit(PlayerEvent.Paused)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePosition()
                if (playbackState == Player.STATE_ENDED && !autoAdvance && !isClosing && state.value.video != null) {
                    autoAdvance = true
                    scope.launch {
                        try {
                            val range = state.value.loop
                            if (range != null) { surfacePlayer.seekTo(range.startMs); surfacePlayer.play() }
                            else advance()
                        } finally { autoAdvance = false }
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                endBoost()
                val failure = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlayerFailure(FailureKind.ACCESS_DENIED, str(R.string.player_error_no_permission))
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlayerFailure(FailureKind.SOURCE_MISSING, str(R.string.player_error_not_found))
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        PlayerFailure(FailureKind.UNSUPPORTED_FORMAT, str(R.string.player_error_unsupported))
                    else -> PlayerFailure(FailureKind.DECODE_FAILED, str(R.string.player_error_decode_failed))
                }
                report(failure)
            }
        })
        scope.launch {
            settings.settings.collect { preferences ->
                val base = preferences.speed
                mutableState.update { it.copy(baseSpeed = base, effectiveSpeed = if (it.boosting) PlaybackRules.temporarySpeed(base, preferences.holdSpeed) else base) }
                surfacePlayer.setPlaybackSpeed(state.value.effectiveSpeed)
            }
        }
        scope.launch {
            repository.hiddenVideos.collect { hidden ->
                val ids = hidden.map { it.id }.toSet()
                if (state.value.video?.id in ids) close()
                else mutableState.update { it.copy(queue = it.queue.filterNot { id -> id in ids }) }
            }
        }
        scope.launch {
            while (isActive) {
                val snapshot = state.value
                if (snapshot.video != null) {
                    val loop = snapshot.loop
                    if (surfacePlayer.isPlaying && loop != null && surfacePlayer.currentPosition >= loop.endMs) surfacePlayer.seekTo(loop.startMs)
                    updatePosition()
                    mutableEvents.tryEmit(PlayerEvent.Position(state.value.positionMs))
                    if (surfacePlayer.isPlaying && System.currentTimeMillis() - lastSavedAt > 5000) saveProgress()
                }
                delay(if (surfacePlayer.isPlaying && state.value.loop != null) 40 else 200)
            }
        }
    }

    override suspend fun execute(command: PlayerCommand): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext Result.failure(IllegalStateException(str(R.string.player_error_session_closed)))
        try {
            when (command) {
                is PlayerCommand.OpenShared -> openMutex.withLock {
                    open(command.video.id, listOf(command.video.id), 0L, false, command.video)
                }
                is PlayerCommand.Open -> openMutex.withLock { open(command.id, command.queue, command.positionMs, command.recordHistory) }
                PlayerCommand.Play -> { state.value.video?.let { repository.requirePlayable(it); if (surfacePlayer.playbackState == Player.STATE_ENDED) surfacePlayer.seekTo(0); surfacePlayer.play() } }
                PlayerCommand.Pause -> { endBoost(); surfacePlayer.pause(); saveProgress() }
                PlayerCommand.Toggle -> if (surfacePlayer.isPlaying) { endBoost(); surfacePlayer.pause() } else {
                    state.value.video?.let { repository.requirePlayable(it); if (surfacePlayer.playbackState == Player.STATE_ENDED) surfacePlayer.seekTo(0); surfacePlayer.play() }
                }
                is PlayerCommand.Seek -> {
                    endBoost()
                    val target = PlaybackRules.seek(command.positionMs, state.value.durationMs)
                    val loop = state.value.loop
                    if (loop != null && target !in loop.startMs..loop.endMs) mutableState.update { it.copy(loop = null) }
                    surfacePlayer.seekTo(target); updatePosition(); saveProgress()
                }
                is PlayerCommand.SetSpeed -> {
                    endBoost()
                    val value = PlaybackRules.speed(command.speed)
                    surfacePlayer.setPlaybackSpeed(value)
                    mutableState.update { it.copy(baseSpeed = value, effectiveSpeed = value) }
                    settings.setSpeed(value)
                }
                is PlayerCommand.SetHoldSpeed -> {
                    val value = PlaybackRules.holdSpeed(command.speed)
                    settings.setHoldSpeed(value)
                    if (state.value.boosting) {
                        val boosted = PlaybackRules.temporarySpeed(state.value.baseSpeed, value)
                        surfacePlayer.setPlaybackSpeed(boosted)
                        mutableState.update { it.copy(effectiveSpeed = boosted) }
                    }
                }
                is PlayerCommand.BeginBoost -> if (surfacePlayer.isPlaying) {
                    val value = PlaybackRules.temporarySpeed(state.value.baseSpeed, command.speed)
                    mutableState.update { it.copy(boosting = true, effectiveSpeed = value) }
                    surfacePlayer.setPlaybackSpeed(value)
                }
                PlayerCommand.EndBoost -> endBoost()
                PlayerCommand.Next -> advance(manual = true)
                PlayerCommand.Previous -> {
                    openMutex.withLock {
                        val s = state.value
                        val i = s.queue.indexOf(s.video?.id)
                        s.queue.getOrNull(i - 1)?.let { open(it, s.queue, null, s.recordHistory) }
                    }
                }
                is PlayerCommand.Enqueue -> {
                    val allowed = repository.videos.first().map { it.id }.toSet()
                    val ids = command.ids.filter { it in allowed }
                    val queue = (state.value.queue + ids).distinct()
                    if (state.value.video == null && queue.isNotEmpty()) openMutex.withLock { open(queue.first(), queue, null, true) }
                    else mutableState.update { it.copy(queue = queue) }
                }
                is PlayerCommand.SetRepeat -> mutableState.update { it.copy(repeatMode = command.mode) }
                is PlayerCommand.SetLoop -> {
                    val range = command.range
                    if (range != null && !PlaybackRules.validLoop(range, state.value.durationMs)) throw IllegalArgumentException(str(R.string.player_error_invalid_loop))
                    mutableState.update { it.copy(loop = range) }
                    if (range != null) { surfacePlayer.seekTo(range.startMs); surfacePlayer.play() }
                }
                PlayerCommand.Close -> close()
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = e as? PlayerFailure ?: PlayerFailure(FailureKind.UNKNOWN, e.message ?: str(R.string.player_error_unknown))
            mutableEvents.tryEmit(PlayerEvent.Error(failure))
            Result.failure(failure)
        }
    }

    private suspend fun open(id: VideoId, queue: List<VideoId>, start: Long?, recordHistory: Boolean, shared: VideoItem? = null) {
        val transient = shared ?: state.value.video?.takeIf { !recordHistory && it.id == id && it.source == "share" }
        val video = transient?.let { repository.requirePlayable(it) } ?: repository.requirePlayable(id)
        val safeQueue = if (transient != null) listOf(id) else {
            val visible = repository.videos.first().map { it.id }.toSet()
            (queue.filter { it in visible } + id).distinct()
        }
        saveProgress()
        endBoost()
        val mode = state.value.repeatMode
        val speed = state.value.baseSpeed
        isClosing = true
        surfacePlayer.stop()
        mutableState.value = PlaybackState(video, safeQueue, durationMs = video.durationMs, baseSpeed = speed, effectiveSpeed = speed, repeatMode = mode, buffering = true, recordHistory = recordHistory)
        val customSource = source?.invoke(video)
        if (customSource != null) surfacePlayer.setMediaSource(customSource) else surfacePlayer.setMediaItem(mediaItem(video))
        val resumePosition = if (recordHistory) PlaybackRules.resume(video.positionMs, video.durationMs) else 0L
        val startPosition = start?.let { if (video.durationMs > 0) PlaybackRules.seek(it, video.durationMs) else it.coerceAtLeast(0L) } ?: resumePosition
        surfacePlayer.seekTo(startPosition)
        surfacePlayer.setPlaybackSpeed(speed)
        surfacePlayer.prepare()
        isClosing = false
        surfacePlayer.play()
        mutableEvents.tryEmit(PlayerEvent.Opened(video))
        updatePosition()
        saveProgress()
    }
    private fun mediaItem(video: VideoItem): MediaItem = MediaItem.Builder().setMediaId(video.id.value).setUri(video.uri).build()
    private suspend fun advance(manual: Boolean = false) {
        val s = state.value
        val current = s.video?.id ?: return
        val mode = if (manual && s.repeatMode == RepeatMode.ONE) RepeatMode.SEQUENCE else s.repeatMode
        val next = PlaybackRules.next(s.queue, current, mode)
        if (next == current) { surfacePlayer.seekTo(0); surfacePlayer.play() }
        else if (next != null) {
            try {
                openMutex.withLock {
                    if (state.value.video?.id == current && state.value.recordHistory == s.recordHistory) {
                        open(next, s.queue, 0, s.recordHistory)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                surfacePlayer.pause()
                report(e as? PlayerFailure ?: PlayerFailure(FailureKind.SOURCE_MISSING, str(R.string.player_error_next_unavailable)))
            }
        } else { surfacePlayer.pause(); endBoost(); saveProgress() }
    }
    private fun updatePosition() {
        if (state.value.video == null) return
        mutableState.update { it.copy(positionMs = surfacePlayer.currentPosition.coerceAtLeast(0),
            durationMs = surfacePlayer.duration.takeIf { d -> d > 0 } ?: it.video?.durationMs ?: 0,
            isPlaying = surfacePlayer.isPlaying, buffering = surfacePlayer.playbackState == Player.STATE_BUFFERING) }
    }
    private fun saveProgress() {
        val snapshot = state.value
        if (!snapshot.recordHistory) return
        val video = snapshot.video ?: return
        val position = surfacePlayer.currentPosition.coerceAtLeast(0)
        lastSavedAt = System.currentTimeMillis()
        scope.launch {
            try { repository.saveProgress(video.id, position) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableEvents.tryEmit(PlayerEvent.Error(PlayerFailure(FailureKind.UNKNOWN, str(R.string.player_error_progress_not_saved)))) }
        }
    }
    private fun endBoost() {
        if (!state.value.boosting) return
        val base = state.value.baseSpeed
        surfacePlayer.setPlaybackSpeed(base)
        mutableState.update { it.copy(boosting = false, effectiveSpeed = base) }
    }
    private fun close() {
        saveProgress(); endBoost()
        isClosing = true
        surfacePlayer.stop(); surfacePlayer.clearMediaItems()
        mutableState.value = PlaybackState(baseSpeed = state.value.baseSpeed, effectiveSpeed = state.value.baseSpeed, repeatMode = state.value.repeatMode)
        isClosing = false
        mutableEvents.tryEmit(PlayerEvent.Closed)
    }
    private fun report(failure: PlayerFailure) {
        mutableState.update { it.copy(failure = failure.message, buffering = false) }
        mutableEvents.tryEmit(PlayerEvent.Error(failure))
    }

    /** Owner must cancel its session scope first, then flush this snapshot before destroying keys. */
    fun release(): PlaybackState {
        if (released) return state.value
        val last = state.value.copy(positionMs = surfacePlayer.currentPosition.coerceAtLeast(0))
        isClosing = true
        endBoost()
        surfacePlayer.release()
        released = true
        mutableState.value = PlaybackState()
        return last
    }
}
