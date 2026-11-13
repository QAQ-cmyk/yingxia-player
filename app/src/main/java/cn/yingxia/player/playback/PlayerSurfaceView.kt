@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.playback

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import cn.yingxia.player.core.quarterTurn

/** The only UI adapter with access to Media3. Product controls use PlaybackController. */
@Composable fun Media3VideoSurface(controller: Media3Controller, subtitleScale: Float, rotation: Int, mirror: Boolean,
    crop: Boolean, zoom: Float, panX: Float, panY: Float, modifier: Modifier = Modifier) {
    val ratio by controller.aspectRatio.collectAsStateWithLifecycle()
    val cues by controller.cues.collectAsStateWithLifecycle()
    val turns = quarterTurn(rotation)
    val swapped = turns == 90 || turns == 270
    val safeRatio = ratio.coerceAtLeast(0.01f)
    val displayRatio = if (swapped) 1f / safeRatio else safeRatio
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val frameRatio = if (maxHeight.value == 0f) displayRatio else maxWidth / maxHeight
        val (visualW, visualH) = if (!crop) {
            val width = minOf(maxWidth, maxHeight * displayRatio)
            width to width / displayRatio
        } else if (displayRatio > frameRatio) maxHeight * displayRatio to maxHeight else maxWidth to maxWidth / displayRatio
        val layoutW = if (swapped) visualH else visualW
        val layoutH = if (swapped) visualW else visualH
        Box(Modifier.size(layoutW, layoutH).graphicsLayer {
            scaleX = zoom * if (mirror) -1f else 1f
            scaleY = zoom
            rotationZ = turns.toFloat()
            translationX = panX
            translationY = panY
            transformOrigin = TransformOrigin.Center
        }) {
            PlayerSurface(player = controller.surfacePlayer, modifier = Modifier.fillMaxSize())
            AndroidView(factory = { SubtitleView(it).apply { setApplyEmbeddedFontSizes(false); setApplyEmbeddedStyles(true) } },
                modifier = Modifier.fillMaxSize(), update = { it.setCues(cues); it.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale) })
        }
    }
}
