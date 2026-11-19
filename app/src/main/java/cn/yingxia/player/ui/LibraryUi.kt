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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Favorite as FilledFavorite
import androidx.compose.material.icons.filled.Folder as FilledFolder
import androidx.compose.material.icons.filled.Home as FilledHome
import androidx.compose.material.icons.filled.Person as FilledPerson
import androidx.compose.material.icons.filled.PlayArrow as FilledPlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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
        var homeGeneration by rememberSaveable { mutableIntStateOf(0) }
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
                    if (library) key(homeGeneration) { LibraryScaffold(vm) { libraryNav = it } }
                    else key(player.video?.source == "share") { PlayerPage(vm, player, settings) }
                }
                FileOperationHost(vm)
                VaultHost(vm, onLockedToHome = { homeGeneration++ })
                GalleryShareDialog(vm)
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = snackbarLift))
            }
        }
    }
}

@Composable private fun GalleryShareDialog(vm: AppViewModel) {
    val pending by vm.share.collectAsStateWithLifecycle()
    val share = pending ?: return
    AlertDialog(onDismissRequest = vm::dismissShare, title = { Text("从相册分享") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    share.error != null -> Text(share.error)
                    share.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取所选视频…") }
                    else -> {
                        Text(if (share.uris.size == 1) "选择怎样打开这个视频" else "已选 ${share.uris.size} 个视频")
                        if (share.uris.size == 1) Button(onClick = { vm.useShare(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("本次不记播放记录") }
                        OutlinedButton(onClick = { vm.useShare(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("放入保险柜") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::dismissShare) { Text(if (share.error != null) "知道了" else "取消") } })
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
    val settings by vm.settings.collectAsStateWithLifecycle()
    val order = settings.sort
    val scope = rememberCoroutineScope()
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
    val showSearch = page != "hidden" && page != "settings" && page != "bookmarks" && page != "backup" && page != "scan" && !(tab == 3 && page.isEmpty()) && !(tab == 1 && folder == null && page.isEmpty())
    SideEffect { onNavigationVisible(!searchFocused || !showSearch) }
    DisposableEffect(Unit) { onDispose { onNavigationVisible(true) } }
    BackHandler(selected.isNotEmpty() || page.isNotEmpty() || folder != null) {
        when { selected.isNotEmpty() -> selected = emptySet(); folder != null -> folder = null; else -> page = "" }
    }
    val current = when {
        page == "hidden" -> hidden
        page == "history" -> videos.filter { it.lastPlayedAt > 0 }
        tab == 2 -> videos.filter { it.favorite }
        folder != null -> videos.filter { it.folderKey == folder || it.folderKey in managedKeys[folder].orEmpty() || belongsToFolder(it, folder!!) }
        else -> videos
    }
    val visible = remember(current, query, order, filter, page, tab) {
        val filtered = current.filter { matchesLibraryQuery(it.displayTitle(), it.name, it.folder, query) }
            .filter { tab != 0 || page.isNotEmpty() || filter != 2 || it.positionMs > 0 && it.durationMs - it.positionMs > 5000 }
        order.apply(filtered)
    }
    val titles = listOf("首页", "文件夹", "收藏", "我的")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Folder, Icons.Outlined.FavoriteBorder, Icons.Outlined.PersonOutline)
    val selectedIcons = listOf(Icons.Filled.FilledHome, Icons.Filled.FilledFolder, Icons.Filled.FilledFavorite, Icons.Filled.FilledPerson)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!searchFocused || !showSearch) Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    titles.forEachIndexed { i, title ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; page = ""; folder = null; query = ""; selected = emptySet(); filter = 0 },
                            icon = { Icon(if (tab == i) selectedIcons[i] else icons[i], title) },
                            label = { Text(title, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal) },
                            modifier = Modifier.testTag("nav-$i"),
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            val nestedTitle = when (page) { "hidden" -> "隐藏管理"; "history" -> "播放历史"; "bookmarks" -> "时间书签"; "settings" -> "播放与外观"; "backup" -> "备份与恢复"; "scan" -> "检查视频"; else -> if (folder != null) managedFolders.firstOrNull { it.uri == folder }?.name ?: current.firstOrNull()?.folder ?: "文件夹" else "" }
            val topLevel = selected.isEmpty() && page.isEmpty() && folder == null
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (topLevel) 16.dp else 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.isNotEmpty() || folder != null) IconButton(onClick = { page = ""; folder = null; selected = emptySet(); query = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text(if (selected.isNotEmpty()) "已选择 ${selected.size} 项" else nestedTitle.ifEmpty { if (tab == 0) "映匣" else titles[tab] },
                    style = if (topLevel) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }, modifier = Modifier.heightIn(min = 44.dp)) { Text("取消") }
                else IconButton(onClick = importFile) { Icon(Icons.Outlined.Add, "打开本地视频", tint = MaterialTheme.colorScheme.onSurface) }
            }
            if (selected.isNotEmpty()) {
                val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selected }
                val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                val dangerColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selected = if (allVisibleSelected) selected - visible.map { it.id }.toSet() else selected + visible.map { it.id }
                    }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(if (allVisibleSelected) "取消全选" else "全选") }
                    if (page == "hidden") TextButton(onClick = { vm.hide(selected, false); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text("恢复显示") }
                    else {
                        if (selected.size == 1) TextButton(onClick = {
                            visible.firstOrNull { it.id in selected }?.let { vm.open(it, listOf(it), recordHistory = false) }
                            selected = emptySet()
                        }, colors = actionColors) { Text("本次不记播放记录") }
                        TextButton(onClick = { vm.favorite(selected, true); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text("收藏") }
                        TextButton(onClick = { vm.favorite(selected, false); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text("取消收藏") }
                        TextButton(onClick = {
                            val chosen = visible.filter { it.id in selected }
                            val targets = chosen.filter { it.accessible }
                            selected = emptySet()
                            when {
                                targets.isEmpty() -> vm.message("这些视频需要重新授权后才能播放")
                                targets.size < chosen.size -> { vm.message("已跳过需要重新授权的视频"); vm.command(PlayerCommand.Enqueue(targets.map { it.id })) }
                                else -> vm.command(PlayerCommand.Enqueue(targets.map { it.id }))
                            }
                        }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text("播放所选") }
                        TextButton(onClick = { vm.hide(selected, true); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text("隐藏") }
                        TextButton(onClick = { vm.graph.vault.show(visible.filter { it.id in selected }); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text("加密") }
                        TextButton(onClick = { moving = true; fileItems = visible.filter { it.id in selected } }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text("移动") }
                        TextButton(onClick = { moving = false; fileItems = visible.filter { it.id in selected } }, modifier = Modifier.heightIn(min = 42.dp), colors = dangerColors) { Text("删除") }
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
            }, label = "library-screen") { currentScreen ->
            when (currentScreen) {
                "settings" -> SettingsPage(vm)
                "backup" -> BackupPage(vm)
                "scan" -> LibraryScanPage(vm)
                "bookmarks" -> BookmarksPage(vm, videos, bookmarks)
                "tab3" -> ProfilePage(videos, bookmarks.size, { if (it == "vault") vm.graph.vault.show() else page = it }, importFile)
                "tab1" -> FolderPage(videos, managedFolders, managedKeys, { newFolder = true }) { folder = it; query = "" }
                else -> {
                    val openedFolder = currentScreen.removePrefix("folder:").takeIf { currentScreen.startsWith("folder:") }
                    val listKind = when (currentScreen) {
                        "hidden" -> "hidden"
                        "history" -> "history"
                        "tab2" -> "favorites"
                        else -> if (openedFolder != null) "folder" else "videos"
                    }
                    Column(Modifier.fillMaxSize()) {
                    if (listKind != "hidden") {
                        SearchField(query, { query = it }, { searchFocused = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    } else Text("只是不在映匣里显示，手机里的文件还在。点选视频后可以恢复。", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    VideoGrid(vm, visible, videos, access, scanning, scanError, order, { scope.launch { vm.graph.settings.setSort(it) } }, query,
                        selected, { selected = it }, listKind, currentScreen == "tab0" && query.isBlank() && filter != 2,
                        filter, { filter = it }, currentScreen == "tab0",
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
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth().height(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("library-search").onFocusChanged { onFocus(it.isFocused) },
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("搜索视频或文件夹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Outlined.Close, "清除搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { onQuery("") })
                }
            }
        }
    )
}

@Composable private fun VideoGrid(vm: AppViewModel, items: List<VideoItem>, all: List<VideoItem>, access: LibraryAccess,
    scanning: Boolean, error: String?, order: SortOrder, onOrder: (SortOrder) -> Unit, query: String,
    selected: Set<VideoId>, onSelect: (Set<VideoId>) -> Unit, listKind: String, showContinue: Boolean,
    filter: Int, onFilter: (Int) -> Unit, showFilterPills: Boolean,
    requestAccess: () -> Unit, importFile: () -> Unit, onFileAction: (VideoItem, Boolean) -> Unit, onRename: (VideoItem) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    val hidden = listKind == "hidden"
    val history = listKind == "history"
    val last = all.filter { it.positionMs > 3000 && it.durationMs - it.positionMs > 5000 }.maxByOrNull { it.lastPlayedAt }
    LazyVerticalGrid(GridCells.Adaptive(158.dp), Modifier.fillMaxSize().testTag("video-grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!hidden && access != LibraryAccess.FULL) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                    Text(if (access == LibraryAccess.PARTIAL) "正在显示你授权的视频" else "授权相册，发现手机里的视频", modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = requestAccess) { Text(if (access == LibraryAccess.PARTIAL) "管理" else "授权") }
                }
            }
        }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::refresh) { Text("重试") }
                }
            }
        }
        if (showContinue && last != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(onClick = { vm.open(last, all) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FilledPlayArrow, "继续播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("继续看：", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                    Text(last.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(durationText(last.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val section = when (listKind) { "hidden" -> "已隐藏"; "history" -> "最近观看"; "favorites" -> "我的收藏"; "folder" -> "文件夹中的视频"; else -> "我的视频" }
            Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showFilterPills) {
                    listOf(0 to "全部视频", 2 to "未看完").forEach { (index, label) ->
                        val on = if (index == 0) filter != 2 else filter == 2
                        Column(
                            Modifier.padding(end = 18.dp).clip(RoundedCornerShape(6.dp)).clickable { onFilter(index) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(if (index == 0) "$label ${items.size}" else label,
                                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                            Box(Modifier.padding(top = 3.dp).size(width = 16.dp, height = 2.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
                        }
                    }
                } else {
                    Text(section, style = MaterialTheme.typography.titleMedium)
                    Text("  ${items.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else IconButton(onClick = vm::refresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Refresh, "刷新视频", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { sortMenu = true }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, "排序", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(3.dp))
                        Text(order.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }, shape = RoundedCornerShape(12.dp)) {
                        SortOrder.entries.forEach { o ->
                            DropdownMenuItem(text = { Text(o.title) }, onClick = { onOrder(o); sortMenu = false })
                        }
                    }
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
                onPlayWithoutHistory = { vm.open(video, listOf(video), recordHistory = false) },
                onMove = { onFileAction(video, true) }, onDelete = { onFileAction(video, false) }, onEncrypt = { vm.graph.vault.show(listOf(video)) })
        }
    }
}

@Composable fun WatchProgress(video: VideoItem, modifier: Modifier = Modifier) {
    if (video.positionMs > 0 && video.durationMs > 0) LinearProgressIndicator(
        progress = { (video.positionMs.toFloat() / video.durationMs).coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(2.5.dp), color = BrandPink, trackColor = Color.Black.copy(alpha = .3f), drawStopIndicator = {})
}

@Composable fun Thumbnail(video: VideoItem, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .22f), modifier = Modifier.size(28.dp))
        if (video.accessible) AsyncImage(ImageRequest.Builder(LocalContext.current).data(video.uri).videoFrameMillis(1000).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun VideoCard(video: VideoItem, selected: Boolean, selecting: Boolean, hidden: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit, onLong: () -> Unit, onFavorite: () -> Unit, onRename: () -> Unit, onHide: () -> Unit, onQueue: () -> Unit,
    onPlayWithoutHistory: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onEncrypt: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).testTag("video-${video.id.value}")
        .semantics { if (selecting || hidden) stateDescription = if (selected) "已选中" else "未选中" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            Thumbnail(video)
            Surface(color = Color.Black.copy(alpha = .52f), shape = RoundedCornerShape(4.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)) {
                Text(durationText(video.durationMs), Modifier.padding(horizontal = 5.dp, vertical = 1.dp), color = Color.White, fontSize = 10.sp)
            }
            if (selecting || hidden) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Surface(color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .4f), shape = CircleShape, modifier = Modifier.size(22.dp)) {
                    if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp))
                    else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White, modifier = Modifier.padding(2.dp))
                }
            } else if (video.favorite) {
                Icon(Icons.Filled.FilledFavorite, "取消收藏", tint = BrandPink,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp).clickable(onClick = onFavorite))
            }
            WatchProgress(video, Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(if (!video.accessible) "需重新授权" else "${video.folder} · ${fileSize(video.sizeBytes)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (!video.accessible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.MoreVert, "更多操作：${video.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(12.dp)) {
                    if (!hidden) {
                        DropdownMenuItem(text = { Text(if (video.favorite) "取消收藏" else "收藏视频") }, onClick = { menu = false; onFavorite() }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) })
                        DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                        DropdownMenuItem(text = { Text("单独播放") }, onClick = { menu = false; onQueue() }, leadingIcon = { Icon(Icons.Outlined.PlayCircleOutline, null) })
                        DropdownMenuItem(text = { Text("本次不记播放记录") }, onClick = { menu = false; onPlayWithoutHistory() }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
                        DropdownMenuItem(text = { Text("移动到文件夹") }, onClick = { menu = false; onMove() }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) })
                        DropdownMenuItem(text = { Text("移入加密保险箱") }, onClick = { menu = false; onEncrypt() }, leadingIcon = { Icon(Icons.Outlined.Lock, null) })
                        DropdownMenuItem(text = { Text("删除原文件", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(68.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onImport != null) Button(onClick = onImport, modifier = Modifier.padding(top = 20.dp).heightIn(min = 42.dp), shape = CircleShape) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("打开本地视频") }
        if (onAccess != null) TextButton(onClick = onAccess, modifier = Modifier.heightIn(min = 42.dp)) { Text("允许访问相册") }
    }
}

@Composable private fun FolderPage(videos: List<VideoItem>, managed: List<StorageFolder>, keys: Map<String, Set<String>>, onCreate: () -> Unit, onFolder: (String) -> Unit) {
    val folders = videos.filterNot { video -> managed.any { target ->
        video.folderKey in keys[target.uri].orEmpty() || belongsToFolder(video, target.uri)
    } }.groupBy { it.folderKey }.entries.sortedBy { it.value.first().folder }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${managed.size + folders.size} 个文件夹", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCreate) { Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("新建文件夹") }
            }
        }
        items(managed, key = { "managed:${it.uri}" }) { target ->
            val count = videos.count { it.folderKey in keys[target.uri].orEmpty() || belongsToFolder(it, target.uri) }
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { onFolder(target.uri) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.FolderOpen, size = 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(target.name, style = MaterialTheme.typography.titleMedium)
                        Text("已创建 · $count 个已收录视频", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (folders.isEmpty() && managed.isEmpty()) item { EmptyState("还没有文件夹", "授权相册或添加视频后，文件夹会自动整理在这里") }
        items(folders, key = { it.key }) { group ->
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { onFolder(group.key) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = group.value.firstOrNull { it.accessible }
                    Box(Modifier.width(76.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                        if (cover != null) Thumbnail(cover) else Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(group.value.first().folder, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.value.size} 个视频 · ${fileSize(group.value.sumOf { it.sizeBytes })}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun ProfilePage(videos: List<VideoItem>, bookmarkCount: Int, onPage: (String) -> Unit, onImport: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(padding = 16.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    ProfileStat(videos.size, "本地视频")
                    ProfileStat(videos.count { it.favorite }, "我的收藏")
                    ProfileStat(bookmarkCount, "时间书签")
                }
            }
        }
        item {
            SectionCard {
                MenuRow(Icons.Outlined.History, "播放历史", "从上次离开的地方继续", { onPage("history") }, BrandPink)
                MenuDivider()
                MenuRow(Icons.Outlined.Bookmarks, "时间书签", "记下值得重看的瞬间", { onPage("bookmarks") }, BrandAmber)
                MenuDivider()
                MenuRow(Icons.Outlined.VisibilityOff, "隐藏管理", "查看与恢复隐藏的视频", { onPage("hidden") }, BrandViolet)
                MenuDivider()
                MenuRow(Icons.Outlined.Lock, "加密保险箱", "密码保护，移除相册原件后仅在此播放", { onPage("vault") }, BrandViolet)
                MenuDivider()
                MenuRow(Icons.AutoMirrored.Outlined.FactCheck, "检查视频", "找出内容相同和打不开的文件", { onPage("scan") }, BrandTeal)
                MenuDivider()
                MenuRow(Icons.Outlined.SaveAlt, "备份与恢复", "导出收藏、进度和设置", { onPage("backup") }, BrandBlue)
            }
        }
        item {
            SectionCard {
                MenuRow(Icons.Outlined.Tune, "播放与外观", "倍速、浅色与深色、防误触", { onPage("settings") }, BrandPinkDeep)
                MenuDivider()
                MenuRow(Icons.Outlined.AddCircleOutline, "打开本地视频", "从文件中选择", onImport, BrandTeal)
            }
        }
        item {
            Text("映匣 ${BuildConfig.VERSION_NAME} · 本地离线播放", Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ProfileStat(number: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(number.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
@Composable private fun MenuDivider() { HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) }
@Composable private fun MenuRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit, tint: Color = BrandPink) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 34.dp, container = tint.copy(alpha = .12f), tint = tint)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
    }
}

@Composable private fun SettingsPage(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionCard(padding = 16.dp) { SettingsTitle("外观"); FlowRow { ThemeMode.entries.forEach { mode -> FilterChip(settings.theme == mode, { scope.launch { vm.graph.settings.setTheme(mode) } }, label = { Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) }, shape = CircleShape, modifier = Modifier.padding(end = 8.dp)) } } } }
        item { SectionCard(padding = 16.dp) {
            SettingsTitle("默认播放速度"); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PlaybackRules.speeds.forEach { speed -> FilterChip(settings.speed == speed, { vm.command(PlayerCommand.SetSpeed(speed)) }, label = { Text(speedText(speed)) }, shape = CircleShape) } }
            Spacer(Modifier.height(12.dp))
            SettingsTitle("长按临时加速"); Row { listOf(2f, 3f).forEach { speed -> FilterChip(settings.holdSpeed == speed, { scope.launch { vm.graph.settings.setHoldSpeed(speed) } }, label = { Text(speedText(speed)) }, shape = CircleShape, modifier = Modifier.padding(end = 8.dp)) } }; Text("松手恢复原速度；当前速度更快时不会降速。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item {
            SectionCard(padding = 16.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("播放时防误触", style = MaterialTheme.typography.titleMedium)
                        Text("播放中先挡住单击和滑动，点画面左侧的锁可以解开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.touchGuard, { scope.launch { vm.graph.settings.setTouchGuard(it) } })
                }
            }
        }
        item { SectionCard(padding = 16.dp) { OutlinedButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp), shape = CircleShape) { Text("管理系统相册权限") }; Text("多次拒绝授权后，可在系统设置中重新允许访问。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) } }
        item { SectionCard(padding = 16.dp) { SettingsTitle("使用提示"); Text("• 底部四个标签是第一层。点进历史、设置或文件夹是第二层，左上角返回。\n• 点一下画面显示控制，点两下播放或暂停，左右滑调节进度。\n• 双指捏合只放大这一次播放。播放工具里可以裁掉黑边。\n• 全屏时左侧调亮度，右侧调音量；长按才出现加速。\n• 搜索可以用拼音首字母，例如 pjh。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { OutlinedButton(onClick = { clearConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp), shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除播放历史与续播进度") } }
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
            if (video != null) SectionCard {
                Row(Modifier.fillMaxWidth().clickable { vm.open(video, videos, bookmark.positionMs) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Text(durationText(bookmark.positionMs), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    }
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
