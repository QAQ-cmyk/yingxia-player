package cn.yingxia.player

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.vault.DEFAULT_VAULT_BACKUP_DIR_NAME
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/**
 * Real Android 14 SAF DocumentsUI end-to-end test:
 * Verifies initial picker navigation to Documents/YingXiaVault, exact ciphertext filenames on external
 * storage (no stray .bin/.txt suffixes appended by ExternalStorageProvider), simulated app uninstall,
 * and one-tap picker restore back into an empty vault.
 */
class VaultBackupSafUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun runShell(cmd: String): String {
        val instrument = InstrumentationRegistry.getInstrumentation()
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrument.uiAutomation.executeShellCommand(cmd),
        ).bufferedReader().use { it.readText() }
    }

    private fun clickNodeMatching(timeoutMs: Long = 20_000, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = instrument.uiAutomation.rootInActiveWindow
            if (root != null) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (predicate(node)) {
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let(queue::add)
                    }
                }
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun dumpWindowSummary(): String {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return "<null root>"
        val texts = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && texts.size < 40) {
            val node = queue.removeFirst()
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) texts += "$t(click=${node.isClickable},en=${node.isEnabled})"
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return "pkg=${root.packageName} texts=$texts"
    }

    private fun approveDocumentsUiFolder() {
        // Click "USE THIS FOLDER" / "使用此文件夹" in DocumentsUI, then click "ALLOW" / "允许" in confirmation dialog.
        assertTrue(
            "Expected USE THIS FOLDER button in DocumentsUI; window=${dumpWindowSummary()}",
            clickNodeMatching { node ->
                val text = node.text?.toString()?.trim().orEmpty()
                node.isEnabled && (
                    text.equals("USE THIS FOLDER", ignoreCase = true) ||
                        text.contains("使用此文件夹")
                    )
            },
        )
        assertTrue(
            "Expected ALLOW button in SAF confirmation dialog; window=${dumpWindowSummary()}",
            clickNodeMatching { node ->
                val id = node.viewIdResourceName.orEmpty()
                val text = node.text?.toString()?.trim().orEmpty()
                id == "android:id/button1" ||
                    text.equals("ALLOW", ignoreCase = true) ||
                    text.contains("允许")
            },
        )
    }

    @Test fun realSafDocumentsUiExportSimulateUninstallAndRestore() = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val resolver = context.contentResolver
        val graph = (context.applicationContext as YingXiaApplication).graph
        Assume.assumeFalse("Existing user vault must be preserved", graph.vault.store.configured)

        val backupPath = "/sdcard/Documents/$DEFAULT_VAULT_BACKUP_DIR_NAME"
        val password = "saf-e2e-password-2026"
        var mediaUri: Uri? = null
        var createdVault = false

        try {
            // Prepare clean Documents state and reset DocumentsUI task history.
            runShell("am force-stop com.google.android.documentsui || true")
            runShell("rm -rf $backupPath && mkdir -p $backupPath")

            // 1. Setup vault and encrypt a real fixture video.
            withContext(Dispatchers.IO) { graph.vault.store.setup(password.toCharArray()) }
            createdVault = true
            mediaUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put("_display_name", "saf-backup-${UUID.randomUUID()}.mp4")
                put("mime_type", "video/mp4")
                put("relative_path", "Movies/YingXiaTest/")
                put("is_pending", 1)
            })!!
            instrument.context.assets.open("fixture.mp4").use { input ->
                resolver.openOutputStream(mediaUri)!!.use { input.copyTo(it) }
            }
            resolver.update(mediaUri, ContentValues().apply { put("is_pending", 0) }, null, null)
            val video = graph.repository.importVideo(mediaUri.toString())
            val entry = graph.vault.store.encrypt(video) {}

            // 2. Open vault UI, unlock, and tap "Choose backup folder" -> DocumentsUI opens directly on Documents.
            withContext(Dispatchers.Main) {
                graph.vault.show()
                graph.vault.unlock(password.toCharArray())
            }
            ui.waitUntil(60_000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }
            ui.onNodeWithTag("vault-backup-bind").performClick()

            // Approve Documents directly (SafVaultBackupBackend.forExport automatically creates/reuses YingXiaVault inside it!).
            approveDocumentsUiFolder()

            // Returning from full-screen SAF picker triggers onStop() auto-lock; unlock again and verify pending sync runs automatically!
            ui.waitUntil(30_000) { graph.vault.state.value.backupConfig.configured }
            if (!graph.vault.state.value.unlocked) {
                withContext(Dispatchers.Main) {
                    graph.vault.dismissMessage()
                    graph.vault.unlock(password.toCharArray())
                }
            }
            ui.waitUntil(60_000) {
                graph.vault.state.value.unlocked &&
                    !graph.vault.state.value.busy &&
                    graph.vault.state.value.backupConfig.lastSyncAt > 0L
            }

            // 3. Verify REAL files written on /sdcard/Documents/YingXiaVault via SAF have exact names (no stray .bin/.txt)!
            val diskListing = runShell("ls -a $backupPath").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            assertTrue("Expected keyset.bin on disk, got: $diskListing", "keyset.bin" in diskListing)
            assertTrue("Expected manifest.json on disk, got: $diskListing", "manifest.json" in diskListing)
            assertTrue("Expected .nomedia on disk, got: $diskListing", ".nomedia" in diskListing)
            assertTrue("Expected ${entry.id}.vault on disk, got: $diskListing", "${entry.id}.vault" in diskListing)
            assertTrue("Expected ${entry.id}.meta on disk, got: $diskListing", "${entry.id}.meta" in diskListing)
            assertFalse("Must NOT append stray .bin to .vault", diskListing.any { it.endsWith(".vault.bin") })

            // 4. Simulate complete app uninstall: close vault, clear private vault directory + backup settings!
            withContext(Dispatchers.Main) { graph.vault.close() }
            graph.vault.store.directory.deleteRecursively()
            graph.settings.clearVaultBackupLocation()
            assertFalse(graph.vault.store.configured)

            // 5. Re-open vault UI (empty state) -> click "Open backup folder to restore".
            // Reset DocumentsUI so we genuinely verify EXTRA_INITIAL_URI lands directly on Documents/YingXiaVault!
            runShell("am force-stop com.google.android.documentsui || true")
            withContext(Dispatchers.Main) { graph.vault.show() }
            ui.waitUntil(15_000) { !graph.vault.state.value.configured && graph.vault.state.value.visible }
            ui.onNodeWithTag("vault-backup-restore-pick").performScrollTo().performClick()

            // Direct one-tap approval without browsing!
            approveDocumentsUiFolder()

            // 6. Password prompt appears -> enter original password -> verify full restoration & unlock!
            ui.waitUntil(15_000) { graph.vault.state.value.pendingRestoreUri != null }
            ui.onNodeWithTag("vault-backup-restore-password").performTextInput(password)
            ui.onNodeWithTag("vault-backup-restore-confirm").performClick()

            ui.waitUntil(60_000) {
                graph.vault.state.value.configured &&
                    graph.vault.state.value.unlocked &&
                    !graph.vault.state.value.busy
            }
            assertEquals(1, graph.vault.state.value.entries.size)
            assertEquals(entry.name, graph.vault.state.value.entries.single().name)
            assertTrue(graph.vault.state.value.entries.single().sourceRemoved)
        } finally {
            withContext(Dispatchers.Main) { graph.vault.close() }
            mediaUri?.let { runCatching { resolver.delete(it, null, null) } }
            runCatching { graph.settings.clearVaultBackupLocation() }
            if (createdVault) graph.vault.store.directory.deleteRecursively()
            runShell("rm -rf $backupPath")
        }
    }
}
