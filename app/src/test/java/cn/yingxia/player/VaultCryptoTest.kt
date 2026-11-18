package cn.yingxia.player

import cn.yingxia.player.vault.VaultCrypto
import cn.yingxia.player.vault.VaultSession
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID

class VaultCryptoTest {
    @get:Rule val temp = TemporaryFolder()
    companion object {
        private lateinit var config: ByteArray
        private lateinit var session: VaultSession
        @JvmStatic @BeforeClass fun setup() {
            val created = VaultCrypto.create("a long private password".toCharArray())
            config = created.first; session = created.second
        }
    }
    private fun assertRejected(action: () -> Unit) {
        try { action(); fail("Unauthenticated data must be rejected") } catch (e: Exception) { /* expected */ }
    }
    private fun readAll(channel: java.nio.channels.SeekableByteChannel): ByteArray = java.io.ByteArrayOutputStream().also {
        // Tink cannot report size until it authenticates its first segment. Do not call available().
        Channels.newInputStream(channel).copyTo(it)
    }.toByteArray()
    @Test fun passwordRoundTripRejectsWrongPasswordAndWipesInput() {
        val wrong = "wrong private password".toCharArray()
        assertRejected { VaultCrypto.unlock(config, wrong) }
        assertTrue(wrong.all { it == '\u0000' })
        val correct = "a long private password".toCharArray()
        VaultCrypto.unlock(config, correct).use { unlocked ->
            val cipher = session.encryptMetadata("id", "私人视频名字".toByteArray())
            assertEquals("私人视频名字", String(unlocked.decryptMetadata("id", cipher)))
        }
        assertTrue(correct.all { it == '\u0000' })
    }
    @Test fun configAndMetadataTamperingAreRejected() {
        val modified = config.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertRejected { VaultCrypto.unlock(modified, "a long private password".toCharArray()) }
        val cipher = session.encryptMetadata("first", "secret".toByteArray())
        assertRejected { session.decryptMetadata("second", cipher) }
        cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
        assertRejected { session.decryptMetadata("first", cipher) }
    }
    @Test fun multiSegmentRoundTripAndRandomSeeking() {
        val bytes = ByteArray(3 * 1024 * 1024 + 123) { ((it * 31 + it / 7) % 251).toByte() }
        val id = UUID.randomUUID().toString(); val file = temp.newFile()
        val written = session.encrypt(id, ByteArrayInputStream(bytes), file, {}, {})
        assertEquals(bytes.size.toLong(), written.first)
        val reader = session.open(id, file)
        try {
            assertArrayEquals(bytes, readAll(reader))
            for (offset in listOf(0, 1024 * 1024 - 25, 1024 * 1024 + 11, bytes.size - 17, 64)) {
                reader.position(offset.toLong())
                val count = minOf(8192, bytes.size - offset)
                val buffer = ByteBuffer.allocate(count)
                while (buffer.hasRemaining()) assertTrue(reader.read(buffer) > 0)
                assertArrayEquals(bytes.copyOfRange(offset, offset + count), buffer.array())
            }
        } finally { session.closeReader(reader) }
        assertRejected { session.open("different-id", file).use { readAll(it) } }
    }
    @Test fun tamperedAndTruncatedVideosDoNotVerify() {
        val id = UUID.randomUUID().toString(); val file = temp.newFile()
        session.encrypt(id, ByteArrayInputStream(ByteArray(1200000) { 7 }), file, {}, {})
        val valid = file.readBytes()
        file.writeBytes(valid.copyOf(valid.size - 1))
        assertRejected { session.open(id, file).use { readAll(it) } }
        valid[100] = (valid[100].toInt() xor 32).toByte(); file.writeBytes(valid)
        assertRejected { session.open(id, file).use { readAll(it) } }
    }
    @Test fun lockClosesExistingReadersAndPreventsNewReads() {
        val unlocked = VaultCrypto.unlock(config, "a long private password".toCharArray())
        val id = UUID.randomUUID().toString(); val file = temp.newFile()
        unlocked.encrypt(id, ByteArrayInputStream(ByteArray(1024)), file, {}, {})
        val reader = unlocked.open(id, file)
        unlocked.close()
        assertFalse(reader.isOpen)
        assertRejected { unlocked.open(id, file) }
        assertRejected { unlocked.encryptMetadata(id, byteArrayOf(1)) }
    }
    @Test fun interruptedEncryptionNeverChangesTheInput() {
        val bytes = ByteArray(2 * 1024 * 1024) { 42 }
        val original = bytes.copyOf()
        var chunks = 0
        assertRejected { session.encrypt("cancelled", ByteArrayInputStream(bytes), temp.newFile(), {
            if (++chunks == 3) throw java.util.concurrent.CancellationException()
        }, {}) }
        assertArrayEquals(original, bytes)
    }
}
