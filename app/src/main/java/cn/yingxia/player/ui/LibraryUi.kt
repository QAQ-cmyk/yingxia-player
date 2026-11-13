@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.yingxia.player.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.BuildConfig
import cn.yingxia.player.core.*
import cn.yingxia.player.data.mediaPermissions
import kotlinx.coroutines.launch

class LocalDocumentContract(private val mime: String) : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable fun YingXiaApp(vm: AppViewModel) {
    val activity = LocalActivity.current ?: return
    val settings by vm.settings.collectAsStateWithLifecycle()
    val player by vm.graph.player.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { notice ->
            val result = snack.showSnackbar(
                message = notice.text,
                actionLabel = notice.actionLabel,
                duration = if (notice.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) notice.onAction?.invoke()
        }
    }
    YingXiaTheme(settings.theme) {
        val dark = settings.theme == ThemeMode.DARK || settings.theme == ThemeMode.SYSTEM && isSystemInDarkTheme()
        SideEffect {
            val window = activity.window
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark && player.video == null
                isAppearanceLightNavigationBars = !dark && player.video == null
            }
        }
        var libraryNav by remember { mutableStateOf(true) }
        val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val showLibrary = player.video == null
        val snackbarLift = when {
            !showLibrary && landscape -> 96.dp
            !showLibrary -> 24.dp
            libraryNav -> 80.dp
            else -> 16.dp
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(showLibrary, Modifier.fillMaxSize(), transitionSpec = {
                    fadeIn(motionTween(200)) togetherWith fadeOut(motionTween(150))
                }, label = "library-player") { library ->
                    if (library) LibraryScaffold(vm) { libraryNav = it } else PlayerPage(vm, player, settings)
                }
                FileOperationHost(vm)
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = snackbarLift))
            }
        }
    }
}

