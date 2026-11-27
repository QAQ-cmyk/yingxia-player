@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.yingxia.player.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import cn.yingxia.player.AppGraph
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import cn.yingxia.player.core.*
import cn.yingxia.player.vault.FileVaultBackupBackend
import cn.yingxia.player.vault.VaultBackupEngine
import cn.yingxia.player.vault.VaultBackupRules
import cn.yingxia.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun createFlavorSyncBridge(graph: AppGraph): SyncExtensionBridge =
    KtorSyncBridge(graph)

private class KtorSyncBridge(private val graph: AppGraph) : SyncExtensionBridge {
    override val enabled: Boolean = true
    private val context = graph.appContext
    private val prefs = context.getSharedPreferences("yingxia_sync_v2", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keyAlias = "yingxia.sync.token.v1"

    private fun str(id: Int, vararg args: Any): String = AppLocale.str(context, id, *args)

    private val mutableState = MutableStateFlow(
        SyncStatusState(
            available = true,
            configured = readServerUrl().isNotBlank() && readBearerToken().isNotBlank(),
            serverUrl = readServerUrl(),
        )
    )
    override val state: StateFlow<SyncStatusState> = mutableState

    private val remoteSource = object : MediaSource {
        override val sourceId: String = "ktor-remote"
        override suspend fun query(): List<VideoItem> = withContext(Dispatchers.IO) {
            val baseUrl = readServerUrl().trimEnd('/')
            val token = readBearerToken()
            if (baseUrl.isBlank() || token.isBlank()) return@withContext emptyList()
            val body = httpGetText("$baseUrl/api/v1/media", token)
            val resp = json.decodeFromString<RemoteMediaListResponse>(body)
            mutableState.update { it.copy(remoteMediaCount = resp.items.size) }
            resp.items.map { item ->
                VideoItem(
                    id = VideoId("ktor:${item.id}"),
                    uri = "$baseUrl/api/v1/media/${item.id}/stream",
                    name = item.name,
                    folder = "Ktor · ${item.folder}",
                    folderKey = "ktor:remote",
                    durationMs = item.durationMs,
                    sizeBytes = item.sizeBytes,
                    addedAt = System.currentTimeMillis(),
                    source = sourceId,
                    accessible = true,
                )
            }
        }
        override suspend fun resolve(video: VideoItem): String {
            check(readServerUrl().isNotBlank() && readBearerToken().isNotBlank()) { str(R.string.space_sync_config_missing) }
            return video.uri
        }
    }

    override val extraMediaSources: List<MediaSource> = listOf(remoteSource)

    override fun customExoMediaSource(video: VideoItem): androidx.media3.exoplayer.source.MediaSource? {
        if (video.source != "ktor-remote") return null
        val token = readBearerToken()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(if (token.isNotBlank()) mapOf("Authorization" to "Bearer $token") else emptyMap())
        return ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(MediaItem.Builder().setMediaId(video.id.value).setUri(video.uri).build())
    }

    override suspend fun updateConfig(serverUrl: String, bearerToken: String) = withContext(Dispatchers.IO) {
        val normalized = serverUrl.trim().trimEnd('/')
        require(
            normalized.startsWith("https://") ||
                normalized.startsWith("http://127.0.0.1") ||
                normalized.startsWith("http://10.0.2.2") ||
                normalized.startsWith("http://localhost")
        ) {
            str(R.string.space_sync_tls_required)
        }
        prefs.edit().putString("server_url", normalized).apply()
        if (bearerToken.isNotBlank()) saveBearerToken(bearerToken)
        mutableState.update {
            it.copy(
                configured = normalized.isNotBlank() && readBearerToken().isNotBlank(),
                serverUrl = normalized,
                statusMessage = null,
            )
        }
    }

    override suspend fun refreshRemoteMedia(): Result<Int> = runCatching {
        mutableState.update { it.copy(busy = true) }
        try {
            val items = remoteSource.query()
            mutableState.update { it.copy(remoteMediaCount = items.size, statusMessage = null) }
            items.size
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    override suspend fun pushEncryptedSnapshot(passphrase: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        mutableState.update { it.copy(busy = true) }
        try {
            runCatching {
                val (baseUrl, token) = requireEndpoint()
                val allVideos = graph.repository.allVideos().filter { it.source != "ktor-remote" }
                val byId = allVideos.associateBy { it.id }
                val bookmarks = graph.repository.bookmarks.first().mapNotNull { bm ->
                    val v = byId[bm.videoId] ?: return@mapNotNull null
                    SyncBookmarkDto(v.name, v.sizeBytes, bm.positionMs, bm.note)
                }
                val records = allVideos.filter {
                    it.favorite || it.positionMs > 0 || it.lastPlayedAt > 0 || !it.displayName.isNullOrBlank() || it.cropBars
                }.map { v ->
                    SyncVideoRecordDto(
                        id = v.id.value,
                        uri = v.uri,
                        name = v.name,
                        sizeBytes = v.sizeBytes,
                        favorite = v.favorite,
                        positionMs = v.positionMs,
                        lastPlayedAt = v.lastPlayedAt,
                        displayName = v.displayName,
                        cropBars = v.cropBars,
                    )
                }
                val now = System.currentTimeMillis()
                val nextRev = prefs.getLong("sync_revision", 0L) + 1L
                val plain = PlainSyncSnapshotDto(exportedAtEpochMs = now, records = records, bookmarks = bookmarks)
                val sealed = ZeroKnowledgeCrypto.sealSnapshot(plain, passphrase, revision = nextRev, updatedAtEpochMs = now)
                val bytes = json.encodeToString(sealed).toByteArray(Charsets.UTF_8)
                httpPutStream(
                    url = "$baseUrl/api/v1/sync/snapshot",
                    token = token,
                    contentLength = bytes.size.toLong(),
                    contentType = "application/json",
                ) { out -> out.write(bytes) }
                prefs.edit().putLong("sync_revision", nextRev).apply()
                mutableState.update { it.copy(lastSyncedAtEpochMs = now, statusMessage = str(R.string.space_sync_push_ok, nextRev)) }
            }
        } finally {
            passphrase.fill('\u0000')
            mutableState.update { it.copy(busy = false) }
        }
    }

    override suspend fun pullEncryptedSnapshot(passphrase: CharArray): Result<Int> = withContext(Dispatchers.IO) {
        mutableState.update { it.copy(busy = true) }
        try {
            runCatching {
                val (baseUrl, token) = requireEndpoint()
                val envText = httpGetText("$baseUrl/api/v1/sync/snapshot", token)
                val env = json.decodeFromString<EncryptedSyncEnvelopeDto>(envText)
                val plain = ZeroKnowledgeCrypto.openSnapshot(env, passphrase)
                val library = graph.repository.allVideos()
                val matched = plain.records.mapNotNull { r ->
                    val hit = library.firstOrNull { it.id.value == r.id }
                        ?: library.filter { it.name == r.name && it.sizeBytes == r.sizeBytes && r.sizeBytes > 0 }.singleOrNull()
                    hit?.let { LibraryRecord(it.id.value, it.uri, r.name, r.sizeBytes, r.favorite, r.positionMs, r.lastPlayedAt, r.displayName, r.cropBars) }
                }
                graph.repository.restoreRecords(matched)

                val existingBookmarks = graph.repository.bookmarks.first()
                    .mapTo(HashSet()) { Triple(it.videoId.value, it.positionMs, it.note) }
                var restoredBookmarks = 0
                plain.bookmarks.forEach { bm ->
                    if (bm.positionMs < 0) return@forEach
                    val hit = library.filter { it.name == bm.videoName && it.sizeBytes == bm.videoSizeBytes && bm.videoSizeBytes > 0 }.singleOrNull()
                        ?: library.filter { it.name == bm.videoName }.singleOrNull()
                        ?: return@forEach
                    val key = Triple(hit.id.value, bm.positionMs, bm.note)
                    if (existingBookmarks.add(key)) {
                        graph.repository.saveBookmark(hit.id, bm.positionMs, bm.note)
                        restoredBookmarks++
                    }
                }

                prefs.edit().putLong("sync_revision", maxOf(prefs.getLong("sync_revision", 0L), env.revision)).apply()
                mutableState.update {
                    it.copy(
                        lastSyncedAtEpochMs = System.currentTimeMillis(),
                        statusMessage = str(R.string.space_sync_pull_ok, matched.size, restoredBookmarks),
                    )
                }
                matched.size
            }
        } finally {
            passphrase.fill('\u0000')
            mutableState.update { it.copy(busy = false) }
        }
    }

    override suspend fun backupVaultBlobs(): Result<Int> = withContext(Dispatchers.IO) {
        mutableState.update { it.copy(busy = true) }
        try {
            runCatching {
                val (baseUrl, token) = requireEndpoint()
                val vaultDir = graph.vault.store.directory
                val files = vaultDir.listFiles().orEmpty()
                    .filter { it.isFile && VaultBackupRules.isSafeVaultArtifactName(it.name) }
                    .sortedBy { it.name }
                files.forEach { file ->
                    val sha = sha256FileHex(file)
                    httpPutStream(
                        url = "$baseUrl/api/v1/vault/blobs/${file.name}",
                        token = token,
                        contentLength = file.length(),
                        contentType = "application/octet-stream",
                        extraHeaders = mapOf("X-Content-SHA256" to sha),
                    ) { out ->
                        file.inputStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n > 0) out.write(buf, 0, n)
                            }
                        }
                    }
                }
                mutableState.update {
                    it.copy(
                        vaultBackupCount = files.size,
                        statusMessage = str(R.string.space_sync_vault_cold_ok, files.size),
                    )
                }
                files.size
            }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    override suspend fun restoreVaultBlobs(password: CharArray): Result<Int> = withContext(Dispatchers.IO) {
        mutableState.update { it.copy(busy = true) }
        val stagingDir = File(context.noBackupFilesDir, "sync-vault-stage-${UUID.randomUUID()}")
        try {
            runCatching {
                val store = graph.vault.store
                if (store.configured && !graph.vault.state.value.unlocked) {
                    error(str(R.string.vault_backup_unlock_first_to_merge))
                }
                val (baseUrl, token) = requireEndpoint()
                val listText = httpGetText("$baseUrl/api/v1/vault/blobs", token)
                val blobList = json.decodeFromString<VaultBlobListResponse>(listText)
                require(blobList.blobs.any { it.blobName == "keyset.bin" }) { str(R.string.vault_backup_error_not_found) }
                stagingDir.mkdirs()
                blobList.blobs.forEach { meta ->
                    require(VaultBackupRules.isSafeVaultArtifactName(meta.blobName)) { str(R.string.vault_backup_error_damaged) }
                    val dest = File(stagingDir, meta.blobName)
                    httpDownloadVerified("$baseUrl/api/v1/vault/blobs/${meta.blobName}", token, dest, meta.sizeBytes, meta.sha256Hex)
                }
                val backend = FileVaultBackupBackend(stagingDir, "KtorSync")
                val restoredCount = if (!store.configured) {
                    val result = VaultBackupEngine.restoreIntoEmpty(context, store, backend, password.clone()) {}
                    withContext(Dispatchers.Main) { graph.vault.lock() }
                    result.restored
                } else {
                    val result = VaultBackupEngine.mergeIntoUnlocked(context, store, backend, password.clone()) {}
                    graph.vault.refreshUnlockedInventory()
                    result.restored
                }
                mutableState.update {
                    it.copy(
                        vaultBackupCount = blobList.blobs.size,
                        statusMessage = str(R.string.space_sync_vault_restore_ok, restoredCount),
                    )
                }
                restoredCount
            }
        } finally {
            password.fill('\u0000')
            stagingDir.deleteRecursively()
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun requireEndpoint(): Pair<String, String> {
        val baseUrl = readServerUrl().trimEnd('/')
        val token = readBearerToken()
        check(baseUrl.isNotBlank() && token.isNotBlank()) { str(R.string.space_sync_config_missing) }
        return baseUrl to token
    }

    private fun readServerUrl(): String = prefs.getString("server_url", "").orEmpty()

    private fun saveBearerToken(token: String) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString("token_sealed", packed).apply()
    }

    private fun readBearerToken(): String = runCatching {
        val packed = prefs.getString("token_sealed", null) ?: return ""
        val parts = packed.split(':', limit = 2)
        if (parts.size != 2) return ""
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(keyAlias, null) as? SecretKey ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrDefault("")

    private fun sha256FileHex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpGetText(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownloadVerified(
        url: String,
        token: String,
        dest: File,
        expectedSize: Long,
        expectedSha256Hex: String,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            val md = MessageDigest.getInstance("SHA-256")
            var written = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                    out.fd.sync()
                }
            }
            val actualSha = md.digest().joinToString("") { "%02x".format(it) }
            require(
                written > 0 &&
                    (expectedSize <= 0L || written == expectedSize) &&
                    actualSha.equals(expectedSha256Hex, ignoreCase = true)
            ) {
                str(R.string.vault_backup_error_damaged)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPutStream(
        url: String,
        token: String,
        contentLength: Long,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
        writer: (OutputStream) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 30_000
            setFixedLengthStreamingMode(contentLength)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", contentType)
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { writer(it) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_CONFLICT) {
                throw IllegalStateException(str(R.string.space_sync_conflict_error))
            }
            check(code in 200..299) { "HTTP $code" }
        } finally {
            conn.disconnect()
        }
    }
}
