package cn.yingxia.player.core

import java.io.InputStream

/**
 * 本机资料库检查。实现后加入 `AppGraph.checks`，检查页会自动跑它。
 * A local library check. Register it on `AppGraph.checks`; the scan page runs it.
 * 只读这台手机上的文件，不要访问网络。
 */
interface LibraryCheck {
    val id: String
    val title: String
    suspend fun inspect(video: VideoItem, open: () -> InputStream?): CheckHit?
}

/** 检查结果。ContentKey 用来找内容完全相同的文件；Unreadable 只表示打不开。 */
sealed interface CheckHit {
    data class ContentKey(val key: String) : CheckHit
    data class Unreadable(val reason: String) : CheckHit
}

/** 把相同内容键收成组。只有两份及以上才算重复。 */
fun duplicateGroups(keys: List<Pair<VideoItem, String>>): List<List<VideoItem>> =
    keys.groupBy { it.second }.values.map { group -> group.map { it.first } }.filter { it.size > 1 }

/**
 * Finds videos that could possibly share identical bytes with another video in the library.
 * Videos with a unique positive byte size cannot be byte-for-byte duplicates and do not need
 * a full-file SHA-256 pass.
 */
fun duplicateCandidateIds(videos: List<VideoItem>): Set<VideoId> {
    val counts = HashMap<Long, Int>(videos.size)
    for (video in videos) {
        if (video.sizeBytes > 0L) {
            counts[video.sizeBytes] = (counts[video.sizeBytes] ?: 0) + 1
        }
    }
    return buildSet {
        for (video in videos) {
            if (video.sizeBytes <= 0L || (counts[video.sizeBytes] ?: 0) > 1) {
                add(video.id)
            }
        }
    }
}
