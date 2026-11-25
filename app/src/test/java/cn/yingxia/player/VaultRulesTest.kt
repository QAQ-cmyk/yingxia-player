package cn.yingxia.player

import cn.yingxia.player.core.VideoId
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.vault.VaultEntry
import cn.yingxia.player.vault.VaultRules
import org.junit.Assert.*
import org.junit.Test

class VaultRulesTest {
    private fun entry(
        id: String,
        originalName: String = "clip.mp4",
        bytes: Long = 10_000L,
        digest: String = "a".repeat(64),
        sourceId: String = "ms:external_primary:1",
        sourceUri: String = "content://media/external_primary/video/media/1",
        removalUri: String = "",
        sourceRemoved: Boolean = false,
        manageOriginalInGallery: Boolean = false,
        created: Long = 1000L,
    ) = VaultEntry(
        id = id,
        name = originalName,
        originalName = originalName,
        mime = "video/mp4",
        bytes = bytes,
        duration = 5000L,
        sourceId = sourceId,
        sourceUri = sourceUri,
        digest = digest,
        created = created,
        sourceRemoved = sourceRemoved,
        removalUri = removalUri,
        manageOriginalInGallery = manageOriginalInGallery,
    )

    private fun video(
        id: String = "ms:external_primary:1",
        uri: String = "content://media/external_primary/video/media/1",
        name: String = "clip.mp4",
        bytes: Long = 10_000L,
        source: String = "media-store",
    ) = VideoItem(
        id = VideoId(id),
        uri = uri,
        name = name,
        folder = "Camera",
        folderKey = "external_primary:1",
        durationMs = 5000L,
        sizeBytes = bytes,
        source = source,
    )

    @Test fun normalizesCopyNumbersAndLegacyRestorePrefixes() {
        assertEquals("vid_001.mp4", VaultRules.normalizeFileName("VID_001.mp4"))
        assertEquals("vid_001.mp4", VaultRules.normalizeFileName("VID_001 (1).mp4"))
        assertEquals("vid_001.mp4", VaultRules.normalizeFileName("VID_001(23).mp4"))
        assertEquals("vid_001.mp4", VaultRules.normalizeFileName("1731920000123-VID_001.mp4"))
        assertEquals("vid_001.mp4", VaultRules.normalizeFileName("1731920000123-VID_001 (2).mp4"))
    }

    @Test fun deduplicatesBatchAcrossAliasUris() {
        val v1 = video(id = "ms:external_primary:42", uri = "content://media/external/video/media/42", source = "share")
        val v2 = video(id = "uri:alias", uri = "content://com.android.providers.media.documents/document/video%3A42", source = "share")
        val v3 = video(id = "ms:external_primary:99", uri = "content://media/external_primary/video/media/99", source = "share")
        val deduped = VaultRules.deduplicateBatch(listOf(v1, v2, v3)) { item ->
            if (item.uri.endsWith("42")) "content://media/external_primary/video/media/42" else item.uri
        }
        assertEquals(listOf(v1, v3), deduped)
    }

    @Test fun identityMatchFindsSameVideoAcrossExternalAndPrimaryUrisAndShareEntries() {
        val shared = entry(
            id = "e1",
            sourceId = "ms:external_primary:42",
            sourceUri = "content://media/external/video/media/42",
            removalUri = "content://media/external_primary/video/media/42",
            manageOriginalInGallery = false,
            sourceRemoved = false,
        )
        val fromLibrary = video(
            id = "ms:external_primary:42",
            uri = "content://media/external_primary/video/media/42",
        )
        assertEquals(shared, VaultRules.findDuplicateByIdentity(listOf(shared), fromLibrary, fromLibrary.uri))

        val viaRemovalUriOnly = video(
            id = "uri:other-hash",
            uri = "content://com.android.providers.media.documents/document/video%3A42",
        )
        assertEquals(
            shared,
            VaultRules.findDuplicateByIdentity(
                listOf(shared),
                viaRemovalUriOnly,
                "content://media/external_primary/video/media/42",
            ),
        )
    }

