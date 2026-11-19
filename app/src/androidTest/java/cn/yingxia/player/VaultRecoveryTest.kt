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
    private fun answers() = phrases.map { it.toCharArray() }
    private lateinit var vault: VaultStore

    @Before fun setup() {
        vault = VaultStore(context, directory)
        vault.setup(old.toCharArray())
    }

    @After fun clean() {
        vault.lock()
        val alias = "yingxia.questions.v1." + directory.name
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
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
        val sidecar = File(directory, "questions-v1.bin")
        assertFalse(vault.recovery.enabled)
        rejected { vault.recovery.enroll(fresh.toCharArray(), answers()) {} }
        assertFalse(sidecar.exists())
        vault.recovery.enroll(old.toCharArray(), answers()) {}
        assertArrayEquals(original, config.readBytes())
        val enrolled = sidecar.readBytes()
        for (phrase in phrases + old) assertFalse(enrolled.toString(Charsets.UTF_8).contains(phrase))
        vault.lock()
        vault = VaultStore(context, directory)
        assertTrue(vault.recovery.enabled)
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
        val duplicate = List(3) { "一个答案".toCharArray() }
        rejected { vault.recovery.enroll(old.toCharArray(), duplicate) {} }
        vault.recovery.enroll(old.toCharArray(), answers()) {}
        repeat(5) {
            rejected { vault.recovery.reset(answers().reversed(), fresh.toCharArray()) {} }
        }
        vault.lock()
        vault = VaultStore(context, directory)
        assertTrue(rejected { vault.recovery.reset(answers(), fresh.toCharArray()) {} }.message.orEmpty().contains("秒后"))
        vault.unlock(old.toCharArray())
        rejected { vault.recovery.disable(fresh.toCharArray()) {} }
        assertTrue(vault.recovery.enabled)
        vault.recovery.disable(old.toCharArray()) {}
        assertFalse(vault.recovery.enabled)
        rejected { vault.recovery.reset(answers(), fresh.toCharArray()) {} }
    }
}
