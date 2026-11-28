package cn.yingxia.player.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import cn.yingxia.player.AppLocale
import cn.yingxia.player.R
import cn.yingxia.player.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

fun mediaPermissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
    sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
fun libraryAccess(context: Context): LibraryAccess {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    if (granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)) return LibraryAccess.FULL
    if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return LibraryAccess.PARTIAL
    return LibraryAccess.NONE
}
fun stableHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private fun Uri.bareAuthority(): String {
    val raw = authority.orEmpty()
    val idx = raw.indexOf('@')
    return if (idx > 0 && raw.substring(0, idx).all(Char::isDigit)) raw.substring(idx + 1) else raw
}

private fun Uri.stripNumericUserId(): Uri {
    val raw = authority.orEmpty()
    val idx = raw.indexOf('@')
    return if (idx > 0 && raw.substring(0, idx).all(Char::isDigit)) buildUpon().authority(raw.substring(idx + 1)).build() else this
}

/** SAF returns a Files URI on some devices; system deletion requires the typed video collection. */
fun canonicalVideoUri(context: Context, input: Uri): Uri? = runCatching {
    val normalized = input.stripNumericUserId()
    val bare = normalized.bareAuthority()
    val resolved = if (bare == "media") normalized else if (Build.VERSION.SDK_INT >= 29) MediaStore.getMediaUri(context, input)?.stripNumericUserId()
    else if (bare == "com.android.externalstorage.documents" && DocumentsContract.isDocumentUri(context, normalized)) {
        val document = DocumentsContract.getDocumentId(normalized)
        val volume = document.substringBefore(':'); val relative = document.substringAfter(':')
        val path = if (volume == "primary") "${android.os.Environment.getExternalStorageDirectory()}/$relative" else "/storage/$volume/$relative"
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), "_data=?", arrayOf(path), null)?.use {
            if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.getLong(0)) else null
        }
    } else null
    if (resolved?.bareAuthority() != "media") return@runCatching null
    if (resolved.pathSegments.contains("video")) resolved
    else if (resolved.pathSegments.contains("file")) {
        val volume = resolved.pathSegments.first()
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ContentUris.withAppendedId(collection, resolved.lastPathSegment!!.toLong())
    } else null
}.getOrNull()

class MediaStoreSource(private val context: Context) : MediaSource {
    override val sourceId = "media-store"
    override suspend fun query(): List<VideoItem> = withContext(Dispatchers.IO) {
        if (libraryAccess(context) == LibraryAccess.NONE) return@withContext emptyList()
        val uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val fields = mutableListOf("_id", "_display_name", "bucket_display_name", "bucket_id", "duration", "_size", "width", "height", "date_added")
        if (Build.VERSION.SDK_INT >= 29) fields += "volume_name"
        val items = mutableListOf<VideoItem>()
        context.contentResolver.query(uri, fields.toTypedArray(), null, null, "date_added DESC")?.use { c ->
            fun str(name: String) = c.getColumnIndex(name).let { if (it >= 0 && !c.isNull(it)) c.getString(it) else "" }
            fun num(name: String) = c.getColumnIndex(name).let { if (it >= 0 && !c.isNull(it)) c.getLong(it) else 0L }
            while (c.moveToNext()) {
                ensureActive()
                val volume = str("volume_name").ifBlank { "external" }
                val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume) else uri
                val mediaId = num("_id")
                items += VideoItem(VideoId("ms:$volume:$mediaId"), ContentUris.withAppendedId(collection, mediaId).toString(),
                    str("_display_name").ifBlank { AppLocale.str(context, R.string.media_untitled_video) }, str("bucket_display_name").ifBlank { AppLocale.str(context, R.string.media_other_videos) },
                    "$volume:${str("bucket_id")}", num("duration"), num("_size"), num("width").toInt(), num("height").toInt(), num("date_added") * 1000)
            }
        }
        items
    }
    override suspend fun resolve(video: VideoItem): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(video.uri), "r")?.use { } ?: throw java.io.FileNotFoundException()
            video.uri
        } catch (_: SecurityException) { throw PlayerFailure(FailureKind.ACCESS_DENIED, AppLocale.str(context, R.string.media_error_access_denied)) }
        catch (_: java.io.FileNotFoundException) { throw PlayerFailure(FailureKind.SOURCE_MISSING, AppLocale.str(context, R.string.media_error_source_missing)) }
    }
}

