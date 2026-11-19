package cn.yingxia.player.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Changing these prompts or their order requires a new recovery format. */
internal val recoveryQuestions = listOf("你会给一座小岛起什么名字？", "你想给秘密书房取什么名字？", "你想给未来的自己留什么暗号？")

internal class VaultRecovery(context: Context, directory: File, private val syncDirectory: () -> Unit) {
    private val config = File(directory, "keyset.bin")
    private val envelope = File(directory, "questions-v1.bin")
    private val alias = "yingxia.questions.v1." + directory.name
    private val attempts = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
    private fun configBytes(): ByteArray {
        check(config.length() in 60..65536) { "保险箱配置不可用" }
        return config.readBytes()
    }

    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    val enabled: Boolean get() {
        if (envelope.length() !in 92..65568 || !config.isFile) return false
        return runCatching {
            val prefix = ByteArray(32)
            DataInputStream(envelope.inputStream()).use { it.readFully(prefix) }
            MessageDigest.isEqual(prefix, fingerprint(configBytes()))
        }.getOrDefault(false)
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun responseKey(answers: List<CharArray>, fingerprint: ByteArray, create: Boolean): CharArray {
        require(answers.size == 3 && answers.all { it.size in 1..96 }) { "请填写三个答案" }
        val key = if (create) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setKeySize(256).build())
            }.generateKey()
        } else store().getKey(alias, null) as? SecretKey ?: error("此设备的密保密钥不可用，请使用原密码")
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        mac.update("YINGXIA-QUESTIONS-1".toByteArray(Charsets.US_ASCII))
        mac.update(fingerprint)
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

    fun enroll(password: CharArray, answers: List<CharArray>, beforeCommit: () -> Unit) {
        check(!enabled) { "请先关闭已有密保" }
        require(answers.size == 3 && answers.all { it.size in 4..96 }) { "每个答案需要 4–96 个字符" }
        require(answers.indices.all { i -> (0 until i).none { answers[it].contentEquals(answers[i]) } }) { "请使用三个不同的答案" }
        val current = configBytes()
        val fingerprint = fingerprint(current)
        val wrapped = try {
            VaultCrypto.rewrap(current, password) {
                beforeCommit()
                responseKey(answers, fingerprint, create = true)
            }
        }
        catch (_: BadPaddingException) { error("当前密码不正确，密保未开启") }
        beforeCommit()
        attempts.edit().clear().commit()
        VaultCrypto.atomicWrite(envelope, fingerprint + wrapped)
        syncDirectory()
    }

    fun reset(answers: List<CharArray>, password: CharArray, beforeCommit: () -> Unit) {
        check(enabled) { "此保险箱尚未设置可用的密保" }
        val wait = attempts.getLong("until", 0L) - System.currentTimeMillis()
        check(wait <= 0) { "请在 " + ((wait + 999) / 1000) + " 秒后再试" }
        val fingerprint = fingerprint(configBytes())
        val data = envelope.readBytes()
        check(MessageDigest.isEqual(data.copyOfRange(0, 32), fingerprint)) { "密保与当前保险箱不匹配" }
        val recovery = responseKey(answers, fingerprint, create = false)
        val replacement = try { VaultCrypto.rewrap(data.copyOfRange(32, data.size), recovery) { password } }
        catch (_: BadPaddingException) {
            val count = attempts.getInt("count", 0) + 1
            val edit = attempts.edit()
            if (count >= 5) edit.putInt("count", 0).putLong("until", System.currentTimeMillis() + 60_000L)
            else edit.putInt("count", count)
            check(edit.commit()) { "无法写入尝试记录，请稍后重试" }
            error(if (count >= 5) "连续五次未通过，请在一分钟后再试" else "三个答案未通过，或密保数据损坏")
        }
        beforeCommit()
        VaultCrypto.atomicWrite(config, replacement)
        syncDirectory()
        // The fingerprint invalidates the old envelope immediately, including across a crash.
        runCatching { retire() }
    }

    fun disable(password: CharArray, beforeCommit: () -> Unit) {
        try { VaultCrypto.unlock(configBytes(), password).close() }
        catch (_: BadPaddingException) { error("当前密码不正确，密保仍然有效") }
        beforeCommit()
        retire()
    }

    private fun retire() {
        store().deleteEntry(alias)
        if (envelope.exists()) check(envelope.delete()) { "请重新打开保险箱，核对密保状态" }
        attempts.edit().clear().commit()
    }
}