@Composable private fun LibraryScaffold(vm: AppViewModel, onNavigationVisible: (Boolean) -> Unit) {
    val videos by vm.videos.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val scanning by vm.graph.repository.scanning.collectAsStateWithLifecycle()
    val access by vm.graph.repository.access.collectAsStateWithLifecycle()
    val scanError by vm.graph.repository.scanError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(SortOrder.NEWEST) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<VideoId>()) }
    var searchFocused by remember { mutableStateOf(false) }
    var fileItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    var moving by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VideoItem?>(null) }
    val managedFolders by vm.folders.collectAsStateWithLifecycle()
    val managedKeys by vm.folderKeys.collectAsStateWithLifecycle()
    if (newFolder) NewFolderDialog(vm, { newFolder = false })
    renaming?.let { target -> RenameVideoDialog(target.displayTitle(), { renaming = null }) { name -> vm.rename(target.id, name); renaming = null } }
    fileItems?.let { targets -> FileActionDialogs(vm, targets, moving) { fileItems = null; selected = emptySet() } }
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val openFile = rememberLauncherForActivityResult(LocalDocumentContract("video/*")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            vm.importVideo(uri.toString())
        }
    }
    val requestAccess = { permissions.launch(mediaPermissions()) }
    val importFile = { openFile.launch(Unit) }
    val showSearch = page != "hidden" && page != "settings" && page != "bookmarks" && !(tab == 3 && page.isEmpty()) && !(tab == 1 && folder == null && page.isEmpty())
    SideEffect { onNavigationVisible(!searchFocused || !showSearch) }
    DisposableEffect(Unit) { onDispose { onNavigationVisible(true) } }
    BackHandler(selected.isNotEmpty() || page.isNotEmpty() || folder != null) {
        when { selected.isNotEmpty() -> selected = emptySet(); folder != null -> folder = null; else -> page = "" }
    }
    val current = when {
        page == "hidden" -> hidden
        page == "history" -> videos.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }
        tab == 2 -> videos.filter { it.favorite }
        folder != null -> videos.filter { it.folderKey == folder || it.folderKey in managedKeys[folder].orEmpty() || belongsToFolder(it, folder!!) }
        else -> videos
    }
    val visible = remember(current, query, order, filter, page, tab) {
        val filtered = current.filter { query.isBlank() || it.displayTitle().contains(query, true) || it.name.contains(query, true) || it.folder.contains(query, true) }
            .filter { tab != 0 || page.isNotEmpty() || filter != 2 || it.positionMs > 0 && it.durationMs - it.positionMs > 5000 }
        when {
            page == "history" -> filtered.sortedByDescending { it.lastPlayedAt }
            order == SortOrder.NAME -> filtered.sortedBy { it.displayTitle().lowercase() }
            order == SortOrder.DURATION -> filtered.sortedByDescending { it.durationMs }
            order == SortOrder.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            else -> filtered.sortedByDescending { it.addedAt }
        }
    }
    val titles = listOf("首页", "文件夹", "收藏", "我的")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Folder, Icons.Outlined.FavoriteBorder, Icons.Outlined.PersonOutline)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!searchFocused || !showSearch) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                titles.forEachIndexed { i, title ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; page = ""; folder = null; query = ""; selected = emptySet(); filter = 0 },
                        icon = { Icon(icons[i], title) }, label = { Text(title) },
                        modifier = Modifier.testTag("nav-$i"),
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            val subTitle = when (page) { "hidden" -> "隐藏管理"; "history" -> "播放历史"; "bookmarks" -> "时间书签"; "settings" -> "播放与外观"; "transfer" -> "同一 Wi-Fi 互传"; "cloud" -> "云端传输"; else -> if (folder != null) managedFolders.firstOrNull { it.uri == folder }?.name ?: current.firstOrNull()?.folder ?: "文件夹" else "" }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.isNotEmpty() || folder != null) IconButton(onClick = { page = ""; folder = null; selected = emptySet(); query = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                else Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(if (selected.isNotEmpty()) "已选择 ${selected.size} 项" else subTitle.ifEmpty { if (tab == 0) "本地视频" else titles[tab] },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected.isEmpty() && tab == 0 && page.isEmpty() && folder == null) Text("只保存在这台手机，不会上传",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                else IconButton(onClick = importFile) { Icon(Icons.Outlined.AddCircleOutline, "打开本地视频") }
            }
            if (selected.isNotEmpty()) {
                val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selected }
                val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                val dangerColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.primaryContainer), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selected = if (allVisibleSelected) selected - visible.map { it.id }.toSet() else selected + visible.map { it.id }
                    }, modifier = Modifier.heightIn(min = 48.dp), colors = actionColors) { Text(if (allVisibleSelected) "取消全选" else "全选") }
                    if (page == "hidden") TextButton(onClick = { vm.hide(selected, false); selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp), colors = actionColors) { Text("恢复显示") }
                    else {
                        TextButton(onClick = { vm.favorite(selected, true); selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp), colors = actionColors) { Text("收藏") }
                        TextButton(onClick = { vm.favorite(selected, false); selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp), colors = actionColors) { Text("取消收藏") }
                        TextButton(onClick = {
                            val chosen = visible.filter { it.id in selected }
                            val targets = chosen.filter { it.accessible }
                            selected = emptySet()
                            when {
                                targets.isEmpty() -> vm.message("这些视频需要重新授权后才能播放")
                                targets.size < chosen.size -> { vm.message("已跳过需要重新授权的视频"); vm.command(PlayerCommand.Enqueue(targets.map { it.id })) }
                                else -> vm.command(PlayerCommand.Enqueue(targets.map { it.id }))
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp), colors = actionColors) { Text("播放所选") }
                        TextButton(onClick = { vm.hide(selected, true); selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp), colors = actionColors) { Text("隐藏") }
                        TextButton(onClick = { moving = true; fileItems = visible.filter { it.id in selected } }, modifier = Modifier.heightIn(min = 48.dp), colors = actionColors) { Text("移动") }
                        TextButton(onClick = { moving = false; fileItems = visible.filter { it.id in selected } }, modifier = Modifier.heightIn(min = 48.dp), colors = dangerColors) { Text("删除") }
                    }
                }
            }
            val screen = when {
                page.isNotEmpty() -> page
                folder != null -> "folder:$folder"
                else -> "tab$tab"
            }
            AnimatedContent(screen, Modifier.weight(1f), transitionSpec = {
                fadeIn(motionTween(220)) togetherWith fadeOut(motionTween(140))
            }, label = "library-screen") { current ->
            when (current) {
                "settings" -> SettingsPage(vm)
                "transfer" -> TransferPage(vm)
                "cloud" -> CloudPage(vm)
                "bookmarks" -> BookmarksPage(vm, videos, bookmarks)
                "tab3" -> ProfilePage(videos, bookmarks.size, { page = it }, importFile)
                "tab1" -> FolderPage(videos, managedFolders, managedKeys, { newFolder = true }) { folder = it; query = "" }
                else -> {
                    val openedFolder = current.removePrefix("folder:").takeIf { current.startsWith("folder:") }
                    val listKind = when (current) {
                        "hidden" -> "hidden"
                        "history" -> "history"
                        "tab2" -> "favorites"
                        else -> if (openedFolder != null) "folder" else "videos"
                    }
                    Column(Modifier.fillMaxSize()) {
                    if (listKind != "hidden") {
                        SearchField(query, { query = it }, { searchFocused = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    } else Text("只是不在映匣里显示，手机里的文件还在。点选视频后可以恢复。", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (current == "tab0") {
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            listOf(0 to "全部视频", 2 to "未看完").forEach { (index, label) ->
                                FilterChip(selected = if (index == 0) filter != 2 else filter == 2, onClick = { filter = index }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp).heightIn(min = 48.dp))
                            }
                        }
                    }
                    VideoGrid(vm, visible, videos, access, scanning, scanError, order, { order = it }, query,
                        selected, { selected = it }, listKind, current == "tab0" && query.isBlank() && filter != 2,
                        requestAccess, importFile, { video, move -> moving = move; fileItems = listOf(video) }, { renaming = it })
                    }
                }
            }
            }
        }
    }
}

