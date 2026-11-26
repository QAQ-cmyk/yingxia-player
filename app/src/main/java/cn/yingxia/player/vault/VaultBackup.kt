package cn.yingxia.player.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal const val DEFAULT_VAULT_BACKUP_DIR_NAME = "YingXiaVault"
private const val BACKUP_MANIFEST_NAME = "manifest.json"
private const val BACKUP_NOMEDIA_NAME = ".nomedia"
private const val BACKUP_KEYSET_NAME = "keyset.bin"
private const val BACKUP_CUSTOM_MIME = "application/vnd.yingxia.vault"
private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
private const val MAX_META_BYTES = 1 * 1024 * 1024
private const val MIN_KEYSET_BYTES = 60
private const val MAX_KEYSET_BYTES = 65_536

internal data class VaultBackupFileInfo(
    val name: String,
    val bytes: Long,
    val sha256: String = "",
)

internal data class VaultBackupManifest(
    val version: Int = 1,
    val exportedAt: Long,
    val files: Map<String, VaultBackupFileInfo>,
) {
    fun toJson(): String {
        val entries = JSONObject()
        files.keys.sorted().forEach { name ->
            val info = files.getValue(name)
            entries.put(
                name,
                JSONObject()
                    .put("bytes", info.bytes)
                    .put("sha256", info.sha256),
            )
        }
        return JSONObject()
            .put("kind", "yingxia-vault-backup")
            .put("version", version)
            .put("exportedAt", exportedAt)
            .put("files", entries)
            .toString()
    }

    companion object {
        fun parse(text: String): VaultBackupManifest {
            val root = JSONObject(text)
            require(root.optString("kind") == "yingxia-vault-backup") { "Invalid vault backup manifest" }
            val version = root.getInt("version")
            require(version == 1) { "Unsupported vault backup version: $version" }
            val exportedAt = root.optLong("exportedAt", 0L)
            val obj = root.getJSONObject("files")
            val parsed = linkedMapOf<String, VaultBackupFileInfo>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                require(VaultBackupRules.isSafeVaultArtifactName(name)) { "Unsafe backup entry name: $name" }
                val row = obj.getJSONObject(name)
                val bytes = row.getLong("bytes")
                val sha256 = row.getString("sha256").lowercase()
                require(bytes > 0 && sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "Invalid manifest entry for $name"
                }
                parsed[name] = VaultBackupFileInfo(name, bytes, sha256)
            }
            return VaultBackupManifest(version, exportedAt, parsed)
        }
    }
}

internal data class VaultBackupSyncPlan(
    val toWrite: List<String>,
    val toSkip: List<String>,
    val toDelete: List<String>,
)

internal data class VaultBackupSyncResult(
    val written: Int,
    val skipped: Int,
    val removed: Int,
    val totalEntries: Int,
    val syncedAt: Long,
)

internal data class VaultBackupRestoreResult(
    val restored: Int,
    val skippedDuplicates: Int,
    val mergedIntoExisting: Boolean,
)

/**
 * Pure vault backup rules kept independent of Android framework APIs so they can be verified
 * directly with JVM unit tests.
 */
internal object VaultBackupRules {
    /**
     * Only keyset.bin and canonical UUID.{vault,meta,playback} ciphertext files are eligible.
     * Device-bound recovery files (questions-v*.bin), temp files (.part/.tmp), and arbitrary paths
     * are strictly rejected.
     */
    fun isSafeVaultArtifactName(name: String): Boolean {
        if (name == BACKUP_KEYSET_NAME) return true
        if ('/' in name || '\\' in name || name.startsWith(".")) return false
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        val stem = name.substring(0, dot)
        val ext = name.substring(dot + 1)
        if (ext != "vault" && ext != "meta" && ext != "playback") return false
        return runCatching { UUID.fromString(stem).toString() == stem }.getOrDefault(false)
    }

