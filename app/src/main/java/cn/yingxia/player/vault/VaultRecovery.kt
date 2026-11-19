package cn.yingxia.player.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Persist these IDs. Keep existing prompts stable when extending the list.
internal val recoveryQuestions = mapOf(
    1 to "你会给一座小岛起什么名字？",
    2 to "你想给秘密书房取什么名字？",
    3 to "你想给未来的自己留什么暗号？",
    4 to "如果开一家深夜小店，你会卖什么？",
    5 to "如果有一扇任意门，你想把它藏在哪里？",
    6 to "如果写一本探险日记，你会用哪句话开头？",
    7 to "如果发明一种冰淇淋，你想用什么配料？",
    8 to "如果能给月亮寄一件礼物，你会寄什么？",
    9 to "如果有一台私人电台，你想用什么开场白？",
)

internal fun localizedRecoveryPresets(context: Context): List<String> =
    AppLocale.stringArray(context, R.array.vault_recovery_presets).toList()

internal const val maxRecoveryPromptLength = 80
internal fun validRecoveryPrompts(prompts: List<String>): Boolean =
    prompts.size == 3 && prompts.distinct().size == 3 && prompts.all { prompt ->
        prompt.isNotBlank() && prompt == prompt.trim() && prompt.length <= maxRecoveryPromptLength &&
            prompt.none(Char::isISOControl)
    }

internal class VaultRecovery(private val context: Context, directory: File, private val syncDirectory: () -> Unit) {
    private fun str(id: Int, vararg args: Any) = AppLocale.str(context, id, *args)
    private val config = File(directory, "keyset.bin")
    private val legacyEnvelope = File(directory, "questions-v1.bin")
    private val presetEnvelope = File(directory, "questions-v2.bin")
    private val envelope = File(directory, "questions-v3.bin")
    private val alias = "yingxia.questions.v1." + directory.name
    private val promptAlias = "yingxia.prompts.v3." + directory.name
    private val attempts = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
    private fun configBytes(): ByteArray {
        check(config.length() in 60..65536) { str(R.string.vault_recovery_config_unavailable) }
        return config.readBytes()
    }

    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class Enrollment(val version: Int, val prompts: List<String>, val binding: ByteArray,
                                  val fingerprint: ByteArray, val wrapped: ByteArray)

    private fun activeFile(): File = when {
        envelope.exists() -> envelope
        presetEnvelope.exists() -> presetEnvelope
        else -> legacyEnvelope
    }

    val enabled: Boolean get() = runCatching {
        val file = activeFile()
        if (!config.isFile || file.length() !in 92..66600) return false
        val prefix = ByteArray(32)
        DataInputStream(file.inputStream()).use { it.readFully(prefix) }
        MessageDigest.isEqual(prefix, fingerprint(configBytes()))
    }.getOrDefault(false)

