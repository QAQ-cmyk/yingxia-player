package cn.yingxia.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Zero-Knowledge encryption for sync snapshots.
 * Uses PBKDF2-HMAC-SHA256 (600,000 iterations) + AES-256-GCM (128-bit authentication tag)
 * with versioned AAD binding schemaVersion, revision, and timestamp.
 */
object ZeroKnowledgeCrypto {
    private const val ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun sealSnapshot(
        snapshot: PlainSyncSnapshotDto,
        passphrase: CharArray,
        revision: Long = 1L,
        updatedAtEpochMs: Long = snapshot.exportedAtEpochMs,
    ): EncryptedSyncEnvelopeDto {
        require(passphrase.size in 8..128) { "Sync passphrase must be 8–128 characters" }
        val plainBytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(1, revision, updatedAtEpochMs, salt))
            val ciphertext = cipher.doFinal(plainBytes)
            val enc = Base64.getEncoder()
            return EncryptedSyncEnvelopeDto(
                schemaVersion = 1,
                revision = revision,
                updatedAtEpochMs = updatedAtEpochMs,
                saltBase64 = enc.encodeToString(salt),
                ivBase64 = enc.encodeToString(iv),
                ciphertextBase64 = enc.encodeToString(ciphertext),
            )
        } finally {
            plainBytes.fill(0)
            key.fill(0)
        }
    }

    fun openSnapshot(envelope: EncryptedSyncEnvelopeDto, passphrase: CharArray): PlainSyncSnapshotDto {
        require(envelope.schemaVersion == 1) { "Unsupported sync envelope schema: ${envelope.schemaVersion}" }
        require(passphrase.size in 8..128) { "Sync passphrase must be 8–128 characters" }
        val dec = Base64.getDecoder()
        val salt = dec.decode(envelope.saltBase64)
        val iv = dec.decode(envelope.ivBase64)
        val ciphertext = dec.decode(envelope.ciphertextBase64)
        require(salt.size == 16 && iv.size == 12 && ciphertext.size >= 16) { "Malformed sync envelope" }
        val key = deriveKey(passphrase, salt)
        var clear: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(envelope.schemaVersion, envelope.revision, envelope.updatedAtEpochMs, salt))
            clear = cipher.doFinal(ciphertext)
            return json.decodeFromString(clear.toString(Charsets.UTF_8))
        } finally {
            key.fill(0)
            clear?.fill(0)
        }
    }

    private fun aad(schemaVersion: Int, revision: Long, updatedAtEpochMs: Long, salt: ByteArray): ByteArray =
        "yingxia-zk-sync-v$schemaVersion:rev=$revision:ts=$updatedAtEpochMs:".toByteArray(Charsets.US_ASCII) + salt

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
