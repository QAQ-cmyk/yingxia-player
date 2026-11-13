package cn.yingxia.player.data

import androidx.room.*
import cn.yingxia.player.core.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val id: String, val uri: String, val name: String, val folder: String,
    val folderKey: String, val durationMs: Long, val sizeBytes: Long, val width: Int,
    val height: Int, val addedAt: Long, val source: String,
    val accessible: Boolean = true, val hidden: Boolean = false, val favorite: Boolean = false,
    val positionMs: Long = 0, val lastPlayedAt: Long = 0,
    val subtitleUri: String? = null, val subtitleName: String? = null,
)
@Entity(tableName = "bookmarks", indices = [Index("videoId")])
data class BookmarkEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String, val positionMs: Long, val note: String)

@Dao interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY addedAt DESC") fun observeVideos(): Flow<List<VideoEntity>>
    @Query("SELECT * FROM videos") suspend fun all(): List<VideoEntity>
    @Query("SELECT * FROM videos WHERE id=:id") suspend fun get(id: String): VideoEntity?
    @Query("SELECT * FROM videos WHERE uri=:uri LIMIT 1") suspend fun byUri(uri: String): VideoEntity?
    @Upsert suspend fun upsert(videos: List<VideoEntity>)
    @Query("UPDATE videos SET accessible=0 WHERE source=:source") suspend fun markSourceUnavailable(source: String)
    @Query("UPDATE videos SET hidden=:hidden WHERE id IN (:ids)") suspend fun hidden(ids: List<String>, hidden: Boolean)
    @Query("UPDATE videos SET favorite=:favorite WHERE id IN (:ids)") suspend fun favorite(ids: List<String>, favorite: Boolean)
    @Query("UPDATE videos SET positionMs=:position, lastPlayedAt=:time WHERE id=:id") suspend fun progress(id: String, position: Long, time: Long)
    @Query("UPDATE videos SET subtitleUri=:uri, subtitleName=:name WHERE id=:id") suspend fun subtitle(id: String, uri: String, name: String)
    @Query("SELECT * FROM bookmarks ORDER BY id DESC") fun observeBookmarks(): Flow<List<BookmarkEntity>>
    @Upsert suspend fun bookmark(bookmark: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun deleteBookmark(id: Long)
    @Query("UPDATE bookmarks SET videoId=:destination WHERE videoId=:source") suspend fun relocateBookmarks(source: String, destination: String)
    @Query("UPDATE videos SET accessible=0 WHERE id=:id") suspend fun unavailable(id: String)
    @Query("DELETE FROM videos WHERE id=:id") suspend fun removeAlias(id: String)
    @Query("UPDATE videos SET positionMs=0, lastPlayedAt=0") suspend fun clearHistory()
}
@Database(entities = [VideoEntity::class, BookmarkEntity::class], version = 1, exportSchema = true)
abstract class VideoDatabase : RoomDatabase() { abstract fun videos(): VideoDao }

fun VideoEntity.domain() = VideoItem(VideoId(id), uri, name, folder, folderKey, durationMs,
    sizeBytes, width, height, addedAt, source, accessible, hidden, favorite, positionMs,
    lastPlayedAt, subtitleUri, subtitleName)
fun VideoItem.entity() = VideoEntity(id.value, uri, name, folder, folderKey, durationMs,
    sizeBytes, width, height, addedAt, source, accessible, hidden, favorite, positionMs,
    lastPlayedAt, subtitleUri, subtitleName)
