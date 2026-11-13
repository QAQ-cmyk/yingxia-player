package cn.yingxia.player

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.yingxia.player.online.BilibiliSource
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class OnlineUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
    private fun js(browser: WebView, script: String): String {
        var result = ""
        val latch = CountDownLatch(1)
        ui.runOnIdle { browser.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        return result
    }
    private fun awaitJs(browser: WebView, script: String, expected: String) {
        val deadline = System.currentTimeMillis() + 15000
        var result: String
        do {
            result = js(browser, script)
            if (result == expected) return
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        assertEquals(expected, result)
    }
    @Test fun accountFromVideoKeepsLoginAndExistingSessionInAccountCenter() {
        Assume.assumeTrue(WebView.getCurrentWebViewPackage() != null)
        ui.onNodeWithTag("source-online").performClick()
        lateinit var browser: WebView
        val signedIn = AtomicBoolean(false)
        val accountUrl = "https://account.bilibili.com/account/home"
        val videoUrl = "https://m.bilibili.com/video/av123"
        ui.runOnIdle {
            browser = requireNotNull(find(ui.activity.window.decorView))
            browser.stopLoading()
            val original = browser.webViewClient
            // Only replace network responses. All app navigation callbacks remain active.
            // The login fixture reproduces the official gourl/referrer/default redirect rule.
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    original.shouldOverrideUrlLoading(view, request)
                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) =
                    original.onPageStarted(view, url, icon)
                override fun onPageFinished(view: WebView, url: String) = original.onPageFinished(view, url)
                override fun onPageCommitVisible(view: WebView, url: String) = original.onPageCommitVisible(view, url)
                override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) =
                    original.doUpdateVisitedHistory(view, url, reload)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val body = when {
                        uri.path == "/fixture-login-complete" -> {
                            signedIn.set(true)
                            "<script>location.replace(new URL(location.href).searchParams.get('gourl'))</script>"
                        }
                        uri.host == "passport.bilibili.com" && uri.path == "/login" ->
                            "<script>location.replace('/h5-app/passport/login'+location.search)</script>"
                        uri.host == "passport.bilibili.com" -> """
                            <script>
                            const target = new URL(location.href).searchParams.get('gourl') || document.referrer || 'https://m.bilibili.com/';
                            if (${signedIn.get()}) location.replace(target);
                            </script>
                            <style>.explain-tips{position:absolute;bottom:1.6rem}</style>
                            <div class='login-wrap'><div class='login-content'>
                            <button id='login' onclick="location.href='/fixture-login-complete?gourl='+encodeURIComponent(target)">模拟完成登录</button>
                            <button id='method'>账号密码登录</button></div>
                            <div class='explain-tips' id='legal'>协议说明</div></div>
                        """.trimIndent()
                        uri.host == "account.bilibili.com" -> if (signedIn.get()) "<p id='account'>账号中心</p>" else
                            "<script>location.replace('https://passport.bilibili.com/login?gourl='+encodeURIComponent(location.href))</script>"
                        uri.path?.startsWith("/video/") == true -> "<p id='video'>视频页</p>"
                        else -> "<p id='home'>推荐页</p>"
                    }
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream("<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body>$body</body></html>".toByteArray()))
                }
            }
            browser.loadUrl(videoUrl)
        }
        awaitJs(browser, "!!document.getElementById('video')", "true")
        ui.onNodeWithTag("online-account").performClick()
        awaitJs(browser, "!!document.getElementById('login')", "true")
        // Missing gourl used to send a user straight back to video/home before seeing a form.
        assertEquals("\"$accountUrl\"", js(browser, "new URL(location.href).searchParams.get('gourl')"))
        ui.onNodeWithTag("online-account").assertIsSelected()
        assertEquals("false", js(browser, "!!document.querySelector('.yx-inline-tools,#yingxia-clean-style')"))
        awaitJs(browser, "getComputedStyle(document.getElementById('legal')).position", "\"static\"")
        assertEquals("true", js(browser, "document.getElementById('legal').getBoundingClientRect().top >= document.getElementById('method').getBoundingClientRect().bottom"))
        assertEquals("1", js(browser, "document.querySelectorAll('#yingxia-login-layout').length"))
        js(browser, "document.getElementById('login').click()")
        awaitJs(browser, "!!document.getElementById('account')", "true")
        ui.onNodeWithTag("online-account").assertIsSelected()
        // A later visit with an existing session must go to the account, without a login form.
        ui.runOnIdle { browser.loadUrl(videoUrl) }
        awaitJs(browser, "!!document.getElementById('video')", "true")
        ui.onNodeWithTag("online-account").performClick()
        awaitJs(browser, "!!document.getElementById('account')", "true")
        assertEquals("\"$accountUrl\"", js(browser, "location.href"))
        ui.onNodeWithTag("online-account").assertIsSelected()
        // Tab switching must still leave the account when deliberately requested.
        ui.onNodeWithTag("online-home").performClick()
        awaitJs(browser, "!!document.getElementById('home')", "true")
        ui.onNodeWithTag("online-home").assertIsSelected()
        ui.onNodeWithTag("source-local").performClick()
    }
    @Test fun shellKeepsSingleInlineVideoRoutesCardsAndSilencesAutomaticAppWakeups() {
        val hasProvider = WebView.getCurrentWebViewPackage() != null
        ui.onNodeWithTag("source-online").performClick()
        if (!hasProvider) {
            ui.onNodeWithText("系统缺少可用的网页组件").assertIsDisplayed()
            ui.onNodeWithTag("source-local").performClick()
            return
        }
        var web: WebView? = null
        ui.runOnIdle { web = find(ui.activity.window.decorView) }
        val browser = requireNotNull(web)
        ui.runOnIdle {
            assertFalse(browser.settings.allowFileAccess)
            assertFalse(browser.settings.allowContentAccess)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, browser.settings.mixedContentMode)
            assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(browser))
            browser.stopLoading()
            browser.loadDataWithBaseURL("https://m.bilibili.com/yingxia-test", """
                <html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body>
                <div class='m-navbar'>重复网页导航</div><div id='ad' class='ad-banner'>广告</div><div id='keep'>普通视频</div><p id='unit' style='font-size:3.2vmin'>标题</p>
                <div class='bili-video-card' id='card'><span class='bili-video-card__info--ad'>广告标识</span></div>
                <div class='video-share' style='display:none'><div class='m-video-player'><div class='player-container'><div class='gsl-wrap'><div class='gsl-area'>
                <video class='gsl-video' id='original' preload='none' src='https://www.bilibili.com/yingxia-fixture.mp4'></video>
                <div class='gsl-poster'>打开 App</div></div></div></div></div></div>
                <m-open-app id='recommended' schema='bilibili://video/123' universallink='https://m.bilibili.com/video/av123'><span>推荐视频</span></m-open-app>
                <div id='search-card' class='v-card-single' data-aid='456'><h2>搜索视频</h2></div>
                <script>window.appLaunches=0;document.getElementById('recommended').addEventListener('click',function(){window.appLaunches++});</script>
                </body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        awaitJs(browser, "[getComputedStyle(document.getElementById('ad')).display,getComputedStyle(document.getElementById('keep')).display,getComputedStyle(document.getElementById('card')).display,parseFloat(getComputedStyle(document.getElementById('unit')).fontSize)>0].join(',')", "\"none,block,none,true\"")
        awaitJs(browser, "[document.querySelectorAll('video').length,document.getElementById('original').controls,document.getElementById('original').getBoundingClientRect().height>0,document.querySelectorAll('.yx-inline-tools').length,!!document.getElementById('yingxia-web-play'),getComputedStyle(document.querySelector('.m-navbar')).display].join(',')", "\"1,true,true,1,false,none\"")
        // App wake-ups from redirects/iframes must neither launch externally nor fill the snackbar queue.
        ui.runOnIdle {
            val client = browser.webViewClient
            repeat(25) {
                val request = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse("bilibili://video/123")
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                assertTrue(client.shouldOverrideUrlLoading(browser, request))
            }
        }
        ui.onNodeWithText("此链接不能在应用内打开；请使用 B 站网页链接").assertDoesNotExist()
        var target: String? = null
        ui.runOnIdle {
            val original = browser.webViewClient
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (BilibiliSource.permitsNavigation(request.url.toString())) { target = request.url.toString(); return true }
                    return original.shouldOverrideUrlLoading(view, request)
                }
            }
        }
        js(browser, "document.querySelector('#recommended span').click()")
        ui.waitUntil(5000) { target != null }
        assertEquals("https://m.bilibili.com/video/av123", target)
        assertEquals("0", js(browser, "window.appLaunches"))
        target = null
        js(browser, "document.querySelector('#search-card h2').click()")
        ui.waitUntil(5000) { target != null }
        assertEquals("https://m.bilibili.com/video/av456", target)
        // Re-injection and DOM replacement are common on SPA navigation; no duplicate controls.
        ui.runOnIdle { browser.evaluateJavascript(ui.activity.assets.open("bilibili-player.js").bufferedReader().use { it.readText() }, null) }
        assertEquals("1", js(browser, "document.querySelectorAll('.yx-inline-tools').length"))
        js(browser, "document.getElementById('original').remove()")
        awaitJs(browser, "document.querySelectorAll('.yx-inline-tools').length", "0")
        ui.onNodeWithTag("online-home").assertIsDisplayed()
        ui.onNodeWithTag("online-search").assertIsDisplayed()
        ui.onNodeWithTag("online-account").assertIsDisplayed()
        ui.onNodeWithTag("source-local").performClick()
    }
}
