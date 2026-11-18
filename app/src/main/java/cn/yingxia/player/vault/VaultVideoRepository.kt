package cn.yingxia.player.vault

import cn.yingxia.player.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Per-unlock private library. No Room, thumbnails, exports or plaintext playback records. */
class VaultVideoRepository(private val store: VaultStore) : VideoRepository {
    override val videos = MutableStateFlow<List<VideoItem>>(emptyList())
    override val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    override val hiddenVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    override val scanning = MutableStateFlow(false)
    override val access = MutableStateFlow(LibraryAccess.FULL)
    override val scanError = MutableStateFlow<String?>(null)
    private val monitor = Any()
    private var closed = false
    private var nextBookmark = 1L
    private val damaged = mutableSetOf<VideoId>()
    private fun file(id: VideoId) = File(store.directory, "${java.util.UUID.fromString(id.value)}.playback")
    private fun aad(id: VideoId) = "playback-v1:${id.value}"
    private fun checkOpen() { check(!closed); store.requireSession() }

    suspend fun initialize(entries: List<VaultEntry>) = withContext(Dispatchers.IO) { synchronized(monitor) {
        checkOpen()
        val marks = mutableListOf<Bookmark>()
        videos.value = entries.map { entry ->
            val id = VideoId(entry.id)
            val base = VideoItem(id, "vault://${entry.id}", entry.originalName, "加密保险箱 · 仅本机", "vault",
                entry.duration, entry.bytes, addedAt = entry.created, source = "vault", displayName = entry.name)
            val path = file(id)
            if (!path.exists()) base else try {
                require(path.length() in 1..1048576)
                val plain = store.requireSession().decryptMetadata(aad(id), path.readBytes())
                val json = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
                require(json.getInt("version") == 1 && json.getString("id") == id.value)
                val rows = json.getJSONArray("bookmarks")
                require(rows.length() <= 256)
                val parsed = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
                    Bookmark(it.getLong("id"), id, it.getLong("position"), it.getString("note"))
                } }
                val item = base.copy(positionMs = json.getLong("position"), favorite = json.getBoolean("favorite"),
                    cropBars = json.getBoolean("crop"))
                marks += parsed
                item
            } catch (_: Exception) {
                checkOpen(); damaged += id; base // Never overwrite a corrupt record with defaults.
            }
        }
        bookmarks.value = marks
        nextBookmark = (marks.maxOfOrNull { it.id } ?: 0) + 1
    } }

    private fun persist(id: VideoId, item: VideoItem, marks: List<Bookmark>) {
        checkOpen()
        check(id !in damaged) { "私密播放记录损坏，原记录已保留" }
        val rows = JSONArray()
        marks.filter { it.videoId == id }.forEach { rows.put(JSONObject().put("id", it.id).put("position", it.positionMs).put("note", it.note)) }
        val plain = JSONObject().put("version", 1).put("id", id.value).put("position", item.positionMs)
            .put("favorite", item.favorite).put("crop", item.cropBars)
            .put("bookmarks", rows).toString().toByteArray(Charsets.UTF_8)
        try { VaultCrypto.atomicWrite(file(id), store.requireSession().encryptMetadata(aad(id), plain)) }
        finally { plain.fill(0) }
    }
    private suspend fun update(id: VideoId, transform: (VideoItem) -> VideoItem) = withContext(Dispatchers.IO) { synchronized(monitor) {
        checkOpen()
        val next = transform(videos.value.first { it.id == id })
        persist(id, next, bookmarks.value)
        videos.value = videos.value.map { if (it.id == id) next else it }
    } }
    override suspend fun saveProgress(id: VideoId, positionMs: Long) = update(id) { it.copy(positionMs = PlaybackRules.seek(positionMs, it.durationMs)) }
    override suspend fun setFavorite(ids: Set<VideoId>, favorite: Boolean) { ids.forEach { update(it) { v -> v.copy(favorite = favorite) } } }
    override suspend fun rename(id: VideoId, displayName: String) = withContext(Dispatchers.IO) { synchronized(monitor) {
        checkOpen()
        val name = displayName.trim()
        require(name.isNotEmpty() && name.length <= 80 && '\n' !in name && '\r' !in name)
        val entry = store.load(id.value)
        store.save(entry.copy(name = name))
        videos.value = videos.value.map { if (it.id == id) it.copy(displayName = name) else it }
    } }
    override suspend fun savePicture(id: VideoId, cropBars: Boolean) = update(id) { it.copy(cropBars = cropBars) }
    override suspend fun saveBookmark(videoId: VideoId, positionMs: Long, note: String, id: Long) = withContext(Dispatchers.IO) { synchronized(monitor) {
        checkOpen()
        val video = videos.value.first { it.id == videoId }
        val existing = bookmarks.value.firstOrNull { it.id == id }
        require(id == 0L || existing?.videoId == videoId)
        require(id != 0L || bookmarks.value.count { it.videoId == videoId } < 256) { "每条视频最多保存 256 个书签" }
        val bookmark = Bookmark(if (id == 0L) nextBookmark++ else id, videoId, PlaybackRules.seek(positionMs, video.durationMs), note.take(200))
        val next = bookmarks.value.filterNot { it.id == bookmark.id } + bookmark
        persist(videoId, video, next); bookmarks.value = next
    } }
    override suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { synchronized(monitor) {
        checkOpen()
        bookmarks.value.firstOrNull { it.id == id }?.let { mark ->
            val next = bookmarks.value.filterNot { it.id == id }
            persist(mark.videoId, videos.value.first { it.id == mark.videoId }, next); bookmarks.value = next
        }; Unit
    } }
    /** Flush before destroying keys. Small encrypted metadata only; no video IO on the main thread. */
    fun finish(id: VideoId?, position: Long) = synchronized(monitor) {
        try {
            if (id != null && !closed) videos.value.firstOrNull { it.id == id }?.let {
                persist(id, it.copy(positionMs = PlaybackRules.seek(position, it.durationMs)), bookmarks.value)
            }
        } finally { closed = true; videos.value = emptyList(); bookmarks.value = emptyList(); damaged.clear() }
    }
    override suspend fun requirePlayable(id: VideoId): VideoItem = synchronized(monitor) {
        checkOpen()
        check(id !in damaged) { "私密播放记录损坏，文件已保留" }
        check(store.payload(id.value).isFile) { "加密视频不存在" }
        videos.value.firstOrNull { it.id == id } ?: error("保险箱中没有此视频")
    }
    override suspend fun get(id: VideoId) = synchronized(monitor) { checkOpen(); videos.value.firstOrNull { it.id == id } }
    override suspend fun allVideos() = synchronized(monitor) { checkOpen(); videos.value }
    override suspend fun refresh() { checkOpen() }
    override suspend fun importVideo(uri: String): VideoItem = error("请使用保险箱的加密导入入口")
    override suspend fun setHidden(ids: Set<VideoId>, hidden: Boolean): Unit = error("保险箱内无需普通隐藏")
    override suspend fun restoreRecords(rows: List<LibraryRecord>): Unit = error("保险箱不接收普通备份")
    override suspend fun clearHistory(): Unit = error("保险箱记录仅在本地加密管理")
}
