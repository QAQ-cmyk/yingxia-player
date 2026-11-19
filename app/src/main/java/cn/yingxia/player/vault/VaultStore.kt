package cn.yingxia.player.vault

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import cn.yingxia.player.core.VideoId
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.core.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class VaultEntry(
    val id: String, val name: String, val originalName: String, val mime: String,
    val bytes: Long, val duration: Long, val sourceId: String, val sourceUri: String,
    val digest: String, val created: Long, val sourceRemoved: Boolean = false,
    val restoredAt: Long = 0, val restorePending: String = "", val removalUri: String = "", val restoreTarget: String = "",
    val manageOriginalInGallery: Boolean = false,
) {
    fun original() = VideoItem(VideoId(sourceId), sourceUri, originalName, "", "", duration, bytes, addedAt = created)
    fun json() = JSONObject().put("version", 1).put("id", id).put("name", name).put("originalName", originalName)
        .put("mime", mime).put("bytes", bytes).put("duration", duration).put("sourceId", sourceId)
        .put("sourceUri", sourceUri).put("digest", digest).put("created", created)
        .put("sourceRemoved", sourceRemoved).put("restoredAt", restoredAt).put("restorePending", restorePending).put("removalUri", removalUri).put("restoreTarget", restoreTarget)
        .put("manageOriginalInGallery", manageOriginalInGallery)
    companion object {
        fun parse(json: JSONObject): VaultEntry {
            require(json.getInt("version") == 1)
            return VaultEntry(json.getString("id"), json.getString("name"), json.getString("originalName"),
                json.getString("mime"), json.getLong("bytes"), json.getLong("duration"), json.getString("sourceId"),
                json.getString("sourceUri"), json.getString("digest"), json.getLong("created"),
                json.getBoolean("sourceRemoved"), json.optLong("restoredAt"), json.optString("restorePending"), json.optString("removalUri"), json.optString("restoreTarget"),
                json.optBoolean("manageOriginalInGallery"))
        }
    }
}

data class VaultInventory(val entries: List<VaultEntry>, val damagedRecords: Int)

/** Private ciphertext storage. All operations are called on IO, under the coordinator's single job. */
class VaultStore(private val context: Context, val directory: File = File(context.noBackupFilesDir, "encrypted-vault")) {
    init { directory.mkdirs() }
    internal val recovery = VaultRecovery(context, directory, ::syncDirectory)
    private val config = File(directory, "keyset.bin")
    @Volatile var session: VaultSession? = null; private set
    val configured get() = config.isFile
    private val resolver get() = context.contentResolver