class LocalVideoRepository(private val context: Context, private val db: VideoDatabase,
    private val extraSources: List<MediaSource> = emptyList()) : VideoRepository {
    private val dao = db.videos()
    private val source = MediaStoreSource(context)
    private val mutex = Mutex()
    override val videos = dao.observeVideos().map { rows -> rows.map { it.domain() }.filter(PlaybackRules::visible) }
    override val hiddenVideos = dao.observeVideos().map { rows -> rows.filter { it.hidden }.map { it.domain() } }
    override val bookmarks = combine(dao.observeBookmarks(), videos) { rows, visible ->
        val allowed = visible.map { it.id.value }.toSet()
        rows.filter { it.videoId in allowed }.map { Bookmark(it.id, VideoId(it.videoId), it.positionMs, it.note) }
    }
    override val scanning = MutableStateFlow(false)
    override val access = MutableStateFlow(libraryAccess(context))
    override val scanError = MutableStateFlow<String?>(null)

    override suspend fun refresh() = mutex.withLock {
        scanning.value = true
        access.value = libraryAccess(context)
        try {
            val fresh = source.query()
            val saved = dao.all().associateBy { it.id }
            val imports = withContext(Dispatchers.IO) {
                saved.values.filter { it.source == "document" }.map { row ->
                    row.copy(accessible = runCatching { source.resolve(row.domain()); true }.getOrDefault(false))
                }
            }
            val aliases = withContext(Dispatchers.IO) { imports.filter { it.accessible }.mapNotNull { row ->
                canonicalVideoUri(context, Uri.parse(row.uri))?.let(::mediaIdentity)?.takeIf { it != row.id }?.let { row.id to it }
            }.toMap() }
            db.withTransaction {
                // Read user records inside the same transaction as the scan merge, so an
                // in-flight scan cannot overwrite a new hide, favorite or playback position.
                val latest = dao.all().associateBy { it.id }.toMutableMap()
                aliases.forEach { (from, to) ->
                    val old = latest[from] ?: return@forEach
                    val existing = latest[to]
                    val recent = if ((existing?.lastPlayedAt ?: 0) > old.lastPlayedAt) existing!! else old
                    val merged = old.copy(id = to, favorite = old.favorite || existing?.favorite == true,
                        hidden = old.hidden || existing?.hidden == true, positionMs = recent.positionMs, lastPlayedAt = recent.lastPlayedAt,
                        displayName = old.displayName ?: existing?.displayName,
                        rotation = 0, mirrored = false, cropBars = old.cropBars || existing?.cropBars == true)
                    dao.upsert(listOf(merged)); dao.relocateBookmarks(from, to); dao.removeAlias(from)
                    latest.remove(from); latest[to] = merged
                }
                dao.markSourceUnavailable(source.sourceId)
                dao.upsert(fresh.map { video ->
                    val old = latest[video.id.value]
                    video.entity().copy(uri = if (old?.source == "document") old.uri else video.uri,
                        source = if (old?.source == "document") "document" else video.source,
                        hidden = old?.hidden ?: false, favorite = old?.favorite ?: false,
                        positionMs = old?.positionMs ?: 0, lastPlayedAt = old?.lastPlayedAt ?: 0,
                        subtitleUri = old?.subtitleUri, subtitleName = old?.subtitleName,
                        displayName = old?.displayName, rotation = 0, mirrored = false, cropBars = old?.cropBars ?: false)
                })
                // A persisted document grant remains usable even without broad gallery access.
                val scannedIds = fresh.map { it.id.value }.toSet()
                dao.upsert(imports.map { it.copy(id = aliases[it.id] ?: it.id) }.filter { it.id !in scannedIds }.map { checked ->
                    (latest[checked.id] ?: checked).copy(accessible = checked.accessible)
                })
                extraSources.forEach { candidate -> dao.markSourceUnavailable(candidate.sourceId) }
                val extra = extraSources.flatMap { candidate -> runCatching { candidate.query() }.getOrDefault(emptyList()) }
                if (extra.isNotEmpty()) dao.upsert(extra.map { video ->
                    val old = dao.get(video.id.value)
                    video.entity().copy(hidden = old?.hidden ?: false, favorite = old?.favorite ?: video.favorite,
                        positionMs = old?.positionMs ?: video.positionMs, lastPlayedAt = old?.lastPlayedAt ?: video.lastPlayedAt,
                        displayName = old?.displayName ?: video.displayName, rotation = 0, mirrored = false,
                        cropBars = old?.cropBars ?: video.cropBars, subtitleUri = null, subtitleName = null)
                })
            }
            scanError.value = null
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) {
            access.value = libraryAccess(context)
            dao.markSourceUnavailable(source.sourceId)
            scanError.value = AppLocale.str(context, R.string.media_error_perm_changed)
        } catch (_: Exception) { scanError.value = AppLocale.str(context, R.string.media_error_scan_failed) }
        finally { scanning.value = false }
    }

    override suspend fun importVideo(uri: String): VideoItem = mutex.withLock {
        withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            require(parsed.scheme == "content") { AppLocale.str(context, R.string.media_error_pick_local) }
            val canonical = canonicalVideoUri(context, parsed)
            val mediaId = mediaIdentity(canonical ?: parsed)
            val known = mediaId?.let { dao.get(it) } ?: dao.byUri(canonical?.toString() ?: uri) ?: dao.byUri(uri)
            if (known != null) {
                if (known.hidden) throw PlayerFailure(FailureKind.ACCESS_DENIED, AppLocale.str(context, R.string.media_error_hidden_import))
                source.resolve(known.domain().copy(uri = uri))
                val updated = known.copy(accessible = true, uri = uri, source = "document")
                dao.upsert(listOf(updated))
                return@withContext updated.domain()
            }
            val item = readDocument(parsed, VideoId(mediaId ?: "uri:${stableHash(uri)}"))
            dao.upsert(listOf(item.entity()))
            item
        }
    }

    /** Inspect the granted URI without importing it or changing an existing library record. */
    internal suspend fun sharedVideo(uri: Uri): VideoItem = withContext(Dispatchers.IO) {
        val canonical = canonicalVideoUri(context, uri)
        val mediaId = mediaIdentity(canonical ?: uri)
        val known = mediaId?.let { dao.get(it) } ?: dao.byUri(canonical?.toString() ?: uri.toString()) ?: dao.byUri(uri.toString())
        if (known?.hidden == true) throw PlayerFailure(FailureKind.ACCESS_DENIED, AppLocale.str(context, R.string.media_error_hidden_import))
        val id = VideoId(known?.id ?: mediaId ?: "uri:${stableHash(uri.toString())}")
        readDocument(uri, id, shared = true)
    }

    private fun readDocument(parsed: Uri, id: VideoId, shared: Boolean = false): VideoItem {
        var name = AppLocale.str(context, R.string.media_imported_video)
        var size = 0L
        val fields = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        runCatching {
            context.contentResolver.query(parsed, fields, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, parsed)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" ||
                number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) > 0 ||
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.startsWith("video/", ignoreCase = true) == true
            if (shared) require(hasVideo)
            VideoItem(id, parsed.toString(), name, AppLocale.str(context, if (shared) R.string.media_shared_folder else R.string.media_manual_folder), "documents",
                number(MediaMetadataRetriever.METADATA_KEY_DURATION), size,
                number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(), number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt(),
                System.currentTimeMillis(), if (shared) "share" else "document")
        } catch (_: SecurityException) { throw PlayerFailure(FailureKind.ACCESS_DENIED, AppLocale.str(context, R.string.media_error_grant_expired)) }
        catch (_: Exception) { throw PlayerFailure(FailureKind.UNSUPPORTED_FORMAT, AppLocale.str(context, R.string.media_error_unreadable)) }
        finally { retriever.release() }
    }
    private fun mediaIdentity(input: Uri): String? {
        val normalized = input.stripNumericUserId()
        val bare = normalized.bareAuthority()
        val mediaUri = if (bare == "com.android.providers.media.documents") {
            val id = runCatching { DocumentsContract.getDocumentId(normalized) }.getOrNull()?.removePrefix("video:")?.toLongOrNull() ?: return null
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else if (bare == "media" && normalized.pathSegments.contains("video")) normalized else return null
        val id = mediaUri.lastPathSegment?.toLongOrNull() ?: return null
        var volume = if (Build.VERSION.SDK_INT >= 29) mediaUri.pathSegments.firstOrNull().orEmpty() else "external"
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { context.contentResolver.query(mediaUri, arrayOf("volume_name"), null, null, null)?.use { if (it.moveToFirst()) volume = it.getString(0) ?: volume } }
            if (volume == "external") {
                // The merged external URI and its specific-volume URI refer to the same row.
                val known = runCatching { context.contentResolver.query(mediaUri, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
                if (known?.startsWith("/storage/emulated/") == true) volume = "external_primary"
            }
        }
        return "ms:$volume:$id"
    }
    override suspend fun allVideos() = dao.all().map { it.domain() }
    /** Completed vault moves must not leak names or notes through ordinary history/JSON backups. */
    suspend fun forgetEncryptedSource(video: VideoItem, extraUris: Set<String> = emptySet()) = mutex.withLock {
        db.withTransaction {
            val uris = extraUris + video.uri
            dao.all().filter { it.id == video.id.value || it.uri in uris }.forEach {
                dao.deleteVideoBookmarks(it.id)
                dao.removeAlias(it.id)
            }
        }
    }
    override suspend fun get(id: VideoId) = dao.get(id.value)?.domain()
    private suspend fun resolveSource(video: VideoItem) {
        val delegate = extraSources.firstOrNull { it.sourceId == video.source } ?: source
        delegate.resolve(video)
    }
    override suspend fun requirePlayable(id: VideoId): VideoItem {
        val video = get(id) ?: throw PlayerFailure(FailureKind.SOURCE_MISSING, AppLocale.str(context, R.string.media_error_not_found))
        if (video.hidden) throw PlayerFailure(FailureKind.ACCESS_DENIED, AppLocale.str(context, R.string.media_error_hidden_play))
        resolveSource(video)
        return video
    }
    override suspend fun requirePlayable(video: VideoItem): VideoItem {
        if (video.source != "share") return requirePlayable(video.id)
        if (get(video.id)?.hidden == true) throw PlayerFailure(FailureKind.ACCESS_DENIED, AppLocale.str(context, R.string.media_error_hidden_play))
        resolveSource(video)
        return video
    }
    override suspend fun setHidden(ids: Set<VideoId>, hidden: Boolean) = db.withTransaction {
        ids.map { it.value }.chunked(900).forEach { dao.hidden(it, hidden) }
    }
    override suspend fun setFavorite(ids: Set<VideoId>, favorite: Boolean) = db.withTransaction {
        ids.map { it.value }.chunked(900).forEach { dao.favorite(it, favorite) }
    }
    override suspend fun savePicture(id: VideoId, cropBars: Boolean) {
        check(dao.get(id.value) != null) { AppLocale.str(context, R.string.media_error_not_found) }
        dao.crop(id.value, cropBars)
    }
    override suspend fun restoreRecords(rows: List<LibraryRecord>) = db.withTransaction {
        rows.forEach { row ->
            val saved = dao.get(row.id) ?: dao.byUri(row.uri) ?: return@forEach
            dao.restore(saved.id, row.favorite, row.positionMs.coerceAtLeast(0), row.lastPlayedAt.coerceAtLeast(0),
                row.displayName ?: saved.displayName, row.cropBars)
        }
    }
    override suspend fun rename(id: VideoId, displayName: String) {
        val trimmed = displayName.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 80) { AppLocale.str(context, R.string.media_error_rename_length) }
        require(trimmed.none { it == '\n' || it == '\r' || it == '\u0000' }) { AppLocale.str(context, R.string.media_error_rename_newline) }
        check(dao.get(id.value) != null) { AppLocale.str(context, R.string.media_error_not_found) }
        dao.rename(id.value, trimmed)
    }
    override suspend fun saveProgress(id: VideoId, positionMs: Long) { dao.progress(id.value, positionMs.coerceAtLeast(0), System.currentTimeMillis()) }
    override suspend fun saveBookmark(videoId: VideoId, positionMs: Long, note: String, id: Long) {
        val video = requirePlayable(videoId)
        val clamped = if (video.durationMs > 0) PlaybackRules.seek(positionMs, video.durationMs) else positionMs.coerceAtLeast(0L)
        dao.bookmark(BookmarkEntity(id, videoId.value, clamped, note.trim().take(200)))
    }
    override suspend fun deleteBookmark(id: Long) { dao.deleteBookmark(id) }
    override suspend fun clearHistory() { dao.clearHistory() }

    /** Called only after a verified file move. Keeps learning records on the new media identity. */
    suspend fun relocated(original: VideoItem, destination: String, folderName: String): VideoItem {
        val imported = importVideo(destination)
        return mutex.withLock {
            db.withTransaction {
                val old = dao.get(original.id.value) ?: original.entity()
                val moved = imported.entity().copy(folder = folderName, folderKey = "managed:$folderName",
                    favorite = old.favorite, hidden = old.hidden, positionMs = old.positionMs,
                    lastPlayedAt = old.lastPlayedAt, subtitleUri = null, subtitleName = null,
                    displayName = old.displayName, rotation = 0, mirrored = false, cropBars = old.cropBars)
                dao.upsert(listOf(moved))
                if (old.id != moved.id) { dao.unavailable(old.id); dao.relocateBookmarks(old.id, moved.id) }
                moved.domain()
            }
        }
    }
}
