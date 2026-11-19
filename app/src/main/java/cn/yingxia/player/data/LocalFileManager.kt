package cn.yingxia.player.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
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
    fun openStream(video: VideoItem) = context.contentResolver.openInputStream(Uri.parse(video.uri))
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

    private fun str(id: Int) = AppLocale.str(context, id)

    suspend fun createFolder(tree: String, name: String): StorageFolder = withContext(Dispatchers.IO) {
        require(validFolderName(name)) { str(R.string.file_error_folder_name) }
        val treeUri = Uri.parse(tree)
        require(treeUri.authority == "com.android.externalstorage.documents") { str(R.string.file_error_external_storage_only) }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        var existing: Uri? = null
        resolver.query(children, arrayOf("document_id", "_display_name", "mime_type"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name.trim()) {
                require(c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { str(R.string.file_error_name_taken) }
                existing = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
            }
        }
        val folder = existing ?: DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name.trim())
            ?: error(str(R.string.file_error_create_dir))
        StorageFolder(folder.toString(), name.trim())
    }

    suspend fun copyVerified(video: VideoItem, folder: StorageFolder): String = withContext(Dispatchers.IO) {
        val target = Uri.parse(folder.uri)
        require(target.authority == "com.android.externalstorage.documents") { str(R.string.file_error_local_target) }
        val source = Uri.parse(video.uri)
        val targetId = DocumentsContract.getDocumentId(target)
        if (source.authority == target.authority && DocumentsContract.isDocumentUri(context, source)) {
            require(DocumentsContract.getDocumentId(source).substringBeforeLast('/') != targetId) { str(R.string.file_error_already_in_folder) }
        } else if (Build.VERSION.SDK_INT >= 29 && source.authority == "media") {
            val path = resolver.query(source, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            require(targetId != "primary:${path?.trimEnd('/')}" || !source.toString().contains("external_primary")) { str(R.string.file_error_already_in_folder) }
        }
        val output = DocumentsContract.createDocument(resolver, target, resolver.getType(source) ?: "video/mp4", video.name)
            ?: error(str(R.string.file_error_create_dest))
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
                } ?: error(str(R.string.file_error_dest_unwritable))
            } ?: error(str(R.string.file_error_source_unreadable))
            require(copied > 0 && (video.sizeBytes <= 0 || copied == video.sizeBytes)) { str(R.string.file_error_source_size_changed) }
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
            } ?: error(str(R.string.file_error_verify_dest))
            require(verifiedBytes == copied && hash.digest().contentEquals(verification.digest())) { str(R.string.file_error_verify_mismatch) }
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
            check(removed) { str(R.string.file_error_delete_failed) }
            DeleteOutcome.Deleted
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException && !afterGrant)
                DeleteOutcome.Consent(e.userAction.actionIntent.intentSender, true, uri.toString())
            else throw IllegalStateException(str(R.string.file_error_no_delete_perm), e)
        }
    }

    suspend fun verifySystemDeletion(mediaUri: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(mediaUri)
        val missing = try { resolver.query(uri, arrayOf("_id"), null, null, null)?.use { !it.moveToFirst() } ?: false }
            catch (_: FileNotFoundException) { true }
        check(missing) { str(R.string.file_error_system_delete_unconfirmed) }
    }

    companion object {
        fun validFolderName(name: String) = name.trim().length in 1..60 && name.trim().any { it != '.' } &&
            name.none { it.code < 32 || it in "\\/:*?\"<>|" }
    }
}
