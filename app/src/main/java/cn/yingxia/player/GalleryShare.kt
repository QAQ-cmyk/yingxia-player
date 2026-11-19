package cn.yingxia.player

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal data class GalleryShare(
    val uris: List<Uri> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Keep incoming grants in memory only. A share must never resolve through our own providers. */
internal fun Intent.galleryShare(packageName: String): GalleryShare? {
    if (action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) ||
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val invalid = GalleryShare(error = "没有收到可读取的本地视频。请在相册中选中视频，再分享给映匣。")
    if (type?.startsWith("video/", ignoreCase = true) != true) return invalid
    return try {
        val uris = linkedSetOf<Uri>()
        if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
        } else {
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
        }
        clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty() || (action == Intent.ACTION_SEND && uris.size != 1) || uris.any {
                val authority = it.authority.orEmpty()
                it.scheme != "content" || authority.isBlank() || '@' in authority || authority == packageName ||
                    authority.startsWith("$packageName.")
            }) invalid else GalleryShare(uris.toList())
    } catch (_: RuntimeException) { invalid }
}
