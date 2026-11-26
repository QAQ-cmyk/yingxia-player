package cn.yingxia.player.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import cn.yingxia.player.core.CheckHit
import cn.yingxia.player.core.LibraryCheck
import cn.yingxia.player.core.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 按文件字节做 SHA-256。相同哈希才算重复，不会上传。 */
class Sha256Check(private val context: Context? = null) : LibraryCheck {
    override val id = "sha256"
    override val title = "Duplicates"
    override suspend fun inspect(video: VideoItem, open: () -> java.io.InputStream?): CheckHit? = withContext(Dispatchers.IO) {
        val jobContext = coroutineContext
        jobContext.ensureActive()
        try {
            val input = open() ?: return@withContext CheckHit.Unreadable(
                context?.let { AppLocale.str(it, R.string.scan_error_cannot_open) } ?: "Cannot open this file",
            )
            input.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    jobContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
                CheckHit.ContentKey(digest.digest().joinToString("") { "%02x".format(it) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CheckHit.Unreadable(
                context?.let { AppLocale.str(it, R.string.scan_error_cannot_open) } ?: "Cannot open this file",
            )
        }
    }
}

/** 用系统媒体解析试着打开。失败只说明读不了，不表示可以修复。 */
class ReadableVideoCheck(private val context: Context) : LibraryCheck {
    override val id = "readable"
    override val title = "Readability"
    override suspend fun inspect(video: VideoItem, open: () -> java.io.InputStream?): CheckHit? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val uri = Uri.parse(video.uri)
        val retriever = MediaMetadataRetriever()
        try {
            val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
            if (afd != null) {
                afd.use { descriptor ->
                    if (descriptor.declaredLength >= 0L) {
                        retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                    } else {
                        retriever.setDataSource(descriptor.fileDescriptor)
                    }
                }
            } else {
                retriever.setDataSource(context, uri)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CheckHit.Unreadable(AppLocale.str(context, R.string.scan_error_unreadable))
        } finally {
            runCatching { retriever.release() }
        }
    }
}
