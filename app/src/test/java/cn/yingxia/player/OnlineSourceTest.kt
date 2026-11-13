package cn.yingxia.player

import cn.yingxia.player.online.BilibiliSource
import cn.yingxia.player.data.LocalFileManager
import org.junit.Assert.*
import org.junit.Test

class OnlineSourceTest {
    @Test fun accountEntryAndLoginReturnStayOnOfficialAccountPages() {
        assertEquals("https://account.bilibili.com/account/home", BilibiliSource.account)
        val query = java.net.URI(BilibiliSource.login).rawQuery
        assertEquals(BilibiliSource.account, java.net.URLDecoder.decode(query.substringAfter("gourl="), "UTF-8"))
        listOf(BilibiliSource.account, BilibiliSource.login,
            "https://passport.bilibili.com/h5-app/passport/login?gourl=abc").forEach {
            assertTrue(BilibiliSource.isAccountPage(it))
            assertFalse(BilibiliSource.permitsCosmetics(it))
            assertFalse(BilibiliSource.blocksResource(it))
        }
        listOf("https://m.bilibili.com/video/av123?from=/account/home",
            "https://account.bilibili.com.evil.test/account/home", "http://account.bilibili.com/account/home").forEach {
            assertFalse(BilibiliSource.isAccountPage(it))
        }
        assertTrue(BilibiliSource.isMobileLoginPage("https://passport.bilibili.com/h5-app/passport/login?gourl=abc"))
        listOf(BilibiliSource.login, BilibiliSource.account,
            "https://m.bilibili.com/h5-app/passport/login", "https://passport.bilibili.com/h5-app/passport/login/findPassword").forEach {
            assertFalse(BilibiliSource.isMobileLoginPage(it))
        }
    }
    @Test fun navigationRejectsSpoofsAndLocalAccess() {
        listOf("https://m.bilibili.com/", "https://passport.bilibili.com/login", "https://b23.tv/abc").forEach {
            assertTrue(it, BilibiliSource.permitsNavigation(it))
        }
        listOf("https://bilibili.com.evil.com/", "https://evilbilibili.com/", "https://bilibili.com@evil.com/",
            "https://evil@bilibili.com/", "https://bilibili.com:444/", "http://bilibili.com/", "file:///sdcard/test.mp4",
            "content://media/external/video/1", "javascript:alert(1)", "intent://bilibili.com").forEach {
            assertFalse(it, BilibiliSource.permitsNavigation(it))
        }
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD/", BilibiliSource.inputUrl(" BV1xx411c7mD "))
        assertNull(BilibiliSource.inputUrl("https://evil.com"))
    }
    @Test fun advertisingRulesKeepLoginAndVideoResources() {
        assertTrue(BilibiliSource.blocksResource("https://cm.bilibili.com/ad"))
        listOf("https://passport.bilibili.com/login", "https://api.bilibili.com/x/player/playurl",
            "https://upos-sz-mirrorcos.bilivideo.com/video.m4s", "https://i0.hdslb.com/image.jpg").forEach {
            assertFalse(it, BilibiliSource.blocksResource(it))
        }
        assertTrue(BilibiliSource.permitsCosmetics("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertFalse(BilibiliSource.permitsCosmetics(BilibiliSource.login))
    }
    @Test fun appVideoLinksBecomeInternalPagesAndPreservePartNumber() {
        assertEquals("https://m.bilibili.com/video/av117540017672330", BilibiliSource.appLink("bilibili://video/117540017672330?trackid=&keyword="))
        assertEquals("https://m.bilibili.com/video/BV1xx411c7mD?p=2", BilibiliSource.appLink("bilibili://video/BV1xx411c7mD?page=1"))
        assertEquals("https://m.bilibili.com/video/BV1xx411c7mD", BilibiliSource.appLink("bilibili://video?bvid=BV1xx411c7mD"))
        assertEquals("https://m.bilibili.com/space/123", BilibiliSource.appLink("bilibili://space/123"))
        assertEquals("https://m.bilibili.com/bangumi/play/ep123", BilibiliSource.appLink("bilibili://bangumi/play/ep123"))
        assertEquals("https://m.bilibili.com/search?keyword=%E5%AD%A6%E4%B9%A0", BilibiliSource.appLink("bilibili://search?keyword=%E5%AD%A6%E4%B9%A0"))
        assertEquals("https://m.bilibili.com/video/av123", BilibiliSource.appLink("intent://video/123#Intent;scheme=bilibili;package=tv.danmaku.bili;end"))
    }
    @Test fun fallbackUrlsAreValidatedAndMalformedIntentsStayBlocked() {
        assertEquals("https://m.bilibili.com/video/av123", BilibiliSource.appLink("intent://open#Intent;S.browser_fallback_url=https%3A%2F%2Fm.bilibili.com%2Fvideo%2Fav123;end"))
        listOf("file:///sdcard/movie.mp4", "javascript:alert(1)", "https://bilibili.com.evil.test/", "https://evil@bilibili.com/", "https://bilibili.com:8443/").forEach {
            val encoded = java.net.URLEncoder.encode(it, "UTF-8")
            assertNull(BilibiliSource.appLink("intent://open#Intent;S.browser_fallback_url=$encoded;end"))
            assertNull(BilibiliSource.appLink("bilibili://browser?url=$encoded"))
        }
        listOf("bilibili://video/../../123", "bilibili://video/123@evil", "bilibili://evil@video/123", "bilibili://video:123/456", "intent://video/123", "bilibili://unknown/1", "bilibili://browser?url=%ZZ").forEach { assertNull(it, BilibiliSource.appLink(it)) }
    }
    @Test fun automaticWakeupsAndRepeatedCurrentVideoNeverNavigate() {
        val current = "https://m.bilibili.com/video/av123?trackid=abc"
        assertNull(BilibiliSource.appNavigation("bilibili://video/456", current, false, true))
        assertNull(BilibiliSource.appNavigation("bilibili://video/456", current, true, false))
        assertNull(BilibiliSource.appNavigation("bilibili://video/123", current, true, true))
        assertEquals("https://m.bilibili.com/video/av456", BilibiliSource.appNavigation("bilibili://video/456", current, true, true))
        assertEquals("https://m.bilibili.com/video/av123?p=2", BilibiliSource.appNavigation("bilibili://video/123?page=1", current, true, true))
        assertEquals("https://m.bilibili.com/search?keyword=second", BilibiliSource.appNavigation("bilibili://search?keyword=second", "https://m.bilibili.com/search?keyword=first", true, true))
    }
    @Test fun folderNamesCannotEscapeSelectedDirectory() {
        listOf("学习", "课程 01", "旅行.2026").forEach { assertTrue(LocalFileManager.validFolderName(it)) }
        listOf("", "  ", "..", "../Movies", "a\\b", "a:b", "a\nb", "x".repeat(61)).forEach {
            assertFalse(it, LocalFileManager.validFolderName(it))
        }
    }
}
