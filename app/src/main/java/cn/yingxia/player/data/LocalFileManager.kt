package cn.yingxia.player.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import cn.yingxia.player.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface DeleteOutcome {
    data object Deleted : DeleteOutcome
    data class Consent(val sender: IntentSender, val retryDelete: Boolean, val mediaUri: String) : DeleteOutcome
}

/** All writes are user initiated. A move keeps the source until the destination is re-read and verified. */
class LocalFileManager(private val context: Context) {
    private val resolver get() = context.contentResolver

    suspend fun folderKeys(folders: List<StorageFolder>): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        folders.associate { folder -> folder.uri to runCatching {
            val document = DocumentsContract.getDocumentId(Uri.parse(folder.uri))
            val volume = document.substringBefore(':')
            val relative = document.substringAfter(':').trimEnd('/') + "/"
            val keys = mutableSetOf<String>()
            if (Build.VERSION.SDK_INT >= 29) {
                val mediaVolume = if (volume == "primary") "external_primary" else volume.lowercase()
                resolver.query(MediaStore.Video.Media.getContentUri(mediaVolume), arrayOf("bucket_id"), "relative_path=?", arrayOf(relative), null)?.use {
                    while (it.moveToNext()) keys += "$mediaVolume:${it.getString(0)}"
                }
            } else {
                val parent = if (volume == "primary") "${android.os.Environment.getExternalStorageDirectory()}/${relative.trimEnd('/')}" else "/storage/$volume/${relative.trimEnd('/')}"
                resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf("bucket_id", "_data"), null, null, null)?.use {
                    while (it.moveToNext()) if (it.getString(1)?.substringBeforeLast('/') == parent) keys += "external:${it.getString(0)}"
                }
            }
            keys.toSet()
        }.getOrDefault(emptySet()) }
    }

    suspend fun createFolder(tree: String, name: String): StorageFolder = withContext(Dispatchers.IO) {
        require(validFolderName(name)) { "名称需为 1–60 个字符，不能包含路径符号或仅为点号" }
        val treeUri = Uri.parse(tree)
        require(treeUri.authority == "com.android.externalstorage.documents") { "请选择手机内部存储或 SD 卡中的文件夹" }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        var existing: Uri? = null
        resolver.query(children, arrayOf("document_id", "_display_name", "mime_type"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name.trim()) {
                require(c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { "这个名称已被一个文件占用" }
                existing = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
            }
        }
        val folder = existing ?: DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name.trim())
            ?: error("无法创建文件夹，请检查存储空间和访问权限")
        StorageFolder(folder.toString(), name.trim())
    }

    suspend fun copyVerified(video: VideoItem, folder: StorageFolder): String = withContext(Dispatchers.IO) {
        val target = Uri.parse(folder.uri)
        require(target.authority == "com.android.externalstorage.documents") { "目标必须是本机文件夹" }
        val source = Uri.parse(video.uri)
        val targetId = DocumentsContract.getDocumentId(target)
        if (source.authority == target.authority && DocumentsContract.isDocumentUri(context, source)) {
            require(DocumentsContract.getDocumentId(source).substringBeforeLast('/') != targetId) { "视频已在这个文件夹中" }
        } else if (Build.VERSION.SDK_INT >= 29 && source.authority == "media") {
            val path = resolver.query(source, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            require(targetId != "primary:${path?.trimEnd('/')}" || !source.toString().contains("external_primary")) { "视频已在这个文件夹中" }
        }
        val output = DocumentsContract.createDocument(resolver, target, resolver.getType(source) ?: "video/mp4", video.name)
            ?: error("无法创建目标文件，请检查文件夹授权或剩余空间")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(output, "w")?.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count); hash.update(buffer, 0, count); copied += count
                    }
                } ?: error("目标文件不可写")
            } ?: error("原视频无法读取，未删除原文件")
            require(copied > 0 && (video.sizeBytes <= 0 || copied == video.sizeBytes)) { "源文件大小发生变化，已保留原文件" }
            val verification = MessageDigest.getInstance("SHA-256")
            var verifiedBytes = 0L
            resolver.openInputStream(output)?.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    verification.update(buffer, 0, count); verifiedBytes += count
                }
            } ?: error("无法校验目标文件")
            require(verifiedBytes == copied && hash.digest().contentEquals(verification.digest())) { "文件校验未通过，原视频未删除" }
            output.toString()
        } catch (e: Exception) {
            // This URI was created by this operation; never remove an existing destination or the source.
            runCatching { DocumentsContract.deleteDocument(resolver, output) }
            throw e
        }
    }

    suspend fun delete(video: VideoItem, afterGrant: Boolean = false): DeleteOutcome = withContext(Dispatchers.IO) {
        val original = Uri.parse(video.uri)
        val uri = canonicalVideoUri(context, original) ?: original
        if (Build.VERSION.SDK_INT >= 30 && uri.authority == "media" && !afterGrant) {
            return@withContext DeleteOutcome.Consent(MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender, false, uri.toString())
        }
        try {
            val removed = if (DocumentsContract.isDocumentUri(context, original)) DocumentsContract.deleteDocument(resolver, original)
                else resolver.delete(uri, null, null) > 0
            check(removed) { "未能删除原视频：文件可能已移除，或文件提供方不支持删除" }
            DeleteOutcome.Deleted
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException && !afterGrant)
                DeleteOutcome.Consent(e.userAction.actionIntent.intentSender, true, uri.toString())
            else throw IllegalStateException("没有删除权限，请重新选择原文件并允许写入，或使用系统文件管理器", e)
        }
    }

    suspend fun verifySystemDeletion(mediaUri: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(mediaUri)
        val missing = try { resolver.query(uri, arrayOf("_id"), null, null, null)?.use { !it.moveToFirst() } ?: false }
            catch (_: FileNotFoundException) { true }
        check(missing) { "系统未确认文件已删除，已保留记录" }
    }

    companion object {
        fun validFolderName(name: String) = name.trim().length in 1..60 && name.trim().any { it != '.' } &&
            name.none { it.code < 32 || it in "\\/:*?\"<>|" }
    }
}
