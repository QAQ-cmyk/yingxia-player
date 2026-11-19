package cn.yingxia.player

import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import android.content.res.Configuration
import cn.yingxia.player.core.*
import cn.yingxia.player.ui.PlayerGestureView
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Runs only when no user vault exists. Never resets or changes an existing vault/password. */
class VaultLifecycleTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun setupImportPlaybackAndBackgroundLockUseSecureUi() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val graph = (context.applicationContext as YingXiaApplication).graph
        Assume.assumeFalse("Existing vault must not be altered by UI tests", graph.vault.store.configured)
        val file = File(context.filesDir, "test-media/vault-ui-${UUID.randomUUID()}.mp4").apply { parentFile!!.mkdirs() }
        instrument.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val video = graph.repository.importVideo(FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", file).toString())
        val oldSettings = graph.settings.settings.first()
        graph.settings.setTouchGuard(false)
        var created = false
        try {
            ui.onNodeWithTag("nav-3").performClick()
            withContext(Dispatchers.Main) { graph.vault.show(listOf(video)) }
            ui.onNodeWithTag("vault-password").performTextInput("UI test password 123")
            ui.onNodeWithTag("vault-password-confirm").performTextInput("UI test password 123")
            ui.onNodeWithTag("vault-acknowledge").performScrollTo().performClick()
            ui.onNodeWithTag("vault-unlock").performScrollTo().performClick()
            withTimeout(60000) { while (!graph.vault.state.value.unlocked || graph.vault.state.value.busy) delay(100) }
            created = true
            assertTrue(ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            ui.onNodeWithText(context.getString(R.string.vault_confirm_encrypt_button)).performClick()
            withTimeout(30000) { while (graph.vault.state.value.busy || graph.vault.state.value.entries.isEmpty()) delay(100) }
            assertFalse(file.exists())
            assertTrue(graph.vault.state.value.entries.single().sourceRemoved)
            withContext(Dispatchers.Main) { graph.vault.dismissMessage(); graph.vault.play(graph.vault.state.value.entries.single()) }
            withTimeout(20000) { while (graph.vault.state.value.playing == null || graph.vault.state.value.busy) delay(50) }
            val session = graph.vault.playback!!
            val player = session.player
            val privateId = player.state.value.video!!.id
            withTimeout(20000) { while (!player.state.value.isPlaying) delay(50) }
            ui.onNodeWithTag("player-frame").assertExists()
            ui.onNodeWithTag("lock-vault-now").assertIsDisplayed()
            player.execute(PlayerCommand.SetRepeat(RepeatMode.ONE))
            player.execute(PlayerCommand.SetSpeed(1.25f))
            ui.waitForIdle()
            fun findGesture(view: View): PlayerGestureView? {
                if (view is PlayerGestureView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) findGesture(view.getChildAt(index))?.let { return it }
                return null
            }
            val gesture = withContext(Dispatchers.Main) { findGesture(ui.activity.window.decorView)!! }
            suspend fun touch(action: Int, x: Float, y: Float) = withContext(Dispatchers.Main) {
                val now = SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, action, x, y, 0).let { gesture.onTouchEvent(it); it.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 100f, 100f)
            withTimeout(5000) { while (!player.state.value.boosting) delay(50) }
            ui.onNodeWithTag("boost-hint").assertExists()
            touch(MotionEvent.ACTION_CANCEL, 100f, 100f)
            assertFalse(player.state.value.boosting)
            assertEquals(1.25f, player.state.value.effectiveSpeed)
            player.execute(PlayerCommand.Pause)
            player.execute(PlayerCommand.Seek(3500))
            ui.waitForIdle()
            touch(MotionEvent.ACTION_DOWN, 80f, 100f)
            touch(MotionEvent.ACTION_MOVE, 280f, 100f)
            touch(MotionEvent.ACTION_CANCEL, 280f, 100f)
            assertEquals(3500L, player.state.value.positionMs)
            touch(MotionEvent.ACTION_DOWN, 80f, 100f)
            touch(MotionEvent.ACTION_MOVE, 280f, 100f)
            touch(MotionEvent.ACTION_UP, 280f, 100f)
            assertTrue(player.state.value.positionMs > 3500)
            assertFalse(player.state.value.isPlaying)
            ui.onNodeWithContentDescription(context.getString(R.string.player_fullscreen)).performClick()
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            assertSame(session, graph.vault.playback)
            assertFalse(player.state.value.isPlaying)
            assertTrue(ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            ui.onNodeWithContentDescription(context.getString(R.string.player_lock_screen)).performClick()
            ui.onNodeWithContentDescription(context.getString(R.string.player_unlock_screen)).performClick()
            ui.onNodeWithContentDescription(context.getString(R.string.player_exit_fullscreen)).performClick()
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            ui.onNode(hasText(context.getString(R.string.action_favorite)) and hasAnyAncestor(hasTestTag("vault-screen")), useUnmergedTree = true).performClick()
            withTimeout(10000) { while (!session.repository.get(privateId)!!.favorite) delay(50) }
            ui.onNode(hasText(context.getString(R.string.player_shortcut_bookmark)) and hasAnyAncestor(hasTestTag("vault-screen")), useUnmergedTree = true).performClick()
            ui.onNodeWithText(context.getString(R.string.player_lock_vault_home)).assertIsDisplayed()
            ui.onNodeWithText(context.getString(R.string.bookmark_note_optional)).performTextInput("安全播放界面的私密书签")
            ui.onNodeWithText(context.getString(R.string.bookmark_add_at, "").trim(), substring = true).performClick()
            withTimeout(10000) { while (session.repository.bookmarks.value.isEmpty()) delay(50) }
            assertEquals("安全播放界面的私密书签", session.repository.bookmarks.value.single().note)
            assertNull(graph.repository.get(privateId))
            withContext(Dispatchers.Main) { graph.vault.dismissMessage() }
            player.execute(PlayerCommand.Seek(4200))
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(graph.vault.state.value.unlocked)
            assertTrue(graph.vault.state.value.entries.isEmpty())
            assertNull(graph.vault.state.value.playing)
            assertNull(graph.vault.store.session)
            assertNull(graph.vault.releasePlayer)
            assertNull(graph.vault.playback)
            assertNull(player.state.value.video)
            assertTrue(session.repository.bookmarks.value.isEmpty())
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.onNodeWithTag("vault-password").assertExists().performTextInput("UI test password 123")
            ui.onNodeWithTag("vault-unlock").performScrollTo().performClick()
            withTimeout(60000) { while (!graph.vault.state.value.unlocked || graph.vault.state.value.busy) delay(100) }
            assertEquals(1, graph.vault.state.value.entries.size)
            withContext(Dispatchers.Main) { graph.vault.play(graph.vault.state.value.entries.single()) }
            withTimeout(20000) { while (graph.vault.state.value.playing == null || graph.vault.state.value.busy) delay(50) }
            val reopened = graph.vault.playback!!
            assertTrue(reopened.player.state.value.positionMs >= 4200)
            assertTrue(reopened.repository.get(privateId)!!.favorite)
            assertEquals("安全播放界面的私密书签", reopened.repository.bookmarks.value.single().note)
            reopened.player.execute(PlayerCommand.Pause)
            ui.onNodeWithContentDescription(context.getString(R.string.player_fullscreen)).performClick()
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            ui.onNodeWithContentDescription(context.getString(R.string.player_lock_screen)).performClick()
            reopened.player.execute(PlayerCommand.Play)
            ui.waitUntil(10000) { reopened.player.state.value.isPlaying }
            ui.onNodeWithTag("lock-vault-now").assertIsDisplayed().performClick()
            assertFalse(reopened.player.state.value.isPlaying)
            assertNull(reopened.player.state.value.video)
            assertNull(graph.vault.store.session)
            assertNull(graph.vault.playback)
            assertTrue(reopened.repository.bookmarks.value.isEmpty())
            ui.onNodeWithTag("vault-screen").assertDoesNotExist()
            ui.onNodeWithTag("nav-0").assertIsSelected()
            ui.onNodeWithTag("nav-3").performClick()
            ui.onNodeWithText(context.getString(R.string.profile_vault_title)).performScrollTo().performClick()
            ui.onNodeWithTag("vault-password").performTextInput("UI test password 123")
            ui.onNodeWithTag("vault-unlock").performScrollTo().performClick()
            ui.waitUntil(60000) { graph.vault.state.value.unlocked && !graph.vault.state.value.busy }
            assertEquals(privateId.value, graph.vault.state.value.entries.single().id)
            ui.onNodeWithText(context.getString(R.string.vault_setup_recovery)).performScrollTo().performClick()
            val chosen = listOf("小时候的树叫什么？", "Where did I keep the map?", "我给雨天起的名字？")
            fun question(index: Int) = ui.onNodeWithTag("recovery-question-" + index)
            fun answer(index: Int) = ui.onNodeWithTag("recovery-answer-" + index)
            val next = ui.onNodeWithTag("recovery-next")
            answer(0).assertDoesNotExist()
            next.assertIsNotEnabled()
            question(0).performScrollTo().performTextInput(chosen[0])
            question(1).performScrollTo().performTextInput(chosen[0])
            question(2).performScrollTo().performTextInput("temporary question")
            next.assertIsNotEnabled()
            question(1).performScrollTo().performTextReplacement(chosen[1])
            next.performScrollTo().performClick()
            answer(0).performScrollTo().performTextInput("keep this answer")
            answer(2).performScrollTo().performTextInput("discard this answer")
            ui.onNodeWithText(context.getString(R.string.recovery_edit_questions)).performScrollTo().performClick()
            ui.onNodeWithTag("recovery-presets-2").performScrollTo().performClick()
            val preset = context.resources.getStringArray(R.array.vault_recovery_presets).first()
            ui.onNodeWithText(preset).performClick()
            question(2).assertTextContains(preset).performScrollTo().performTextReplacement(chosen[2])
            next.performScrollTo().performClick()
            ui.onNodeWithText(context.getString(R.string.recovery_show_answers)).performScrollTo().performClick()
            answer(0).assertTextContains("keep this answer")
            answer(2).assertTextEquals(context.getString(R.string.recovery_answer_label, 3), "")
            fun fillAnswers() {
                listOf("island willow", "blue night library", "hello future").forEachIndexed { index, phrase ->
                    ui.onNodeWithTag("recovery-prompt-" + index).assertTextEquals(chosen[index])
                    answer(index).performScrollTo().performTextReplacement(phrase)
                }
            }
            fillAnswers()
            ui.onNodeWithTag("recovery-password").performScrollTo().performTextInput("wrong current password")
            ui.onNodeWithTag("recovery-submit").performScrollTo().performClick()
            val wrongPwdMsg = context.getString(R.string.vault_recovery_wrong_pwd_enroll)
            ui.waitUntil(60000) { graph.vault.state.value.message?.contains(wrongPwdMsg) == true }
            ui.onNodeWithText(context.getString(R.string.action_got_it)).performClick()
            answer(0).assertTextContains("island willow")
            ui.onNodeWithTag("recovery-password").performScrollTo().performTextInput("UI test password 123")
            ui.onNodeWithTag("recovery-submit").performScrollTo().performClick()
            ui.waitUntil(60000) { graph.vault.state.value.recoveryEnabled }
            ui.onNodeWithText(context.getString(R.string.action_got_it)).performClick()
            ui.onNodeWithContentDescription(context.getString(R.string.vault_lock_now)).performClick()
            ui.onNodeWithText(context.getString(R.string.vault_forgot_password)).performScrollTo().performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithTag("recovery-prompt-0").fetchSemanticsNodes().isNotEmpty() }
            question(0).assertDoesNotExist()
            fillAnswers()
            ui.onNodeWithTag("recovery-password").performScrollTo().performTextInput("my new UI password")
            ui.onNodeWithTag("recovery-confirm").performScrollTo().performTextInput("my new UI password")
            ui.onNodeWithTag("recovery-submit").performScrollTo().performClick()
            ui.waitUntil(60000) { !graph.vault.state.value.recoveryEnabled }
            ui.onNodeWithText(context.getString(R.string.action_got_it)).performClick()
            ui.onNodeWithTag("vault-password").performScrollTo().performTextInput("my new UI password")
            ui.onNodeWithTag("vault-unlock").performScrollTo().performClick()
            ui.waitUntil(60000) { graph.vault.state.value.entries.size == 1 && !graph.vault.state.value.busy }
            assertEquals(privateId.value, graph.vault.state.value.entries.single().id)

        } finally {
            withContext(Dispatchers.Main) { graph.vault.close() }
            if (created) graph.vault.store.directory.deleteRecursively()
            file.delete()
            graph.repository.forgetEncryptedSource(video)
            graph.settings.replacePlayback(oldSettings)
        }
    }
}
