package cn.yingxia.player.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

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

private val legacyQuestionIds = listOf(1, 2, 3)
private fun validSelection(ids: List<Int>) =
    ids.size == 3 && ids.distinct().size == 3 && ids.all { it in recoveryQuestions }

internal class VaultRecovery(context: Context, directory: File, private val syncDirectory: () -> Unit) {
    private val config = File(directory, "keyset.bin")
    private val legacyEnvelope = File(directory, "questions-v1.bin")
    private val envelope = File(directory, "questions-v2.bin")
    private val alias = "yingxia.questions.v1." + directory.name
    private val attempts = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
    private fun configBytes(): ByteArray {
        check(config.length() in 60..65536) { "保险箱配置不可用" }
        return config.readBytes()
    }

    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class Enrollment(val legacy: Boolean, val ids: List<Int>, val fingerprint: ByteArray, val wrapped: ByteArray)

    private fun readEnrollment(): Enrollment? {
        val legacy = !envelope.exists()
        val file = if (legacy) legacyEnvelope else envelope
        val header = if (legacy) 32 else 35
        if (file.length() !in (header + 60L)..(header + 65536L) || !config.isFile) return null
        return runCatching {
            val data = file.readBytes()
            val digest = fingerprint(configBytes())
            if (!MessageDigest.isEqual(data.copyOfRange(0, 32), digest)) return null
            val ids = if (legacy) legacyQuestionIds else (32 until header).map { data[it].toInt() and 255 }
            if (!validSelection(ids)) return null
            Enrollment(legacy, ids, digest, data.copyOfRange(header, data.size))
        }.getOrNull()
    }

    val questionIds: List<Int> get() = readEnrollment()?.ids.orEmpty()
    val enabled: Boolean get() = questionIds.isNotEmpty()

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun responseKey(answers: List<CharArray>, fingerprint: ByteArray, ids: List<Int>, legacy: Boolean, create: Boolean): CharArray {
        require(answers.size == 3 && answers.all { it.size in 1..96 }) { "请填写三个答案" }
        val key = if (create) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setKeySize(256).build())
            }.generateKey()
        } else store().getKey(alias, null) as? SecretKey ?: error("此设备的密保密钥不可用，请使用原密码")
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val domain = if (legacy) "YINGXIA-QUESTIONS-1" else "YINGXIA-QUESTIONS-2"
        mac.update(domain.toByteArray(Charsets.US_ASCII))
        mac.update(fingerprint)
        if (!legacy) ids.forEach { mac.update(it.toByte()) }
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

    fun enroll(password: CharArray, ids: List<Int>, answers: List<CharArray>, beforeCommit: () -> Unit) {
        check(!enabled) { "请先关闭已有密保" }
        require(validSelection(ids)) { "请选择三个不同的密保问题" }
        require(answers.size == 3 && answers.all { it.size in 4..96 }) { "每个答案需要 4–96 个字符" }
        require(answers.indices.all { i -> (0 until i).none { answers[it].contentEquals(answers[i]) } }) { "请使用三个不同的答案" }
        val current = configBytes()
        val fingerprint = fingerprint(current)
        val wrapped = try {
            VaultCrypto.rewrap(current, password) {
                beforeCommit()
                responseKey(answers, fingerprint, ids, legacy = false, create = true)
            }
        }
        catch (_: BadPaddingException) { error("当前密码不正确，密保未开启") }
        beforeCommit()
        attempts.edit().clear().commit()
        VaultCrypto.atomicWrite(envelope, fingerprint + ids.map { it.toByte() }.toByteArray() + wrapped)
        syncDirectory()
    }

    fun reset(answers: List<CharArray>, password: CharArray, beforeCommit: () -> Unit) {
        val enrollment = checkNotNull(readEnrollment()) { "此保险箱尚未设置可用的密保" }
        val wait = attempts.getLong("until", 0L) - System.currentTimeMillis()
        check(wait <= 0) { "请在 " + ((wait + 999) / 1000) + " 秒后再试" }
        val recovery = responseKey(answers, enrollment.fingerprint, enrollment.ids, enrollment.legacy, create = false)
        val replacement = try { VaultCrypto.rewrap(enrollment.wrapped, recovery) { password } }
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
        for (file in listOf(legacyEnvelope, envelope)) {
            if (file.exists()) check(file.delete()) { "请重新打开保险箱，核对密保状态" }
        }
        attempts.edit().clear().commit()
    }
}
