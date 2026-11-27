package cn.yingxia.player.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Biometric unlock envelope for Encrypted Vault.
 * Wraps a dedicated random 256-bit credential in AndroidKeyStore (BIOMETRIC_STRONG),
 * cryptographically bound to SHA-256(keyset.bin). Any password change, reset, or
 * new OS biometric enrollment automatically invalidates the envelope.
 */
internal class VaultBiometric(
    private val context: Context,
    directory: File,
    private val syncDirectory: () -> Unit,
) {
    private val config = File(directory, "keyset.bin")
    private val envelope = File(directory, "biometric-v1.bin")
    private val alias = "yingxia.biometric.v1." + directory.name
    private val random = SecureRandom()

    fun hardwareAvailable(): Boolean {
        val mgr = BiometricManager.from(context)
        return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    val enabled: Boolean
        get() = runCatching {
            if (!hardwareAvailable() || !config.isFile || envelope.length() !in 92..66600) return false
            val prefix = ByteArray(32)
            DataInputStream(envelope.inputStream()).use { it.readFully(prefix) }
            MessageDigest.isEqual(prefix, fingerprint(config.readBytes())) && keyStore().containsAlias(alias)
        }.getOrDefault(false)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun fingerprint(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun createEnrollCipher(): Cipher {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        val secretKey = generator.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
    }

    fun completeEnroll(password: CharArray, authedCipher: Cipher) {
        check(config.length() in 60..65536) { "Vault is not configured" }
        val currentConfig = config.readBytes()
        val digest = fingerprint(currentConfig)
        val rawSecret = ByteArray(32).also(random::nextBytes)
        val hexChars = toHexChars(rawSecret)
        val credBytes = String(hexChars).toByteArray(Charsets.US_ASCII)
        try {
            val wrappedKeyset = try {
                VaultCrypto.rewrap(currentConfig, password) { hexChars.clone() }
            } catch (_: BadPaddingException) {
                error("Incorrect vault password")
            }
            authedCipher.updateAAD(digest)
            val encryptedCred = authedCipher.doFinal(credBytes)
            val iv = authedCipher.iv
            require(iv != null && iv.size == 12)
            val out = java.io.ByteArrayOutputStream()
            DataOutputStream(out).use { s ->
                s.write(digest)
                s.write(iv)
                s.writeInt(encryptedCred.size)
                s.write(encryptedCred)
                s.writeInt(wrappedKeyset.size)
                s.write(wrappedKeyset)
            }
            VaultCrypto.atomicWrite(envelope, out.toByteArray())
            syncDirectory()
        } finally {
            rawSecret.fill(0)
            hexChars.fill('\u0000')
            credBytes.fill(0)
            password.fill('\u0000')
        }
    }

    fun createUnlockCipher(): Cipher {
        check(enabled) { "Biometric unlock is not enabled" }
        val data = envelope.readBytes()
        val digest = fingerprint(config.readBytes())
        check(MessageDigest.isEqual(data.copyOfRange(0, 32), digest)) { "Vault password changed; re-enable biometric unlock" }
        val iv = data.copyOfRange(32, 44)
        val key = keyStore().getKey(alias, null) as? SecretKey ?: error("Biometric key unavailable")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            throw IllegalStateException("System biometrics changed; unlock with password", e)
        }
    }

    fun unlockWithCipher(authedCipher: Cipher): VaultSession {
        val data = envelope.readBytes()
        val digest = fingerprint(config.readBytes())
        check(MessageDigest.isEqual(data.copyOfRange(0, 32), digest)) { "Biometric envelope out of date" }
        DataInputStream(data.inputStream(44, data.size - 44)).use { input ->
            val encLen = input.readInt()
            require(encLen in 16..512 && encLen <= input.available())
            val encCred = ByteArray(encLen).also(input::readFully)
            val wrapLen = input.readInt()
            require(wrapLen in 60..65536 && wrapLen == input.available())
            val wrappedKeyset = ByteArray(wrapLen).also(input::readFully)
            authedCipher.updateAAD(digest)
            val credBytes = authedCipher.doFinal(encCred)
            val credChars = CharArray(credBytes.size) { credBytes[it].toInt().toChar() }
            try {
                return VaultCrypto.unlock(wrappedKeyset, credChars)
            } finally {
                credBytes.fill(0)
                credChars.fill('\u0000')
            }
        }
    }

    fun disable() {
        runCatching {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
        if (envelope.exists()) {
            envelope.delete()
            runCatching { syncDirectory() }
        }
    }

    private fun toHexChars(bytes: ByteArray): CharArray {
        val digits = "0123456789abcdef"
        return CharArray(bytes.size * 2) { i ->
            val v = bytes[i / 2].toInt()
            digits[(if (i % 2 == 0) v ushr 4 else v) and 15]
        }
    }
}
