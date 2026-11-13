@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/** What “清晰度” is allowed to do. It never invents detail that was not recorded. */
enum class ClarityMode { OFF, CONTRAST, UPSCALE }

object ClarityPolicy {
    const val CONTRAST = 0.1f
    fun choose(enabled: Boolean, height: Int, lowRam: Boolean): ClarityMode = when {
        !enabled -> ClarityMode.OFF
        lowRam || height <= 0 || height >= 720 -> ClarityMode.CONTRAST
        else -> ClarityMode.UPSCALE
    }
    fun targetHeight(height: Int) = (height * 2).coerceIn(2, 720)
}

/** A light bass/treble lift with a soft ceiling. Inactive unless [active] is set. */
class EnhancementAudioProcessor : BaseAudioProcessor() {
    @Volatile var active = false
    private val low = FloatArray(8)
    private val previous = FloatArray(8)
    private var channels = 1
    private var index = 0
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET
    }
    override fun onFlush() = clear()
    override fun onReset() = clear()
    private fun clear() { low.fill(0f); previous.fill(0f); index = 0 }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!active) output.put(inputBuffer) else while (inputBuffer.remaining() >= 2) output.putShort(enhance(inputBuffer.short))
        output.flip()
    }
    private fun enhance(sample: Short): Short {
        val channel = index++ % channels
        val x = sample / 32768f
        low[channel] += 0.08f * (x - low[channel])
        val high = x - previous[channel]
        previous[channel] = x
        val mixed = (x + 0.15f * low[channel] + 0.12f * high).coerceIn(-0.98f, 0.98f)
        return (mixed * 32767f).toInt().toShort()
    }
}

class EnhancingRenderersFactory(context: Context, private val processor: AudioProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(processor))
            .build()
}