    private fun encodePrompts(prompts: List<String>): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { output -> prompts.forEach(output::writeUTF) }
    }.toByteArray()

    private fun readEnrollment(): Enrollment {
        val file = activeFile()
        check(file.length() in 92..66600 && config.isFile) { str(R.string.vault_recovery_not_configured) }
        val data = file.readBytes()
        val digest = fingerprint(configBytes())
        check(MessageDigest.isEqual(data.copyOfRange(0, 32), digest)) { str(R.string.vault_recovery_mismatch) }
        if (file == envelope) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = store().getKey(promptAlias, null) as? SecretKey ?: error(str(R.string.vault_recovery_prompt_key_unavailable))
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 32, 12))
            cipher.updateAAD(digest)
            val clear = cipher.doFinal(data, 44, data.size - 44)
            return try {
                DataInputStream(clear.inputStream()).use { input ->
                    val prompts = List(3) { input.readUTF() }
                    require(validRecoveryPrompts(prompts) && input.available() in 60..65536)
                    Enrollment(3, prompts, encodePrompts(prompts), digest, input.readBytes())
                }
            } finally { clear.fill(0) }
        }
        val preset = file == presetEnvelope
        val ids = if (preset) (32 until 35).map { data[it].toInt() and 255 } else listOf(1, 2, 3)
        require(ids.distinct().size == 3 && ids.all { it in recoveryQuestions })
        val binding = if (preset) data.copyOfRange(32, 35) else byteArrayOf()
        return Enrollment(if (preset) 2 else 1, ids.map(recoveryQuestions::getValue), binding, digest,
            data.copyOfRange(32 + binding.size, data.size))
    }

    fun enrolledPrompts(): List<String> = readEnrollment().prompts

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun responseKey(answers: List<CharArray>, fingerprint: ByteArray, version: Int, binding: ByteArray, create: Boolean): CharArray {
        require(answers.size == 3 && answers.all { it.size in 1..96 }) { str(R.string.vault_recovery_fill_three) }
        val key = if (create) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setKeySize(256).build())
            }.generateKey()
        } else store().getKey(alias, null) as? SecretKey ?: error(str(R.string.vault_recovery_device_key_unavailable))
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val domain = "YINGXIA-QUESTIONS-" + version
        mac.update(domain.toByteArray(Charsets.US_ASCII))
        mac.update(fingerprint)
        mac.update(binding)
        for (answer in answers) {
            mac.update(answer.size.toByte())
            for (ch in answer) {
                mac.update((ch.code ushr 8).toByte())
                mac.update(ch.code.toByte())
            }
        }
        val digest = mac.doFinal()
        val digits = "0123456789abcdef"
        return try {
            CharArray(digest.size * 2) { i ->
                val value = digest[i / 2].toInt()
                digits[(if (i % 2 == 0) value ushr 4 else value) and 15]
            }
        } finally { digest.fill(0) }
    }

    fun enroll(password: CharArray, prompts: List<String>, answers: List<CharArray>, beforeCommit: () -> Unit) {
        check(!enabled) { str(R.string.vault_recovery_disable_first) }
        require(validRecoveryPrompts(prompts)) { str(R.string.vault_recovery_prompts_invalid) }
        require(answers.size == 3 && answers.all { it.size in 4..96 }) { str(R.string.vault_recovery_answers_length) }
        require(answers.indices.all { i -> (0 until i).none { answers[it].contentEquals(answers[i]) } }) { str(R.string.vault_recovery_answers_distinct) }
        val current = configBytes()
        val fingerprint = fingerprint(current)
        val binding = encodePrompts(prompts)
        val wrapped = try {
            VaultCrypto.rewrap(current, password) {
                beforeCommit()
                responseKey(answers, fingerprint, 3, binding, create = true)
            }
        }
        catch (_: BadPaddingException) { error(str(R.string.vault_recovery_wrong_pwd_enroll)) }
        val promptKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(promptAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, promptKey)
        cipher.updateAAD(fingerprint)
        val encrypted = cipher.doFinal(binding + wrapped)
        beforeCommit()
        attempts.edit().clear().commit()
        VaultCrypto.atomicWrite(envelope, fingerprint + cipher.iv + encrypted)
        syncDirectory()
    }

    fun reset(answers: List<CharArray>, password: CharArray, beforeCommit: () -> Unit) {
        val wait = attempts.getLong("until", 0L) - System.currentTimeMillis()
        check(wait <= 0) { str(R.string.vault_recovery_cooldown, (wait + 999) / 1000) }
        val enrollment = readEnrollment()
        val recovery = responseKey(answers, enrollment.fingerprint, enrollment.version, enrollment.binding, create = false)
        val replacement = try { VaultCrypto.rewrap(enrollment.wrapped, recovery) { password } }
        catch (_: BadPaddingException) {
            val count = attempts.getInt("count", 0) + 1
            val edit = attempts.edit()
            if (count >= 5) edit.putInt("count", 0).putLong("until", System.currentTimeMillis() + 60_000L)
            else edit.putInt("count", count)
            check(edit.commit()) { str(R.string.vault_recovery_attempt_write_failed) }
            error(str(if (count >= 5) R.string.vault_recovery_locked_out else R.string.vault_recovery_answers_wrong))
        }
        beforeCommit()
        VaultCrypto.atomicWrite(config, replacement)
        syncDirectory()
        // The fingerprint invalidates the old envelope immediately, including across a crash.
        runCatching { retire() }
    }

    fun disable(password: CharArray, beforeCommit: () -> Unit) {
        try { VaultCrypto.unlock(configBytes(), password).close() }
        catch (_: BadPaddingException) { error(str(R.string.vault_recovery_wrong_pwd_disable)) }
        beforeCommit()
        retire()
    }

    private fun retire() {
        store().apply { deleteEntry(alias); if (containsAlias(promptAlias)) deleteEntry(promptAlias) }
        for (file in listOf(legacyEnvelope, presetEnvelope, envelope)) {
            if (file.exists()) check(file.delete()) { str(R.string.vault_recovery_recheck_status) }
        }
        attempts.edit().clear().commit()
    }
}