    fun setup(password: CharArray) {
        check(!configured) { "保险箱已经存在，请解锁" }
        val (bytes, unlocked) = VaultCrypto.create(password)
        try { VaultCrypto.atomicWrite(config, bytes); syncDirectory(); session = unlocked }
        catch (e: Exception) { unlocked.close(); throw e }
    }
    fun unlock(password: CharArray) {
        require(config.length() in 60..65536) { "保险箱配置损坏" }
        val unlocked = VaultCrypto.unlock(config.readBytes(), password)
        session?.close(); session = unlocked
    }
    fun lock() { session?.close(); session = null }
    fun requireSession() = session?.also { it.requireOpen() } ?: error("保险箱已锁定，请重新输入密码")
    private fun safeId(id: String) = UUID.fromString(id).toString().also { require(it == id) }
    fun payload(id: String) = File(directory, "${safeId(id)}.vault")
    fun load(id: String): VaultEntry {
        val file = File(directory, "${safeId(id)}.meta")
        require(file.length() in 1..1048576) { "保险箱记录损坏，文件已保留" }
        val bytes = requireSession().decryptMetadata(id, file.readBytes())
        return try { VaultEntry.parse(JSONObject(bytes.toString(Charsets.UTF_8))).also { require(it.id == id) } }
        finally { bytes.fill(0) }
    }
    fun inventory(): VaultInventory {
        requireSession()
        var damaged = 0
        val entries = directory.listFiles().orEmpty().filter { it.extension == "meta" }.mapNotNull {
            try { load(it.nameWithoutExtension) }
            catch (_: Exception) { requireSession(); damaged++; null }
        }.sortedByDescending { it.created }
        return VaultInventory(entries, damaged)
    }
    fun entries(): List<VaultEntry> = inventory().entries
    fun save(entry: VaultEntry) {
        val bytes = entry.json().toString().toByteArray(Charsets.UTF_8)
        try { VaultCrypto.atomicWrite(File(directory, "${safeId(entry.id)}.meta"), requireSession().encryptMetadata(entry.id, bytes)); syncDirectory() }
        finally { bytes.fill(0) }
    }
    fun remove(id: String) {
        requireSession()
        // Metadata is retained if payload deletion fails, so the entry remains inspectable.
        val file = payload(id)
        check(!file.exists() || file.delete()) { "无法移除加密文件" }
        val playback = File(directory, "${safeId(id)}.playback")
        check(!playback.exists() || playback.delete()) { "视频已移除，但加密播放记录清理失败" }
        check(File(directory, "${safeId(id)}.meta").delete()) { "文件已移除，但记录清理失败" }
        syncDirectory()
    }
    private fun syncDirectory() {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    suspend fun encrypt(video: VideoItem, progress: (String) -> Unit): VaultEntry = withContext(Dispatchers.IO) {
        val unlocked = requireSession()
        val jobContext = coroutineContext
        val check = { jobContext.ensureActive(); unlocked.requireOpen() }
        val id = UUID.randomUUID().toString()
        val temp = File(directory, "$id.part")
        val committed = payload(id)
        // A file is never deleted from the gallery inside this method.
        try {
            val (size, hash) = resolver.openInputStream(Uri.parse(video.uri))?.use { input ->
                unlocked.encrypt(id, input, temp, check) { bytes -> progress("正在加密 · ${bytes / 1048576} MB") }
            } ?: error("原视频无法读取，未移除相册原件")
            require(size > 0 && (video.sizeBytes <= 0 || size == video.sizeBytes)) { "原视频大小发生变化，未移除原件" }
            progress("正在完整校验加密副本")
            verifyFile(unlocked, id, temp, size, hash, check)
            check()
            Files.move(temp.toPath(), committed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val uri = Uri.parse(video.uri)
            val parts = uri.pathSegments
            val mediaRow = uri.authority == MediaStore.AUTHORITY && parts.size == 4 &&
                parts[1] == "video" && parts[2] == "media" && parts.last().toLongOrNull() != null
            val entry = VaultEntry(id, video.displayTitle(), video.name, resolver.getType(uri) ?: "video/mp4",
                size, video.durationMs, video.id.value, video.uri, hash.hex(), System.currentTimeMillis(),
                manageOriginalInGallery = video.source == "share" && !mediaRow)
            save(entry)
            entry
        } finally { temp.delete() }
    }

    suspend fun verifyBeforeRemoval(entry: VaultEntry) = withContext(Dispatchers.IO) {
        val unlocked = requireSession(); val jobContext = coroutineContext
        val check = { jobContext.ensureActive(); unlocked.requireOpen() }
        verifyFile(unlocked, entry.id, payload(entry.id), entry.bytes, entry.digest.unhex(), check)
        val actual = resolver.openInputStream(Uri.parse(entry.sourceUri))?.use { VaultCrypto.digest(it, check) }
            ?: error("无法重新读取相册原件；已保留加密副本")
        require(actual.first == entry.bytes && MessageDigest.isEqual(actual.second, entry.digest.unhex())) {
            "原件已改变，不能移除。加密副本保留的是加密时的内容"
        }
    }
    private fun verifyFile(unlocked: VaultSession, id: String, file: File, expectedSize: Long, hash: ByteArray, check: () -> Unit) {
        val reader = unlocked.open(id, file)
        try {
            val actual = VaultCrypto.digest(Channels.newInputStream(reader), check)
            require(actual.first == expectedSize && MessageDigest.isEqual(actual.second, hash)) { "加密副本校验失败，原件未移除" }
        } finally { unlocked.closeReader(reader) }
    }

    /** Leaves the encrypted original intact even after successful restoration. */
    suspend fun restore(entry: VaultEntry, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val unlocked = requireSession(); val jobContext = coroutineContext
        val check = { jobContext.ensureActive(); unlocked.requireOpen() }
        var destination: Uri? = null
        var legacyFile: File? = null
        var published = false
        try {
            val name = entry.originalName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180).ifBlank { "视频.mp4" }
            if (Build.VERSION.SDK_INT >= 29) {
                destination = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/映匣恢复/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("无法创建恢复文件，请检查剩余空间")
            } else {
                val pending = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "映匣恢复/.pending").apply { mkdirs() }
                File(pending, ".nomedia").createNewFile()
                legacyFile = File(pending, "${entry.id}-${UUID.randomUUID()}.part")
                check(legacyFile.createNewFile())
                destination = Uri.fromFile(legacyFile)
            }
            save(entry.copy(restorePending = destination.toString()))
            val reader = unlocked.open(entry.id, payload(entry.id))
            try {
                val input = Channels.newInputStream(reader)
                val output = legacyFile?.let { FileOutputStream(it) } ?: resolver.openOutputStream(destination, "w") ?: error("无法写入相册")
                output.use { out ->
                    val buffer = ByteArray(256 * 1024); var bytes = 0L
                    try { while (true) {
                        check(); val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); bytes += n; progress("正在解密恢复 · ${bytes / 1048576} MB")
                    } } finally { buffer.fill(0) }
                    if (out is FileOutputStream) out.fd.sync()
                }
            } finally { unlocked.closeReader(reader) }
            progress("正在校验恢复文件")
            val verify = (legacyFile?.inputStream() ?: resolver.openInputStream(destination))?.use { VaultCrypto.digest(it, check) }
                ?: error("无法校验恢复文件")
            require(verify.first == entry.bytes && MessageDigest.isEqual(verify.second, entry.digest.unhex())) { "恢复校验失败，保险箱原件已保留" }
            check()
            if (Build.VERSION.SDK_INT >= 29) {
                check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            } else {
                val parent = legacyFile!!.parentFile!!.parentFile!!
                var target = File(parent, name)
                if (target.exists()) target = File(parent, "${System.currentTimeMillis()}-$name")
                // Journal the final name before publication, and never overwrite an existing file.
                save(entry.copy(restorePending = destination.toString(), restoreTarget = target.absolutePath))
                Files.move(legacyFile.toPath(), target.toPath())
                published = true
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(entry.mime), null)
            }
            published = true
            save(entry.copy(restoredAt = System.currentTimeMillis(), restorePending = "", restoreTarget = ""))
            "已恢复到 Movies/映匣恢复；保险箱副本仍保留"
        } finally {
            if (!published) {
                // Only this operation's newly created unpublished output is removed.
                runCatching { if (legacyFile != null) legacyFile.delete() else destination?.let { resolver.delete(it, null, null) } }
            }
        }
    }

    /** Recover interrupted restores, but never remove an already published gallery file. */
    fun recoverRestore(entry: VaultEntry): VaultEntry {
        if (entry.restorePending.isBlank()) return entry
        val uri = Uri.parse(entry.restorePending)
        var restored = entry.restoredAt
        if (uri.scheme == "content" && Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
            val pending = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
            if (pending == true) resolver.delete(uri, null, null)
            else if (pending == false) restored = System.currentTimeMillis()
        } else if (uri.scheme == "file" && Build.VERSION.SDK_INT < 29) {
            val expected = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "映匣恢复/.pending").canonicalFile
            val candidate = File(uri.path.orEmpty()).canonicalFile
            if (candidate.parentFile == expected && candidate.name.startsWith(entry.id) && candidate.extension == "part") candidate.delete()
            if (entry.restoreTarget.isNotBlank()) {
                val target = File(entry.restoreTarget).canonicalFile
                if (target.parentFile == expected.parentFile && target.isFile) {
                    val actual = target.inputStream().use { VaultCrypto.digest(it) { requireSession() } }
                    if (actual.first == entry.bytes && MessageDigest.isEqual(actual.second, entry.digest.unhex())) {
                        restored = System.currentTimeMillis()
                        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(entry.mime), null)
                    }
                }
            }
        }
        return entry.copy(restorePending = "", restoreTarget = "", restoredAt = restored).also(::save)
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun String.unhex(): ByteArray {
    require(length == 64)
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
