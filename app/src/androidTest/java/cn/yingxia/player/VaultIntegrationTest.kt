@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.*
import cn.yingxia.player.data.*
import cn.yingxia.player.vault.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VaultIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrument.targetContext
    private lateinit var store: VaultStore
    private lateinit var database: VideoDatabase
    private lateinit var repository: LocalVideoRepository
    private lateinit var fixture: VideoItem
    private lateinit var fixtureFile: File
    private val createdUris = mutableListOf<Uri>()
    private val createdFiles = mutableListOf<File>()

    @Before fun prepare() = runBlocking {
        if (Build.VERSION.SDK_INT <= 28) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).forEach {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("pm grant ${context.packageName} $it")).use { input -> input.readBytes() }
            }
        }
        database = Room.inMemoryDatabaseBuilder(context, VideoDatabase::class.java).build()
        repository = LocalVideoRepository(context, database)
        store = VaultStore(context, File(context.noBackupFilesDir, "vault-test-${UUID.randomUUID()}"))
        withContext(Dispatchers.IO) { store.setup("test vault password 123".toCharArray()) }
        fixtureFile = File(context.filesDir, "test-media/vault-${UUID.randomUUID()}.mp4").apply { parentFile!!.mkdirs() }
        instrument.context.assets.open("fixture.mp4").use { input -> fixtureFile.outputStream().use { input.copyTo(it) } }
        fixture = repository.importVideo(FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", fixtureFile).toString())
    }
    @After fun cleanup() {
        store.lock()
        createdUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        createdFiles.forEach { it.delete() }
        fixtureFile.delete()
        store.directory.deleteRecursively() // Isolated test-only directory, never the user's vault.
        database.close()
    }
    @Test fun verifiedEncryptionRemovalAndReopenKeepNamesPrivate() = runBlocking {
        repository.setFavorite(setOf(fixture.id), true)
        repository.saveProgress(fixture.id, 1234)
        repository.saveBookmark(fixture.id, 1000, "private note")
        val entry = store.encrypt(fixture) {}
        assertTrue(fixtureFile.exists()) // Encryption alone is not permission to remove a gallery source.
        assertFalse(entry.sourceRemoved)
        assertFalse(store.payload(entry.id).readBytes().contentEquals(fixtureFile.readBytes()))
        val metadata = File(store.directory, "${entry.id}.meta").readText(Charsets.ISO_8859_1)
        assertFalse(metadata.contains(fixture.name)); assertFalse(metadata.contains(fixture.uri))
        store.verifyBeforeRemoval(entry)
        assertEquals(DeleteOutcome.Deleted, LocalFileManager(context).delete(fixture))
        repository.forgetEncryptedSource(fixture)
        store.save(entry.copy(sourceRemoved = true))
        assertFalse(fixtureFile.exists()); assertNull(repository.get(fixture.id))
        assertTrue(repository.allVideos().isEmpty())
        store.lock()
        withContext(Dispatchers.IO) { store.unlock("test vault password 123".toCharArray()) }
        assertTrue(store.entries().single().sourceRemoved)
        assertEquals(fixture.name, store.entries().single().originalName)
    }
    @Test fun changedOriginalOrDamagedCiphertextNeverPassRemovalCheck() = runBlocking {
        val entry = store.encrypt(fixture) {}
        val original = fixtureFile.readBytes()
        fixtureFile.appendBytes(byteArrayOf(10))
        try { store.verifyBeforeRemoval(entry); fail("Changed original must be retained") } catch (_: IllegalArgumentException) { }
        assertTrue(fixtureFile.exists())
        fixtureFile.writeBytes(original)
        val cipher = store.payload(entry.id).readBytes()
        cipher[150] = (cipher[150].toInt() xor 1).toByte(); store.payload(entry.id).writeBytes(cipher)
        try { store.verifyBeforeRemoval(entry); fail("Damaged ciphertext must not permit deletion") } catch (_: java.io.IOException) { }
        assertArrayEquals(original, fixtureFile.readBytes())
    }
    @Test fun randomAccessPlayerSeeksAndLockClosesStream() = runBlocking {
        val entry = store.encrypt(fixture) {}
        val source = VaultDataSource(store, entry.id)
        val position = 130L
        source.open(DataSpec.Builder().setUri("vault://${entry.id}").setPosition(position).setLength(200).build())
        val actual = ByteArray(200); var read = 0
        while (read < actual.size) read += source.read(actual, read, actual.size - read)
        assertArrayEquals(fixtureFile.readBytes().copyOfRange(130, 330), actual)
        source.close()
        val player = withContext(Dispatchers.Main) {
            ExoPlayer.Builder(context).build().apply {
                setMediaSource(ProgressiveMediaSource.Factory { VaultDataSource(store, entry.id) }.createMediaSource(MediaItem.fromUri("vault://${entry.id}")))
                prepare(); playWhenReady = true
            }
        }
        try {
            withTimeout(20000) { while (!withContext(Dispatchers.Main) { player.isPlaying }) delay(100) }
            withContext(Dispatchers.Main) { player.seekTo(6000) }
            withTimeout(10000) { while (!withContext(Dispatchers.Main) { player.currentPosition >= 6000 && player.isPlaying }) delay(100) }
            source.open(DataSpec(Uri.parse("vault://${entry.id}")))
            store.lock()
            try { source.read(ByteArray(32), 0, 32); fail("Locked stream must reject reads") } catch (_: java.io.IOException) { }
        } finally { source.close(); withContext(Dispatchers.Main) { player.release() } }
    }
    @Test fun restorePublishesVerifiedCopyAndKeepsEncryptedOriginal() = runBlocking {
        val entry = store.encrypt(fixture) {}
        val original = fixtureFile.readBytes()
        if (Build.VERSION.SDK_INT < 29) {
            val target = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "映匣恢复/${fixture.name}")
            createdFiles += target
            store.restore(entry) {}
            assertArrayEquals(original, target.readBytes())
            assertTrue(store.payload(entry.id).exists())
            assertTrue(store.load(entry.id).restoredAt > 0)
            return@runBlocking
        }
        store.restore(entry) {}
        val resolver = context.contentResolver
        val restored = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id", "is_pending"), "_display_name=? AND relative_path=?", arrayOf(fixture.name, "Movies/映匣恢复/"), null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(1))
            android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        createdUris += restored
        assertArrayEquals(original, resolver.openInputStream(restored)!!.use { it.readBytes() })
        assertTrue(store.payload(entry.id).exists())
        assertTrue(store.load(entry.id).restoredAt > 0)
        assertEquals("", store.load(entry.id).restorePending)
    }
    @Test fun interruptedRestoreRecoveryOnlyDeletesUnpublishedOutput() = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val entry = store.encrypt(fixture) {}
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put("_display_name", "pending-${entry.id}.mp4"); put("mime_type", "video/mp4")
            put("relative_path", "Movies/映匣恢复/"); put("is_pending", 1)
        })!!
        createdUris += uri
        store.save(entry.copy(restorePending = uri.toString()))
        store.recoverRestore(store.load(entry.id))
        assertFalse(context.contentResolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() })
        assertTrue(store.payload(entry.id).exists()); assertTrue(fixtureFile.exists())
    }
    @Test fun damagedMetadataDoesNotHideOtherVideosOrDeleteFiles() = runBlocking {
        val first = store.encrypt(fixture) {}
        val second = store.encrypt(fixture) {}
        val meta = File(store.directory, "${first.id}.meta")
        val corrupt = meta.readBytes().apply { this[30] = (this[30].toInt() xor 1).toByte() }
        meta.writeBytes(corrupt)
        val inventory = store.inventory()
        assertEquals(1, inventory.damagedRecords)
        assertEquals(second.id, inventory.entries.single().id)
        assertTrue(store.payload(first.id).exists()); assertTrue(meta.exists()); assertTrue(fixtureFile.exists())
    }
    @Test fun legacyPublishedRestoreSurvivesInterruptedJournalCommit() = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT < 29)
        val entry = store.encrypt(fixture) {}
        val root = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "映匣恢复").apply { mkdirs() }
        val target = File(root, fixture.name)
        createdFiles += target
        fixtureFile.copyTo(target)
        val pending = File(root, ".pending/${entry.id}-${UUID.randomUUID()}.part")
        store.save(entry.copy(restorePending = Uri.fromFile(pending).toString(), restoreTarget = target.absolutePath))
        val recovered = store.recoverRestore(store.load(entry.id))
        assertTrue(recovered.restoredAt > 0); assertEquals("", recovered.restorePending)
        assertArrayEquals(fixtureFile.readBytes(), target.readBytes())
        assertTrue(store.payload(entry.id).exists())
    }
    @Test fun privateRecordsAreEncryptedReopenableAndAbsentFromOrdinaryLibrary() = runBlocking {
        val entry = store.encrypt(fixture) {}
        val id = VideoId(entry.id)
        val records = VaultVideoRepository(store)
        records.initialize(listOf(entry)) // 1.6.0 vaults have no playback file.
        assertEquals(0L, records.get(id)!!.positionMs)
        records.setFavorite(setOf(id), true)
        records.rename(id, "仅保险箱内的私密标题")
        records.savePicture(id, true)
        records.saveBookmark(id, 2000, "不能进入普通历史的私密备注")
        val mark = records.bookmarks.value.single()
        records.saveBookmark(id, 2500, "更新后的私密备注", mark.id)
        records.finish(id, 4200)
        assertTrue(records.videos.value.isEmpty()); assertTrue(records.bookmarks.value.isEmpty())
        val cipher = File(store.directory, "${entry.id}.playback")
        val encrypted = cipher.readBytes()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("私密"))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("position"))
        assertNull(repository.get(id))
        assertFalse(repository.allVideos().any { it.id == id })
        store.lock()
        try { records.saveProgress(id, 9000); fail("Closed records must reject late writes") } catch (_: IllegalStateException) { }
        withContext(Dispatchers.IO) { store.unlock("test vault password 123".toCharArray()) }
        val reopened = VaultVideoRepository(store)
        reopened.initialize(store.entries())
        assertEquals(4200L, reopened.get(id)!!.positionMs)
        assertTrue(reopened.get(id)!!.favorite); assertTrue(reopened.get(id)!!.cropBars)
        assertEquals("仅保险箱内的私密标题", reopened.get(id)!!.displayName)
        assertEquals("更新后的私密备注", reopened.bookmarks.value.single().note)
        assertEquals(2500L, reopened.bookmarks.value.single().positionMs)
        reopened.deleteBookmark(mark.id)
        assertTrue(reopened.bookmarks.value.isEmpty())
        reopened.finish(null, 0)
        store.remove(entry.id)
        assertFalse(cipher.exists()); assertFalse(store.payload(entry.id).exists())
        assertTrue(fixtureFile.exists())
    }

    @Test fun corruptOrSwappedPrivateRecordsArePreservedAndNeverOverwritten() = runBlocking {
        val first = store.encrypt(fixture) {}
        val second = store.encrypt(fixture) {}
        val records = VaultVideoRepository(store)
        records.initialize(listOf(first, second))
        records.saveProgress(VideoId(first.id), 1234)
        records.finish(null, 0)
        val original = File(store.directory, "${first.id}.playback").readBytes()
        // A valid ciphertext for another video must fail AAD authentication.
        val swapped = File(store.directory, "${second.id}.playback").apply { writeBytes(original) }
        val damaged = VaultVideoRepository(store)
        damaged.initialize(store.entries())
        assertEquals(1234L, damaged.requirePlayable(VideoId(first.id)).positionMs)
        try { damaged.requirePlayable(VideoId(second.id)); fail("Swapped records must be rejected") } catch (_: IllegalStateException) { }
        try { damaged.saveProgress(VideoId(second.id), 5000); fail("Must preserve damaged record") } catch (_: IllegalStateException) { }
        assertArrayEquals(original, swapped.readBytes())
        assertTrue(store.payload(second.id).exists())
        damaged.finish(null, 0)
    }

    @Test fun encryptedPlayerSupportsSpeedQueueLoopTimerAndFlushOnClose() = runBlocking {
        val first = store.encrypt(fixture) {}
        val second = store.encrypt(fixture) {}
        val records = VaultVideoRepository(store)
        records.initialize(listOf(first, second))
        val settings = SettingsRepository(context)
        val old = settings.settings.first()
        val session = withContext(Dispatchers.Main) { VaultPlaybackSession(context, store, records, settings, {}, {}) }
        val player = session.player
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(50) }
        try {
            session.open(first.id)
            waitFor { player.state.value.isPlaying }
            for (speed in PlaybackRules.speeds) {
                player.execute(PlayerCommand.SetSpeed(speed)).getOrThrow()
                assertEquals(speed, player.state.value.baseSpeed)
            }
            player.execute(PlayerCommand.BeginBoost(2f)); assertEquals(3f, player.state.value.effectiveSpeed)
            player.execute(PlayerCommand.SetSpeed(1.25f))
            player.execute(PlayerCommand.BeginBoost(3f)); assertTrue(player.state.value.boosting)
            player.execute(PlayerCommand.EndBoost); assertEquals(1.25f, player.state.value.effectiveSpeed)
            player.execute(PlayerCommand.Pause)
            player.execute(PlayerCommand.BeginBoost(3f)); assertFalse(player.state.value.boosting)
            player.execute(PlayerCommand.Seek(4000)); assertFalse(player.state.value.isPlaying)
            assertTrue(player.execute(PlayerCommand.SetLoop(LoopRange(2000, 2000))).isFailure)
            player.execute(PlayerCommand.SetSpeed(1f))
            player.execute(PlayerCommand.SetLoop(LoopRange(1000, 2000))).getOrThrow()
            delay(2500)
            assertEquals(VideoId(first.id), player.state.value.video!!.id)
            assertTrue(player.state.value.positionMs in 900..2300)
            withContext(Dispatchers.Main) { session.timer.startMilliseconds(400) }
            waitFor { session.timer.remainingMs.value == 0L && !player.state.value.isPlaying }
            assertNotNull(player.state.value.loop)
            player.execute(PlayerCommand.Next).getOrThrow()
            assertEquals(VideoId(second.id), player.state.value.video!!.id)
            assertNull(player.state.value.loop)
            player.execute(PlayerCommand.SetRepeat(RepeatMode.ALL))
            player.execute(PlayerCommand.Next).getOrThrow()
            assertEquals(VideoId(first.id), player.state.value.video!!.id)
            player.execute(PlayerCommand.Pause)
            player.execute(PlayerCommand.Seek(4500))
            withContext(Dispatchers.Main) { session.close() }
            assertNull(player.state.value.video)
            assertTrue(records.videos.value.isEmpty())
            val reopened = VaultVideoRepository(store)
            reopened.initialize(store.entries())
            assertEquals(4500L, reopened.get(VideoId(first.id))!!.positionMs)
            reopened.finish(null, 0)
        } finally { withContext(Dispatchers.Main) { session.close() }; settings.replacePlayback(old) }
    }

    @Test fun installedAppHasNoNetworkPermissionAndDisablesBackup() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in permissions)
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(store.directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
    }

}