@Composable private fun SearchField(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    TextField(query, onQuery, modifier.fillMaxWidth().testTag("library-search").onFocusChanged { onFocus(it.isFocused) }, singleLine = true,
        placeholder = { Text("搜索视频或文件夹", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(22.dp)) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "清除搜索") } }) else null,
        shape = RoundedCornerShape(14.dp), colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
}

@Composable private fun VideoGrid(vm: AppViewModel, items: List<VideoItem>, all: List<VideoItem>, access: LibraryAccess,
    scanning: Boolean, error: String?, order: SortOrder, onOrder: (SortOrder) -> Unit, query: String,
    selected: Set<VideoId>, onSelect: (Set<VideoId>) -> Unit, listKind: String, showContinue: Boolean,
    requestAccess: () -> Unit, importFile: () -> Unit, onFileAction: (VideoItem, Boolean) -> Unit, onRename: (VideoItem) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    val hidden = listKind == "hidden"
    val history = listKind == "history"
    val last = all.filter { it.positionMs > 3000 && it.durationMs - it.positionMs > 5000 }.maxByOrNull { it.lastPlayedAt }
    LazyVerticalGrid(GridCells.Adaptive(155.dp), Modifier.fillMaxSize().testTag("video-grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (!hidden && access != LibraryAccess.FULL) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Text(if (access == LibraryAccess.PARTIAL) "正在显示你授权的视频" else "授权相册，发现手机里的视频", modifier = Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = requestAccess) { Text(if (access == LibraryAccess.PARTIAL) "管理" else "授权") }
                }
            }
        }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
                }
            }
        }
        if (showContinue && last != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(onClick = { vm.open(last, all) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp))) {
                        Thumbnail(last)
                        WatchProgress(last, Modifier.align(Alignment.BottomCenter))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("接着上次，继续看", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                        Text(last.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                        Text("已看到 ${durationText(last.positionMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Outlined.PlayCircleOutline, "继续播放", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val section = when (listKind) { "hidden" -> "已隐藏"; "history" -> "最近观看"; "favorites" -> "我的收藏"; "folder" -> "文件夹中的视频"; else -> "我的视频" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(section, style = MaterialTheme.typography.titleMedium)
                Text("  ${items.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                IconButton(onClick = vm::refresh, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "刷新视频", modifier = Modifier.size(20.dp)) }
                if (history) Text("按观看时间", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                else Box {
                    TextButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.AutoMirrored.Outlined.Sort, "排序", modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(order.title, fontSize = 12.sp) }
                    DropdownMenu(sortMenu, { sortMenu = false }) { SortOrder.entries.forEach { o -> DropdownMenuItem(text = { Text(o.title) }, onClick = { onOrder(o); sortMenu = false }) } }
                }
            }
        }
        if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            val (emptyTitle, emptyMessage, allowImport) = when {
                scanning -> Triple("正在整理你的视频", "正在读取相册，稍等一下。", false)
                query.isNotEmpty() -> Triple("没有找到相关视频", "换个关键词，或清除搜索后再看。", false)
                hidden -> Triple("这里还没有隐藏视频", "在视频卡片的「更多」里选择隐藏。隐藏不会删除手机里的文件。", false)
                history -> Triple("还没有播放记录", "看过的视频会按时间留在这里，方便接着看。", false)
                listKind == "favorites" -> Triple("还没有收藏", "播放时点「收藏」，或在卡片的「更多」里收藏。", false)
                listKind == "folder" -> Triple("这个文件夹还没有视频", "可以在其他视频的「更多」里选择「移动到文件夹」。", false)
                else -> Triple("让好视频，有个家", "从相册发现，或选择一个本地文件。\n视频始终留在这台手机里。", true)
            }
            EmptyState(emptyTitle, emptyMessage, if (allowImport) importFile else null, if (listKind == "videos" && access == LibraryAccess.NONE && query.isEmpty() && !scanning) requestAccess else null)
        }
        items(items, key = { it.id.value }) { video ->
            VideoCard(video, selected.contains(video.id), selected.isNotEmpty(), hidden, Modifier.animateItem(),
                onClick = {
                    if (selected.isNotEmpty() || hidden) onSelect(if (video.id in selected) selected - video.id else selected + video.id)
                    else vm.open(video, items)
                }, onLong = { onSelect(selected + video.id) },
                onFavorite = { vm.favorite(setOf(video.id), !video.favorite) }, onRename = { onRename(video) },
                onHide = { vm.hide(setOf(video.id), !hidden) }, onQueue = { vm.open(video, listOf(video)) },
                onMove = { onFileAction(video, true) }, onDelete = { onFileAction(video, false) })
        }
    }
}

