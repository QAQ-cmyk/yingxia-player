package cn.yingxia.player.transfer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream

object GalleryWriter {
    fun save(context: Context, name: String, input: InputStream, length: Long, progress: (Long) -> Unit): Boolean = runCatching {
        val fileName = TransferProtocol.safeFileName(name)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, if (fileName.endsWith(".mov", true)) "video/quicktime" else "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/YingXia")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { output ->
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            val limit = if (length > 0) length else Long.MAX_VALUE
            while (sent < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - sent).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                sent += read
                progress(sent)
            }
        } ?: return false
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)
}
