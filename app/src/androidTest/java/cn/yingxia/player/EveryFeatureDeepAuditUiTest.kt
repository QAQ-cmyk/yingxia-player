package cn.yingxia.player

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.PlayerCommand
import cn.yingxia.player.core.RepeatMode
import cn.yingxia.player.core.SortOrder
import cn.yingxia.player.core.ThemeMode
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.security.KeyStore
import java.util.UUID

/**
 * Exhaustive deep audit exercising every interactive feature, dialog, orientation switch,
 * screen-lock guard, history ordering, watch-without-history, continue-watching, bookmarks CRUD,
 * scan delete-pruning, settings controls, and encrypted vault picker/recovery/playback UI flows.
 */
class EveryFeatureDeepAuditUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun libraryPlayerOrientationHistoryBookmarksAndSettingsDeepAudit(): Unit = runBlocking {
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

        fun dismissSheet(markerText: String) {
            repeat(3) {
                if (ui.onAllNodesWithText(markerText).fetchSemanticsNodes().isEmpty()) return
                instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                ui.waitForIdle()
            }
            ui.waitUntil(5_000) { ui.onAllNodesWithText(markerText).fetchSemanticsNodes().isEmpty() }
        }

        try {
            graph.repository.clearHistory()
            graph.settings.setSort(SortOrder.NEWEST)
            graph.settings.setSpeed(1f)
            graph.settings.setHoldSpeed(2f)
            graph.settings.setTouchGuard(false)
            graph.settings.setTheme(ThemeMode.SYSTEM)

            val uriA = insertMedia("YXDeepA$tag", "A_Alpha_$tag.mp4", "fixture.mp4")
            val uriB = insertMedia("YXDeepB$tag", "Z_Zulu_$tag.mp4", "fixture-subtitled.mp4")
            val videoA = graph.repository.importVideo(uriA.toString())
            val videoB = graph.repository.importVideo(uriB.toString())
            graph.repository.refresh()

            ui.waitUntil(15_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty() &&
                    ui.onAllNodesWithTag("video-${videoB.id.value}").fetchSemanticsNodes().isNotEmpty()
            }

            // 1. "In progress" empty state when no videos are in progress (Bug 12 regression check).
            ui.onNodeWithText(context.getString(R.string.filter_in_progress)).performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(context.getString(R.string.empty_in_progress_title)).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(context.getString(R.string.filter_all_videos), substring = true).performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty()
            }

            // 2. Cycle through ALL 8 Sort orders cleanly without crash.
            val sortStrings = listOf(
                R.string.sort_oldest,
                R.string.sort_recent_play,
                R.string.sort_name_asc,
                R.string.sort_name_desc,
                R.string.sort_size_desc,
                R.string.sort_size_asc,
                R.string.sort_duration,
                R.string.sort_name_asc, // Leave Home sorted A-Z for History recency test later.
            )
            sortStrings.forEach { res ->
                ui.onNodeWithContentDescription(context.getString(R.string.action_sort)).performClick()
                ui.onAllNodesWithText(context.getString(res)).onLast().performClick()
                ui.waitForIdle()
            }

