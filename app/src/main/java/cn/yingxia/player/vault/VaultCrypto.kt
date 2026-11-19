package cn.yingxia.player.vault

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.*
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned on-disk format. Passwords and clear keysets are never written to disk. */
object VaultCrypto {
    private const val ITERATIONS = 600_000
    private val header = "YINGXIA-VAULT-1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()
    init { AeadConfig.register(); StreamingAeadConfig.register() }

    fun create(password: CharArray): Pair<ByteArray, VaultSession> {
        require(password.size in 8..128) { "密码需要 8–128 个字符" }
        val stream = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
        val meta = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val a = TinkProtoKeysetFormat.serializeKeyset(stream, InsecureSecretKeyAccess.get())
        val b = TinkProtoKeysetFormat.serializeKeyset(meta, InsecureSecretKeyAccess.get())
        val bundle = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(a.size); it.write(a); it.writeInt(b.size); it.write(b) }
        }.toByteArray()
        try {
            return seal(bundle, password) to session(stream, meta)
        } finally { a.fill(0); b.fill(0); bundle.fill(0); password.fill('\u0000') }
    }

    private fun seal(bundle: ByteArray, password: CharArray): ByteArray {
        require(password.size in 8..128) { "密码需要 8–128 个字符" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = derive(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header + salt)
            return header + salt + nonce + cipher.doFinal(bundle)
        } finally { key.fill(0) }
    }

    private fun <T> withBundle(config: ByteArray, password: CharArray, read: (ByteArray) -> T): T {
        try {
            require(config.size in 60..65536 && config.copyOfRange(0, header.size).contentEquals(header)) { "保险箱配置损坏或版本不受支持" }
            val salt = config.copyOfRange(header.size, header.size + 16)
            val nonce = config.copyOfRange(header.size + 16, header.size + 28)
            val key = derive(password, salt)
            val clear = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(header + salt)
                cipher.doFinal(config.copyOfRange(header.size + 28, config.size))
            } finally { key.fill(0) }
            return try { read(clear) } finally { clear.fill(0) }
        } finally { password.fill('\u0000') }
    }

    internal fun rewrap(config: ByteArray, current: CharArray, credential: () -> CharArray): ByteArray =
        withBundle(config, current) { clear ->
            val replacement = credential()
            try { seal(clear, replacement) } finally { replacement.fill('\u0000') }
        }

    fun unlock(config: ByteArray, password: CharArray): VaultSession =
        withBundle(config, password) { clear ->
            DataInputStream(ByteArrayInputStream(clear)).use { input ->
                fun readKey(): KeysetHandle {
                    val length = input.readInt()
                    require(length in 1..16384 && length <= input.available())
                    val bytes = ByteArray(length).also(input::readFully)
                    return try { TinkProtoKeysetFormat.parseKeyset(bytes, InsecureSecretKeyAccess.get()) }
                    finally { bytes.fill(0) }
                }
                val stream = readKey(); val meta = readKey()
                require(input.available() == 0)
                session(stream, meta)
            }
        }

    private fun session(stream: KeysetHandle, meta: KeysetHandle) = VaultSession(
        stream.getPrimitive(StreamingAead::class.java), meta.getPrimitive(Aead::class.java))

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun digest(input: InputStream, check: () -> Unit = {}): Pair<Long, ByteArray> {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var size = 0L
        try {
            while (true) {
                check()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                hash.update(buffer, 0, n); size += n
            }
            return size to hash.digest()
        } finally { buffer.fill(0) }
    }
}

class VaultSession internal constructor(private var stream: StreamingAead?, private var meta: Aead?) : Closeable {
    @Volatile private var closed = false
    private val readers = mutableSetOf<SeekableByteChannel>()
    fun requireOpen() { check(!closed) { "保险箱已锁定，请重新输入密码" } }
    private fun aad(id: String, kind: String) = "yingxia-vault-v1:$kind:$id".toByteArray(Charsets.UTF_8)
    @Synchronized fun encryptMetadata(id: String, bytes: ByteArray): ByteArray { requireOpen(); return meta!!.encrypt(bytes, aad(id, "metadata")) }
    @Synchronized fun decryptMetadata(id: String, bytes: ByteArray): ByteArray { requireOpen(); return meta!!.decrypt(bytes, aad(id, "metadata")) }

    fun encrypt(id: String, input: InputStream, file: File, check: () -> Unit, progress: (Long) -> Unit): Pair<Long, ByteArray> {
        val primitive = synchronized(this) { requireOpen(); stream!! }
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var size = 0L
        try {
            primitive.newEncryptingStream(FileOutputStream(file), aad(id, "video")).use { out ->
                while (true) {
                    requireOpen(); check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n); hash.update(buffer, 0, n); size += n; progress(size)
                }
            }
            RandomAccessFile(file, "rw").use { it.fd.sync() }
            return size to hash.digest()
        } finally { buffer.fill(0) }
    }

    @Synchronized fun open(id: String, file: File): SeekableByteChannel {
        requireOpen()
        val source = FileInputStream(file).channel
        return try { stream!!.newSeekableDecryptingChannel(source, aad(id, "video")).also { readers += it } }
        catch (e: Exception) { source.close(); throw e }
    }
    @Synchronized fun closeReader(reader: SeekableByteChannel) { readers.remove(reader); reader.close() }
    @Synchronized override fun close() {
        closed = true
        readers.forEach { runCatching { it.close() } }; readers.clear()
        stream = null; meta = null
    }
}
