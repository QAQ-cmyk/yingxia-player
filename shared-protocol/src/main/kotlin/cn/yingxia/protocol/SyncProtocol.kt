package cn.yingxia.protocol

import kotlinx.serialization.Serializable

@Serializable
data class RemoteMediaDto(
    val id: String,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val durationMs: Long = 0,
    val mimeType: String = "video/mp4",
    val sha256: String = "",
)

@Serializable
data class RemoteMediaListResponse(
    val serverVersion: String = "2.0.0",
    val items: List<RemoteMediaDto>,
)

@Serializable
data class SyncVideoRecordDto(
    val id: String,
    val uri: String = "",
    val name: String,
    val sizeBytes: Long,
    val favorite: Boolean,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val displayName: String? = null,
    val cropBars: Boolean = false,
)

@Serializable
data class SyncBookmarkDto(
    val videoName: String,
    val videoSizeBytes: Long,
    val positionMs: Long,
    val note: String,
)

@Serializable
data class PlainSyncSnapshotDto(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val records: List<SyncVideoRecordDto>,
    val bookmarks: List<SyncBookmarkDto> = emptyList(),
)

/**
 * Zero-Knowledge E2EE envelope: the server receives ONLY this opaque ciphertext envelope.
 * Passphrase and derived keys never leave the client device.
 */
@Serializable
data class EncryptedSyncEnvelopeDto(
    val schemaVersion: Int = 1,
    val revision: Long = 1,
    val updatedAtEpochMs: Long,
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
)

@Serializable
data class VaultBlobMetaDto(
    val blobName: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val updatedAtEpochMs: Long,
)

@Serializable
data class VaultBlobListResponse(
    val blobs: List<VaultBlobMetaDto>,
)
