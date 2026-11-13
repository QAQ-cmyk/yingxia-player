package cn.yingxia.player.playback

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cn.yingxia.player.core.*
import cn.yingxia.player.data.stableHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class LocalSubtitleProvider(private val context: Context) : SubtitleProvider {
    override suspend fun load(uri: String): SubtitleAttachment = withContext(Dispatchers.IO) {
        try {
            val parsed = Uri.parse(uri)
            require(parsed.scheme == "content")
            var name = "字幕.srt"
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("srt", "vtt")) throw IllegalArgumentException("请选择 SRT 或 VTT 字幕文件")
            val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readBytesLimited(5 * 1024 * 1024) }
                ?: throw IllegalArgumentException("无法读取字幕")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val content = decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            if ("-->" !in content || (ext == "vtt" && !content.trimStart().startsWith("WEBVTT")))
                throw IllegalArgumentException("字幕内容无效，请使用带时间轴的 UTF-8 字幕")
            val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
            val file = File(dir, "${stableHash(content)}.$ext")
            if (!file.exists()) file.writeText(content, Charsets.UTF_8)
            SubtitleAttachment(Uri.fromFile(file).toString(), name, if (ext == "srt") "application/x-subrip" else "text/vtt")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            throw PlayerFailure(FailureKind.INVALID_SUBTITLE, e.message?.takeIf { it.contains("字幕") } ?: "字幕读取失败，请使用 UTF-8 编码的 SRT 或 VTT 文件（最大 5 MB）")
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (output.size() + n > limit) throw IllegalArgumentException("字幕文件不能超过 5 MB")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