    @Test fun contentMatchDeduplicatesThirdPartyGalleryShareAndRestoredCopies() {
        val digest = "1".repeat(64)
        val fromThirdPartyShare = entry(
            id = "e-share",
            originalName = "VID_2026.mp4",
            bytes = 25_000L,
            digest = digest,
            sourceId = "uri:oem-gallery-1",
            sourceUri = "content://com.oem.gallery.provider/tmp/1",
            manageOriginalInGallery = true,
            sourceRemoved = false,
        )
        // Re-sharing the same video via a brand new ephemeral provider URI must not duplicate.
        val reshared = video(
            id = "uri:oem-gallery-2",
            uri = "content://com.oem.gallery.provider/tmp/2",
            name = "temp_share_999.mp4",
            bytes = 25_000L,
            source = "share",
        )
        assertEquals(fromThirdPartyShare, VaultRules.findDuplicateByContent(listOf(fromThirdPartyShare), reshared, 25_000L, digest))

        // Importing the same file later from the local library must also reuse the existing vault entry.
        val local = video(
            id = "ms:external_primary:88",
            uri = "content://media/external_primary/video/media/88",
            name = "VID_2026.mp4",
            bytes = 25_000L,
            source = "media-store",
        )
        assertEquals(fromThirdPartyShare, VaultRules.findDuplicateByContent(listOf(fromThirdPartyShare), local, 25_000L, digest))

        // Restored copy in Movies/映匣恢复 with "(1)" suffix re-imported must also reuse without duplicating.
        val removed = entry(
            id = "e-removed",
            originalName = "VID_2026.mp4",
            bytes = 25_000L,
            digest = digest,
            sourceId = "ms:external_primary:88",
            sourceRemoved = true,
        )
        val restoredCopy = video(
            id = "ms:external_primary:205",
            uri = "content://media/external_primary/video/media/205",
            name = "VID_2026 (1).mp4",
            bytes = 25_000L,
            source = "media-store",
        )
        assertEquals(removed, VaultRules.findDuplicateByContent(listOf(removed), restoredCopy, 25_000L, digest))
    }

    @Test fun contentMatchKeepsDistinctNamedMediaStoreSharesSeparate() {
        val digest = "2".repeat(64)
        val first = entry(
            id = "e1",
            originalName = "share-uuid-1.mp4",
            bytes = 12_345L,
            digest = digest,
            sourceId = "ms:external_primary:101",
            sourceUri = "content://media/external_primary/video/media/101",
            manageOriginalInGallery = false,
            sourceRemoved = false,
        )
        val second = video(
            id = "ms:external_primary:102",
            uri = "content://media/external_primary/video/media/102",
            name = "share-uuid-2.mp4",
            bytes = 12_345L,
            source = "share",
        )
        assertNull(VaultRules.findDuplicateByContent(listOf(first), second, 12_345L, digest))
    }

    @Test fun duplicateGroupsPickBestKeeperAndCountRedundantCopies() {
        val digestA = "a".repeat(64)
        val digestB = "b".repeat(64)
        val pendingOld = entry(id = "pending-old", bytes = 5000, digest = digestA, sourceRemoved = false, created = 100)
        val removedNoPlayback = entry(id = "removed-no-pb", bytes = 5000, digest = digestA, sourceRemoved = true, created = 200)
        val removedWithPlayback = entry(id = "removed-with-pb", bytes = 5000, digest = digestA, sourceRemoved = true, created = 300)
        val unique = entry(id = "unique", bytes = 9000, digest = digestB, sourceRemoved = true, created = 400)

        val entries = listOf(pendingOld, removedNoPlayback, removedWithPlayback, unique)
        assertEquals(2, VaultRules.redundantCopyCount(entries))

        val groups = VaultRules.duplicateContentGroups(entries) { it == "removed-with-pb" }
        assertEquals(1, groups.size)
        assertEquals(listOf("removed-with-pb", "removed-no-pb", "pending-old"), groups.single().map { it.id })
    }

    @Test fun groupsVideosByFolderAndCountsExistingVaultEntries() {
        val cam1 = video(id = "ms:external_primary:1", uri = "content://media/external_primary/video/media/1", name = "a.mp4", bytes = 4000L).copy(folder = "Camera", folderKey = "external_primary:cam")
        val cam2 = video(id = "ms:external_primary:2", uri = "content://media/external_primary/video/media/2", name = "b.mp4", bytes = 6000L).copy(folder = "Camera", folderKey = "external_primary:cam")
        val dl1 = video(id = "ms:external_primary:3", uri = "content://media/external_primary/video/media/3", name = "c.mp4", bytes = 9000L).copy(folder = "Download", folderKey = "external_primary:dl")
        val existing = entry(id = "e1", sourceId = "ms:external_primary:1", sourceUri = "content://media/external_primary/video/media/1")

        val groups = VaultRules.groupVideosByFolder(listOf(dl1, cam1, cam2), listOf(existing))
        assertEquals(listOf("Camera", "Download"), groups.map { it.name })
        assertEquals(2, groups[0].videos.size)
        assertEquals(10_000L, groups[0].totalBytes)
        assertEquals(1, groups[0].inVaultCount)
        assertEquals(listOf(cam2), groups[0].unencryptedVideos)
        assertEquals(1, groups[1].videos.size)
        assertEquals(0, groups[1].inVaultCount)
    }
}
