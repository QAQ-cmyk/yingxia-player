package cn.yingxia.player.sync

import cn.yingxia.player.core.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SyncNodeConfig(
    val serverUrl: String = "",
    val bearerToken: String = "",
)

data class SyncStatusState(
    val available: Boolean = false,
    val configured: Boolean = false,
    val serverUrl: String = "",
    val remoteMediaCount: Int = 0,
    val lastSyncedAtEpochMs: Long = 0,
    val vaultBackupCount: Int = 0,
    val busy: Boolean = false,
    val statusMessage: String? = null,
)

/**
 * Compile-time isolated extension bridge.
 * In `offline` flavor, `available == false` and zero network classes are compiled into the APK.
 * In `sync` flavor, connects to the user's self-hosted Kotlin + Ktor server for HTTP 206 streaming
 * and Zero-Knowledge E2EE snapshot & vault ciphertext backup.
 */
interface SyncExtensionBridge {
    val enabled: Boolean
    val state: StateFlow<SyncStatusState>
    val extraMediaSources: List<MediaSource>
    fun customExoMediaSource(video: cn.yingxia.player.core.VideoItem): androidx.media3.exoplayer.source.MediaSource? = null
    suspend fun updateConfig(serverUrl: String, bearerToken: String) {}
    suspend fun refreshRemoteMedia(): Result<Int> = Result.success(0)
    suspend fun pushEncryptedSnapshot(passphrase: CharArray): Result<Unit> {
        passphrase.fill('\u0000')
        return Result.failure(UnsupportedOperationException("Sync disabled in offline edition"))
    }
    suspend fun pullEncryptedSnapshot(passphrase: CharArray): Result<Int> {
        passphrase.fill('\u0000')
        return Result.failure(UnsupportedOperationException("Sync disabled in offline edition"))
    }
    suspend fun backupVaultBlobs(): Result<Int> =
        Result.failure(UnsupportedOperationException("Sync disabled in offline edition"))
    suspend fun restoreVaultBlobs(password: CharArray): Result<Int> {
        password.fill('\u0000')
        return Result.failure(UnsupportedOperationException("Sync disabled in offline edition"))
    }
}

object DisabledSyncBridge : SyncExtensionBridge {
    override val enabled: Boolean = false
    override val state: StateFlow<SyncStatusState> = MutableStateFlow(SyncStatusState(available = false))
    override val extraMediaSources: List<MediaSource> = emptyList()
}
