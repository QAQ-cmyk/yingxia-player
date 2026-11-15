package cn.yingxia.player.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import cn.yingxia.player.core.CheckHit
import cn.yingxia.player.core.LibraryCheck
import cn.yingxia.player.core.VideoItem
import java.security.MessageDigest

/** 按文件字节做 SHA-256。相同哈希才算重复，不会上传。 */
class Sha256Check : LibraryCheck {
    override val id = "sha256"
    override val title = "内容重复"
    override suspend fun inspect(video: VideoItem, open: () -> java.io.InputStream?): CheckHit? {
        val input = open() ?: return CheckHit.Unreadable("打不开这个文件")
        input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return CheckHit.ContentKey(digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}

/** 用系统媒体解析试着打开。失败只说明读不了，不表示可以修复。 */
class ReadableVideoCheck(private val context: Context) : LibraryCheck {
    override val id = "readable"
    override val title = "能否打开"
    override suspend fun inspect(video: VideoItem, open: () -> java.io.InputStream?): CheckHit? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(video.uri))
            null
        } catch (_: Exception) {
            CheckHit.Unreadable("打不开这个文件，不能播放。这不表示可以修复。")
        } finally {
            runCatching { retriever.release() }
        }
    }
}
