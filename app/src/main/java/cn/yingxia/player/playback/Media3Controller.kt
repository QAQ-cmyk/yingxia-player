@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.playback

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.effect.Contrast
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import cn.yingxia.player.core.*
import cn.yingxia.player.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Media3Controller(context: Context, private val repository: VideoRepository,
    private val settings: SettingsRepository, private val scope: CoroutineScope) : PlaybackController {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 32)
    override val events = mutableEvents.asSharedFlow()
    internal val cues = MutableStateFlow<List<Cue>>(emptyList())
    internal val aspectRatio = MutableStateFlow(16f / 9f)
    private val audioEnhance = EnhancementAudioProcessor()
    private val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let { it.isLowRamDevice || it.memoryClass < 192 }
    private val allow4k = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let { !it.isLowRamDevice && it.memoryClass >= 256 }
    private var clarityKey = ""
    private var activeClarity = ClarityMode.OFF
    private var told4kRefuse = false
    internal val surfacePlayer: ExoPlayer = ExoPlayer.Builder(context, EnhancingRenderersFactory(context, audioEnhance)).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
        setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
    }
    private var autoAdvance = false
    private var lastSavedAt = 0L
    private var isClosing = false
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
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    scope.launch {
                        val prefs = settings.settings.first()
                        applyClarity(prefs.clarity, prefs.upscale4k, videoSize.width, videoSize.height)
                    }
                }
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
            override fun onTracksChanged(tracks: Tracks) { updateTracks(tracks) }
            override fun onCues(cueGroup: CueGroup) { cues.value = cueGroup.cues }
            override fun onPlayerError(error: PlaybackException) {
                endBoost()
                if (activeClarity != ClarityMode.OFF && error.errorCode in 7000..7003) {
                    val was4k = activeClarity == ClarityMode.UPSCALE_4K
                    clarityKey = ""
                    activeClarity = ClarityMode.OFF
                    runCatching { surfacePlayer.setVideoEffects(emptyList()) }
                    if (was4k) {
                        scope.launch { settings.setUpscale4k(false) }
                        report(PlayerFailure(FailureKind.DECODE_FAILED, "这台设备无法放大到 4K，已自动关闭"))
                    } else {
                        scope.launch { settings.setClarity(false) }
                        report(PlayerFailure(FailureKind.DECODE_FAILED, "这台设备开不了清晰度，已自动关闭"))
                    }
                    return
                }
                val failure = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlayerFailure(FailureKind.ACCESS_DENIED, "视频访问权限已失效，请重新授权")
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlayerFailure(FailureKind.SOURCE_MISSING, "视频文件已被移动或删除")
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        PlayerFailure(FailureKind.UNSUPPORTED_FORMAT, "设备不支持这个视频的编码，可尝试 MP4 / H.264 格式")
                    else -> PlayerFailure(FailureKind.DECODE_FAILED, "播放失败，文件可能损坏或编码不受设备支持")
                }
                report(failure)
            }
        })
        scope.launch {
            settings.settings.collect { preferences ->
                val base = preferences.speed
                mutableState.update { it.copy(baseSpeed = base, effectiveSpeed = if (it.boosting) PlaybackRules.temporarySpeed(base, preferences.holdSpeed) else base) }
                surfacePlayer.setPlaybackSpeed(state.value.effectiveSpeed)
                audioEnhance.active = preferences.audioEnhance
                applyClarity(preferences.clarity, preferences.upscale4k, surfacePlayer.videoSize.width, surfacePlayer.videoSize.height)
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
        try {
            when (command) {
                is PlayerCommand.Open -> openMutex.withLock { open(command.id, command.queue, command.positionMs) }
                PlayerCommand.Play -> { if (state.value.video != null) { repository.requirePlayable(state.value.video!!.id); if (surfacePlayer.playbackState == Player.STATE_ENDED) surfacePlayer.seekTo(0); surfacePlayer.play() } }
                PlayerCommand.Pause -> { endBoost(); surfacePlayer.pause(); saveProgress() }
                PlayerCommand.Toggle -> if (surfacePlayer.isPlaying) { endBoost(); surfacePlayer.pause() } else {
                    state.value.video?.let { repository.requirePlayable(it.id); if (surfacePlayer.playbackState == Player.STATE_ENDED) surfacePlayer.seekTo(0); surfacePlayer.play() }
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
                is PlayerCommand.BeginBoost -> if (surfacePlayer.isPlaying) {
                    val value = PlaybackRules.temporarySpeed(state.value.baseSpeed, command.speed)
                    mutableState.update { it.copy(boosting = true, effectiveSpeed = value) }
                    surfacePlayer.setPlaybackSpeed(value)
                }
                PlayerCommand.EndBoost -> endBoost()
                PlayerCommand.Next -> advance(manual = true)
                PlayerCommand.Previous -> {
                    val s = state.value
                    val i = s.queue.indexOf(s.video?.id)
                    s.queue.getOrNull(i - 1)?.let { openMutex.withLock { open(it, s.queue, null) } }
                }
                is PlayerCommand.Enqueue -> {
                    val allowed = repository.videos.first().map { it.id }.toSet()
                    val ids = command.ids.filter { it in allowed }
                    val queue = (state.value.queue + ids).distinct()
                    if (state.value.video == null && queue.isNotEmpty()) openMutex.withLock { open(queue.first(), queue, null) }
                    else mutableState.update { it.copy(queue = queue) }
                }
                is PlayerCommand.SetRepeat -> mutableState.update { it.copy(repeatMode = command.mode) }
                is PlayerCommand.SetLoop -> {
                    val range = command.range
                    if (range != null && !PlaybackRules.validLoop(range, state.value.durationMs)) throw IllegalArgumentException("B 点必须晚于 A 点至少 0.5 秒，且不能超过视频时长")
                    mutableState.update { it.copy(loop = range) }
                    if (range != null) { surfacePlayer.seekTo(range.startMs); surfacePlayer.play() }
                }
                is PlayerCommand.AttachSubtitle -> attachSubtitle(command.attachment)
                is PlayerCommand.SelectSubtitle -> selectSubtitle(command.id)
                PlayerCommand.Close -> close()
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = e as? PlayerFailure ?: PlayerFailure(FailureKind.UNKNOWN, e.message ?: "操作未能完成，请重试")
            mutableEvents.tryEmit(PlayerEvent.Error(failure))
            Result.failure(failure)
        }
    }

    private suspend fun open(id: VideoId, queue: List<VideoId>, start: Long?) {
        val video = repository.requirePlayable(id)
        val visible = repository.videos.first().map { it.id }.toSet()
        val safeQueue = (queue.filter { it in visible } + id).distinct()
        saveProgress()
        endBoost()
        val mode = state.value.repeatMode
        val speed = state.value.baseSpeed
        isClosing = true
        surfacePlayer.stop()
        surfacePlayer.trackSelectionParameters = surfacePlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        cues.value = emptyList()
        mutableState.value = PlaybackState(video, safeQueue, durationMs = video.durationMs, baseSpeed = speed, effectiveSpeed = speed, repeatMode = mode, buffering = true)
        surfacePlayer.setMediaItem(mediaItem(video))
        surfacePlayer.seekTo(start?.let { PlaybackRules.seek(it, video.durationMs) } ?: PlaybackRules.resume(video.positionMs, video.durationMs))
        surfacePlayer.setPlaybackSpeed(speed)
        surfacePlayer.prepare()
        isClosing = false
        surfacePlayer.play()
        mutableEvents.tryEmit(PlayerEvent.Opened(video))
        updatePosition()
        saveProgress()
    }
    private fun mediaItem(video: VideoItem, attachment: SubtitleAttachment? = null): MediaItem {
        val builder = MediaItem.Builder().setMediaId(video.id.value).setUri(video.uri)
        val subtitle = attachment ?: video.subtitleUri?.let {
            if (Uri.parse(it).scheme == "file" && !java.io.File(Uri.parse(it).path ?: "").exists()) null
            else SubtitleAttachment(it, video.subtitleName ?: "外挂字幕", if (it.endsWith(".vtt")) "text/vtt" else "application/x-subrip")
        }
        if (subtitle != null) builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri))
            .setMimeType(subtitle.mimeType).setLabel(subtitle.name).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        return builder.build()
    }
    private suspend fun attachSubtitle(attachment: SubtitleAttachment) {
        val s = state.value
        val video = s.video ?: return
        repository.saveSubtitle(video.id, attachment)
        val position = surfacePlayer.currentPosition
        val playing = surfacePlayer.playWhenReady
        endBoost()
        val updated = video.copy(subtitleUri = attachment.uri, subtitleName = attachment.name)
        mutableState.update { it.copy(video = updated) }
        surfacePlayer.trackSelectionParameters = surfacePlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        surfacePlayer.setMediaItem(mediaItem(updated, attachment), position)
        surfacePlayer.prepare()
        surfacePlayer.playWhenReady = playing
    }
    private fun updateTracks(tracks: Tracks) {
        val list = tracks.groups.mapIndexedNotNull { i, group ->
            if (group.type != C.TRACK_TYPE_TEXT) null else {
                val format = group.getTrackFormat(0)
                SubtitleTrack(i.toString(), format.label ?: format.language?.takeIf { it != "und" } ?: "字幕 ${i + 1}", group.isSelected)
            }
        }
        mutableState.update { it.copy(subtitles = list) }
    }
    private fun selectSubtitle(id: String?) {
        val builder = surfacePlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, id == null)
        if (id != null) {
            val group = surfacePlayer.currentTracks.groups.getOrNull(id.toIntOrNull() ?: -1) ?: return
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        surfacePlayer.trackSelectionParameters = builder.build()
        if (id == null) cues.value = emptyList()
    }
    private suspend fun advance(manual: Boolean = false) {
        val s = state.value
        val current = s.video?.id ?: return
        val mode = if (manual && s.repeatMode == RepeatMode.ONE) RepeatMode.SEQUENCE else s.repeatMode
        val next = PlaybackRules.next(s.queue, current, mode)
        if (next == current) { surfacePlayer.seekTo(0); surfacePlayer.play() }
        else if (next != null) {
            try { openMutex.withLock { open(next, s.queue, 0) } }
            catch (e: Exception) {
                surfacePlayer.pause()
                report(e as? PlayerFailure ?: PlayerFailure(FailureKind.SOURCE_MISSING, "下一条视频不可用，请重新选择"))
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
        val video = state.value.video ?: return
        val position = surfacePlayer.currentPosition.coerceAtLeast(0)
        lastSavedAt = System.currentTimeMillis()
        scope.launch { repository.saveProgress(video.id, position) }
    }
    private fun applyClarity(clarity: Boolean, upscale4k: Boolean, width: Int, height: Int) {
        if (upscale4k && !allow4k) {
            if (!told4kRefuse) {
                told4kRefuse = true
                scope.launch { settings.setUpscale4k(false) }
                mutableEvents.tryEmit(PlayerEvent.Error(PlayerFailure(FailureKind.DECODE_FAILED, "这台设备内存不足，无法放大到 4K，已自动关闭")))
            }
            installClarity(clarity, false, width, height)
            return
        }
        told4kRefuse = false
        installClarity(clarity, upscale4k, width, height)
    }
    private fun pictureEffects(mode: ClarityMode, width: Int, height: Int) = when (mode) {
        ClarityMode.OFF -> emptyList()
        ClarityMode.CONTRAST -> listOf(Contrast(ClarityPolicy.CONTRAST))
        ClarityMode.UPSCALE -> listOf(Presentation.createForHeight(ClarityPolicy.targetHeight(height)), Contrast(ClarityPolicy.CONTRAST))
        ClarityMode.UPSCALE_4K -> listOf(Presentation.createForHeight(ClarityPolicy.targetHeight4k(width, height)), Contrast(ClarityPolicy.CONTRAST))
    }
    private fun installClarity(clarity: Boolean, upscale4k: Boolean, width: Int, height: Int) {
        val mode = ClarityPolicy.choose(clarity, height, lowRam, upscale4k, width, allow4k)
        val key = "$clarity-$upscale4k-$mode-$width-$height"
        if (key == clarityKey) return
        val effects = pictureEffects(mode, width, height)
        if (runCatching { surfacePlayer.setVideoEffects(effects) }.isSuccess) {
            clarityKey = key
            activeClarity = mode
        } else {
            clarityKey = key
            activeClarity = ClarityMode.OFF
            if (mode == ClarityMode.UPSCALE_4K) {
                val fallback = ClarityPolicy.choose(clarity, height, lowRam, false, width, false)
                runCatching { surfacePlayer.setVideoEffects(pictureEffects(fallback, width, height)) }
                scope.launch { settings.setUpscale4k(false) }
                mutableEvents.tryEmit(PlayerEvent.Error(PlayerFailure(FailureKind.DECODE_FAILED, "这台设备无法放大到 4K，已自动关闭")))
            } else if (clarity) {
                runCatching { surfacePlayer.setVideoEffects(emptyList()) }
                scope.launch { settings.setClarity(false) }
                mutableEvents.tryEmit(PlayerEvent.Error(PlayerFailure(FailureKind.DECODE_FAILED, "这台设备开不了清晰度，已自动关闭")))
            }
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
        cues.value = emptyList()
        mutableState.value = PlaybackState(baseSpeed = state.value.baseSpeed, effectiveSpeed = state.value.baseSpeed, repeatMode = state.value.repeatMode)
        isClosing = false
        mutableEvents.tryEmit(PlayerEvent.Closed)
    }
    private fun report(failure: PlayerFailure) {
        mutableState.update { it.copy(failure = failure.message, buffering = false) }
        mutableEvents.tryEmit(PlayerEvent.Error(failure))
    }
}
