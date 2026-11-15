@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.playback

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.PlayerSurface

/** The only UI adapter with access to Media3. Product controls use PlaybackController. */
@Composable fun Media3VideoSurface(controller: Media3Controller, crop: Boolean, zoom: Float, panX: Float, panY: Float, modifier: Modifier = Modifier) {
    val ratio by controller.aspectRatio.collectAsStateWithLifecycle()
    val displayRatio = ratio.coerceAtLeast(0.01f)
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val frameRatio = if (maxHeight.value == 0f) displayRatio else maxWidth / maxHeight
        val (visualW, visualH) = if (!crop) {
            val width = minOf(maxWidth, maxHeight * displayRatio)
            width to width / displayRatio
        } else if (displayRatio > frameRatio) maxHeight * displayRatio to maxHeight else maxWidth to maxWidth / displayRatio
        Box(Modifier.size(visualW, visualH).graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = panX
            translationY = panY
            transformOrigin = TransformOrigin.Center
        }) {
            PlayerSurface(player = controller.surfacePlayer, modifier = Modifier.fillMaxSize())
        }
    }
}