    /** Computes an incremental sync plan between local ciphertext files and remote backup state. */
    fun computeSyncPlan(
        localFiles: Map<String, VaultBackupFileInfo>,
        remoteFiles: Map<String, Long>,
        remoteManifest: VaultBackupManifest?,
    ): VaultBackupSyncPlan {
        val toWrite = mutableListOf<String>()
        val toSkip = mutableListOf<String>()
        localFiles.keys.sortedWith(compareBy<String> { it == BACKUP_KEYSET_NAME }.thenBy { it }).forEach { name ->
            val local = localFiles.getValue(name)
            val remoteSize = remoteFiles[name]
            val manifestEntry = remoteManifest?.files?.get(name)
            val unchanged = remoteSize != null &&
                remoteSize == local.bytes &&
                manifestEntry != null &&
                manifestEntry.bytes == local.bytes &&
                manifestEntry.sha256.equals(local.sha256, ignoreCase = true)
            if (unchanged) toSkip += name else toWrite += name
        }
        val toDelete = remoteFiles.keys
            .filter { isSafeVaultArtifactName(it) && it !in localFiles }
            .sorted()
        return VaultBackupSyncPlan(toWrite = toWrite, toSkip = toSkip, toDelete = toDelete)
    }

    /** Selects the concrete backup directory name when choosing a user-selected tree root. */
    fun shouldUseSelectedRootDirectly(rootName: String, childNames: Set<String>): Boolean =
        rootName.equals(DEFAULT_VAULT_BACKUP_DIR_NAME, ignoreCase = true) ||
            BACKUP_KEYSET_NAME in childNames ||
            BACKUP_MANIFEST_NAME in childNames
}

/** Abstract storage backend for local directory or Android SAF document tree. */
internal interface VaultBackupBackend {
    val label: String
    fun listFiles(): Map<String, Long>
    fun openInput(name: String): InputStream?
    fun writeStream(name: String, mimeType: String, writer: (OutputStream) -> Unit)
    fun delete(name: String): Boolean
    fun ensureNoMedia()
}