@Composable fun WatchProgress(video: VideoItem, modifier: Modifier = Modifier) {
    if (video.positionMs > 0 && video.durationMs > 0) LinearProgressIndicator(
        progress = { (video.positionMs.toFloat() / video.durationMs).coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(3.dp), color = BrandPink, trackColor = Color.Black.copy(alpha = .45f), drawStopIndicator = {})
}

@Composable fun Thumbnail(video: VideoItem, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f), modifier = Modifier.size(36.dp))
        if (video.accessible) AsyncImage(ImageRequest.Builder(LocalContext.current).data(video.uri).videoFrameMillis(1000).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun VideoCard(video: VideoItem, selected: Boolean, selecting: Boolean, hidden: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit, onLong: () -> Unit, onFavorite: () -> Unit, onRename: () -> Unit, onHide: () -> Unit, onQueue: () -> Unit,
    onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).testTag("video-${video.id.value}")
        .semantics { if (selecting || hidden) stateDescription = if (selected) "已选中" else "未选中" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            Thumbnail(video)
            Surface(color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(4.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                Text(durationText(video.durationMs), Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, fontSize = 11.sp)
            }
            if (selecting || hidden) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Surface(color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .4f), shape = CircleShape, modifier = Modifier.size(25.dp)) {
                    if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(4.dp))
                    else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White, modifier = Modifier.padding(3.dp))
                }
            } else Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = .38f)).clickable(onClick = onFavorite), contentAlignment = Alignment.Center) {
                Icon(if (video.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (video.favorite) "取消收藏" else "收藏",
                    tint = if (video.favorite) BrandPink else Color.White, modifier = Modifier.size(18.dp))
            }
            WatchProgress(video, Modifier.align(Alignment.BottomCenter))
        }
        Text(video.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 9.dp, start = 2.dp, end = 2.dp).heightIn(min = 38.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (!video.accessible) "需重新授权" else video.folder, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 2.dp))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "更多操作：${video.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    if (!hidden) {
                        DropdownMenuItem(text = { Text(if (video.favorite) "取消收藏" else "收藏视频") }, onClick = { menu = false; onFavorite() }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) })
                        DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                        DropdownMenuItem(text = { Text("单独播放") }, onClick = { menu = false; onQueue() }, leadingIcon = { Icon(Icons.Outlined.PlayCircleOutline, null) })
                        DropdownMenuItem(text = { Text("移动到文件夹") }, onClick = { menu = false; onMove() }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) })
                        DropdownMenuItem(text = { Text("删除原文件", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                    }
                    DropdownMenuItem(text = { Text(if (hidden) "恢复显示" else "隐藏视频") }, onClick = { menu = false; onHide() }, leadingIcon = { Icon(if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) })
                }
            }
        }
    }
}

