package cn.yingxia.player.vault

import cn.yingxia.player.core.VideoItem

internal const val VAULT_DELETE_BATCH_LIMIT = 100

/**
 * Pure vault deduplication and copy-selection helpers kept free of Android framework calls
 * so they can be verified directly with JVM unit tests.
 */
internal object VaultRules {
    private val copySuffix = Regex("""\s*\(\d+\)$""")
    private val legacyRestorePrefix = Regex("""^\d{13}-""")

    /** Normalizes auto-numbered gallery/restore filenames such as "clip (1).mp4" or "1731920000123-clip.mp4". */
    fun normalizeFileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        val withoutTimestamp = legacyRestorePrefix.replaceFirst(trimmed, "")
        val dot = withoutTimestamp.lastIndexOf('.')
        val stem = if (dot > 0) withoutTimestamp.substring(0, dot) else withoutTimestamp
        val ext = if (dot > 0) withoutTimestamp.substring(dot) else ""
        val cleanStem = copySuffix.replace(stem.trim(), "").trim()
        return (cleanStem + ext).lowercase()
    }

    /** Deduplicates incoming videos within a single import batch by stable ID and canonical URI. */
    fun deduplicateBatch(items: List<VideoItem>, canonicalUri: (VideoItem) -> String?): List<VideoItem> {
        val seenIds = mutableSetOf<String>()
        val seenUris = mutableSetOf<String>()
        val result = mutableListOf<VideoItem>()
        for (item in items) {
            val resolved = canonicalUri(item)?.takeIf { it.isNotBlank() } ?: item.uri
            val newId = seenIds.add(item.id.value)
            val newUri = seenUris.add(resolved)
            if (newId && newUri) result += item
        }
        return result
    }

    /**
     * Finds an existing vault entry that clearly corresponds to the same source video identity
     * (same sourceId or same raw/canonical/removal URI).
     */
    fun findDuplicateByIdentity(
        entries: List<VaultEntry>,
        video: VideoItem,
        canonicalUri: String?,
    ): VaultEntry? {
        val uris = buildSet {
            if (video.uri.isNotBlank()) add(video.uri)
            if (!canonicalUri.isNullOrBlank()) add(canonicalUri)
        }
        val matches = entries.filter { entry ->
            entry.sourceId == video.id.value ||
                entry.sourceUri in uris ||
                (entry.removalUri.isNotBlank() && entry.removalUri in uris)
        }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { !it.sourceRemoved } ?: matches.first()
    }

    /**
     * Finds an existing vault entry with identical content (exact byte size + SHA-256 digest)
     * when the incoming item is a duplicate copy across different URIs, providers, or restores.
     *
     * Note: distinct MediaStore files with different display names shared in a single gallery batch
     * are preserved separately so tests or users intentionally sharing distinct files with matching
     * bytes keep both entries; cross-entry imports, re-imports, restored copies, non-MediaStore
     * share providers, and local library imports are deduplicated.
     */
    fun findDuplicateByContent(
        entries: List<VaultEntry>,
        video: VideoItem,
        actualBytes: Long,
        digestHex: String,
    ): VaultEntry? {
        if (actualBytes <= 0 || digestHex.isBlank()) return null
        val normalizedIncoming = normalizeFileName(video.name)
        val incomingNonMedia = video.id.value.startsWith("uri:")
        val candidates = entries.filter { entry ->
            entry.bytes == actualBytes && entry.digest.equals(digestHex, ignoreCase = true)
        }
        if (candidates.isEmpty()) return null
        val matched = candidates.filter { entry ->
            val sameFileName = normalizedIncoming.isNotEmpty() &&
                normalizeFileName(entry.originalName) == normalizedIncoming
            val crossProvider = entry.manageOriginalInGallery ||
                entry.sourceId.startsWith("uri:") ||
                incomingNonMedia
            sameFileName || crossProvider || video.source != "share"
        }
        if (matched.isEmpty()) return null
        return matched.firstOrNull { !it.sourceRemoved } ?: matched.first()
    }

    /**
     * Groups vault entries that have identical byte size and SHA-256 digest and returns each group
     * ordered with the preferred keeper first (sourceRemoved > manageable > has playback record > oldest).
     */
    fun duplicateContentGroups(
        entries: List<VaultEntry>,
        hasPlaybackRecord: (String) -> Boolean = { false },
    ): List<List<VaultEntry>> = entries
        .filter { it.bytes > 0 && it.digest.isNotBlank() }
        .groupBy { it.bytes to it.digest.lowercase() }
        .values
        .filter { it.size > 1 }
        .map { group ->
            group.sortedWith(
                compareByDescending<VaultEntry> { it.sourceRemoved }
                    .thenByDescending { !it.manageOriginalInGallery }
                    .thenByDescending { hasPlaybackRecord(it.id) }
                    .thenBy { it.created }
                    .thenBy { it.id },
            )
        }

    /** Counts how many redundant encrypted copies exist across all duplicate content groups. */
    fun redundantCopyCount(entries: List<VaultEntry>): Int =
        duplicateContentGroups(entries).sumOf { it.size - 1 }

    /** Checks whether a library item already matches an entry currently inside the vault. */
    fun matchesVaultEntry(entries: List<VaultEntry>, video: VideoItem): Boolean =
        entries.any { entry ->
            entry.sourceId == video.id.value ||
                entry.sourceUri == video.uri ||
                (entry.removalUri.isNotBlank() && entry.removalUri == video.uri)
        }
}