/** Direct filesystem backend used for JVM tests, Android 8-10 auto-detection, and emulator tests. */
internal class FileVaultBackupBackend(
    val directory: File,
    override val label: String = directory.name.ifBlank { DEFAULT_VAULT_BACKUP_DIR_NAME },
) : VaultBackupBackend {
    override fun listFiles(): Map<String, Long> {
        if (!directory.isDirectory) return emptyMap()
        return directory.listFiles().orEmpty()
            .filter { it.isFile }
            .associate { it.name to it.length() }
    }

    override fun openInput(name: String): InputStream? {
        val file = safeChild(name)
        return if (file.isFile) FileInputStream(file) else null
    }

    override fun writeStream(name: String, mimeType: String, writer: (OutputStream) -> Unit) {
        directory.mkdirs()
        val target = safeChild(name)
        val temp = File(directory, "$name.tmp")
        try {
            FileOutputStream(temp).use { out ->
                writer(out)
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    override fun delete(name: String): Boolean {
        val file = safeChild(name)
        return !file.exists() || file.delete()
    }

    override fun ensureNoMedia() {
        directory.mkdirs()
        val file = File(directory, BACKUP_NOMEDIA_NAME)
        if (!file.exists()) file.createNewFile()
    }

    private fun safeChild(name: String): File {
        require(name == BACKUP_MANIFEST_NAME || name == BACKUP_NOMEDIA_NAME || VaultBackupRules.isSafeVaultArtifactName(name)) {
            "Rejected unsafe backup file name: $name"
        }
        val child = File(directory, name)
        require(child.parentFile?.canonicalFile == directory.canonicalFile) { "Path escape rejected" }
        return child
    }
}

/** Android SAF DocumentsContract backend supporting Documents/YingXiaVault, SD cards, and USB OTG. */
internal class SafVaultBackupBackend private constructor(
    private val context: Context,
    private val treeUri: Uri,
    private val targetFolderUri: Uri,
    override val label: String,
) : VaultBackupBackend {
    private val resolver get() = context.contentResolver

    private data class ChildDoc(val documentId: String, val name: String, val mime: String, val size: Long)

    private fun queryChildren(folderUri: Uri): List<ChildDoc> {
        val folderId = DocumentsContract.getDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val result = mutableListOf<ChildDoc>()
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                result += ChildDoc(id, name, mime, size)
            }
        }
        return result
    }

    private fun childUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    override fun listFiles(): Map<String, Long> = queryChildren(targetFolderUri)
        .filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
        .associate { it.name to it.size }

    override fun openInput(name: String): InputStream? {
        requireAllowedName(name)
        val child = queryChildren(targetFolderUri).firstOrNull {
            it.mime != DocumentsContract.Document.MIME_TYPE_DIR && it.name == name
        } ?: return null
        return resolver.openInputStream(childUri(child.documentId))
    }

    override fun writeStream(name: String, mimeType: String, writer: (OutputStream) -> Unit) {
        requireAllowedName(name)
        val existing = queryChildren(targetFolderUri).firstOrNull {
            it.mime != DocumentsContract.Document.MIME_TYPE_DIR && it.name == name
        }
        val docUri = if (existing != null) {
            childUri(existing.documentId)
        } else {
            DocumentsContract.createDocument(resolver, targetFolderUri, mimeType, name)
                ?: error("Could not create backup document: $name")
        }
        try {
            val out = resolver.openOutputStream(docUri, "wt")
                ?: resolver.openOutputStream(docUri, "w")
                ?: error("Could not open backup output stream: $name")
            out.use { writer(it) }
        } catch (e: Exception) {
            if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
            throw e
        }
    }

    override fun delete(name: String): Boolean {
        requireAllowedName(name)
        val existing = queryChildren(targetFolderUri).firstOrNull {
            it.mime != DocumentsContract.Document.MIME_TYPE_DIR && it.name == name
        } ?: return true
        return runCatching { DocumentsContract.deleteDocument(resolver, childUri(existing.documentId)) }.getOrDefault(false)
    }

    override fun ensureNoMedia() {
        val exists = queryChildren(targetFolderUri).any { it.name == BACKUP_NOMEDIA_NAME }
        if (!exists) {
            runCatching {
                DocumentsContract.createDocument(resolver, targetFolderUri, BACKUP_CUSTOM_MIME, BACKUP_NOMEDIA_NAME)
            }
        }
    }

    companion object {
        private fun requireAllowedName(name: String) {
            require(name == BACKUP_MANIFEST_NAME || name == BACKUP_NOMEDIA_NAME || VaultBackupRules.isSafeVaultArtifactName(name)) {
                "Rejected unsafe backup file name: $name"
            }
        }

        private fun validateTreeUri(context: Context, treeUri: Uri) {
            val authority = treeUri.authority.orEmpty()
            require(
                treeUri.scheme == "content" &&
                    authority.isNotBlank() &&
                    '@' !in authority &&
                    authority != context.packageName &&
                    !authority.startsWith("${context.packageName}."),
            ) { "Invalid backup folder URI" }
        }

        private fun queryDisplayName(context: Context, docUri: Uri, fallback: String): String =
            runCatching {
                context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

        private fun listChildren(context: Context, treeUri: Uri, folderUri: Uri): List<ChildDoc> {
            val folderId = DocumentsContract.getDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val out = mutableListOf<ChildDoc>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    out += ChildDoc(id, name, mime, size)
                }
            }
            return out
        }

        /** Opens or creates the target backup folder (reusing YingXiaVault directly without nesting). */
        fun forExport(context: Context, treeUri: Uri): SafVaultBackupBackend {
            validateTreeUri(context, treeUri)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val rootName = queryDisplayName(context, rootUri, rootId.substringAfterLast(':').substringAfterLast('/'))
            val children = listChildren(context, treeUri, rootUri)
            val childNames = children.map { it.name }.toSet()
            if (VaultBackupRules.shouldUseSelectedRootDirectly(rootName, childNames)) {
                return SafVaultBackupBackend(context, treeUri, rootUri, formatTreeLabel(rootId, rootName, nested = false))
            }
            val existingSub = children.firstOrNull {
                it.mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    it.name.equals(DEFAULT_VAULT_BACKUP_DIR_NAME, ignoreCase = true)
            }
            val subUri = if (existingSub != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingSub.documentId)
            } else {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    rootUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    DEFAULT_VAULT_BACKUP_DIR_NAME,
                ) ?: error("Could not create $DEFAULT_VAULT_BACKUP_DIR_NAME folder")
            }
            return SafVaultBackupBackend(context, treeUri, subUri, formatTreeLabel(rootId, rootName, nested = true))
        }

        /**
         * Resolves a user-selected folder for restoration. Accepts either the YingXiaVault folder
         * itself or a parent folder (such as Documents/ or a USB root) containing YingXiaVault/.
         */
        fun forRestore(context: Context, treeUri: Uri): SafVaultBackupBackend {
            validateTreeUri(context, treeUri)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val rootName = queryDisplayName(context, rootUri, rootId.substringAfterLast(':').substringAfterLast('/'))
            val children = listChildren(context, treeUri, rootUri)
            if (children.any { it.mime != DocumentsContract.Document.MIME_TYPE_DIR && it.name == BACKUP_KEYSET_NAME }) {
                return SafVaultBackupBackend(context, treeUri, rootUri, formatTreeLabel(rootId, rootName, nested = false))
            }
            val sub = children.firstOrNull {
                it.mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    it.name.equals(DEFAULT_VAULT_BACKUP_DIR_NAME, ignoreCase = true)
            }
            if (sub != null) {
                val subUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, sub.documentId)
                return SafVaultBackupBackend(context, treeUri, subUri, formatTreeLabel(rootId, rootName, nested = true))
            }
            return SafVaultBackupBackend(context, treeUri, rootUri, formatTreeLabel(rootId, rootName, nested = false))
        }

        private fun formatTreeLabel(rootId: String, rootName: String, nested: Boolean): String {
            val clean = rootId.substringAfter(':', rootName).trim('/').ifBlank { rootName.ifBlank { DEFAULT_VAULT_BACKUP_DIR_NAME } }
            return if (nested && !clean.endsWith(DEFAULT_VAULT_BACKUP_DIR_NAME, ignoreCase = true)) {
                "$clean/$DEFAULT_VAULT_BACKUP_DIR_NAME"
            } else {
                clean
            }
        }
    }
}

