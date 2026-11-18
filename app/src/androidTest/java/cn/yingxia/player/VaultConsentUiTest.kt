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
            ui.onNodeWithText("加密并移除原件").performClick()
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
}