@Composable fun RenameVideoDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    val trimmed = text.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 80 && trimmed.none { it == '\n' || it == '\r' }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重命名") },
        text = { Column {
            Text("只改映匣里显示的名称，不会修改手机里的文件。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it.take(80) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("显示名称") }, singleLine = true, isError = text.isNotEmpty() && !valid)
        } },
        confirmButton = { TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable fun EmptyState(title: String, message: String, onImport: (() -> Unit)? = null, onAccess: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 45.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f), modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp)) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (onImport != null) Button(onClick = onImport, modifier = Modifier.padding(top = 24.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("打开本地视频") }
        if (onAccess != null) TextButton(onClick = onAccess, modifier = Modifier.heightIn(min = 48.dp)) { Text("允许访问相册") }
    }
}

@Composable private fun FolderPage(videos: List<VideoItem>, managed: List<StorageFolder>, keys: Map<String, Set<String>>, onCreate: () -> Unit, onFolder: (String) -> Unit) {
    // A directory created here can also be discovered by MediaStore; show one entry.
    val folders = videos.filterNot { video -> managed.any { target ->
        video.folderKey in keys[target.uri].orEmpty() || belongsToFolder(video, target.uri)
    } }.groupBy { it.folderKey }.entries.sortedBy { it.value.first().folder }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("手机存储中的文件夹", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCreate) { Icon(Icons.Outlined.CreateNewFolder, null); Text("新建文件夹") }
        } }
        items(managed, key = { "managed:${it.uri}" }) { target ->
            Surface(onClick = { onFolder(target.uri) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(target.name, style = MaterialTheme.typography.titleMedium)
                        Text("已创建 · ${videos.count { it.folderKey in keys[target.uri].orEmpty() || belongsToFolder(it, target.uri) }} 个已收录视频", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
            }
        }
        if (folders.isEmpty() && managed.isEmpty()) item { EmptyState("还没有文件夹", "授权相册或添加视频后，文件夹会自动整理在这里") }
        items(folders, key = { it.key }) { group ->
            Surface(onClick = { onFolder(group.key) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(group.value.first().folder, style = MaterialTheme.typography.titleMedium)
                        Text("${group.value.size} 个视频 · ${fileSize(group.value.sumOf { it.sizeBytes })}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun ProfilePage(videos: List<VideoItem>, bookmarkCount: Int, onPage: (String) -> Unit, onImport: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surface, shape = CircleShape, modifier = Modifier.size(54.dp)) { Icon(Icons.Outlined.PlayCircleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp)) }
                        Column(Modifier.padding(start = 16.dp)) { Text("你的随身放映室", style = MaterialTheme.typography.titleMedium); Text("好好收藏，慢慢看。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        ProfileStat(videos.size, "本地视频")
                        ProfileStat(videos.count { it.favorite }, "我的收藏")
                        ProfileStat(bookmarkCount, "时间书签")
                    }
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
                Column {
                    MenuRow(Icons.Outlined.History, "播放历史", "从上次离开的地方继续", { onPage("history") })
                    MenuRow(Icons.Outlined.Bookmarks, "时间书签", "记下值得重看的瞬间", { onPage("bookmarks") })
                    MenuRow(Icons.Outlined.VisibilityOff, "隐藏管理", "查看与恢复隐藏的视频", { onPage("hidden") })
                    MenuRow(Icons.Outlined.Wifi, "同一 Wi-Fi 互传", "附近的映匣互相收发视频", { onPage("transfer") })
                    MenuRow(Icons.Outlined.CloudUpload, "云端传输", "用你自己的 WebDAV 搬运视频", { onPage("cloud") })
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
                Column { MenuRow(Icons.Outlined.Tune, "播放与外观", "倍速、字幕、浅色与深色", { onPage("settings") }); MenuRow(Icons.Outlined.AddCircleOutline, "打开本地视频", "从文件中选择", onImport) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text("  视频只保存在这台手机", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Text("映匣 ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ProfileStat(number: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(number.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
}
@Composable private fun MenuRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f).padding(horizontal = 15.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsPage(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { SettingsTitle("外观"); FlowRow { ThemeMode.entries.forEach { mode -> FilterChip(settings.theme == mode, { scope.launch { vm.graph.settings.setTheme(mode) } }, label = { Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) }, modifier = Modifier.padding(end = 8.dp)) } } }
        item { SettingsTitle("默认播放速度"); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PlaybackRules.speeds.forEach { speed -> FilterChip(settings.speed == speed, { vm.command(PlayerCommand.SetSpeed(speed)) }, label = { Text(speedText(speed)) }) } } }
        item { SettingsTitle("长按临时加速"); Row { listOf(2f, 3f).forEach { speed -> FilterChip(settings.holdSpeed == speed, { scope.launch { vm.graph.settings.setHoldSpeed(speed) } }, label = { Text(speedText(speed)) }, modifier = Modifier.padding(end = 8.dp)) } }; Text("松手恢复原速度；当前速度更快时不会降速。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsTitle("字幕字号"); FlowRow { listOf(.75f, 1f, 1.25f, 1.5f).forEach { scale -> FilterChip(settings.subtitleScale == scale, { scope.launch { vm.graph.settings.setSubtitleScale(scale) } }, label = { Text(when (scale) { .75f -> "小"; 1f -> "标准"; 1.25f -> "大"; else -> "特大" }) }, modifier = Modifier.padding(end = 8.dp)) } } }
        item { OutlinedButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("管理系统相册权限") }; Text("多次拒绝授权后，可在系统设置中重新允许访问。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingsTitle("使用提示"); Text("• 点击画面显示控制，双击播放或暂停，左右滑动调节进度。\n• 全屏时左侧调亮度，右侧调音量；长按画面临时加速。\n• 长按视频卡片可以多选，方便收藏、隐藏或移动。\n• 隐藏可以在底部提示里撤销，不会删除手机里的文件。\n• 视频与记录只存在这台手机，卸载会清除设置与记录。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedButton(onClick = { clearConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清除播放历史与续播进度") } }
    }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清除播放历史？") }, text = { Text("播放记录和续播进度会被清除。收藏、书签和视频文件不受影响。") },
        confirmButton = { TextButton(onClick = { scope.launch { vm.graph.repository.clearHistory() }; vm.message("已清除播放历史与续播进度"); clearConfirm = false }) { Text("清除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
}
@Composable private fun SettingsTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)) }

@Composable private fun BookmarksPage(vm: AppViewModel, videos: List<VideoItem>, bookmarks: List<Bookmark>) {
    val byId = videos.associateBy { it.id }
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<Bookmark?>(null) }
    var note by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (bookmarks.isEmpty()) item { EmptyState("留住值得重看的瞬间", "播放视频时，打开「时间书签」添加备注") }
        items(bookmarks, key = { it.id }) { bookmark ->
            val video = byId[bookmark.videoId]
            if (video != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().clickable { vm.open(video, videos, bookmark.positionMs) }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Text(durationText(bookmark.positionMs), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(bookmark.note.ifBlank { "未命名书签" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium); Text(video.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = { edit = bookmark; note = bookmark.note }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Edit, "编辑书签", modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = { pendingDelete = bookmark }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除书签", modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
    edit?.let { bookmark -> AlertDialog(onDismissRequest = { edit = null }, title = { Text("编辑书签") },
        text = { OutlinedTextField(note, { if (it.length <= 200) note = it }, label = { Text("备注") }) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { vm.graph.repository.saveBookmark(bookmark.videoId, bookmark.positionMs, note, bookmark.id) }.onFailure { vm.message(it.message ?: "保存失败") } }; edit = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { edit = null }) { Text("取消") } }) }
    pendingDelete?.let { bookmark ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除这本书签？") },
            text = { Text("「${bookmark.note.ifBlank { "未命名书签" }}」（${durationText(bookmark.positionMs)}）会从本机记录中移除。") },
            confirmButton = { TextButton(onClick = { scope.launch { vm.graph.repository.deleteBookmark(bookmark.id) }; pendingDelete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}