/** Helper URIs and auto-detection for making backup/restore folder picking one tap. */
internal object VaultBackupLocations {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun initialBackupPickerUri(): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Documents")

    fun initialRestorePickerUri(): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Documents/$DEFAULT_VAULT_BACKUP_DIR_NAME")

    /**
     * On Android 8-10 when storage read permission is already granted, detect the default
     * Documents/YingXiaVault backup directory automatically so users do not need to browse for it.
     */
    fun detectLegacyLocalBackupDir(context: Context): File? {
        if (Build.VERSION.SDK_INT > 29) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) ?: return null
        val dir = File(docs, DEFAULT_VAULT_BACKUP_DIR_NAME)
        return dir.takeIf { File(it, BACKUP_KEYSET_NAME).isFile }
    }
}

/**
 * Pure ciphertext incremental export and password-verified restore/merge engine.
 * Never exports plaintext videos, plaintext titles, passwords, or device-bound KeyStore recovery files.
 */
internal object VaultBackupEngine {
    suspend fun syncTo(
        context: Context,
        store: VaultStore,
        backend: VaultBackupBackend,
        progress: (String) -> Unit,
    ): VaultBackupSyncResult = withContext(Dispatchers.IO) {
        val unlocked = store.requireSession()
        val jobContext = coroutineContext
        val check = { jobContext.ensureActive(); unlocked.requireOpen() }
        val configFile = store.configFile()
        check(configFile.isFile) { AppLocale.str(context, R.string.vault_store_config_damaged) }

        val entries = store.entries()
        val localFiles = linkedMapOf<String, VaultBackupFileInfo>()
        val sourceFiles = linkedMapOf<String, File>()

        fun addLocalFile(file: File) {
            if (!file.isFile) return
            require(VaultBackupRules.isSafeVaultArtifactName(file.name))
            check()
            val (size, hash) = file.inputStream().use { VaultCrypto.digest(it, check) }
            localFiles[file.name] = VaultBackupFileInfo(file.name, size, hash.hex())
            sourceFiles[file.name] = file
        }

        progress(AppLocale.str(context, R.string.vault_backup_prog_scanning))
        addLocalFile(configFile)
        entries.forEach { entry ->
            addLocalFile(store.metaFile(entry.id))
            addLocalFile(store.payload(entry.id))
            val playback = store.playbackFile(entry.id)
            if (playback.isFile) addLocalFile(playback)
        }

        backend.ensureNoMedia()
        val remoteFiles = backend.listFiles()
        val remoteManifest = runCatching {
            backend.openInput(BACKUP_MANIFEST_NAME)?.use { input ->
                VaultBackupManifest.parse(readBoundedUtf8(input, MAX_MANIFEST_BYTES))
            }
        }.getOrNull()

        val plan = VaultBackupRules.computeSyncPlan(localFiles, remoteFiles, remoteManifest)
        var written = 0
        plan.toWrite.forEachIndexed { index, name ->
            check()
            progress(AppLocale.str(context, R.string.vault_backup_prog_writing, index + 1, plan.toWrite.size))
            val src = sourceFiles.getValue(name)
            val expected = localFiles.getValue(name)
            var copiedBytes = 0L
            val sha = MessageDigest.getInstance("SHA-256")
            backend.writeStream(name, BACKUP_CUSTOM_MIME) { out ->
                val buffer = ByteArray(256 * 1024)
                try {
                    src.inputStream().use { input ->
                        while (true) {
                            check()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buffer, 0, n)
                            sha.update(buffer, 0, n)
                            copiedBytes += n
                        }
                    }
                    out.flush()
                } finally { buffer.fill(0) }
            }
            require(copiedBytes == expected.bytes && sha.digest().hex() == expected.sha256) {
                AppLocale.str(context, R.string.vault_backup_error_write_verify)
            }
            written++
        }

