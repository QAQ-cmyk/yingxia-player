package cn.yingxia.player

import cn.yingxia.player.core.*
import cn.yingxia.player.data.LocalFileManager
import cn.yingxia.player.data.mediaPermissions
import org.junit.Assert.*
import org.junit.Test

class PlaybackRulesTest {
    @Test fun supportedSpeedsAndTemporaryBoostNeverSlowPlayback() {
        assertEquals(listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f), PlaybackRules.speeds)
        for (base in PlaybackRules.speeds) {
            assertTrue(PlaybackRules.temporarySpeed(base, 2f) >= base)
            assertTrue(PlaybackRules.temporarySpeed(base, 3f) >= base)
        }
        assertEquals(3f, PlaybackRules.temporarySpeed(3f, 2f))
        assertEquals(1f, PlaybackRules.speed(Float.NaN))
    }
    @Test fun seekAndResumeHandleEmptyCompletedAndOutOfRangeMedia() {
        assertEquals(0L, PlaybackRules.seek(-400, 20_000))
        assertEquals(20_000L, PlaybackRules.seek(Long.MAX_VALUE, 20_000))
        assertEquals(0L, PlaybackRules.seek(1000, -1))
        assertEquals(0L, PlaybackRules.resume(17_000, 20_000))
        assertEquals(10_000L, PlaybackRules.resume(10_000, 20_000))
    }
    @Test fun loopRejectsEqualReversedNegativeAndOutsideEndpoints() {
        assertFalse(PlaybackRules.validLoop(LoopRange(1000, 1000), 20_000))
        assertFalse(PlaybackRules.validLoop(LoopRange(2000, 1000), 20_000))
        assertFalse(PlaybackRules.validLoop(LoopRange(-1, 1000), 20_000))
        assertFalse(PlaybackRules.validLoop(LoopRange(1000, 21_000), 20_000))
        assertFalse(PlaybackRules.validLoop(LoopRange(1000, 1499), 20_000))
        assertTrue(PlaybackRules.validLoop(LoopRange(1000, 1500), 20_000))
    }
    @Test fun queuesStopOrWrapAndHandleRemovedCurrentVideo() {
        val a = VideoId("a"); val b = VideoId("b"); val queue = listOf(a, b)
        assertEquals(b, PlaybackRules.next(queue, a, RepeatMode.SEQUENCE))
        assertNull(PlaybackRules.next(queue, b, RepeatMode.SEQUENCE))
        assertEquals(a, PlaybackRules.next(queue, b, RepeatMode.ALL))
        assertEquals(b, PlaybackRules.next(queue, b, RepeatMode.ONE))
        assertNull(PlaybackRules.next(emptyList(), a, RepeatMode.SEQUENCE))
        assertNull(PlaybackRules.next(queue, VideoId("removed"), RepeatMode.ALL))
    }
    @Test fun portraitVerticalDragCannotBecomeASeekOrVolumeGesture() {
        assertEquals(GestureMode.PENDING, chooseDrag(5f, 100f, 10f, false, true))
        assertEquals(GestureMode.SEEK, chooseDrag(-100f, 5f, 10f, false, true))
        assertEquals(GestureMode.BRIGHTNESS, chooseDrag(5f, -100f, 10f, true, true))
        assertEquals(GestureMode.VOLUME, chooseDrag(5f, 100f, 10f, true, false))
        assertEquals(GestureMode.PENDING, chooseDrag(50f, 50f, 10f, true, false))
    }
    @Test fun sortCoversSizeTimeAndPinyinName() {
        fun clip(name: String, added: Long = 0, played: Long = 0, size: Long = 0) =
            VideoItem(VideoId(name), "content://v", name, "文件夹", "k", 1_000, size, addedAt = added, lastPlayedAt = played)
        val items = listOf(clip("北京", added = 2, played = 1, size = 20), clip("阿里", added = 9, played = 8, size = 5), clip("zebra", added = 3, played = 4, size = 50))
        assertEquals(listOf("阿里", "zebra", "北京"), SortOrder.NEWEST.apply(items).map { it.name })
        assertEquals(listOf("北京", "zebra", "阿里"), SortOrder.OLDEST.apply(items).map { it.name })
        assertEquals(listOf("阿里", "zebra", "北京"), SortOrder.RECENT_PLAY.apply(items).map { it.name })
        assertEquals(listOf("zebra", "北京", "阿里"), SortOrder.SIZE_DESC.apply(items).map { it.name })
        assertEquals(listOf("阿里", "北京", "zebra"), SortOrder.SIZE_ASC.apply(items).map { it.name })
        val ascending = SortOrder.NAME_ASC.apply(items).map { it.name }
        assertEquals(ascending.asReversed(), SortOrder.NAME_DESC.apply(items).map { it.name })
        assertTrue(ascending.indexOf("阿里") < ascending.indexOf("北京"))
    }
    @Test fun permissionRequestsMatchAndroid8_13_14_16() {
        assertArrayEquals(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), mediaPermissions(26))
        assertArrayEquals(arrayOf("android.permission.READ_MEDIA_VIDEO"), mediaPermissions(33))
        val selected = arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        assertArrayEquals(selected, mediaPermissions(34)); assertArrayEquals(selected, mediaPermissions(36))
    }
    @Test fun folderNamesCannotEscapeSelectedDirectory() {
        listOf("学习", "课程 01", "旅行.2026").forEach { assertTrue(LocalFileManager.validFolderName(it)) }
        listOf("", "  ", "..", "../Movies", "a\\b", "a:b", "a\nb", "x".repeat(61)).forEach {
            assertFalse(it, LocalFileManager.validFolderName(it))
        }
    }
}
