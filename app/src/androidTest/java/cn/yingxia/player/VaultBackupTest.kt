@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.VideoId
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.data.LocalVideoRepository
import cn.yingxia.player.data.VideoDatabase
import cn.yingxia.player.vault.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VaultBackupTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrument.targetContext
    private lateinit var database: VideoDatabase
    private lateinit var repository: LocalVideoRepository
    private val tempDirs = mutableListOf<File>()
    private val tempFiles = mutableListOf<File>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, VideoDatabase::class.java).build()
        repository = LocalVideoRepository(context, database)
    }

    @After fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        database.close()
    }

    private fun isolatedDir(prefix: String): File =
        File(context.noBackupFilesDir, "$prefix-${UUID.randomUUID()}").also {
            it.mkdirs()
            tempDirs += it
        }

    private fun copyAssetFixture(name: String): VideoItem {
        val file = File(context.filesDir, "test-media/$name-${UUID.randomUUID()}.mp4").apply {
            parentFile!!.mkdirs()
            tempFiles += this
        }
        instrument.context.assets.open("fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", file)
        return runBlocking { repository.importVideo(uri.toString()) }
    }

    private fun createSyntheticBinaryVideo(name: String, sizeBytes: Int): Pair<File, VideoItem> {
        val file = File(context.filesDir, "test-media/$name-${UUID.randomUUID()}.mp4").apply {
            parentFile!!.mkdirs()
            tempFiles += this
        }
        val rng = SecureRandom()
        val chunk = ByteArray(256 * 1024)
        var remaining = sizeBytes
        FileOutputStream(file).use { out ->
            while (remaining > 0) {
                val n = minOf(chunk.size, remaining)
                rng.nextBytes(chunk)
                out.write(chunk, 0, n)
                remaining -= n
            }
            out.fd.sync()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", file)
        val item = VideoItem(
            id = VideoId("uri:$uri"),
            uri = uri.toString(),
            name = "$name.mp4",
            folder = "PerfTest",
            folderKey = "perf",
            durationMs = 12_000L,
            sizeBytes = file.length(),
            source = "document",
        )
        return file to item
    }

    @Test fun exportClearSimulateUninstallRestoreAndPlayWithMetadata() = runBlocking {
        val sourceStore = VaultStore(context, isolatedDir("vault-src"))
        val backupDir = isolatedDir("vault-backup-ext")
        val backupBackend = FileVaultBackupBackend(backupDir)
        val password = "strong-backup-password-2026"
        val secretTitle = "TopSecretVacation2026"
        val secretBookmark = "PrivateMomentAt3s"

        try {
            withContext(Dispatchers.IO) { sourceStore.setup(password.toCharArray()) }
            val fixture = copyAssetFixture("secret-clip-name")
            val entry = sourceStore.encrypt(fixture) {}

            // Mutate title, playback progress, and bookmark inside vault, and enroll device-bound recovery questions.
            val vaultRepo = VaultVideoRepository(sourceStore)
            vaultRepo.initialize(sourceStore.entries())
            vaultRepo.rename(VideoId(entry.id), secretTitle)
            vaultRepo.saveProgress(VideoId(entry.id), 4_500L)
            vaultRepo.saveBookmark(VideoId(entry.id), 3_000L, secretBookmark)
            vaultRepo.finish(VideoId(entry.id), 4_500L)

            withContext(Dispatchers.IO) {
                sourceStore.recovery.enroll(
                    password.toCharArray(),
                    listOf("Prompt A", "Prompt B", "Prompt C"),
                    listOf("alpha123".toCharArray(), "bravo456".toCharArray(), "charlie789".toCharArray()),
                    beforeCommit = {},
                )
            }
            assertTrue(sourceStore.recovery.enabled)

            // 1. Export pure ciphertext backup.
            val syncResult = VaultBackupEngine.syncTo(context, sourceStore, backupBackend) {}
            assertEquals(1, syncResult.totalEntries)
            assertTrue(syncResult.written >= 4) // keyset.bin + .meta + .vault + .playback

            // Security checks on exported external folder:
            val exportedFiles = backupDir.listFiles().orEmpty()
            val names = exportedFiles.map { it.name }.toSet()
            assertTrue(".nomedia must be written", ".nomedia" in names)
            assertTrue("manifest.json must be written", "manifest.json" in names)
            assertTrue("keyset.bin must be exported", "keyset.bin" in names)
            assertTrue("${entry.id}.vault must be exported", "${entry.id}.vault" in names)
            assertTrue("${entry.id}.meta must be exported", "${entry.id}.meta" in names)
            assertTrue("${entry.id}.playback must be exported", "${entry.id}.playback" in names)
            assertFalse("Device-bound recovery envelope must NEVER be exported", names.any { it.startsWith("questions-") })

            // Verify zero plaintext leakage across every single exported file.
            exportedFiles.forEach { f ->
                val raw = f.readText(Charsets.ISO_8859_1)
                assertFalse("Exported ${f.name} leaked custom title", raw.contains(secretTitle))
                assertFalse("Exported ${f.name} leaked original filename", raw.contains("secret-clip-name"))
                assertFalse("Exported ${f.name} leaked bookmark note", raw.contains(secretBookmark))
                assertFalse("Exported ${f.name} leaked password", raw.contains(password))
            }
        } finally {
            sourceStore.lock()
            // Simulate complete app uninstall by deleting the entire private vault directory.
            sourceStore.directory.deleteRecursively()
        }

        // 2. Fresh vault directory on "reinstalled app / new phone".
        val freshStore = VaultStore(context, isolatedDir("vault-reinstalled"))
        try {
            assertFalse(freshStore.configured)

            // Wrong password must fail safely without configuring the fresh vault.
            try {
                VaultBackupEngine.restoreIntoEmpty(context, freshStore, backupBackend, "wrong-password-999".toCharArray()) {}
                fail("Wrong password must be rejected")
            } catch (_: Exception) {
                assertFalse("Failed restore must not leave keyset.bin behind", freshStore.configured)
            }

            // Correct password restores all encrypted data and unlocks automatically.
            val restored = VaultBackupEngine.restoreIntoEmpty(context, freshStore, backupBackend, password.toCharArray()) {}
            assertEquals(1, restored.restored)
            assertEquals(0, restored.skippedDuplicates)
            assertFalse(restored.mergedIntoExisting)
            assertTrue(freshStore.configured)

            val entries = freshStore.entries()
            assertEquals(1, entries.size)
            val restoredEntry = entries.single()
            assertEquals(secretTitle, restoredEntry.name)
            assertTrue("Restored entries must detach old gallery removal state", restoredEntry.sourceRemoved)
            assertEquals("", restoredEntry.removalUri)
            assertFalse(restoredEntry.manageOriginalInGallery)

            // Verify playback metadata & bookmarks survived intact.
            val restoredRepo = VaultVideoRepository(freshStore)
            restoredRepo.initialize(entries)
            val restoredVideo = restoredRepo.videos.value.single()
            assertEquals(4_500L, restoredVideo.positionMs)
            assertEquals(1, restoredRepo.bookmarks.value.size)
            assertEquals(secretBookmark, restoredRepo.bookmarks.value.single().note)
            assertEquals(3_000L, restoredRepo.bookmarks.value.single().positionMs)

            // Verify restored video actually plays and seeks through ExoPlayer + VaultDataSource.
            val player = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaSource(
                        ProgressiveMediaSource.Factory { VaultDataSource(freshStore, restoredEntry.id) }
                            .createMediaSource(MediaItem.fromUri("vault://${restoredEntry.id}")),
                    )
                    prepare()
                    playWhenReady = true
                }
            }
            try {
                withTimeout(20_000) { while (!withContext(Dispatchers.Main) { player.isPlaying }) delay(100) }
                withContext(Dispatchers.Main) { player.seekTo(6_000) }
                withTimeout(10_000) { while (!withContext(Dispatchers.Main) { player.currentPosition >= 6_000 && player.isPlaying }) delay(100) }
            } finally {
                withContext(Dispatchers.Main) { player.release() }
            }
        } finally {
            freshStore.lock()
        }
    }

    @Test fun tamperedCiphertextRejectsRestoreWithAndWithoutManifest() = runBlocking {
        val sourceStore = VaultStore(context, isolatedDir("vault-tamper-src"))
        val backupDir = isolatedDir("vault-tamper-ext")
        val backend = FileVaultBackupBackend(backupDir)
        val password = "tamper-test-password-1"
        try {
            withContext(Dispatchers.IO) { sourceStore.setup(password.toCharArray()) }
            val entry = sourceStore.encrypt(copyAssetFixture("tamper-clip")) {}
            VaultBackupEngine.syncTo(context, sourceStore, backend) {}

            // Corrupt 1 byte of the exported ciphertext payload.
            val exportedVault = File(backupDir, "${entry.id}.vault")
            val bytes = exportedVault.readBytes()
            bytes[120] = (bytes[120].toInt() xor 0xFF).toByte()
            exportedVault.writeBytes(bytes)

            val targetWithManifest = VaultStore(context, isolatedDir("vault-tamper-dst1"))
            try {
                VaultBackupEngine.restoreIntoEmpty(context, targetWithManifest, backend, password.toCharArray()) {}
                fail("Tampered ciphertext must fail manifest + AEAD verification")
            } catch (_: Exception) {
                assertFalse(targetWithManifest.configured)
            }

            // Even if manifest.json is deleted, AEAD + SHA-256 verification must still catch tampering.
            assertTrue(File(backupDir, "manifest.json").delete())
            val targetWithoutManifest = VaultStore(context, isolatedDir("vault-tamper-dst2"))
            try {
                VaultBackupEngine.restoreIntoEmpty(context, targetWithoutManifest, backend, password.toCharArray()) {}
                fail("Tampered ciphertext must fail AEAD verification even without manifest")
            } catch (_: Exception) {
                assertFalse(targetWithoutManifest.configured)
            }
        } finally {
            sourceStore.lock()
        }
    }

    @Test fun mergeIntoExistingVaultDeduplicatesAndPreservesExistingPassword() = runBlocking {
        val storeA = VaultStore(context, isolatedDir("vault-merge-a"))
        val storeB = VaultStore(context, isolatedDir("vault-merge-b"))
        val backupA = FileVaultBackupBackend(isolatedDir("vault-merge-bak"))
        val passA = "password-for-vault-A"
        val passB = "password-for-vault-B"
        try {
            withContext(Dispatchers.IO) {
                storeA.setup(passA.toCharArray())
                storeB.setup(passB.toCharArray())
            }
            val sharedFixture = copyAssetFixture("shared-clip")
            val (_, uniqueAItem) = createSyntheticBinaryVideo("unique-from-a", 512 * 1024)
            val (_, uniqueBItem) = createSyntheticBinaryVideo("unique-from-b", 384 * 1024)

            storeA.encrypt(sharedFixture) {}
            val entryUniqueA = storeA.encrypt(uniqueAItem) {}
            val repoA = VaultVideoRepository(storeA)
            repoA.initialize(storeA.entries())
            repoA.rename(VideoId(entryUniqueA.id), "ClipFromDeviceA")
            repoA.saveBookmark(VideoId(entryUniqueA.id), 2_000L, "BookmarkFromA")
            repoA.finish(VideoId(entryUniqueA.id), 2_000L)
            VaultBackupEngine.syncTo(context, storeA, backupA) {}

            storeB.encrypt(sharedFixture) {}
            storeB.encrypt(uniqueBItem) {}

            val merge = VaultBackupEngine.mergeIntoUnlocked(context, storeB, backupA, passA.toCharArray()) {}
            assertTrue(merge.mergedIntoExisting)
            assertEquals(1, merge.restored)
            assertEquals(1, merge.skippedDuplicates)

            val allB = storeB.entries()
            assertEquals(3, allB.size)
            val imported = allB.first { it.name == "ClipFromDeviceA" }
            assertNotEquals(entryUniqueA.id, imported.id)
            storeB.verifyPayload(imported)

            val repoB = VaultVideoRepository(storeB)
            repoB.initialize(allB)
            val importedMarks = repoB.bookmarks.value.filter { it.videoId == VideoId(imported.id) }
            assertEquals(1, importedMarks.size)
            assertEquals("BookmarkFromA", importedMarks.single().note)

            // Current vault password must remain passB (unchanged by merge).
            storeB.lock()
            withContext(Dispatchers.IO) { storeB.unlock(passB.toCharArray()) }
            assertEquals(3, storeB.entries().size)
        } finally {
            storeA.lock()
            storeB.lock()
        }
    }

    @Test fun largePayloadIncrementalBackupAndRestorePerformanceBenchmark() = runBlocking {
        val store = VaultStore(context, isolatedDir("vault-perf-src"))
        val restoredStore = VaultStore(context, isolatedDir("vault-perf-dst"))
        val mergeStore = VaultStore(context, isolatedDir("vault-perf-merge"))
        val backupDir = isolatedDir("vault-perf-ext")
        val backend = FileVaultBackupBackend(backupDir)
        val password = "perf-benchmark-password"

        val largeSize = 32 * 1024 * 1024 // 32 MiB incompressible ciphertext payload
        val deltaSize = 4 * 1024 * 1024  // 4 MiB incremental addition
        val totalMb = (largeSize + deltaSize) / (1024.0 * 1024.0)

        fun usedHeapMb(): Double {
            val rt = Runtime.getRuntime()
            return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
        }

        try {
            withContext(Dispatchers.IO) { store.setup(password.toCharArray()) }
            val (_, largeItem) = createSyntheticBinaryVideo("perf-32mb", largeSize)
            val (_, deltaItem) = createSyntheticBinaryVideo("perf-4mb", deltaSize)

            val encryptStart = SystemClock.elapsedRealtime()
            store.encrypt(largeItem) {}
            val encryptMs = (SystemClock.elapsedRealtime() - encryptStart).coerceAtLeast(1L)

            // 1. First full export of 32 MiB ciphertext (streaming bounded memory).
            System.gc()
            val heapBeforeFull = usedHeapMb()
            val fullStart = SystemClock.elapsedRealtime()
            val fullSync = VaultBackupEngine.syncTo(context, store, backend) {}
            val fullMs = (SystemClock.elapsedRealtime() - fullStart).coerceAtLeast(1L)
            val heapDeltaFull = usedHeapMb() - heapBeforeFull
            val fullThroughput = 32.0 / (fullMs / 1000.0)
            assertEquals(1, fullSync.totalEntries)
            assertEquals(3, fullSync.written) // keyset.bin + .meta + 32MB .vault
            assertEquals(0, fullSync.skipped)
            assertTrue("Streaming export heap delta must stay well below 32 MiB, was $heapDeltaFull MiB", heapDeltaFull < 24.0)

            // 2. Incremental sync after adding 4 MiB video: 32 MiB video must be skipped!
            store.encrypt(deltaItem) {}
            val incStart = SystemClock.elapsedRealtime()
            val incSync = VaultBackupEngine.syncTo(context, store, backend) {}
            val incMs = (SystemClock.elapsedRealtime() - incStart).coerceAtLeast(1L)
            assertEquals(2, incSync.totalEntries)
            assertEquals(2, incSync.written) // Only new .meta + new 4MB .vault
            assertEquals(3, incSync.skipped) // keyset.bin + old .meta + old 32MB .vault skipped!

            // 3. No-op sync when nothing changed must write 0 files.
            val noopStart = SystemClock.elapsedRealtime()
            val noopSync = VaultBackupEngine.syncTo(context, store, backend) {}
            val noopMs = (SystemClock.elapsedRealtime() - noopStart).coerceAtLeast(1L)
            assertEquals(0, noopSync.written)
            assertEquals(5, noopSync.skipped)

            // 4. Full restore of 36 MiB into fresh empty vault (zero re-encryption, full SHA-256 verification).
            System.gc()
            val heapBeforeRestore = usedHeapMb()
            val restoreStart = SystemClock.elapsedRealtime()
            val restored = VaultBackupEngine.restoreIntoEmpty(context, restoredStore, backend, password.toCharArray()) {}
            val restoreMs = (SystemClock.elapsedRealtime() - restoreStart).coerceAtLeast(1L)
            val heapDeltaRestore = usedHeapMb() - heapBeforeRestore
            val restoreThroughput = totalMb / (restoreMs / 1000.0)
            assertEquals(2, restored.restored)
            assertEquals(2, restoredStore.entries().size)
            assertTrue("Streaming restore heap delta must stay well below 36 MiB, was $heapDeltaRestore MiB", heapDeltaRestore < 28.0)

            // 5. Merge 36 MiB into another unlocked vault, then verify second merge skips all duplicates instantly.
            withContext(Dispatchers.IO) { mergeStore.setup("other-device-password".toCharArray()) }
            val mergeStart = SystemClock.elapsedRealtime()
            val merged = VaultBackupEngine.mergeIntoUnlocked(context, mergeStore, backend, password.toCharArray()) {}
            val mergeMs = (SystemClock.elapsedRealtime() - mergeStart).coerceAtLeast(1L)
            assertEquals(2, merged.restored)
            assertEquals(0, merged.skippedDuplicates)

            val dedupStart = SystemClock.elapsedRealtime()
            val mergedAgain = VaultBackupEngine.mergeIntoUnlocked(context, mergeStore, backend, password.toCharArray()) {}
            val dedupMs = (SystemClock.elapsedRealtime() - dedupStart).coerceAtLeast(1L)
            assertEquals(0, mergedAgain.restored)
            assertEquals(2, mergedAgain.skippedDuplicates)

            Log.i(
                "VaultBackupPerf",
                "PERF_RESULT encrypt32MB=${encryptMs}ms fullExport32MB=${fullMs}ms (${"%.1f".format(fullThroughput)}MB/s, heapDelta=${"%.1f".format(heapDeltaFull)}MB) " +
                    "incremental4MB=${incMs}ms (written=${incSync.written}, skipped=${incSync.skipped}) noopSync=${noopMs}ms " +
                    "restore36MB=${restoreMs}ms (${"%.1f".format(restoreThroughput)}MB/s, heapDelta=${"%.1f".format(heapDeltaRestore)}MB) " +
                    "merge36MB=${mergeMs}ms dedupMerge=${dedupMs}ms",
            )
            // Sanity performance bounds on emulated storage:
            assertTrue("Incremental 4MB sync (${incMs}ms) should complete quickly (< 15s)", incMs < 15_000L)
            assertTrue("Duplicate re-merge (${dedupMs}ms) should be fast (< 5s)", dedupMs < 5_000L)
        } finally {
            store.lock()
            restoredStore.lock()
            mergeStore.lock()
        }
    }

    @Test fun defaultPickerUrisTargetDocumentsYingXiaVault() {
        val backupUri = VaultBackupLocations.initialBackupPickerUri()
        val restoreUri = VaultBackupLocations.initialRestorePickerUri()
        assertEquals("com.android.externalstorage.documents", backupUri.authority)
        assertEquals("com.android.externalstorage.documents", restoreUri.authority)
        assertTrue(backupUri.toString().contains("primary%3ADocuments"))
        assertTrue(restoreUri.toString().contains("primary%3ADocuments%2FYingXiaVault"))
    }
}
