package cn.yingxia.protocol

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class ZeroKnowledgeCryptoTest {
    @Test
    fun roundTripAndTamperRejection() {
        val snap = PlainSyncSnapshotDto(
            exportedAtEpochMs = 1_795_670_000_000L,
            records = listOf(
                SyncVideoRecordDto(
                    id = "media:1",
                    name = "lecture.mp4",
                    sizeBytes = 1048576L,
                    favorite = true,
                    positionMs = 42_000L,
                    lastPlayedAt = 1_795_669_000_000L,
                    displayName = "Kotlin Lecture",
                    cropBars = true,
                )
            ),
            bookmarks = listOf(SyncBookmarkDto("lecture.mp4", 1048576L, 12_000L, "Key concept")),
        )
        val env = ZeroKnowledgeCrypto.sealSnapshot(snap, "CorrectHorseBattery9!".toCharArray(), revision = 3L)
        assertFalse(env.ciphertextBase64.contains("Kotlin Lecture"))

        val opened = ZeroKnowledgeCrypto.openSnapshot(env, "CorrectHorseBattery9!".toCharArray())
        assertEquals(snap, opened)

        // Wrong passphrase must fail AEAD authentication
        assertThrows(AEADBadTagException::class.java) {
            ZeroKnowledgeCrypto.openSnapshot(env, "WrongPassphrase123!".toCharArray())
        }

        // Tampered revision metadata must fail AAD authentication
        assertThrows(AEADBadTagException::class.java) {
            ZeroKnowledgeCrypto.openSnapshot(env.copy(revision = 4L), "CorrectHorseBattery9!".toCharArray())
        }
    }
}
