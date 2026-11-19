package cn.yingxia.player

import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.vault.VaultStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels
import java.security.KeyStore
import java.util.UUID

class VaultRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "recovery-test-" + UUID.randomUUID())
    private val old = "test vault password"
    private val fresh = "new vault password"
    private val phrases = listOf("白帆的小岛", "我的书房 short", "明年的礼物 Seven")
    private val chosen = listOf("第一次录下的雨声在哪里？", "What did I call the blue door?", "月亮收到的礼物 🌙")
    private fun answers() = phrases.map { it.toCharArray() }
    private lateinit var vault: VaultStore

    @Before fun setup() {
        vault = VaultStore(context, directory)
        vault.setup(old.toCharArray())
    }

    @After fun clean() {
        vault.lock()
        val alias = "yingxia.questions.v1." + directory.name
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias); deleteEntry("yingxia.prompts.v3." + directory.name) }
        context.deleteSharedPreferences(alias)
        directory.deleteRecursively()
    }

    private fun rejected(block: () -> Unit): Exception {
        try { block() } catch (expected: Exception) { return expected }
        throw AssertionError("Expected operation to fail")
    }

    @Test fun recoverySurvivesReopenPreservesVideoAndRevokesOldCredentials() {
        val id = UUID.randomUUID().toString()
        val payload = vault.payload(id)
        val sample = ByteArray(1_070_013) { (it % 251).toByte() }
        val meta = vault.requireSession().encryptMetadata(id, "私人片段".toByteArray())
        vault.requireSession().encrypt(id, sample.inputStream(), payload, {}, {})
        val diskPayload = payload.readBytes()
        val config = File(directory, "keyset.bin")
        val original = config.readBytes()
        val sidecar = File(directory, "questions-v3.bin")
        assertFalse(vault.recovery.enabled)
        rejected { vault.recovery.enroll(fresh.toCharArray(), chosen, answers()) {} }
        assertFalse(sidecar.exists())
        vault.recovery.enroll(old.toCharArray(), chosen, answers()) {}
        assertArrayEquals(original, config.readBytes())
        val enrolled = sidecar.readBytes()
        assertEquals(chosen, VaultStore(context, directory).recovery.enrolledPrompts())
        for (phrase in chosen + phrases + old) assertFalse(enrolled.toString(Charsets.UTF_8).contains(phrase))
        vault.lock()
        vault = VaultStore(context, directory)
        assertTrue(vault.recovery.enabled)
        sidecar.writeBytes(enrolled.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        rejected { vault.recovery.enrolledPrompts() }
        rejected { vault.recovery.reset(answers(), fresh.toCharArray()) {} }
        assertArrayEquals(original, config.readBytes())
        sidecar.writeBytes(enrolled)
        rejected { vault.recovery.reset(answers().reversed(), fresh.toCharArray()) {} }
        rejected { vault.recovery.reset(answers(), fresh.toCharArray()) { error("cancelled") } }
        assertArrayEquals(original, config.readBytes())
        vault.recovery.reset(answers(), fresh.toCharArray()) {}
        rejected { vault.unlock(old.toCharArray()) }
        vault.unlock(fresh.toCharArray())
        assertArrayEquals(diskPayload, payload.readBytes())
        vault.requireSession().open(id, payload).use {
            val decoded = ByteArrayOutputStream()
            Channels.newInputStream(it).copyTo(decoded)
            assertArrayEquals(sample, decoded.toByteArray())
        }
        assertEquals("私人片段", vault.requireSession().decryptMetadata(id, meta).toString(Charsets.UTF_8))
        assertFalse(vault.recovery.enabled)
        sidecar.writeBytes(enrolled)
        assertFalse(vault.recovery.enabled)
        rejected { vault.recovery.reset(answers(), old.toCharArray()) {} }
    }

    @Test fun cooldownSurvivesReopenAndDisableRequiresPassword() {
        for (invalid in listOf(chosen.take(2), chosen + "第四题", listOf("a", "a", "b"),
            listOf("a", "b", " "), listOf("a", "b", "x".repeat(81)), listOf("a", "b", "two\nlines"))) {
            rejected { vault.recovery.enroll(old.toCharArray(), invalid, answers()) {} }
            assertFalse(vault.recovery.enabled)
        }
        val duplicate = List(3) { "一个答案".toCharArray() }
        rejected { vault.recovery.enroll(old.toCharArray(), chosen, duplicate) {} }
        vault.recovery.enroll(old.toCharArray(), chosen, answers()) {}
        repeat(5) {
            rejected { vault.recovery.reset(answers().reversed(), fresh.toCharArray()) {} }
        }
        vault.lock()
        vault = VaultStore(context, directory)
        val cooldownMsg = rejected { vault.recovery.reset(answers(), fresh.toCharArray()) {} }.message.orEmpty()
        assertTrue(cooldownMsg, cooldownMsg.contains("秒后") || cooldownMsg.contains("seconds", ignoreCase = true))
        vault.unlock(old.toCharArray())
        rejected { vault.recovery.disable(fresh.toCharArray()) {} }
        assertTrue(vault.recovery.enabled)
        vault.recovery.disable(old.toCharArray()) {}
        assertFalse(vault.recovery.enabled)
        rejected { vault.recovery.reset(answers(), fresh.toCharArray()) {} }
    }
}
