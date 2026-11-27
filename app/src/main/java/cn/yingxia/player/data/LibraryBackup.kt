package cn.yingxia.player.data

import android.content.Context
import cn.yingxia.player.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupBookmark(
    val videoId: String,
    val videoUri: String,
    val videoName: String,
    val videoSizeBytes: Long,
    val positionMs: Long,
    val note: String,
)

data class LibraryBackupFile(
    val settings: UserSettings,
    val records: List<LibraryRecord>,
    val bookmarks: List<BackupBookmark> = emptyList(),
)

data class RestorePlan(
    val settings: UserSettings,
    val matched: List<LibraryRecord>,
    val skipped: Int,
    val matchedBookmarks: List<Pair<VideoId, BackupBookmark>> = emptyList(),
)

class InvalidBackupException : IllegalArgumentException("Not a YingXia backup file")
class EncryptedBackupPasswordRequiredException : IllegalArgumentException("Encrypted backup requires password")
class BackupTooLargeException : IllegalArgumentException("Backup file exceeds size limit")

object LibraryBackup {
    const val MAX_IMPORT_BYTES = 10 * 1024 * 1024
    private const val PBKDF2_ITERATIONS = 600_000
    private val ENC_HEADER = "YINGXIA-BACKUP-ENC-1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun readBoundedBytes(input: InputStream, maxBytes: Int = MAX_IMPORT_BYTES): ByteArray {
        require(maxBytes > 0)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(minOf(16 * 1024, maxBytes))
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            if (n > maxBytes - total) throw BackupTooLargeException()
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    fun readBoundedUtf8(input: InputStream, maxBytes: Int = MAX_IMPORT_BYTES): String =
        String(readBoundedBytes(input, maxBytes), Charsets.UTF_8)

    fun isEncryptedBackup(bytes: ByteArray): Boolean =
        bytes.size >= ENC_HEADER.size + 16 + 12 + 16 &&
            bytes.copyOfRange(0, ENC_HEADER.size).contentEquals(ENC_HEADER)