        // Only update manifest and prune deleted remote artifacts after every new/changed file succeeded.
        val syncedAt = System.currentTimeMillis()
        val newManifest = VaultBackupManifest(version = 1, exportedAt = syncedAt, files = localFiles)
        val manifestBytes = newManifest.toJson().toByteArray(Charsets.UTF_8)
        backend.writeStream(BACKUP_MANIFEST_NAME, "application/json") { out ->
            out.write(manifestBytes)
            out.flush()
        }

        var removed = 0
        plan.toDelete.forEach { stale ->
            check()
            if (backend.delete(stale)) removed++
        }

        VaultBackupSyncResult(
            written = written,
            skipped = plan.toSkip.size,
            removed = removed,
            totalEntries = entries.size,
            syncedAt = syncedAt,
        )
    }

    /**
     * Restores an encrypted backup into an unconfigured (empty) vault without re-encrypting videos.
     * Verifies password + manifest hashes + every entry's decrypted SHA-256 before committing keyset.bin.
     */
    suspend fun restoreIntoEmpty(
        context: Context,
        store: VaultStore,
        backend: VaultBackupBackend,
        password: CharArray,
        progress: (String) -> Unit,
    ): VaultBackupRestoreResult = withContext(Dispatchers.IO) {
        val jobContext = coroutineContext
        val check = { jobContext.ensureActive() }
        val stagedFiles = mutableListOf<File>()
        var backupSession: VaultSession? = null
        try {
            check(!store.configured) { AppLocale.str(context, R.string.vault_store_already_exists) }
            progress(AppLocale.str(context, R.string.vault_backup_prog_verifying_password))
            val remoteFiles = backend.listFiles()
            require(BACKUP_KEYSET_NAME in remoteFiles) { AppLocale.str(context, R.string.vault_backup_error_not_found) }
            val manifest = loadOptionalManifest(context, backend)
            val keysetBytes = readExactVerifiedBytes(context, backend, BACKUP_KEYSET_NAME, MAX_KEYSET_BYTES, manifest, check)
            require(keysetBytes.size in MIN_KEYSET_BYTES..MAX_KEYSET_BYTES) {
                AppLocale.str(context, R.string.vault_backup_error_damaged)
            }
            val unlocked = try {
                VaultCrypto.unlock(keysetBytes, password)
            } catch (_: Exception) {
                error(AppLocale.str(context, R.string.vault_backup_error_wrong_password))
            }
            backupSession = unlocked

            val ids = discoverEntryIds(remoteFiles, manifest)
            val stagedEntries = mutableListOf<Pair<VaultEntry, File>>()
            val stagedPlaybacks = mutableListOf<Pair<String, File>>()
            val stagingDir = File(store.directory, ".restore-staging-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                ids.forEachIndexed { index, id ->
                    check()
                    progress(AppLocale.str(context, R.string.vault_backup_prog_verifying_entry, index + 1, ids.size))
                    val metaName = "$id.meta"
                    val vaultName = "$id.vault"
                    val playbackName = "$id.playback"
                    val metaBytes = readExactVerifiedBytes(context, backend, metaName, MAX_META_BYTES, manifest, check)
                    val entry = parseEncryptedEntry(unlocked, id, metaBytes)
                    val stagedVault = File(stagingDir, vaultName)
                    copyVerifiedCipherFile(context, backend, vaultName, stagedVault, manifest, check)
                    store.verifyFile(unlocked, id, stagedVault, entry.bytes, entry.digest.unhex(), check)
                    // Detach old-device gallery deletion/restore tracking so restored items never touch unrelated files on this device.
                    val sanitized = entry.copy(
                        sourceRemoved = true,
                        restorePending = "",
                        removalUri = "",
                        restoreTarget = "",
                        manageOriginalInGallery = false,
                    )
                    stagedEntries += sanitized to stagedVault
                    if (playbackName in remoteFiles) {
                        val pbBytes = readExactVerifiedBytes(context, backend, playbackName, MAX_META_BYTES, manifest, check)
                        // Authenticate playback ciphertext before staging.
                        val plainPb = unlocked.decryptMetadata("playback-v1:$id", pbBytes)
                        plainPb.fill(0)
                        val stagedPb = File(stagingDir, playbackName)
                        VaultCrypto.atomicWrite(stagedPb, pbBytes)
                        stagedPlaybacks += id to stagedPb
                    }
                }

                // Commit all verified payloads, metadata, and playback files atomically, then keyset.bin last.
                store.directory.mkdirs()
                stagedEntries.forEach { (entry, stagedVault) ->
                    check()
                    val destVault = store.payload(entry.id)
                    Files.move(stagedVault.toPath(), destVault.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    stagedFiles += destVault
                    val plainMeta = entry.json().toString().toByteArray(Charsets.UTF_8)
                    val encMeta = try { unlocked.encryptMetadata(entry.id, plainMeta) } finally { plainMeta.fill(0) }
                    val destMeta = store.metaFile(entry.id)
                    VaultCrypto.atomicWrite(destMeta, encMeta)
                    stagedFiles += destMeta
                }
                stagedPlaybacks.forEach { (id, stagedPb) ->
                    val destPb = store.playbackFile(id)
                    Files.move(stagedPb.toPath(), destPb.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    stagedFiles += destPb
                }
                VaultCrypto.atomicWrite(store.configFile(), keysetBytes)
                store.syncDirectory()
                store.adoptUnlockedSession(unlocked)
                backupSession = null // Ownership transferred to store.
                VaultBackupRestoreResult(restored = stagedEntries.size, skippedDuplicates = 0, mergedIntoExisting = false)
            } finally {
                stagingDir.deleteRecursively()
            }
        } catch (e: Exception) {
            backupSession?.close()
            if (!store.configured) {
                stagedFiles.forEach { runCatching { it.delete() } }
            }
            throw e
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Merges an encrypted backup into an already-unlocked vault using its own password.
     * Re-encrypts each unique entry in memory into the current vault's keyset, skips SHA-256 duplicates,
     * and never modifies existing entries or the current vault password.
     */
    suspend fun mergeIntoUnlocked(
        context: Context,
        store: VaultStore,
        backend: VaultBackupBackend,
        backupPassword: CharArray,
        progress: (String) -> Unit,
    ): VaultBackupRestoreResult = withContext(Dispatchers.IO) {
        val targetSession = store.requireSession()
        val jobContext = coroutineContext
        val check = { jobContext.ensureActive(); targetSession.requireOpen() }
        var backupSession: VaultSession? = null
        val stagingDir = File(store.directory, ".merge-staging-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress(AppLocale.str(context, R.string.vault_backup_prog_verifying_password))
            val remoteFiles = backend.listFiles()
            require(BACKUP_KEYSET_NAME in remoteFiles) { AppLocale.str(context, R.string.vault_backup_error_not_found) }
            val manifest = loadOptionalManifest(context, backend)
            val keysetBytes = readExactVerifiedBytes(context, backend, BACKUP_KEYSET_NAME, MAX_KEYSET_BYTES, manifest, check)
            require(keysetBytes.size in MIN_KEYSET_BYTES..MAX_KEYSET_BYTES) {
                AppLocale.str(context, R.string.vault_backup_error_damaged)
            }
            val unlockedBackup = try {
                VaultCrypto.unlock(keysetBytes, backupPassword)
            } catch (_: Exception) {
                error(AppLocale.str(context, R.string.vault_backup_error_wrong_password))
            }
            backupSession = unlockedBackup

            val existingKeys = store.entries()
                .filter { it.bytes > 0 && it.digest.isNotBlank() }
                .map { it.bytes to it.digest.lowercase() }
                .toMutableSet()

            val ids = discoverEntryIds(remoteFiles, manifest)
            var restored = 0
            var skippedDuplicates = 0

            ids.forEachIndexed { index, oldId ->
                check()
                progress(AppLocale.str(context, R.string.vault_backup_prog_merging_entry, index + 1, ids.size))
                val metaBytes = readExactVerifiedBytes(context, backend, "$oldId.meta", MAX_META_BYTES, manifest, check)
                val backupEntry = parseEncryptedEntry(unlockedBackup, oldId, metaBytes)
                val contentKey = backupEntry.bytes to backupEntry.digest.lowercase()
                if (contentKey in existingKeys) {
                    skippedDuplicates++
                    return@forEachIndexed
                }

                val stagedSource = File(stagingDir, "$oldId.vault")
                val newId = UUID.randomUUID().toString()
                val tempTarget = File(store.directory, "$newId.part")
                try {
                    copyVerifiedCipherFile(context, backend, "$oldId.vault", stagedSource, manifest, check)
                    val reader = unlockedBackup.open(oldId, stagedSource)
                    val (size, hash) = try {
                        targetSession.encrypt(newId, Channels.newInputStream(reader), tempTarget, check) {}
                    } finally {
                        unlockedBackup.closeReader(reader)
                    }
                    require(size == backupEntry.bytes && MessageDigest.isEqual(hash, backupEntry.digest.unhex())) {
                        AppLocale.str(context, R.string.vault_backup_error_damaged)
                    }
                    store.verifyFile(targetSession, newId, tempTarget, size, hash, check)
                    check()
                    Files.move(tempTarget.toPath(), store.payload(newId).toPath(), StandardCopyOption.ATOMIC_MOVE)

                    val playbackName = "$oldId.playback"
                    if (playbackName in remoteFiles) {
                        val pbBytes = readExactVerifiedBytes(context, backend, playbackName, MAX_META_BYTES, manifest, check)
                        val plainPb = unlockedBackup.decryptMetadata("playback-v1:$oldId", pbBytes)
                        val rewrappedPlain = try {
                            val json = JSONObject(String(plainPb, Charsets.UTF_8))
                            require(json.getInt("version") == 1 && json.getString("id") == oldId)
                            json.put("id", newId)
                            json.toString().toByteArray(Charsets.UTF_8)
                        } finally { plainPb.fill(0) }
                        val reencryptedPb = try {
                            targetSession.encryptMetadata("playback-v1:$newId", rewrappedPlain)
                        } finally { rewrappedPlain.fill(0) }
                        VaultCrypto.atomicWrite(store.playbackFile(newId), reencryptedPb)
                    }

                    val mergedEntry = backupEntry.copy(
                        id = newId,
                        sourceRemoved = true,
                        restorePending = "",
                        removalUri = "",
                        restoreTarget = "",
                        manageOriginalInGallery = false,
                    )
                    store.save(mergedEntry)
                    existingKeys += contentKey
                    restored++
                } finally {
                    tempTarget.delete()
                    stagedSource.delete()
                }
            }
            VaultBackupRestoreResult(restored = restored, skippedDuplicates = skippedDuplicates, mergedIntoExisting = true)
        } finally {
            backupPassword.fill('\u0000')
            backupSession?.close()
            stagingDir.deleteRecursively()
        }
    }

    private fun loadOptionalManifest(context: Context, backend: VaultBackupBackend): VaultBackupManifest? {
        val stream = backend.openInput(BACKUP_MANIFEST_NAME) ?: return null
        return try {
            stream.use { VaultBackupManifest.parse(readBoundedUtf8(it, MAX_MANIFEST_BYTES)) }
        } catch (_: Exception) {
            error(AppLocale.str(context, R.string.vault_backup_error_damaged))
        }
    }

    private fun discoverEntryIds(
        remoteFiles: Map<String, Long>,
        manifest: VaultBackupManifest?,
    ): List<String> {
        val candidates = if (manifest != null) manifest.files.keys else remoteFiles.keys
        val metaIds = candidates
            .filter { VaultBackupRules.isSafeVaultArtifactName(it) && it.endsWith(".meta") }
            .map { it.removeSuffix(".meta") }
            .sorted()
        metaIds.forEach { id ->
            require("$id.vault" in remoteFiles) { "Missing encrypted video file for entry $id" }
        }
        return metaIds
    }

    private fun parseEncryptedEntry(session: VaultSession, id: String, metaBytes: ByteArray): VaultEntry {
        val plain = session.decryptMetadata(id, metaBytes)
        return try {
            VaultEntry.parse(JSONObject(String(plain, Charsets.UTF_8))).also { require(it.id == id) }
        } finally {
            plain.fill(0)
        }
    }

    private fun readExactVerifiedBytes(
        context: Context,
        backend: VaultBackupBackend,
        name: String,
        maxBytes: Int,
        manifest: VaultBackupManifest?,
        check: () -> Unit,
    ): ByteArray {
        val bytes = backend.openInput(name)?.use { readBoundedBytes(it, maxBytes, check) }
            ?: error(AppLocale.str(context, R.string.vault_backup_error_damaged))
        manifest?.files?.get(name)?.let { expected ->
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes)
            require(bytes.size.toLong() == expected.bytes && MessageDigest.isEqual(actualHash, expected.sha256.unhex())) {
                AppLocale.str(context, R.string.vault_backup_error_damaged)
            }
        }
        return bytes
    }

    private fun copyVerifiedCipherFile(
        context: Context,
        backend: VaultBackupBackend,
        name: String,
        destination: File,
        manifest: VaultBackupManifest?,
        check: () -> Unit,
    ) {
        val input = backend.openInput(name) ?: error(AppLocale.str(context, R.string.vault_backup_error_damaged))
        val sha = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        val buffer = ByteArray(256 * 1024)
        try {
            input.use { src ->
                FileOutputStream(destination).use { out ->
                    while (true) {
                        check()
                        val n = src.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        copied += n
                    }
                    out.fd.sync()
                }
            }
        } finally {
            buffer.fill(0)
        }
        require(copied > 0) { AppLocale.str(context, R.string.vault_backup_error_damaged) }
        manifest?.files?.get(name)?.let { expected ->
            require(copied == expected.bytes && MessageDigest.isEqual(sha.digest(), expected.sha256.unhex())) {
                AppLocale.str(context, R.string.vault_backup_error_damaged)
            }
        }
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int, check: () -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(minOf(16 * 1024, maxBytes))
        var total = 0
        try {
            while (true) {
                check()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (n > maxBytes - total) error("Backup metadata exceeds size limit")
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun readBoundedUtf8(input: InputStream, maxBytes: Int): String =
        String(readBoundedBytes(input, maxBytes), Charsets.UTF_8)
}
