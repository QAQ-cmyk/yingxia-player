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
        ui.onNodeWithText("隐藏管理").assertExists().performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("lifecycle").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("lifecycle").assertExists()
        graph.repository.setHidden(setOf(video.id), false)
    }
}