    fun exportEncrypted(
        videos: List<VideoItem>,
        settings: UserSettings,
        password: CharArray,
        bookmarks: List<Bookmark> = emptyList(),
    ): ByteArray {
        require(password.size in 8..128) { "Backup password must be 8–128 characters" }
        val plain = export(videos, settings, bookmarks).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(ENC_HEADER + salt)
            ENC_HEADER + salt + iv + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
            key.fill(0)
            password.fill('\u0000')
        }
    }

    fun decryptBackup(bytes: ByteArray, password: CharArray): LibraryBackupFile {
        if (!isEncryptedBackup(bytes)) {
            password.fill('\u0000')
            throw InvalidBackupException()
        }
        require(password.size in 8..128) { "Backup password must be 8–128 characters" }
        val salt = bytes.copyOfRange(ENC_HEADER.size, ENC_HEADER.size + 16)
        val iv = bytes.copyOfRange(ENC_HEADER.size + 16, ENC_HEADER.size + 28)
        val cipherText = bytes.copyOfRange(ENC_HEADER.size + 28, bytes.size)
        val key = deriveKey(password, salt)
        var clear: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(ENC_HEADER + salt)
            clear = cipher.doFinal(cipherText)
            parse(String(clear, Charsets.UTF_8))
        } finally {
            key.fill(0)
            clear?.fill(0)
            password.fill('\u0000')
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Zeroes out and removes temporary shared backup files in cacheDir/backup. */
    fun clearShareCache(context: Context) {
        val dir = File(context.cacheDir, "backup")
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            runCatching {
                if (file.isFile && file.length() > 0 && file.length() <= MAX_IMPORT_BYTES) {
                    RandomAccessFile(file, "rws").use { raf ->
                        val zeros = ByteArray(16 * 1024)
                        var remaining = raf.length()
                        raf.seek(0)
                        while (remaining > 0) {
                            val chunk = minOf(zeros.size.toLong(), remaining).toInt()
                            raf.write(zeros, 0, chunk)
                            remaining -= chunk
                        }
                    }
                }
                file.delete()
            }
        }
    }

    fun export(videos: List<VideoItem>, settings: UserSettings, bookmarks: List<Bookmark> = emptyList()): String {
        val byId = videos.associateBy { it.id }
        val records = JSONArray()
        videos.filter { it.favorite || it.positionMs > 0 || it.lastPlayedAt > 0 || !it.displayName.isNullOrBlank() || it.cropBars }
            .forEach { video ->
                records.put(JSONObject()
                    .put("id", video.id.value).put("uri", video.uri).put("name", video.name).put("sizeBytes", video.sizeBytes)
                    .put("favorite", video.favorite).put("positionMs", video.positionMs).put("lastPlayedAt", video.lastPlayedAt)
                    .put("displayName", video.displayName ?: "")
                    .put("cropBars", video.cropBars))
            }
        val bookmarkRows = JSONArray()
        bookmarks.forEach { bm ->
            val video = byId[bm.videoId] ?: return@forEach
            bookmarkRows.put(JSONObject()
                .put("videoId", video.id.value)
                .put("videoUri", video.uri)
                .put("videoName", video.name)
                .put("videoSizeBytes", video.sizeBytes)
                .put("positionMs", bm.positionMs)
                .put("note", bm.note))
        }
        val prefs = JSONObject()
            .put("theme", settings.theme.name).put("speed", settings.speed.toDouble()).put("holdSpeed", settings.holdSpeed.toDouble())
            .put("sort", settings.sort.name).put("touchGuard", settings.touchGuard)
        return JSONObject()
            .put("kind", "yingxia-backup")
            .put("version", 1)
            .put("settings", prefs)
            .put("records", records)
            .put("bookmarks", bookmarkRows)
            .toString()
    }

    fun parse(text: String): LibraryBackupFile {
        val root = JSONObject(text)
        if (root.optString("kind") != "yingxia-backup") throw InvalidBackupException()
        val prefs = root.getJSONObject("settings")
        val settings = UserSettings(
            theme = runCatching { ThemeMode.valueOf(prefs.optString("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM),
            speed = PlaybackRules.speed(prefs.optDouble("speed", 1.0).toFloat()),
            holdSpeed = if (prefs.optDouble("holdSpeed", 2.0).toFloat() == 3f) 3f else 2f,
            sort = runCatching { SortOrder.valueOf(prefs.optString("sort", "NEWEST")) }.getOrDefault(SortOrder.NEWEST),
            touchGuard = prefs.optBoolean("touchGuard", false),
        )
        val records = root.optJSONArray("records") ?: JSONArray()
        val rows = buildList {
            for (i in 0 until records.length()) {
                val row = records.getJSONObject(i)
                add(LibraryRecord(row.optString("id"), row.optString("uri"), row.optString("name"), row.optLong("sizeBytes"),
                    row.optBoolean("favorite"), row.optLong("positionMs"), row.optLong("lastPlayedAt"),
                    row.optString("displayName").ifBlank { null }, row.optBoolean("cropBars")))
            }
        }
        val rawBookmarks = root.optJSONArray("bookmarks") ?: JSONArray()
        val parsedBookmarks = buildList {
            for (i in 0 until rawBookmarks.length()) {
                val row = rawBookmarks.getJSONObject(i)
                add(BackupBookmark(
                    videoId = row.optString("videoId"),
                    videoUri = row.optString("videoUri"),
                    videoName = row.optString("videoName"),
                    videoSizeBytes = row.optLong("videoSizeBytes"),
                    positionMs = row.optLong("positionMs"),
                    note = row.optString("note"),
                ))
            }
        }
        return LibraryBackupFile(settings, rows, parsedBookmarks)
    }

    private fun findMatch(
        library: List<VideoItem>,
        uri: String,
        id: String,
        name: String,
        sizeBytes: Long,
    ): VideoItem? =
        library.firstOrNull { it.uri == uri && uri.isNotBlank() }
            ?: library.firstOrNull { it.id.value == id && id.isNotBlank() }
            ?: library.filter { it.name == name && it.sizeBytes == sizeBytes && sizeBytes > 0 }.singleOrNull()

    fun plan(library: List<VideoItem>, file: LibraryBackupFile): RestorePlan {
        val matched = mutableListOf<LibraryRecord>()
        var skipped = 0
        file.records.forEach { row ->
            val hit = findMatch(library, row.uri, row.id, row.name, row.sizeBytes)
            if (hit == null) skipped++ else matched += row.copy(id = hit.id.value)
        }
        val matchedBookmarks = mutableListOf<Pair<VideoId, BackupBookmark>>()
        file.bookmarks.forEach { bm ->
            if (bm.positionMs < 0) return@forEach
            val hit = findMatch(library, bm.videoUri, bm.videoId, bm.videoName, bm.videoSizeBytes) ?: return@forEach
            matchedBookmarks += hit.id to bm
        }
        return RestorePlan(file.settings, matched, skipped, matchedBookmarks)
    }
}
