package cn.yingxia.player

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LifecycleUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun openWithoutHistoryFromCardMenuAndLongPress() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val graph = (context.applicationContext as YingXiaApplication).graph
        val file = File(context.filesDir, "test-media/private-session-${java.util.UUID.randomUUID()}.mp4")
        file.parentFile!!.mkdirs()
        instrument.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val video = graph.repository.importVideo(FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", file).toString())
        try {
            graph.repository.setFavorite(setOf(video.id), true)
            for (longPress in listOf(false, true)) {
                ui.onNodeWithTag("nav-2").performClick()
                val cardTag = "video-${video.id.value}"
                ui.onNodeWithTag("video-grid").performScrollToNode(hasTestTag(cardTag))
                if (longPress) ui.onNodeWithTag(cardTag).performTouchInput { longClick() }
                else ui.onNodeWithContentDescription(context.getString(R.string.action_more_for_video, video.name)).performClick()
                ui.onNodeWithText(context.getString(R.string.action_watch_without_history)).performClick()
                ui.waitUntil(15000) { graph.player.state.value.isPlaying }
                ui.onNodeWithTag("lock-vault-now").assertDoesNotExist()
                ui.activityRule.scenario.recreate()
                assertFalse(graph.player.state.value.recordHistory)
                ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                ui.waitUntil(5000) { !graph.player.state.value.isPlaying }
                ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                ui.onNodeWithContentDescription(context.getString(R.string.action_watch_without_history)).assertExists()
                ui.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
                ui.waitUntil(5000) { graph.player.state.value.video == null }
                assertEquals(0L, graph.repository.get(video.id)!!.lastPlayedAt)
                assertEquals(0L, graph.repository.get(video.id)!!.positionMs)
            }
        } finally {
            graph.player.execute(PlayerCommand.Close)
            graph.repository.forgetEncryptedSource(video)
            file.delete()
        }
        Unit
    }

    @Test fun rotationRetainsPlaybackBackgroundPausesAndHidingClosesPlayer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as YingXiaApplication).graph
        graph.repository.setHidden(graph.repository.hiddenVideos.first().filter { it.name == "lifecycle.mp4" }.map { it.id }.toSet(), false)
        val file = File(context.filesDir, "test-media/lifecycle.mp4").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val video = graph.repository.importVideo(FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", file).toString())
        graph.repository.setHidden(setOf(video.id), false)
        graph.player.execute(PlayerCommand.SetSpeed(1.25f))
        graph.player.execute(PlayerCommand.Open(video.id, listOf(video.id), 6000))
        withTimeout(15000) { while (!graph.player.state.value.isPlaying) delay(50) }
        ui.onNodeWithTag("player-frame").assertExists()
        graph.player.execute(PlayerCommand.BeginBoost(2f))
        ui.waitUntil(3000) { ui.onAllNodesWithTag("boost-hint").fetchSemanticsNodes().isNotEmpty() }
        val frame = ui.onNodeWithTag("player-frame").fetchSemanticsNode().boundsInRoot
        val hint = ui.onNodeWithTag("boost-hint").fetchSemanticsNode().boundsInRoot
        assertTrue(hint.center.y < frame.center.y)
        assertTrue(hint.width < frame.width * .5f)
        assertTrue(hint.height < frame.height * .2f)
        graph.player.execute(PlayerCommand.EndBoost)
        ui.activityRule.scenario.recreate()
        delay(500)
        assertEquals(video.id, graph.player.state.value.video?.id)
        assertTrue(graph.player.state.value.positionMs >= 5900)
        assertTrue(graph.player.state.value.isPlaying)
        graph.player.execute(PlayerCommand.BeginBoost(3f))
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        withTimeout(5000) { while (graph.player.state.value.isPlaying) delay(50) }
        assertFalse(graph.player.state.value.boosting)
        assertEquals(1.25f, graph.player.state.value.effectiveSpeed)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertFalse(graph.player.state.value.isPlaying)
        graph.repository.setHidden(setOf(video.id), true)
        withTimeout(5000) { while (graph.player.state.value.video != null) delay(50) }
        ui.onNodeWithTag("nav-3").performClick()
        ui.onNodeWithText(context.getString(R.string.title_hidden_manager)).assertExists().performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("lifecycle").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("lifecycle").assertExists()
        graph.repository.setHidden(setOf(video.id), false)
    }
}
