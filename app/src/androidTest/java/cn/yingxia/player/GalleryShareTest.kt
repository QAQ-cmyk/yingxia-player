package cn.yingxia.player

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.PlayerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class GalleryShareTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val graph get() = (context.applicationContext as YingXiaApplication).graph
    private val resolver get() = context.contentResolver

    private fun fixture(): Uri {
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "share-${UUID.randomUUID()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }))
        instrument.context.assets.open("fixture.mp4").use { input -> resolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) } }
        return uri
    }

    private fun send(intent: Intent) {
        intent.setPackage(context.packageName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ui.activityRule.scenario.onActivity { it.startActivity(intent) }
    }

    @Test fun validatesPayloadAndDeduplicatesAcrossExtrasAndClip() {
        val video = Uri.parse("content://gallery/video/1")
        val base = Intent(Intent.ACTION_SEND).setType("video/mp4")
        base.clipData = ClipData.newRawUri("video", video)
        assertEquals(listOf(video), base.galleryShare(context.packageName)!!.uris)
        base.putExtra(Intent.EXTRA_STREAM, video)
        assertEquals(listOf(video), base.galleryShare(context.packageName)!!.uris)
        for (uri in listOf(
            "https://example.org/a.mp4",
            "file:///tmp/a.mp4",
            "content://${context.packageName}.files/backups/test",
            "content://0@${context.packageName}.files/backups/test",
            "content://evil@media/external/video/media/1",
        )) {
            assertNotNull(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).galleryShare(context.packageName)!!.error)
        }
        val userPrefixed = Uri.parse("content://0@media/external/video/media/1")
        assertEquals(listOf(userPrefixed), Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, userPrefixed).galleryShare(context.packageName)!!.uris)
        assertEquals(listOf(userPrefixed), Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, userPrefixed).galleryShare(context.packageName)!!.uris)
        val oemAliasSend = Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.miui.gallery.open/raw/1.mp4"))
        oemAliasSend.clipData = ClipData.newRawUri("video", userPrefixed)
        assertEquals(listOf(Uri.parse("content://com.miui.gallery.open/raw/1.mp4")), oemAliasSend.galleryShare(context.packageName)!!.uris)
        assertNotNull(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, video).galleryShare(context.packageName)!!.error)
        assertNull(base.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY).galleryShare(context.packageName))
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun singleShareCanCancelPlayWithoutHistoryAndRejectHidden() = runBlocking {
        val uri = fixture()
        try {
            val intent = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            send(Intent(intent))
            ui.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
            assertNull(graph.player.state.value.video)
            send(Intent(intent))
            ui.onNodeWithText(context.getString(R.string.action_watch_without_history)).assertExists()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText(context.getString(R.string.action_watch_without_history)).performClick()
            ui.waitUntil(15000) { graph.player.state.value.isPlaying }
            val video = graph.player.state.value.video!!
            assertFalse(graph.player.state.value.recordHistory)
            assertEquals("share", video.source)
            graph.player.execute(PlayerCommand.Pause).getOrThrow()
            graph.player.execute(PlayerCommand.Play).getOrThrow()
            graph.player.execute(PlayerCommand.Close).getOrThrow()
            val row = graph.repository.get(video.id)
            assertTrue(row == null || row.source == "media-store" && row.lastPlayedAt == 0L && row.positionMs == 0L)

            val imported = graph.repository.importVideo(uri.toString())
            try {
                graph.repository.setHidden(setOf(imported.id), true)
                send(Intent(intent))
                ui.onNodeWithText(context.getString(R.string.action_watch_without_history)).performClick()
                val hiddenMsg = context.getString(R.string.media_error_hidden_import)
                ui.waitUntil(8000) { ui.onAllNodesWithText(hiddenMsg, substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertNull(graph.player.state.value.video)
            } finally { graph.repository.forgetEncryptedSource(imported) }
        } finally {
            graph.player.execute(PlayerCommand.Close)
            resolver.delete(uri, null, null)
        }
        Unit
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun batchRequiresUnlockAndConfirmationAndKeepsBothOriginals() = runBlocking {
        val vault = graph.vault
        assumeFalse("Preserve an existing vault", vault.store.configured)
        val uris = arrayListOf(fixture(), fixture())
        try {
            withContext(Dispatchers.IO) { vault.store.setup("gallery test password".toCharArray()); vault.store.lock() }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.clipData = ClipData.newRawUri("video", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
            send(intent)
            ui.onNodeWithText(context.getString(R.string.share_selected_count, 2)).assertExists()
            ui.onNodeWithText(context.getString(R.string.action_watch_without_history)).assertDoesNotExist()
            ui.onNodeWithText(context.getString(R.string.share_action_vault)).performClick()
            ui.waitUntil(12000) { ui.onAllNodesWithTag("vault-password").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("vault-password").performTextInput("gallery test password")
            ui.onNodeWithTag("vault-unlock").performScrollTo().performClick()
            val saveCopiesText = context.getString(R.string.vault_confirm_share_button)
            ui.waitUntil(20000) { ui.onAllNodesWithText(saveCopiesText).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(vault.state.value.entries.isEmpty())
            ui.onNodeWithText(saveCopiesText).performClick()
            ui.waitUntil(20000) { vault.state.value.entries.size == 2 && !vault.state.value.busy }
            assertTrue(vault.state.value.entries.none { it.sourceRemoved })
            vault.state.value.entries.forEach { entry -> vault.store.verifyBeforeRemoval(entry) }
            uris.forEach { uri -> assertTrue(resolver.openInputStream(uri)!!.use { it.read() >= 0 }) }
            val entry = vault.state.value.entries.first()
            withContext(Dispatchers.Main) { vault.play(entry) }
            ui.waitUntil(15000) { vault.playback?.player?.state?.value?.isPlaying == true }
        } finally {
            withContext(Dispatchers.Main) { vault.close() }
            vault.store.directory.deleteRecursively()
            uris.forEach { resolver.delete(it, null, null) }
        }
        Unit
    }
}