            // 3. System Back inside Search clears query & restores nav bar instead of exiting app (Bug 14 & Bug 18).
            ui.onNodeWithTag("library-search").performTextInput("Alpha_$tag")
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("video-${videoB.id.value}").fetchSemanticsNodes().isEmpty()
            }
            repeat(3) {
                if (ui.onAllNodesWithTag("video-${videoB.id.value}").fetchSemanticsNodes().isEmpty()) {
                    instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    delay(350)
                    ui.waitForIdle()
                }
            }
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("video-${videoB.id.value}").fetchSemanticsNodes().isNotEmpty() &&
                    ui.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty()
            }

            // 4. Card More menu -> Watch without history leaves zero trace in DB!
            ui.onNodeWithContentDescription(context.getString(R.string.action_more_for_video, videoB.name)).performClick()
            ui.onNodeWithText(context.getString(R.string.action_watch_without_history)).performClick()
            ui.waitUntil(20_000) { graph.player.state.value.video?.id == videoB.id && graph.player.state.value.isPlaying }
            assertFalse("Watch without history must set recordHistory=false", graph.player.state.value.recordHistory)
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Seek(5_000L)) }
            delay(400)
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Close) }
            ui.waitUntil(10_000) { graph.player.state.value.video == null }
            val afterNoHistory = graph.repository.get(videoB.id)!!
            assertEquals("Watch without history must not save lastPlayedAt", 0L, afterNoHistory.lastPlayedAt)
            assertEquals("Watch without history must not save positionMs", 0L, afterNoHistory.positionMs)

            // 5. Card More menu -> Play alone -> Player Rename, Favorite, Restart, Crop, Repeat, Sleep until-end, Bookmark CRUD, Fullscreen+Lock, Hide+Undo!
            ui.onNodeWithContentDescription(context.getString(R.string.action_more_for_video, videoA.name)).performClick()
            ui.onNodeWithText(context.getString(R.string.action_play_alone)).performClick()
            ui.waitUntil(20_000) { graph.player.state.value.video?.id == videoA.id && graph.player.state.value.isPlaying }
            assertEquals("Play alone must create a 1-item queue", 1, graph.player.state.value.queue.size)

            // 5a. Player inline rename button.
            val playerRenamed = "A_PlayerRename_$tag"
            ui.onNodeWithContentDescription(context.getString(R.string.action_rename)).performClick()
            ui.onNodeWithText(context.getString(R.string.rename_field_label)).performTextClearance()
            ui.onNodeWithText(context.getString(R.string.rename_field_label)).performTextInput(playerRenamed)
            ui.onNodeWithText(context.getString(R.string.action_save)).performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText(playerRenamed).fetchSemanticsNodes().isNotEmpty()
            }

            // 5b. Player Favorite toggle & Restart.
            ui.onNodeWithText(context.getString(R.string.action_favorite)).performClick()
            ui.waitUntil(5_000) { runBlocking { graph.repository.get(videoA.id)?.favorite == true } }
            ui.onNodeWithText(context.getString(R.string.player_favorited)).performClick()
            ui.waitUntil(5_000) { runBlocking { graph.repository.get(videoA.id)?.favorite == false } }
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Seek(4_000L)) }
            ui.onNodeWithText(context.getString(R.string.player_restart)).performClick()
            ui.waitUntil(5_000) { graph.player.state.value.positionMs < 2_500L }

            // 5c. Player Bookmarks: add untitled -> edit note -> delete via confirmation.
            ui.onNodeWithText(context.getString(R.string.player_shortcut_bookmark)).performClick()
            ui.onNodeWithText(context.getString(R.string.bookmark_add_at, ""), substring = true).performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(context.getString(R.string.bookmark_untitled)).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithContentDescription(context.getString(R.string.bookmark_edit)).performClick()
            val editedNote = "EditedMark-$tag"
            ui.onNodeWithText(context.getString(R.string.bookmark_note)).performTextInput(editedNote)
            ui.onNodeWithText(context.getString(R.string.action_save)).performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(editedNote).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithContentDescription(context.getString(R.string.bookmark_delete)).performClick()
            ui.onNodeWithText(context.getString(R.string.action_delete)).performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(editedNote).fetchSemanticsNodes().isEmpty()
            }
            dismissSheet(context.getString(R.string.bookmark_note_optional))

            // 5d. Player Tools: Crop black bars toggle + Repeat modes + Sleep "Stop after current".
            ui.onNodeWithText(context.getString(R.string.player_tools)).performClick()
            ui.onNode(isToggleable()).performClick()
            ui.waitUntil(5_000) { runBlocking { graph.repository.get(videoA.id)?.cropBars == true } }
            ui.onNode(isToggleable()).performClick()
            ui.waitUntil(5_000) { runBlocking { graph.repository.get(videoA.id)?.cropBars == false } }
            ui.onNodeWithText(context.getString(R.string.repeat_one)).performClick()
            ui.waitUntil(5_000) { graph.player.state.value.repeatMode == RepeatMode.ONE }
            ui.onNodeWithText(context.getString(R.string.repeat_all)).performClick()
            ui.waitUntil(5_000) { graph.player.state.value.repeatMode == RepeatMode.ALL }
            ui.onNodeWithText(context.getString(R.string.repeat_sequence)).performClick()
            ui.waitUntil(5_000) { graph.player.state.value.repeatMode == RepeatMode.SEQUENCE }

            ui.onNodeWithText(context.getString(R.string.feature_sleep_title), substring = true).performClick()
            ui.onNodeWithText(context.getString(R.string.sleep_after_current)).performClick()
            ui.waitUntil(5_000) { graph.timer.untilEnd.value && graph.timer.remainingMs.value > 0L }
            ui.onNodeWithText(context.getString(R.string.sleep_cancel)).performClick()
            ui.waitUntil(5_000) { !graph.timer.untilEnd.value && graph.timer.remainingMs.value == 0L }
            dismissSheet(context.getString(R.string.sleep_panel_desc))

            // 5e. Landscape Fullscreen -> Screen Lock -> Unlock -> Back exits fullscreen to portrait!
            ui.onNodeWithContentDescription(context.getString(R.string.player_fullscreen)).performClick()
            ui.waitUntil(10_000) {
                ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            ui.onNodeWithContentDescription(context.getString(R.string.player_lock_screen)).performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(context.getString(R.string.player_locked_badge)).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithContentDescription(context.getString(R.string.player_unlock_screen)).performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(context.getString(R.string.player_locked_badge)).fetchSemanticsNodes().isEmpty()
            }
            ui.waitForIdle()
            ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            ui.waitUntil(10_000) {
                ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    graph.player.state.value.video?.id == videoA.id
            }

            // 5f. Player Tools -> Hide this video closes player & Snackbar Undo restores it!
            ui.onNodeWithText(context.getString(R.string.player_tools)).performClick()
            ui.onNodeWithText(context.getString(R.string.player_hide_current), substring = true).performScrollTo().performClick()
            ui.waitUntil(10_000) {
                graph.player.state.value.video == null &&
                    ui.onAllNodesWithText(context.getString(R.string.action_undo)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(graph.repository.get(videoA.id)!!.hidden)
            ui.onNodeWithText(context.getString(R.string.action_undo)).performClick()
            ui.waitUntil(10_000) { runBlocking { graph.repository.get(videoA.id)?.hidden == false } }

            // 6. Home Continue Watching banner resumes saved progress!
            graph.repository.saveProgress(videoA.id, 6_000L)
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText(context.getString(R.string.continue_watching_prefix), substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(context.getString(R.string.continue_watching_prefix), substring = true).performClick()
            ui.waitUntil(20_000) { graph.player.state.value.video?.id == videoA.id && graph.player.state.value.isPlaying }
            assertTrue("Continue Watching should resume near 6s", graph.player.state.value.positionMs >= 4_500L)
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Close) }
            ui.waitUntil(10_000) { graph.player.state.value.video == null }

            // 7. Watch History defaults to RECENT_PLAY even while Home sort is NAME_ASC (Bug 13)!
            delay(50)
            graph.repository.saveProgress(videoB.id, 5_000L) // videoB ("Z_Zulu") watched AFTER videoA ("A_PlayerRename").
            ui.onNodeWithTag("nav-3").performClick()
            ui.onNodeWithText(context.getString(R.string.title_history)).performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("video-${videoA.id.value}").fetchSemanticsNodes().isNotEmpty() &&
                    ui.onAllNodesWithTag("video-${videoB.id.value}").fetchSemanticsNodes().isNotEmpty()
            }
            val posA = ui.onNodeWithTag("video-${videoA.id.value}").fetchSemanticsNode().boundsInRoot
            val posB = ui.onNodeWithTag("video-${videoB.id.value}").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "More recently watched videoB must appear before videoA in History (posB=$posB, posA=$posA)",
                posB.top < posA.top || (posB.top == posA.top && posB.left < posA.left),
            )
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

            // 8. Me -> Bookmarks click-to-jump + edit + delete.
            val jumpNote = "Jump6s-$tag"
            graph.repository.saveBookmark(videoA.id, 6_000L, jumpNote)
            ui.onNode(hasText(context.getString(R.string.title_bookmarks)) and hasClickAction()).performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithText(jumpNote).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(jumpNote).performClick()
            ui.waitUntil(20_000) { graph.player.state.value.video?.id == videoA.id && graph.player.state.value.isPlaying }
            assertTrue("Clicking bookmark must jump near 6s", graph.player.state.value.positionMs >= 4_500L)
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Close) }
            ui.waitUntil(10_000) { graph.player.state.value.video == null }
            ui.waitUntil(5_000) { ui.onAllNodesWithText(jumpNote).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription(context.getString(R.string.bookmark_delete)).performClick()
            ui.onNodeWithText(context.getString(R.string.action_delete)).performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithText(jumpNote).fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

            // 9. Settings -> Speed, Hold speed, Touch guard switch, Light/Dark/System theme.
            ui.onNodeWithText(context.getString(R.string.title_settings)).performClick()
            ui.onNodeWithText(context.getString(R.string.theme_light)).performClick()
            ui.waitUntil(5_000) { graph.settings.settings.let { runBlocking { graph.settings.settings } }; ui.onAllNodesWithText(context.getString(R.string.theme_light)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(context.getString(R.string.theme_dark)).performClick()
            ui.onAllNodesWithText(context.getString(R.string.theme_system))[1].performClick()
            ui.onNodeWithText("1.25×").performClick()
            ui.waitUntil(5_000) { graph.player.state.value.baseSpeed == 1.25f }
            ui.onNodeWithText("1×").performClick()
            ui.waitUntil(5_000) { graph.player.state.value.baseSpeed == 1f }
            ui.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.settings_touch_guard)))
            ui.onNode(isToggleable()).performClick()
            ui.waitForIdle()
            ui.onNode(isToggleable()).performClick()
            ui.waitForIdle()
            ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        } finally {
            withContext(Dispatchers.Main) {
                graph.player.execute(PlayerCommand.Close)
                ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            AppLocale.set(context, AppLanguage.SYSTEM)
            runCatching {
                graph.settings.setSort(SortOrder.NEWEST)
                graph.settings.setSpeed(1f)
                graph.settings.setHoldSpeed(2f)
                graph.settings.setTouchGuard(false)
                graph.settings.setTheme(ThemeMode.SYSTEM)
                graph.repository.clearHistory()
            }
            createdUris.forEach { runCatching { resolver.delete(it, null, null) } }
            runCatching { graph.repository.refresh() }
        }
    }

    @Test fun vaultPickerRecoveryErrorsPlaybackAndLockNowDeepAudit(): Unit = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val resolver = context.contentResolver
        val graph = (context.applicationContext as YingXiaApplication).graph
        Assume.assumeFalse("Existing user vault must be preserved", graph.vault.store.configured)

        val tag = UUID.randomUUID().toString().take(6)
        val password = "deep-vault-pwd-$tag"
        val createdUris = mutableListOf<Uri>()
        var createdVault = false

        fun insertMedia(folder: String, name: String): Uri {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put("_display_name", name)
                put("mime_type", "video/mp4")
                put("relative_path", "Movies/$folder/")
                put("is_pending", 1)
            })!!
            instrument.context.assets.open("fixture.mp4").use { input ->
                resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
            createdUris += uri
            return uri
        }

        fun dismissSheet(markerText: String) {
            repeat(3) {
                if (ui.onAllNodesWithText(markerText).fetchSemanticsNodes().isEmpty()) return
                instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                ui.waitForIdle()
            }
        }

        try {
            val uriA = insertMedia("YXVaultPickA$tag", "VaultClipA_$tag.mp4")
            val uriB = insertMedia("YXVaultPickB$tag", "VaultClipB_$tag.mp4")
            graph.repository.importVideo(uriA.toString())
            graph.repository.importVideo(uriB.toString())
            graph.repository.refresh()

            // 1. Open Encrypted Vault from Me tab & verify Create form validation.
            ui.onNodeWithTag("nav-3").performClick()
            ui.onNodeWithText(context.getString(R.string.profile_vault_title)).performClick()
            ui.onNodeWithTag("vault-unlock").assertIsNotEnabled()
            ui.onNodeWithTag("vault-password").performTextInput("short")
            ui.onNodeWithTag("vault-password-confirm").performTextInput("short")
            ui.onNodeWithTag("vault-acknowledge").performClick()
            ui.onNodeWithTag("vault-unlock").assertIsNotEnabled()

            // Enter valid matching password -> create & unlock.
            ui.onNodeWithTag("vault-password").performTextClearance()
            ui.onNodeWithTag("vault-password-confirm").performTextClearance()
            ui.onNodeWithTag("vault-password").performTextInput(password)
            ui.onNodeWithTag("vault-password-confirm").performTextInput(password)
            ui.onNodeWithTag("vault-unlock").assertIsEnabled().performClick()
            createdVault = true
            ui.waitUntil(60_000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }

            // 2. Vault Picker UI: switch Folders <-> Videos tabs, search, select clip, encrypt, deny system deletion.
            ui.onNodeWithText(context.getString(R.string.vault_encrypt_local)).performClick()
            ui.onNodeWithTag("vault-picker-tab-videos").performClick()
            ui.onNodeWithText("VaultClipA_$tag", substring = true).performClick()
            ui.onNodeWithText(context.getString(R.string.vault_picker_confirm, 1)).performClick()
            ui.onNodeWithText(context.getString(R.string.vault_confirm_encrypt_button)).performClick()

            // Click Deny (button2) on Android 14 MediaStore deletion confirmation so test fixture remains clean.
            ui.waitUntil(30_000) {
                instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button2")
                    ?.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            ui.waitForIdle()
            if (!graph.vault.state.value.unlocked) {
                withContext(Dispatchers.Main) { graph.vault.unlock(password.toCharArray()) }
            }
            ui.waitUntil(60_000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy && graph.vault.state.value.entries.size == 1 }
            // Dismiss completion notice dialog if present.
            if (ui.onAllNodesWithText(context.getString(R.string.action_got_it)).fetchSemanticsNodes().isNotEmpty()) {
                ui.onNodeWithText(context.getString(R.string.action_got_it)).performClick()
            }

            // 3. Vault Recovery UI: enroll -> lock -> wrong-answer reset keeps RecoveryForm open (Bug 9)!
            ui.onNode(hasAnyAncestor(hasTestTag("vault-screen")) and hasScrollAction())
                .performScrollToNode(hasText(context.getString(R.string.vault_setup_recovery)))
            ui.onNodeWithText(context.getString(R.string.vault_setup_recovery)).performClick()
            ui.onNodeWithTag("recovery-question-0").performTextInput("Q1-$tag")
            ui.onNodeWithTag("recovery-question-1").performTextInput("Q2-$tag")
            ui.onNodeWithTag("recovery-question-2").performTextInput("Q3-$tag")
            ui.onNodeWithTag("recovery-next").performScrollTo().performClick()

            ui.onNodeWithTag("recovery-answer-0").performTextInput("alpha-$tag")
            ui.onNodeWithTag("recovery-answer-1").performTextInput("bravo-$tag")
            ui.onNodeWithTag("recovery-answer-2").performTextInput("charlie-$tag")
            ui.onNodeWithTag("recovery-password").performScrollTo().performTextInput(password)
            ui.onNodeWithTag("recovery-submit").performScrollTo().performClick()
            ui.waitUntil(60_000) { graph.vault.state.value.recoveryEnabled && !graph.vault.state.value.busy }
            if (ui.onAllNodesWithText(context.getString(R.string.action_got_it)).fetchSemanticsNodes().isNotEmpty()) {
                ui.onNodeWithText(context.getString(R.string.action_got_it)).performClick()
            }

            // Lock vault -> Forgot password -> submit WRONG answers -> verify RecoveryForm stays open!
            ui.onNodeWithContentDescription(context.getString(R.string.vault_lock_now)).performClick()
            ui.waitUntil(10_000) { !graph.vault.state.value.unlocked }
            ui.onNodeWithText(context.getString(R.string.vault_forgot_password)).performScrollTo().performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("recovery-answer-0").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("recovery-answer-0").performTextInput("wrong-one")
            ui.onNodeWithTag("recovery-answer-1").performTextInput("wrong-two")
            ui.onNodeWithTag("recovery-answer-2").performTextInput("wrong-three")
            ui.onNodeWithTag("recovery-password").performScrollTo().performTextInput("brand-new-pwd-99")
            ui.onNodeWithTag("recovery-confirm").performScrollTo().performTextInput("brand-new-pwd-99")
            ui.onNodeWithTag("recovery-submit").performScrollTo().performClick()
            ui.waitUntil(60_000) { !graph.vault.state.value.busy && graph.vault.state.value.message != null }
            ui.onNodeWithText(context.getString(R.string.action_got_it)).performClick()
            // Bug 9 regression assertion: RecoveryForm must STILL be open after wrong-answer failure!
            ui.onNodeWithTag("recovery-answer-0").assertExists()
            ui.onNodeWithText(context.getString(R.string.action_cancel)).performScrollTo().performClick()

            // Re-unlock vault with original password.
            ui.onNodeWithTag("vault-password").performTextInput(password)
            ui.onNodeWithTag("vault-unlock").performClick()
            ui.waitUntil(60_000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }

            // 4. Vault Playback UI: play encrypted video -> rename -> add encrypted bookmark -> back -> verify renamed card -> lock-vault-now!
            ui.onNode(hasAnyAncestor(hasTestTag("vault-screen")) and hasScrollAction())
                .performScrollToNode(hasText(context.getString(R.string.vault_action_play)))
            ui.onNodeWithText(context.getString(R.string.vault_action_play)).performClick()
            ui.waitUntil(20_000) {
                graph.vault.state.value.playing != null &&
                    graph.vault.playback?.player?.state?.value?.isPlaying == true
            }
            val vaultRenamed = "VaultSecret_$tag"
            ui.onNodeWithContentDescription(context.getString(R.string.action_rename)).performClick()
            ui.onNodeWithText(context.getString(R.string.rename_field_label)).performTextClearance()
            ui.onNodeWithText(context.getString(R.string.rename_field_label)).performTextInput(vaultRenamed)
            ui.onNodeWithText(context.getString(R.string.action_save)).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText(vaultRenamed).fetchSemanticsNodes().isNotEmpty() }

            ui.onNode(hasAnyAncestor(hasTestTag("vault-screen")) and hasText(context.getString(R.string.player_shortcut_bookmark))).performClick()
            val secretNote = "VaultMark-$tag"
            ui.onNodeWithText(context.getString(R.string.bookmark_note_optional)).performTextInput(secretNote)
            ui.onNodeWithText(context.getString(R.string.bookmark_add_at, ""), substring = true).performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithText(secretNote).fetchSemanticsNodes().isNotEmpty() }
            dismissSheet(context.getString(R.string.bookmark_note_optional))

            // Exit playback back to Vault list and verify renamed entry title updated!
            withContext(Dispatchers.Main) { graph.vault.stopPlaying() }
            ui.waitUntil(10_000) { graph.vault.state.value.playing == null }
            ui.waitUntil(10_000) { ui.onAllNodesWithText(vaultRenamed).fetchSemanticsNodes().isNotEmpty() }

            // Play again & hit "Lock vault" button directly from Player -> immediately locks & returns to Home!
            ui.onNodeWithText(context.getString(R.string.vault_action_play)).performClick()
            ui.waitUntil(20_000) { graph.vault.state.value.playing != null }
            ui.onNodeWithTag("lock-vault-now").performClick()
            ui.waitUntil(10_000) {
                !graph.vault.state.value.visible && !graph.vault.state.value.unlocked &&
                    ui.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            withContext(Dispatchers.Main) { graph.vault.close() }
            if (createdVault) {
                val dir = graph.vault.store.directory
                runCatching {
                    val alias = "yingxia.questions.v1.${dir.name}"
                    KeyStore.getInstance("AndroidKeyStore").apply {
                        load(null)
                        deleteEntry(alias)
                        deleteEntry("yingxia.prompts.v3.${dir.name}")
                    }
                    context.deleteSharedPreferences(alias)
                }
                dir.deleteRecursively()
            }
            createdUris.forEach { runCatching { resolver.delete(it, null, null) } }
            runCatching { graph.repository.refresh() }
        }
    }

    @Test fun backupRestoreAndScanDeletePruningDeepAudit(): Unit = runBlocking {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val resolver = context.contentResolver
        val graph = (context.applicationContext as YingXiaApplication).graph
        val createdUris = mutableListOf<Uri>()
        val tag = UUID.randomUUID().toString().take(6)

        fun insertMedia(name: String): Uri {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put("_display_name", name)
                put("mime_type", "video/mp4")
                put("relative_path", "Movies/YXScanPrune$tag/")
                put("is_pending", 1)
            })!!
            instrument.context.assets.open("fixture.mp4").use { input ->
                resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
            createdUris += uri
            return uri
        }

        try {
            val dup1Uri = insertMedia("PruneDup1_$tag.mp4")
            val dup2Uri = insertMedia("PruneDup2_$tag.mp4")
            val v1 = graph.repository.importVideo(dup1Uri.toString())
            graph.repository.importVideo(dup2Uri.toString())
            graph.repository.refresh()

            // 1. JSON Backup export -> mutate -> restore round-trip (off-main-thread Dispatchers.IO).
            val customTitle = "BackedUpTitle_$tag"
            graph.repository.setFavorite(setOf(v1.id), true)
            graph.repository.rename(v1.id, customTitle)
            graph.repository.savePicture(v1.id, true)
            val exported = cn.yingxia.player.data.LibraryBackup.export(
                graph.repository.allVideos(),
                cn.yingxia.player.core.UserSettings(theme = ThemeMode.DARK, speed = 1.5f, holdSpeed = 3f, sort = SortOrder.DURATION, touchGuard = true),
            )
            // Wipe state before restoring.
            graph.repository.setFavorite(setOf(v1.id), false)
            graph.repository.savePicture(v1.id, false)
            graph.settings.replacePlayback(cn.yingxia.player.core.UserSettings())

            val plan = cn.yingxia.player.data.LibraryBackup.plan(
                graph.repository.allVideos(),
                cn.yingxia.player.data.LibraryBackup.parse(exported),
            )
            graph.settings.replacePlayback(plan.settings)
            graph.repository.restoreRecords(plan.matched)
            val restored = graph.repository.get(v1.id)!!
            assertTrue("Backup restore must restore favorite", restored.favorite)
            assertTrue("Backup restore must restore cropBars", restored.cropBars)
            assertEquals("Backup restore must restore displayName", customTitle, restored.displayName)
            // Reset settings cleanly.
            graph.settings.replacePlayback(cn.yingxia.player.core.UserSettings())

            // 2. Library Check -> detect duplicate pair -> delete 1 duplicate -> verify scan list prunes immediately (Bug 15)!
            ui.onNodeWithTag("nav-3").performClick()
            ui.onNodeWithText(context.getString(R.string.title_scan)).performClick()
            ui.onNodeWithTag("scan-start").performClick()
            ui.waitUntil(30_000) {
                ui.onAllNodesWithText("PruneDup2_$tag", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            // Select PruneDup2's checkbox row and delete it through the real Android 14 delete confirmation dialog.
            val dup2Checkbox = ui.onAllNodesWithContentDescription(context.getString(R.string.action_select)).onLast()
            dup2Checkbox.performClick()
            val deleteSelectedText = context.getString(R.string.scan_delete_selected)
            ui.onNode(hasScrollAction()).performScrollToNode(hasText(deleteSelectedText))
            ui.onNodeWithText(deleteSelectedText).performClick()
            ui.onNodeWithText(context.getString(R.string.file_dialog_delete_confirm)).performClick()
            ui.waitUntil(30_000) {
                instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")
                    ?.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            ui.waitUntil(15_000) {
                ui.onAllNodesWithText(context.getString(R.string.action_got_it)).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(context.getString(R.string.action_got_it)).performClick()
            // Bug 15 regression assertion: deleted duplicate AND its now-singleton group must vanish from scan UI!
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText("PruneDup2_$tag", substring = true).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            runCatching { graph.settings.replacePlayback(cn.yingxia.player.core.UserSettings()) }
            createdUris.forEach { runCatching { resolver.delete(it, null, null) } }
            runCatching { graph.repository.refresh() }
        }
    }
}
