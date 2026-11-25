package cn.yingxia.player

import cn.yingxia.player.vault.VaultBackupFileInfo
import cn.yingxia.player.vault.VaultBackupManifest
import cn.yingxia.player.vault.VaultBackupRules
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VaultBackupRulesTest {
    @Test fun artifactWhitelistAllowsOnlyKeysetAndCanonicalUuidCiphertexts() {
        val id = UUID.randomUUID().toString()
        assertTrue(VaultBackupRules.isSafeVaultArtifactName("keyset.bin"))
        assertTrue(VaultBackupRules.isSafeVaultArtifactName("$id.vault"))
        assertTrue(VaultBackupRules.isSafeVaultArtifactName("$id.meta"))
        assertTrue(VaultBackupRules.isSafeVaultArtifactName("$id.playback"))

        // Device-bound recovery files, temp files, media files, and traversal attempts must be rejected.
        listOf(
            "questions-v1.bin",
            "questions-v2.bin",
            "questions-v3.bin",
            "$id.part",
            "$id.tmp",
            "$id.mp4",
            "../keyset.bin",
            "sub/$id.vault",
            "sub\\$id.vault",
            ".nomedia",
            "manifest.json",
            "${id.uppercase()}.vault",
            "not-a-uuid.vault",
        ).forEach { name ->
            assertFalse("Should reject $name", VaultBackupRules.isSafeVaultArtifactName(name))
        }
    }

    @Test fun manifestRoundTripAndRejectsUnsafeOrCorruptEntries() {
        val id = UUID.randomUUID().toString()
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val manifest = VaultBackupManifest(
            version = 1,
            exportedAt = 1_764_000_000_000L,
            files = mapOf(
                "keyset.bin" to VaultBackupFileInfo("keyset.bin", 180L, hashA),
                "$id.vault" to VaultBackupFileInfo("$id.vault", 4_096L, hashB),
            ),
        )
        val parsed = VaultBackupManifest.parse(manifest.toJson())
        assertEquals(1, parsed.version)
        assertEquals(1_764_000_000_000L, parsed.exportedAt)
        assertEquals(2, parsed.files.size)
        assertEquals(hashB, parsed.files["$id.vault"]?.sha256)

        val badPathJson = """{"kind":"yingxia-vault-backup","version":1,"exportedAt":1,"files":{"questions-v3.bin":{"bytes":100,"sha256":"${"c".repeat(64)}"}}}"""
        assertThrows(IllegalArgumentException::class.java) { VaultBackupManifest.parse(badPathJson) }

        val badHashJson = """{"kind":"yingxia-vault-backup","version":1,"exportedAt":1,"files":{"keyset.bin":{"bytes":100,"sha256":"short"}}}"""
        assertThrows(IllegalArgumentException::class.java) { VaultBackupManifest.parse(badHashJson) }
    }

    @Test fun incrementalSyncSkipsUnchangedUpdatesChangedAndPrunesDeleted() {
        val keptId = UUID.randomUUID().toString()
        val newId = UUID.randomUUID().toString()
        val removedId = UUID.randomUUID().toString()
        val hKeyOld = "1".repeat(64)
        val hKeyNew = "2".repeat(64)
        val hKept = "3".repeat(64)
        val hNew = "4".repeat(64)

        val local = mapOf(
            "keyset.bin" to VaultBackupFileInfo("keyset.bin", 200L, hKeyNew),
            "$keptId.vault" to VaultBackupFileInfo("$keptId.vault", 50_000L, hKept),
            "$newId.vault" to VaultBackupFileInfo("$newId.vault", 10_000L, hNew),
        )
        val remoteFiles = mapOf(
            "keyset.bin" to 200L,
            "$keptId.vault" to 50_000L,
            "$removedId.vault" to 9_000L,
            "user-readme.txt" to 123L, // Unrelated user file must never be pruned.
        )
        val remoteManifest = VaultBackupManifest(
            version = 1,
            exportedAt = 100L,
            files = mapOf(
                "keyset.bin" to VaultBackupFileInfo("keyset.bin", 200L, hKeyOld),
                "$keptId.vault" to VaultBackupFileInfo("$keptId.vault", 50_000L, hKept),
                "$removedId.vault" to VaultBackupFileInfo("$removedId.vault", 9_000L, "5".repeat(64)),
            ),
        )

        val plan = VaultBackupRules.computeSyncPlan(local, remoteFiles, remoteManifest)
        assertEquals(listOf("$keptId.vault"), plan.toSkip)
        assertEquals(listOf("$newId.vault", "keyset.bin"), plan.toWrite)
        assertEquals(listOf("$removedId.vault"), plan.toDelete)
    }

    @Test fun selectedRootRulePreventsNestedYingXiaVaultDirectory() {
        assertTrue(VaultBackupRules.shouldUseSelectedRootDirectly("YingXiaVault", emptySet()))
        assertTrue(VaultBackupRules.shouldUseSelectedRootDirectly("yingxiavault", emptySet()))
        assertTrue(VaultBackupRules.shouldUseSelectedRootDirectly("CustomBackup", setOf("keyset.bin")))
        assertTrue(VaultBackupRules.shouldUseSelectedRootDirectly("CustomBackup", setOf("manifest.json")))
        assertFalse(VaultBackupRules.shouldUseSelectedRootDirectly("Documents", setOf("other.txt")))
    }
}
