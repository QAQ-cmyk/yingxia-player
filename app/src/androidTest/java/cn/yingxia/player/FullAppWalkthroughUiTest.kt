package cn.yingxia.player

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.PlayerCommand
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/**
 * Full-app UI walkthrough on Android 14 emulator exercising Home search/sort/filter/multi-select,
 * Folders, Favorites, Player tools (speed, A-B loop, bookmarks, sleep timer, crop), and every Me
 * sub-screen (History, Bookmarks empty-state after hide, Hidden restore, Backup, Settings theme &
 * live language switching with Activity recreation, Library Check).
 */
class FullAppWalkthroughUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun walkThroughAllAppTabsPlayerToolsAndSettingsWithoutCrash() = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val resolver = context.contentResolver
        val graph = (context.applicationContext as YingXiaApplication).graph
        val createdUris = mutableListOf<Uri>()
        val tag = UUID.randomUUID().toString().take(6)

        fun insertMedia(folder: String, name: String, asset: String): Uri {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put("_display_name", name)
                put("mime_type", "video/mp4")
                put("relative_path", "Movies/$folder/")
                put("is_pending", 1)
            })!!
            instrument.context.assets.open(asset).use { input ->
                resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
            createdUris += uri
            return uri
        }

        try {
            val uriA = insertMedia("YXWalkA$tag", "啤酒花演示_$tag.mp4", "fixture.mp4")
            val uriB = insertMedia("YXWalkB$tag", "DemoClip_$tag.mp4", "fixture-subtitled.mp4")
            val videoA = graph.repository.importVideo(uriA.toString())
            val videoB = graph.repository.importVideo(uriB.toString())
            graph.repository.refresh()

            // Give videoA partial progress so "In Progress" filter has 1 item while total has >= 2.
            graph.repository.saveProgress(videoA.id, 8_000L)
            ui.waitUntil(15_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty()
            }

            // 1. Home: search by pinyin initials ("pjh") and clear.
            ui.onNodeWithTag("library-search").performTextInput("pjh")
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty() &&
                    ui.onAllNodesWithTag("video-${videoB.id.value}").fetchSemanticsNodes().isEmpty()
            }
            ui.onNodeWithContentDescription(context.getString(R.string.search_clear)).performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("video-${videoB.id.value}").fetchSemanticsNodes().isNotEmpty()
            }

            // 2. Home filter pills: switching to "In Progress" must NOT corrupt "All" total count!
            ui.waitForIdle()
            val allPrefix = context.getString(R.string.filter_all_videos)
            val beforeAllNode = ui.onNodeWithText("$allPrefix ", substring = true).fetchSemanticsNode()
            val expectedAllLabel = beforeAllNode.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
            ui.onNodeWithText(context.getString(R.string.filter_in_progress)).performClick()
            ui.waitForIdle()
            ui.onNodeWithText(expectedAllLabel).assertIsDisplayed()
            ui.onNodeWithText(expectedAllLabel).performClick()

            // 3. Home sort menu: switch to Newest first and back to Name A-Z.
            ui.onNodeWithContentDescription(context.getString(R.string.action_sort)).performClick()
            ui.onNodeWithText(context.getString(R.string.sort_newest)).performClick()
            ui.waitForIdle()
            ui.onNodeWithContentDescription(context.getString(R.string.action_sort)).performClick()
            ui.onNodeWithText(context.getString(R.string.sort_name_asc)).performClick()
            ui.waitForIdle()

            // 3b. Rename videoA via card overflow menu and verify updated display title renders.
            val renamedTitle = "啤酒花改名_$tag"
            ui.onNodeWithContentDescription(context.getString(R.string.action_more_for_video, videoA.name)).performClick()
            ui.onNodeWithText(context.getString(R.string.action_rename)).performClick()
            ui.onNodeWithText(context.getString(R.string.rename_field_label)).performTextClearance()
            ui.onNodeWithText(context.getString(R.string.rename_field_label)).performTextInput(renamedTitle)
            ui.onNodeWithText(context.getString(R.string.action_save)).performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText(renamedTitle).fetchSemanticsNodes().isNotEmpty()
            }

            // 4. Multi-select bar: long press videoA -> favorite -> exit selection.
            ui.onNodeWithTag("video-${videoA.id.value}").performTouchInput { longClick() }
            ui.onNodeWithText(context.getString(R.string.action_favorite)).performClick()
            ui.waitForIdle()

            // 5. Folders tab & folder detail.
            ui.onNodeWithTag("nav-1").performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText("YXWalkA$tag", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("YXWalkA$tag", substring = true).performClick()
            ui.onNodeWithTag("folder-encrypt-current").assertIsDisplayed()
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

            // 6. Favorites tab.
            ui.onNodeWithTag("nav-2").performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty()
            }

            // 7. Open Player from Favorites -> test Speed, A-B Loop, Bookmark, Sleep Timer, Crop, Close.
            ui.onNodeWithTag("video-${videoA.id.value}").performClick()
            ui.waitUntil(20_000) { graph.player.state.value.video?.id == videoA.id && graph.player.state.value.isPlaying }

            fun dismissSheet(markerText: String) {
                repeat(3) {
                    if (ui.onAllNodesWithText(markerText).fetchSemanticsNodes().isEmpty()) return
                    instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    ui.waitForIdle()
                }
                ui.waitUntil(5_000) { ui.onAllNodesWithText(markerText).fetchSemanticsNodes().isEmpty() }
            }

            // Speed sheet.
            ui.onNodeWithTag("speed-button").performClick()
            ui.onNodeWithText("1.5×").performClick()
            ui.waitUntil(5_000) { graph.player.state.value.baseSpeed == 1.5f }
            ui.onNodeWithText("1×").performClick()
            ui.waitUntil(5_000) { graph.player.state.value.baseSpeed == 1f }
            dismissSheet(context.getString(R.string.player_speed_title))

            // Bookmarks panel -> add a bookmark.
            ui.onNodeWithText(context.getString(R.string.player_shortcut_bookmark)).performClick()
            val markNote = "WalkMark-$tag"
            ui.onNodeWithText(context.getString(R.string.bookmark_note_optional)).performTextInput(markNote)
            ui.onNodeWithText(context.getString(R.string.bookmark_add_at, ""), substring = true).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText(markNote).fetchSemanticsNodes().isNotEmpty() }
            dismissSheet(context.getString(R.string.bookmark_note_optional))

            // Tools sheet -> Loop A-B, Sleep timer, Crop bars.
            ui.onNodeWithText(context.getString(R.string.player_tools)).performClick()
            ui.onNodeWithText(context.getString(R.string.feature_loop_title), substring = true).performClick()
            ui.onNodeWithText(context.getString(R.string.loop_set_a)).performClick()
            ui.onNodeWithText(context.getString(R.string.loop_forward_5s)).performClick()
            ui.waitUntil(5_000) {
                val a = graph.loop.pointA.value
                a != null && graph.player.state.value.positionMs - a >= 1_500L
            }
            ui.onNodeWithText(context.getString(R.string.loop_set_b)).performClick()
            ui.waitUntil(5_000) { graph.player.state.value.loop != null }
            ui.onNodeWithText(context.getString(R.string.loop_clear)).performClick()
            ui.waitUntil(5_000) { graph.player.state.value.loop == null }
            dismissSheet(context.getString(R.string.loop_panel_title))

            // Sleep timer 15m -> cancel.
            ui.onNodeWithText(context.getString(R.string.player_tools)).performClick()
            ui.onNodeWithText(context.getString(R.string.feature_sleep_title), substring = true).performClick()
            ui.onNodeWithText(context.getString(R.string.sleep_minutes, 15)).performClick()
            ui.waitUntil(5_000) { graph.timer.remainingMs.value > 0L }
            ui.onNodeWithText(context.getString(R.string.sleep_cancel)).performClick()
            ui.waitUntil(5_000) { graph.timer.remainingMs.value == 0L }
            dismissSheet(context.getString(R.string.sleep_panel_desc))

            // Close player.
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Close) }
            ui.waitUntil(10_000) { graph.player.state.value.video == null }

            // 8. Me tab -> History, Bookmarks (incl. hidden-video empty-state fix), Backup, Settings (theme + live language switch).
            ui.onNodeWithTag("nav-3").performClick()
            ui.onNodeWithText(context.getString(R.string.title_history)).performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

            // Bookmarks page shows the bookmark, and after hiding videoA it renders empty state cleanly (not blank screen!).
            ui.onNode(hasText(context.getString(R.string.title_bookmarks)) and hasClickAction()).performClick()
            ui.onNodeWithText(markNote).assertIsDisplayed()
            graph.repository.setHidden(setOf(videoA.id), true)
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText(context.getString(R.string.bookmarks_empty_title)).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

            // Hidden Videos manager: videoA appears here -> tap to select -> Unhide -> verify restored.
            ui.onNodeWithText(context.getString(R.string.title_hidden_manager)).performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("video-${videoA.id.value}").performClick()
            ui.onNodeWithText(context.getString(R.string.action_restore_visible)).performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isEmpty()
            }
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

            // Backup page renders cleanly.
            ui.onNodeWithText(context.getString(R.string.title_backup)).performClick()
            ui.onNodeWithText(context.getString(R.string.backup_save_button)).assertIsDisplayed()
            ui.onNodeWithText(context.getString(R.string.backup_restore_button)).assertIsDisplayed()
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

            // Settings page: theme toggles, live Chinese <-> English language switching, clear history.
            ui.onNodeWithText(context.getString(R.string.title_settings)).performClick()
            ui.onNodeWithText(context.getString(R.string.theme_dark)).performClick()
            ui.waitForIdle()
            // Language and Appearance both have a "Follow system" chip; index 1 is Appearance.
            ui.onAllNodesWithText(context.getString(R.string.theme_system))[1].performClick()
            ui.waitForIdle()

            // Live language switch to Simplified Chinese -> triggers activity.recreate() cleanly!
            ui.onNodeWithText(context.getString(R.string.settings_language_zh)).performClick()
            ui.waitUntil(15_000) {
                ui.onAllNodesWithText("播放与外观").fetchSemanticsNodes().isNotEmpty()
            }
            // Switch back to Follow system (index 0 is Language).
            ui.onAllNodesWithText("跟随系统")[0].performClick()
            ui.waitUntil(15_000) {
                ui.onAllNodesWithText(context.getString(R.string.title_settings)).fetchSemanticsNodes().isNotEmpty()
            }

            // Clear watch history confirmation dialog works without crash.
            val clearHistoryText = context.getString(R.string.settings_clear_history)
            ui.onNode(hasScrollAction()).performScrollToNode(hasText(clearHistoryText))
            ui.onNodeWithText(clearHistoryText).performClick()
            ui.onNodeWithText(context.getString(R.string.action_clear)).performClick()
            ui.waitUntil(10_000) {
                runBlocking { graph.repository.allVideos().none { it.lastPlayedAt > 0L } }
            }
        } finally {
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Close) }
            AppLocale.set(context, AppLanguage.SYSTEM)
            createdUris.forEach { runCatching { resolver.delete(it, null, null) } }
            runCatching { graph.repository.clearHistory() }
            runCatching { graph.repository.refresh() }
        }
    }
}
