package cn.yingxia.player.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.online.BilibiliSource
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

@Composable fun SourceSwitcher(online: Boolean, select: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("映匣", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(!online, { select(false) }, { Text("本地") }, leadingIcon = { Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(end = 8.dp).heightIn(min = 48.dp).testTag("source-local"))
            FilterChip(online, { select(true) }, { Text("B 站") }, leadingIcon = { Icon(Icons.Outlined.Public, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("source-online"))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun OnlinePage(vm: AppViewModel) {
    var providerCheck by remember { mutableIntStateOf(0) }
    val hasProvider = remember(providerCheck) { runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false) }
    if (!hasProvider) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("系统缺少可用的网页组件", style = MaterialTheme.typography.titleLarge)
            Text("请在系统中启用或更新 Android System WebView / Chrome，再重新检测。本地视频仍可正常使用。", Modifier.padding(vertical = 16.dp))
            Button(onClick = { providerCheck++ }) { Text("重新检测") }
        }
        return
    }
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val latestSettings by rememberUpdatedState(settings)
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var enterLink by remember { mutableStateOf(false) }
    var clearLogin by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var lastUrl by rememberSaveable { mutableStateOf(BilibiliSource.home) }
    var canGoBack by remember { mutableStateOf(false) }
    var fullContainer by remember { mutableStateOf<FrameLayout?>(null) }
    var fullCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var previousOrientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    val cleanScript = remember { context.assets.open("bilibili-clean.js").bufferedReader().use { it.readText() } }
    val playerScript = remember { context.assets.open("bilibili-player.js").bufferedReader().use { it.readText() } }
    val loginLayoutScript = remember { context.assets.open("bilibili-login.js").bufferedReader().use { it.readText() } }
    val hideFullscreen = {
        fullContainer?.let { (it.parent as? ViewGroup)?.removeView(it); it.removeAllViews() }
        fullContainer = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        activity.requestedOrientation = previousOrientation
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }
    val web = remember {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            WebView.setWebContentsDebuggingEnabled(true)
        WebView(context).apply {
            // WebView treats WRAP_CONTENT height as a zero CSS viewport (vmin/vh collapse).
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            this.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = true; loadWithOverviewMode = true
                builtInZoomControls = true; displayZoomControls = false
                safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            // No JavascriptInterface, native file chooser, downloads or SSL bypass.
            webViewClient = object : WebViewClient() {
                private fun applyShell(view: WebView, url: String) {
                    val adapters = when {
                        BilibiliSource.isMobileLoginPage(url) -> loginLayoutScript
                        BilibiliSource.permitsCosmetics(url) -> playerScript + "\n" + if (latestSettings.blockWebAds) cleanScript else ""
                        else -> return
                    }
                        // A commit can arrive before the new document has a root/head.
                        // Wait for its DOM, but not for images or other remote resources.
                        view.evaluateJavascript("""
                            (function(){
                              function apply(){ $adapters }
                              if(document.readyState==='loading'||!document.documentElement)
                                document.addEventListener('DOMContentLoaded',apply,{once:true});
                              else apply();
                            })();
                        """.trimIndent(), null)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (!request.isForMainFrame) return request.url.scheme !in setOf("https", "about")
                    if (BilibiliSource.permitsNavigation(url)) return false
                    // Auto-launch probes are not user errors. Do not queue a snackbar for each one.
                    BilibiliSource.appNavigation(url, view.url, request.hasGesture(), request.isForMainFrame)?.let { target ->
                        view.post { view.loadUrl(target) }
                    }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (latestSettings.blockWebAds && !request.isForMainFrame && BilibiliSource.blocksResource(request.url.toString()))
                        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    error = null; progress = 0
                    if (BilibiliSource.permitsNavigation(url)) { lastUrl = url; canGoBack = view.canGoBack() }
                    else { view.stopLoading(); error = "已停止访问非 B 站页面" }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    progress = 100; canGoBack = view.canGoBack(); CookieManager.getInstance().flush()
                    applyShell(view, url)
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    // Slow images/CDN resources must not delay the shell until page-finished.
                    // Both adapters are idempotent and observe subsequently inserted content.
                    applyShell(view, url)
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                    if (BilibiliSource.permitsNavigation(url)) lastUrl = url
                    view.post { canGoBack = view.canGoBack() }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) { error = "网页加载失败，请检查网络或更新系统 WebView 后重试"; progress = 100 }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) error = "B 站暂时无法打开此页面（${response.statusCode}），可稍后重试"
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                    handler.cancel(); error = "网站安全连接验证失败，已停止加载"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) { progress = value }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullContainer != null) { callback.onCustomViewHidden(); return }
                    previousOrientation = activity.requestedOrientation
                    val frame = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(view, FrameLayout.LayoutParams(-1, -1)) }
                    (activity.window.decorView as ViewGroup).addView(frame, ViewGroup.LayoutParams(-1, -1))
                    fullContainer = frame; fullCallback = callback
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
                override fun onHideCustomView() { hideFullscreen() }
            }
            // App promotion may invoke APK downloads without a deliberate download action.
            setDownloadListener { _, _, _, _, _ -> }
        }
    }
    val defaultAgent = remember { WebSettings.getDefaultUserAgent(context).replace("; wv", "").replace("Version/4.0 ", "") }
    LaunchedEffect(settings.webDesktop) {
        web.settings.userAgentString = if (settings.webDesktop) defaultAgent.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "") else defaultAgent
        val next = if (settings.webDesktop) lastUrl.replace("https://m.bilibili.com/", "https://www.bilibili.com/")
            else lastUrl.replace("https://www.bilibili.com/", "https://m.bilibili.com/")
        web.loadUrl(next)
    }
    var appliedFilter by remember { mutableStateOf(settings.blockWebAds) }
    LaunchedEffect(settings.blockWebAds) {
        if (appliedFilter != settings.blockWebAds) { appliedFilter = settings.blockWebAds; web.reload() }
    }
    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){v.pause()})", null)
                web.onPause(); CookieManager.getInstance().flush()
            } else if (event == Lifecycle.Event.ON_START) web.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); hideFullscreen()
            web.stopLoading(); web.onPause(); CookieManager.getInstance().flush()
            (web.parent as? ViewGroup)?.removeView(web); web.removeAllViews(); web.destroy()
        }
    }
    val homeUrl = if (settings.webDesktop) "https://www.bilibili.com/" else BilibiliSource.home
    val awayFromHome = lastUrl.substringBefore('?').substringBefore('#').trimEnd('/') != homeUrl.trimEnd('/')
    val navigateBack = { if (web.canGoBack()) web.goBack() else web.loadUrl(homeUrl) }
    BackHandler(fullContainer != null || canGoBack || awayFromHome) { if (fullContainer != null) hideFullscreen() else navigateBack() }
    val isAccount = BilibiliSource.isAccountPage(lastUrl)
    val isSearch = lastUrl.contains("/search")
    val isVideo = lastUrl.contains("/video/") || lastUrl.contains("/bangumi/play/")
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoBack || awayFromHome) IconButton(onClick = navigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一页") }
            Text(if (isAccount) "B 站账号" else if (isSearch) "搜索" else if (isVideo) "视频" else "推荐",
                Modifier.weight(1f).padding(start = if (canGoBack || awayFromHome) 0.dp else 16.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { web.loadUrl(BilibiliSource.search) }) { Icon(Icons.Outlined.Search, "搜索B站视频") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "在线设置") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("刷新内容") }, onClick = { menu = false; web.reload() })
                    DropdownMenuItem(text = { Text("打开链接或 BV 号") }, onClick = { menu = false; enterLink = true })
                    DropdownMenuItem(text = { Text(if (settings.webDesktop) "切换手机版网页" else "切换电脑版网页") }, onClick = { menu = false; scope.launch { vm.graph.settings.setWebDesktop(!settings.webDesktop) } })
                    DropdownMenuItem(text = { Text(if (settings.blockWebAds) "广告过滤：开（点击关闭）" else "广告过滤：关（点击开启）") }, onClick = { menu = false; scope.launch { vm.graph.settings.setBlockWebAds(!settings.blockWebAds) } })
                    DropdownMenuItem(text = { Text("清除网页登录状态") }, onClick = { menu = false; clearLogin = true })
                }
            }
        }
        if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.padding(16.dp)) { Text(error!!); TextButton(onClick = { error = null; web.reload() }) { Text("重新加载") } }
        }
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("bilibili-webview"))
        NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
            NavigationBarItem(selected = !isAccount && !isSearch, onClick = { web.loadUrl(if (settings.webDesktop) "https://www.bilibili.com/" else BilibiliSource.home) }, icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("推荐") }, modifier = Modifier.testTag("online-home"))
            NavigationBarItem(selected = isSearch, onClick = { web.loadUrl(BilibiliSource.search) }, icon = { Icon(Icons.Outlined.Search, null) }, label = { Text("搜索") }, modifier = Modifier.testTag("online-search"))
            // Login resolves an existing session directly to account via the explicit gourl.
            // Starting here avoids loading the whole account site before showing a login form.
            NavigationBarItem(selected = isAccount, onClick = { web.stopLoading(); web.loadUrl(BilibiliSource.login) }, icon = { Icon(Icons.Outlined.AccountCircle, null) }, label = { Text("账号") }, modifier = Modifier.testTag("online-account"))
        }
    }
    if (enterLink) AlertDialog(onDismissRequest = { enterLink = false }, title = { Text("打开 B 站视频") },
        text = { OutlinedTextField(address, { address = it }, label = { Text("粘贴 https 链接或 BV 号") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { BilibiliSource.inputUrl(address)?.let { web.loadUrl(if (settings.webDesktop) it else it.replace("https://www.bilibili.com/", "https://m.bilibili.com/")); enterLink = false } ?: vm.message("请输入有效的 B 站 https 链接或 BV 号") }) { Text("打开") } },
        dismissButton = { TextButton(onClick = { enterLink = false }) { Text("取消") } })
    if (clearLogin) AlertDialog(onDismissRequest = { clearLogin = false }, title = { Text("退出网页登录？") },
        text = { Text("清除 B 站 Cookie 和网页本地数据。映匣中的本地视频、收藏和书签不受影响。") },
        confirmButton = { TextButton(onClick = {
            clearLogin = false; web.stopLoading()
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData(); web.clearCache(true); web.clearHistory(); web.loadUrl(BilibiliSource.home) }
        }) { Text("清除并退出") } }, dismissButton = { TextButton(onClick = { clearLogin = false }) { Text("取消") } })
}
