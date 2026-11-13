@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.*
import cn.yingxia.player.data.*
import cn.yingxia.player.features.*
import cn.yingxia.player.playback.*
import cn.yingxia.player.ui.PlayerGestureView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlayerIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrument.targetContext
    private lateinit var database: VideoDatabase
    private lateinit var repository: LocalVideoRepository
    private lateinit var player: Media3Controller
    private lateinit var scope: CoroutineScope
    private lateinit var fixture: VideoItem
    private lateinit var second: VideoItem

    @Before fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, VideoDatabase::class.java).build()
        repository = LocalVideoRepository(context, database)
        val settings = SettingsRepository(context)
        settings.setSpeed(1f)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        player = withContext(Dispatchers.Main) { Media3Controller(context, repository, settings, scope) }
        fixture = repository.importVideo(fixtureUri("sample.mp4"))
        second = repository.importVideo(fixtureUri("second.mp4"))
    }
    @After fun cleanup() = runBlocking {
        player.execute(PlayerCommand.Close)
        delay(100)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        withContext(Dispatchers.Main) { player.surfacePlayer.release() }
        database.close()
    }
    private fun fixtureUri(name: String, content: ByteArray? = null): String {
        val file = File(context.filesDir, "test-media/$name")
        file.parentFile!!.mkdirs()
        if (content != null) file.writeBytes(content)
        else if (!file.exists()) instrument.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        return FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", file).toString()
    }
    private suspend fun open() {
        assertTrue(player.execute(PlayerCommand.Open(fixture.id, listOf(fixture.id, second.id))).isSuccess)
        eventually { player.state.value.isPlaying }
    }
    @Test fun relocationPreservesRecordsAndRealDeleteOnlyRemovesSelectedFile() = runBlocking {
        repository.setFavorite(setOf(fixture.id), true)
        repository.saveProgress(fixture.id, 4200)
        repository.saveBookmark(fixture.id, 3000, "移动后保留")
        val destination = fixtureUri("relocated.mp4")
        val moved = repository.relocated(fixture, destination, "学习")
        assertTrue(moved.favorite)
        assertEquals(4200L, moved.positionMs)
        assertEquals(moved.id, repository.bookmarks.first().single().videoId)
        assertFalse(repository.get(fixture.id)!!.accessible)
        assertTrue(repository.requirePlayable(moved.id).favorite)
        val disposable = repository.importVideo(fixtureUri("disposable.mp4"))
        assertEquals(DeleteOutcome.Deleted, LocalFileManager(context).delete(disposable))
        assertFalse(File(context.filesDir, "test-media/disposable.mp4").exists())
        assertTrue(File(context.filesDir, "test-media/sample.mp4").exists())
        assertTrue(File(context.filesDir, "test-media/relocated.mp4").exists())
    }
    @Test fun filesCollectionAliasMergesWithScannedVideoWithoutLosingBookmarks() = runBlocking {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put("_display_name", "yingxia-alias-test.mp4"); put("mime_type", "video/mp4")
            put("relative_path", "Movies/YingXiaTests/")
        }
        val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            instrument.context.assets.open("fixture.mp4").use { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
            val fileUri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Files.getContentUri("external"), android.content.ContentUris.parseId(uri))
            assertTrue(canonicalVideoUri(context, fileUri)!!.path!!.contains("/video/media/"))
            val video = repository.importVideo(fileUri.toString())
            val alias = video.copy(id = VideoId("uri:legacy-test"), favorite = true, positionMs = 4200, lastPlayedAt = 99)
            database.videos().upsert(listOf(alias.entity()))
            repository.saveBookmark(alias.id, 3000, "别名合并")
            repository.refresh()
            assertNull(repository.get(alias.id))
            val merged = repository.get(video.id)!!
            assertTrue(merged.favorite); assertEquals(4200L, merged.positionMs)
            assertEquals(video.id, repository.bookmarks.first().single().videoId)
        } finally { resolver.delete(uri, null, null) }
    }
    private suspend fun eventually(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(50) }

    @Test fun allSpeedsTemporaryBoostCancelAndPausedHold() = runBlocking {
        open()
        for (speed in PlaybackRules.speeds) {
            player.execute(PlayerCommand.SetSpeed(speed)).getOrThrow()
            assertEquals(speed, player.state.value.baseSpeed)
            val actual = withContext(Dispatchers.Main) { player.surfacePlayer.playbackParameters.speed }
            assertEquals(speed, actual)
        }
        player.execute(PlayerCommand.BeginBoost(2f))
        assertEquals(3f, player.state.value.effectiveSpeed)
        player.execute(PlayerCommand.EndBoost)
        player.execute(PlayerCommand.SetSpeed(1.25f))
        player.execute(PlayerCommand.BeginBoost(3f))
        assertEquals(3f, player.state.value.effectiveSpeed)
        player.execute(PlayerCommand.EndBoost)
        assertEquals(1.25f, player.state.value.effectiveSpeed)
        player.execute(PlayerCommand.BeginBoost(2f))
        player.execute(PlayerCommand.Pause)
        assertEquals(1.25f, player.state.value.effectiveSpeed)
        assertFalse(player.state.value.boosting)
        player.execute(PlayerCommand.BeginBoost(3f))
        assertFalse(player.state.value.isPlaying)
        assertFalse(player.state.value.boosting)
        player.execute(PlayerCommand.Play)
        eventually { player.state.value.isPlaying }
        player.execute(PlayerCommand.BeginBoost(3f))
        player.execute(PlayerCommand.Open(second.id, listOf(fixture.id, second.id)))
        assertFalse(player.state.value.boosting)
        assertEquals(1.25f, player.state.value.effectiveSpeed)
    }

    @Test fun hiddenVideoDisappearsFromAllQueriesAndStopsCurrentPlayback() = runBlocking {
        open()
        repository.setFavorite(setOf(fixture.id), true)
        repository.saveBookmark(fixture.id, 4200, "重点片段")
        player.execute(PlayerCommand.Pause)
        player.execute(PlayerCommand.Seek(8000))
        delay(150)
        repository.setHidden(setOf(fixture.id), true)
        eventually { player.state.value.video == null }
        assertFalse(repository.videos.first().any { it.id == fixture.id })
        assertFalse(repository.bookmarks.first().any { it.videoId == fixture.id })
        assertTrue(repository.hiddenVideos.first().any { it.id == fixture.id })
        assertTrue(player.execute(PlayerCommand.Open(fixture.id, listOf(fixture.id))).isFailure)
        assertTrue(runCatching { repository.importVideo(fixture.uri) }.isFailure)
        repository.refresh()
        val restarted = LocalVideoRepository(context, database)
        assertTrue(restarted.hiddenVideos.first().any { it.id == fixture.id })
        repository.setHidden(setOf(fixture.id), false)
        val restored = repository.videos.first().first { it.id == fixture.id }
        assertTrue(restored.favorite)
        assertTrue(restored.positionMs >= 7900)
        assertEquals("重点片段", repository.bookmarks.first().single().note)
    }

    @Test fun queuedHiddenVideoIsRemovedBeforeAutoPlayAndBatchRestoreWorks() = runBlocking {
        open()
        repository.setHidden(setOf(second.id), true)
        eventually { second.id !in player.state.value.queue }
        repository.setHidden(setOf(fixture.id, second.id), true)
        assertEquals(2, repository.hiddenVideos.first().size)
        repository.setHidden(setOf(fixture.id, second.id), false)
        assertEquals(2, repository.videos.first().size)
    }

    @Test fun seekBoundariesPreservePausedStateAndResumeSurvivesReopen() = runBlocking {
        open()
        player.execute(PlayerCommand.Pause)
        player.execute(PlayerCommand.Seek(-500))
        assertEquals(0L, player.state.value.positionMs)
        player.execute(PlayerCommand.Seek(9000))
        assertFalse(player.state.value.isPlaying)
        player.execute(PlayerCommand.Close)
        delay(200)
        player.execute(PlayerCommand.Open(fixture.id, listOf(fixture.id)))
        eventually { player.state.value.isPlaying }
        assertTrue(player.state.value.positionMs in 8900..12_000)
        player.execute(PlayerCommand.Pause)
        player.execute(PlayerCommand.Seek(Long.MAX_VALUE))
        assertTrue(player.state.value.positionMs <= player.state.value.durationMs)
        assertFalse(player.state.value.isPlaying)
    }

    @Test fun abLoopTakesPriorityOverQueueAndSleepStillPauses() = runBlocking {
        open()
        assertTrue(player.execute(PlayerCommand.SetLoop(LoopRange(3000, 3000))).isFailure)
        player.execute(PlayerCommand.SetLoop(LoopRange(1000, 2000))).getOrThrow()
        delay(3200)
        assertEquals(fixture.id, player.state.value.video!!.id)
        assertTrue(player.state.value.positionMs in 900..2300)
        val timer = SleepTimerFeature(player, scope)
        withContext(Dispatchers.Main) { timer.startMilliseconds(400) }
        eventually { timer.remainingMs.value == 0L && !player.state.value.isPlaying }
        assertNotNull(player.state.value.loop)
        assertEquals(0L, timer.remainingMs.value)
        player.execute(PlayerCommand.Seek(5000))
        assertNull(player.state.value.loop)
        player.execute(PlayerCommand.SetLoop(LoopRange(1000, 2000)))
        player.execute(PlayerCommand.Open(second.id, listOf(fixture.id, second.id)))
        assertNull(player.state.value.loop)
    }

    @Test fun chineseSrtVttTrackSwitchOffAndInvalidEncoding() = runBlocking {
        open()
        val provider = LocalSubtitleProvider(context)
        val srt = provider.load(fixtureUri("中文.srt", "1\n00:00:00,000 --> 00:00:29,000\n你好，映匣！\n".toByteArray()))
        player.execute(PlayerCommand.AttachSubtitle(srt)).getOrThrow()
        eventually { player.state.value.subtitles.isNotEmpty() }
        eventually { player.cues.value.any { it.text?.contains("你好") == true } }
        player.execute(PlayerCommand.SelectSubtitle(null))
        eventually { player.cues.value.isEmpty() && player.state.value.subtitles.none { it.selected } }
        player.execute(PlayerCommand.SelectSubtitle(player.state.value.subtitles.first().id))
        eventually { player.state.value.subtitles.any { it.selected } }
        val vtt = provider.load(fixtureUri("中文.vtt", "WEBVTT\n\n00:00:00.000 --> 00:00:29.000\n学习片段\n".toByteArray()))
        player.execute(PlayerCommand.AttachSubtitle(vtt))
        eventually { player.cues.value.any { it.text?.contains("学习") == true } }
        assertTrue(runCatching { provider.load(fixtureUri("坏字幕.srt", byteArrayOf(0xff.toByte(), 0xfe.toByte()))) }.isFailure)
        assertTrue(runCatching { provider.load(fixtureUri("无时间轴.srt", "没有时间轴".toByteArray())) }.isFailure)
    }

    @Test fun bookmarksEditDeleteAndMissingFileRetainsRecords() = runBlocking {
        repository.setFavorite(setOf(second.id), true)
        repository.saveBookmark(second.id, 3000, "原备注")
        val mark = repository.bookmarks.first().single()
        repository.saveBookmark(second.id, mark.positionMs, "修改后", mark.id)
        assertEquals("修改后", repository.bookmarks.first().single().note)
        val file = File(context.filesDir, "test-media/second.mp4")
        assertTrue(file.delete())
        assertTrue(runCatching { repository.requirePlayable(second.id) }.isFailure)
        repository.refresh()
        assertFalse(repository.videos.first().any { it.id == second.id })
        assertTrue(repository.get(second.id)!!.favorite)
        assertEquals(1, database.videos().observeBookmarks().first().size)
        fixtureUri("second.mp4")
        repository.refresh()
        assertEquals("修改后", repository.bookmarks.first().single().note)
        repository.deleteBookmark(mark.id)
        assertTrue(repository.bookmarks.first().isEmpty())
    }

    @Test fun unpluggingAudioPausesAndClearsTemporarySpeed() = runBlocking {
        open()
        player.execute(PlayerCommand.BeginBoost(3f))
        // A protected system broadcast must be sent by the test shell, not the app UID.
        instrument.uiAutomation.executeShellCommand("am broadcast -a ${AudioManager.ACTION_AUDIO_BECOMING_NOISY}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        // Playback and temporary-speed updates arrive asynchronously; await both outcomes.
        eventually { player.state.value.let { !it.isPlaying && !it.boosting } }
        assertFalse(player.state.value.boosting)
        assertEquals(player.state.value.baseSpeed, player.state.value.effectiveSpeed)
    }

    @Test fun transientAudioFocusLossPausesUntilUserResumes() = runBlocking {
        open()
        player.execute(PlayerCommand.BeginBoost(3f))
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            .setOnAudioFocusChangeListener({ }, android.os.Handler(android.os.Looper.getMainLooper())).build()
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(request))
            eventually { !player.state.value.isPlaying }
            assertFalse(player.state.value.boosting)
        } finally { audio.abandonAudioFocusRequest(request) }
        delay(400)
        assertFalse(player.state.value.isPlaying)
        assertEquals(player.state.value.baseSpeed, player.state.value.effectiveSpeed)
    }

    @Test fun embeddedChineseSubtitleCanBeSelectedAndDisabled() = runBlocking {
        val bytes = instrument.context.assets.open("fixture-subtitled.mp4").use { it.readBytes() }
        val item = repository.importVideo(fixtureUri("embedded.mp4", bytes))
        player.execute(PlayerCommand.Open(item.id, listOf(item.id))).getOrThrow()
        eventually { player.state.value.subtitles.isNotEmpty() }
        player.execute(PlayerCommand.SelectSubtitle(player.state.value.subtitles.first().id)).getOrThrow()
        eventually { player.cues.value.any { it.text?.contains("内嵌字幕") == true } }
        player.execute(PlayerCommand.SelectSubtitle(null))
        eventually { player.cues.value.isEmpty() }
    }

    @Test fun largeBatchHideRestoreAndCorruptFileFailure() = runBlocking {
        val rows = (1..1500).map { fixture.copy(id = VideoId("large:$it"), name = "视频 $it.mp4").entity() }
        database.videos().upsert(rows)
        val ids = rows.map { VideoId(it.id) }.toSet()
        repository.setFavorite(ids, true)
        repository.setHidden(ids, true)
        assertEquals(1500, repository.hiddenVideos.first().size)
        assertEquals(2, repository.videos.first().size)
        repository.setHidden(ids, false)
        assertEquals(1500, repository.videos.first().count { it.favorite })
        val failure = runCatching { repository.importVideo(fixtureUri("corrupt.mp4", "bad video".toByteArray())) }.exceptionOrNull()
        assertTrue(failure is PlayerFailure)
        assertEquals(FailureKind.UNSUPPORTED_FORMAT, (failure as PlayerFailure).kind)
    }

    @Test fun gestureCancelNeverSeeksAndDoubleTapDoesNotSingleTap() = runBlocking {
        val commands = mutableListOf<PlayerCommand>()
        var taps = 0
        var preview: Long? = null
        lateinit var view: PlayerGestureView
        withContext(Dispatchers.Main) {
            view = PlayerGestureView(context).apply {
                layout(0, 0, 1000, 600)
                snapshot = { PlaybackState(video = fixture, isPlaying = true, positionMs = 5000, durationMs = 30_000) }
                command = { commands += it }; tap = { taps++ }; this.preview = { preview = it }
            }
        }
        suspend fun touch(action: Int, x: Float, y: Float) = withContext(Dispatchers.Main) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, x, y, 0).let { view.onTouchEvent(it); it.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(MotionEvent.ACTION_MOVE, 600f, 200f)
        assertNotNull(preview)
        touch(MotionEvent.ACTION_CANCEL, 600f, 200f)
        assertNull(preview); assertTrue(commands.isEmpty())
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        delay(500)
        assertTrue(commands.last() is PlayerCommand.BeginBoost)
        touch(MotionEvent.ACTION_CANCEL, 100f, 200f)
        assertEquals(PlayerCommand.EndBoost, commands.last())
        commands.clear()
        touch(MotionEvent.ACTION_DOWN, 100f, 200f); touch(MotionEvent.ACTION_UP, 100f, 200f)
        delay(60)
        touch(MotionEvent.ACTION_DOWN, 100f, 200f); touch(MotionEvent.ACTION_UP, 100f, 200f)
        delay(350)
        assertEquals(listOf(PlayerCommand.Toggle), commands)
        assertEquals(0, taps)
        withContext(Dispatchers.Main) { view.cancelGesture() }
    }
}
