package cn.yingxia.player

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal const val MAX_SHARED_VIDEO_URIS = 100

internal data class GalleryShare(
    val uris: List<Uri> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Validate a content URI authority from an incoming share.
 * Allows Android's numeric multi-user prefix (e.g. "0@media", "999@com.miui.gallery.open"),
 * while still rejecting arbitrary userInfo spoofing or any authority belonging to this app.
 */
internal fun isSafeSharedAuthority(rawAuthority: String, packageName: String): Boolean {
    if (rawAuthority.isBlank()) return false
    val bareAuthority = if ('@' in rawAuthority) {
        val prefix = rawAuthority.substringBefore('@')
        val host = rawAuthority.substringAfter('@')
        if (prefix.isEmpty() || !prefix.all(Char::isDigit) || '@' in host) return false
        host
    } else {
        rawAuthority
    }
    return bareAuthority.isNotBlank() &&
        bareAuthority != packageName &&
        !bareAuthority.startsWith("$packageName.")
}

private fun Uri.normalizedShareKey(): String {
    val rawAuthority = authority.orEmpty()
    val idx = rawAuthority.indexOf('@')
    if (idx <= 0 || !rawAuthority.substring(0, idx).all(Char::isDigit)) return toString()
    val bareAuthority = rawAuthority.substring(idx + 1)
    return buildUpon().authority(bareAuthority).build().toString()
}

/** Keep incoming grants in memory only. A share must never resolve through our own providers. */
internal fun Intent.galleryShare(
    packageName: String,
    invalidMessage: String = "No readable local videos received. Select videos in your gallery and share them to YingXia.",
): GalleryShare? {
    if (action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) ||
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    ) return null
    val invalid = GalleryShare(error = invalidMessage)
    val acceptedType = type == null ||
        type.equals("*/*", ignoreCase = true) ||
        type?.startsWith("video/", ignoreCase = true) == true
    if (!acceptedType) return invalid
    return try {
        val urisByKey = linkedMapOf<String, Uri>()
        fun addUri(uri: Uri): Boolean {
            val key = uri.normalizedShareKey()
            if (key !in urisByKey) {
                if (urisByKey.size >= MAX_SHARED_VIDEO_URIS) return false
                urisByKey[key] = uri
            }
            return true
        }
        if (action == Intent.ACTION_SEND) {
            val single = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
                ?: IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.takeIf { it.size == 1 }?.firstOrNull()
            if (single != null) {
                addUri(single)
            } else {
                clipData?.let { clip ->
                    if (clip.itemCount > MAX_SHARED_VIDEO_URIS) return invalid
                    for (i in 0 until clip.itemCount) {
                        val u = clip.getItemAt(i).uri ?: continue
                        if (!addUri(u)) return invalid
                    }
                }
            }
        } else {
            val streamList = IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            if (streamList != null) {
                if (streamList.size > MAX_SHARED_VIDEO_URIS) return invalid
                for (u in streamList) {
                    if (!addUri(u)) return invalid
                }
            } else {
                IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                    if (!addUri(it)) return invalid
                }
            }
            clipData?.let { clip ->
                if (clip.itemCount > MAX_SHARED_VIDEO_URIS) return invalid
                for (i in 0 until clip.itemCount) {
                    val u = clip.getItemAt(i).uri ?: continue
                    if (!addUri(u)) return invalid
                }
            }
        }
        val uris = urisByKey.values.toList()
        if (uris.isEmpty() || (action == Intent.ACTION_SEND && uris.size != 1) || uris.any {
                it.scheme != "content" || !isSafeSharedAuthority(it.authority.orEmpty(), packageName)
            }
        ) invalid else GalleryShare(uris)
    } catch (_: RuntimeException) {
        invalid
    }
}
