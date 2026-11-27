package cn.yingxia.player

import cn.yingxia.player.core.*
import cn.yingxia.player.data.LibraryBackup
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class SecurityAndBackupV2Test {
    @Test
    fun encryptedLocalBackupRoundTripAndRejectsWrongPassword() {
        val videos = listOf(
            VideoItem(
                id = VideoId("ms:external:101"),
                uri = "content://media/external/video/media/101",
                name = "night_walk.mp4",
                folder = "Travel",
                folderKey = "external:1",
                durationMs = 60_000L,
                sizeBytes = 20_971_520L,
                favorite = true,
                positionMs = 15_000L,
                lastPlayedAt = 1_795_000_000_000L,
                displayName = "Night Walk 4K",
                cropBars = true,
            )
        )
        val settings = UserSettings(theme = ThemeMode.DARK, speed = 1.25f, holdSpeed = 3f, sort = SortOrder.RECENT_PLAY, touchGuard = true)
        val bookmarks = listOf(Bookmark(1L, videos.first().id, 22_000L, "Starlight scene"))
        val encrypted = LibraryBackup.exportEncrypted(videos, settings, "VaultGradeBackup#9".toCharArray(), bookmarks)
        assertTrue(LibraryBackup.isEncryptedBackup(encrypted))
        val rawString = String(encrypted, Charsets.ISO_8859_1)
        assertFalse("Ciphertext must not leak display title", rawString.contains("Night Walk 4K"))
        assertFalse("Ciphertext must not leak bookmark note", rawString.contains("Starlight scene"))

        val parsed = LibraryBackup.decryptBackup(encrypted, "VaultGradeBackup#9".toCharArray())
        assertEquals(ThemeMode.DARK, parsed.settings.theme)
        assertEquals(1.25f, parsed.settings.speed)
        assertEquals(1, parsed.records.size)
        assertEquals("Night Walk 4K", parsed.records.single().displayName)
        assertEquals(1, parsed.bookmarks.size)
        assertEquals("Starlight scene", parsed.bookmarks.single().note)

        val plan = LibraryBackup.plan(videos, parsed)
        assertEquals(1, plan.matchedBookmarks.size)
        assertEquals(videos.first().id, plan.matchedBookmarks.single().first)
        assertEquals(22_000L, plan.matchedBookmarks.single().second.positionMs)

        assertThrows(AEADBadTagException::class.java) {
            LibraryBackup.decryptBackup(encrypted, "WrongPassword#123".toCharArray())
        }
    }
}
