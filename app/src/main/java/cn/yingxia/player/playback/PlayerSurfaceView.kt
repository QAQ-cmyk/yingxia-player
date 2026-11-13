@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.playback

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface

/** The only UI adapter with access to Media3. Product controls use PlaybackController. */
@Composable fun Media3VideoSurface(controller: Media3Controller, subtitleScale: Float, modifier: Modifier = Modifier) {
    val ratio by controller.aspectRatio.collectAsStateWithLifecycle()
    val cues by controller.cues.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val videoWidth = minOf(maxWidth, maxHeight * ratio)
        val videoHeight = videoWidth / ratio
        Box(Modifier.size(videoWidth, videoHeight)) {
            PlayerSurface(player = controller.surfacePlayer, modifier = Modifier.fillMaxSize())
            AndroidView(factory = { SubtitleView(it).apply { setApplyEmbeddedFontSizes(false); setApplyEmbeddedStyles(true) } },
                modifier = Modifier.fillMaxSize(), update = { it.setCues(cues); it.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale) })
        }
    }
}
