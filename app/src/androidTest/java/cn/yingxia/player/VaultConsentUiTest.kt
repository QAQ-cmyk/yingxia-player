package cn.yingxia.player

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Real Android deletion dialog, using only a uniquely named media fixture created by this test. */
class VaultConsentUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun cancellingSystemDeletionKeepsGalleryOriginalThenRetryRemovesIt() = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val resolver = context.contentResolver
        val graph = (context.applicationContext as YingXiaApplication).graph
        Assume.assumeFalse("Existing user vault must be preserved", graph.vault.store.configured)
        var uri: Uri? = null
        var created = false
        try {
            withContext(Dispatchers.IO) { graph.vault.store.setup("consent test password".toCharArray()) }
            created = true
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put("_display_name", "vault-consent-${UUID.randomUUID()}.mp4")
                put("mime_type", "video/mp4"); put("relative_path", "Movies/YingXiaTest/"); put("is_pending", 1)
            })!!
            instrument.context.assets.open("fixture.mp4").use { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
            val video = graph.repository.importVideo(uri.toString())
            withContext(Dispatchers.Main) { graph.vault.show(listOf(video)); graph.vault.unlock("consent test password".toCharArray()) }
            ui.waitUntil(60000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }
            ui.onNodeWithText(context.getString(R.string.vault_confirm_encrypt_button)).performClick()
            fun clickSystemButton(id: String) {
                ui.waitUntil(30000) {
                    instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)
                        ?.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
            }
            suspend fun unlockIfNeeded() {
                ui.waitForIdle()
                if (!graph.vault.state.value.unlocked) {
                    withContext(Dispatchers.Main) { graph.vault.unlock("consent test password".toCharArray()) }
                }
                ui.waitUntil(60000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }
            }
            clickSystemButton("android:id/button2")
            unlockIfNeeded()
            assertTrue(resolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() })
            assertFalse(graph.vault.state.value.entries.single().sourceRemoved)
            val id = graph.vault.state.value.entries.single().id
            assertTrue(graph.vault.store.payload(id).exists())
            withContext(Dispatchers.Main) { graph.vault.dismissMessage(); graph.vault.retryRemoval(id) }
            clickSystemButton("android:id/button1")
            unlockIfNeeded()
            assertFalse(resolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() })
            assertTrue(graph.vault.state.value.entries.single().sourceRemoved)
            assertNull(graph.repository.get(video.id))
            assertTrue(graph.vault.store.payload(id).exists())
        } finally {
            withContext(Dispatchers.Main) { graph.vault.close() }
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            if (created) graph.vault.store.directory.deleteRecursively()
        }
    }

    @Test fun batchImportUsesSingleDialogDeduplicatesRestoredReimportAndCleansDuplicates() = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val resolver = context.contentResolver
        val graph = (context.applicationContext as YingXiaApplication).graph
        Assume.assumeFalse("Existing user vault must be preserved", graph.vault.store.configured)
        val createdUris = mutableListOf<Uri>()
        var created = false
        fun createMedia(asset: String, displayName: String): Uri {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put("_display_name", displayName)
                put("mime_type", "video/mp4")
                put("relative_path", "Movies/YingXiaTest/")
                put("is_pending", 1)
            })!!
            instrument.context.assets.open(asset).use { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
            createdUris += uri
            return uri
        }
        fun clickSystemButton(id: String) {
            ui.waitUntil(30000) {
                instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)
                    ?.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
        suspend fun unlockIfNeeded() {
            ui.waitForIdle()
            if (!graph.vault.state.value.unlocked) {
                withContext(Dispatchers.Main) { graph.vault.unlock("batch test password".toCharArray()) }
            }
            ui.waitUntil(60000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }
        }
        fun existsInMediaStore(uri: Uri) = resolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } == true
        fun findRestored(name: String): Uri? = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id"),
            "_display_name=? AND relative_path=?",
            arrayOf(name, "Movies/映匣恢复/"),
            "_id DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            else null
        }
        try {
            withContext(Dispatchers.IO) { graph.vault.store.setup("batch test password".toCharArray()) }
            created = true
            val name1 = "vault-batch-a-${UUID.randomUUID()}.mp4"
            val name2 = "vault-batch-b-${UUID.randomUUID()}.mp4"
            val uri1 = createMedia("fixture.mp4", name1)
            val uri2 = createMedia("fixture-subtitled.mp4", name2)
            val v1 = graph.repository.importVideo(uri1.toString())
            val v2 = graph.repository.importVideo(uri2.toString())

            // 1. Batch import 2 distinct videos -> ONE single system deletion dialog removes both.
            withContext(Dispatchers.Main) {
                graph.vault.show(listOf(v1, v2))
                graph.vault.unlock("batch test password".toCharArray())
            }
            ui.waitUntil(60000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }
            ui.onNodeWithText(context.getString(R.string.vault_confirm_encrypt_button)).performClick()
            clickSystemButton("android:id/button1")
            unlockIfNeeded()
            assertEquals(2, graph.vault.state.value.entries.size)
            assertTrue(graph.vault.state.value.entries.all { it.sourceRemoved })
            assertFalse(existsInMediaStore(uri1))
            assertFalse(existsInMediaStore(uri2))

            // 2. Restore entry1 (keep copy), then re-import the restored file -> deduplicated (still 2 entries, NOT 3!).
            val entry1 = graph.vault.state.value.entries.first { it.originalName == name1 }
            val entry2 = graph.vault.state.value.entries.first { it.originalName == name2 }
            withContext(Dispatchers.Main) { graph.vault.dismissMessage(); graph.vault.restore(entry1.id, removeVaultCopy = false) }
            ui.waitUntil(60000) { !graph.vault.state.value.busy && graph.vault.state.value.message != null }
            val restored1 = checkNotNull(findRestored(name1))
            createdUris += restored1
            val reimportItem = graph.repository.importVideo(restored1.toString())
            withContext(Dispatchers.Main) {
                graph.vault.dismissMessage()
                graph.vault.requestImport(listOf(reimportItem))
            }
            ui.onNodeWithText(context.getString(R.string.vault_confirm_encrypt_button)).performClick()
            clickSystemButton("android:id/button1")
            unlockIfNeeded()
            assertEquals(2, graph.vault.state.value.entries.size)
            assertFalse(existsInMediaStore(restored1))

            // 3. Simulate legacy duplicate copy in vault -> deduplicateEntries cleans it while keeping verified copy.
            val dupSource = createMedia("fixture.mp4", "dup-${UUID.randomUUID()}.mp4")
            val dupItem = graph.repository.importVideo(dupSource.toString())
            withContext(Dispatchers.IO) { graph.vault.store.encrypt(dupItem) {} }
            withContext(Dispatchers.Main) { graph.vault.lock(); graph.vault.unlock("batch test password".toCharArray()) }
            ui.waitUntil(60000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }
            assertEquals(3, graph.vault.state.value.entries.size)
            assertEquals(1, graph.vault.state.value.redundantCopies)
            withContext(Dispatchers.Main) { graph.vault.deduplicateEntries() }
            ui.waitUntil(60000) { !graph.vault.state.value.busy && graph.vault.state.value.redundantCopies == 0 }
            assertEquals(2, graph.vault.state.value.entries.size)
            graph.vault.store.verifyPayload(graph.vault.state.value.entries.first { it.id == entry1.id })

            // 4. Restore entry2 with removeVaultCopy = true -> restored to gallery AND removed from vault.
            withContext(Dispatchers.Main) { graph.vault.dismissMessage(); graph.vault.restore(entry2.id, removeVaultCopy = true) }
            ui.waitUntil(60000) { !graph.vault.state.value.busy && graph.vault.state.value.entries.size == 1 }
            val restored2 = checkNotNull(findRestored(name2))
            createdUris += restored2
            assertTrue(existsInMediaStore(restored2))
            assertFalse(graph.vault.store.payload(entry2.id).exists())
        } finally {
            withContext(Dispatchers.Main) { graph.vault.close() }
            createdUris.forEach { runCatching { resolver.delete(it, null, null) } }
            if (created) graph.vault.store.directory.deleteRecursively()
        }
    }
}
