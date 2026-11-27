@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.yingxia.player.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Favorite as FilledFavorite
import androidx.compose.material.icons.filled.Folder as FilledFolder
import androidx.compose.material.icons.filled.GridView as FilledGridView
import androidx.compose.material.icons.filled.PlayArrow as FilledPlayArrow
import androidx.compose.material.icons.filled.Shield as FilledShield
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppLanguage
import cn.yingxia.player.AppLocale
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.BuildConfig
import cn.yingxia.player.R
import cn.yingxia.player.core.*
import cn.yingxia.player.data.mediaPermissions
import kotlinx.coroutines.ensureActive
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
    val hasActiveVideo by vm.hasActiveVideo.collectAsStateWithLifecycle()
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
                isAppearanceLightStatusBars = !dark && !hasActiveVideo
                isAppearanceLightNavigationBars = !dark && !hasActiveVideo
            }
        }
        LaunchedEffect(dark, hasActiveVideo) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = !dark && !hasActiveVideo
            controller.isAppearanceLightNavigationBars = !dark && !hasActiveVideo
            if (!hasActiveVideo) {
                kotlinx.coroutines.delay(160)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
        var libraryNav by remember { mutableStateOf(true) }
        var floatingBarVisible by remember { mutableStateOf(false) }
        var homeGeneration by rememberSaveable { mutableIntStateOf(0) }
        var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Library, 1 = Folders, 2 = Space
        var page by rememberSaveable { mutableStateOf("") }
        var folder by rememberSaveable { mutableStateOf<String?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        var filter by rememberSaveable { mutableIntStateOf(0) } // 0=All, 2=InProgress, 1=Favorites, 3=Bookmarks, 4=History
        var historyOrder by rememberSaveable { mutableStateOf(SortOrder.RECENT_PLAY) }
        val config = LocalConfiguration.current
        val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wideScreen = config.screenWidthDp >= 600
        val showLibrary = !hasActiveVideo
        val baseLift = when {
            !showLibrary && landscape -> 96.dp
            !showLibrary -> 24.dp
            libraryNav && !wideScreen -> 84.dp
            else -> 16.dp
        }
        val snackbarLift = if (showLibrary && floatingBarVisible) baseLift + 64.dp else baseLift
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    showLibrary,
                    Modifier.fillMaxSize(),
                    transitionSpec = { fadeIn(motionTween(180)) togetherWith fadeOut(motionTween(140)) },
                    label = "library-player",
                ) { library ->
                    if (library) {
                        key(homeGeneration) {
                            LibraryScaffold(
                                vm = vm,
                                tab = tab, onTab = { tab = it },
                                page = page, onPage = { page = it },
                                folder = folder, onFolder = { folder = it },
                                query = query, onQuery = { query = it },
                                filter = filter, onFilter = { filter = it },
                                historyOrder = historyOrder, onHistoryOrder = { historyOrder = it },
                                onNavigationVisible = { libraryNav = it },
                                onFloatingBarVisible = { floatingBarVisible = it },
                            )
                        }
                    } else {
                        PlayerPageHost(vm, settings)
                    }
                }
                FileOperationHost(vm)
                VaultHost(vm, onLockedToHome = {
                    tab = 0; page = ""; folder = null; query = ""; filter = 0
                    homeGeneration++
                })
                GalleryShareDialog(vm)
                SnackbarHost(
                    snack,
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = snackbarLift),
                )
            }
        }
    }
}

@Composable private fun GalleryShareDialog(vm: AppViewModel) {
    val pending by vm.share.collectAsStateWithLifecycle()
    val share = pending ?: return
    AlertDialog(
        onDismissRequest = vm::dismissShare,
        title = { Text(stringResource(R.string.share_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    share.error != null -> Text(share.error)
                    share.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.share_loading)) }
                    else -> {
                        Text(if (share.uris.size == 1) stringResource(R.string.share_choose_single) else stringResource(R.string.share_selected_count, share.uris.size))
                        if (share.uris.size == 1) Button(onClick = { vm.useShare(false) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_watch_without_history)) }
                        OutlinedButton(onClick = { vm.useShare(true) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.share_action_vault)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::dismissShare) { Text(stringResource(if (share.error != null) R.string.action_got_it else R.string.action_cancel)) } },
    )
}

@Composable private fun LibraryScaffold(
    vm: AppViewModel,
    tab: Int, onTab: (Int) -> Unit,
    page: String, onPage: (String) -> Unit,
    folder: String?, onFolder: (String?) -> Unit,
    query: String, onQuery: (String) -> Unit,
    filter: Int, onFilter: (Int) -> Unit,
    historyOrder: SortOrder, onHistoryOrder: (SortOrder) -> Unit,
    onNavigationVisible: (Boolean) -> Unit,
    onFloatingBarVisible: (Boolean) -> Unit,
) {
    val videos by vm.videos.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val continueItem by vm.continueWatching.collectAsStateWithLifecycle()
    val scanning by vm.graph.repository.scanning.collectAsStateWithLifecycle()
    val access by vm.graph.repository.access.collectAsStateWithLifecycle()
    val scanError by vm.graph.repository.scanError.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncState by vm.graph.sync.state.collectAsStateWithLifecycle()
    val order = settings.sort
    val isHistoryView = page == "history" || (tab == 0 && page.isEmpty() && filter == 4)
    val effectiveOrder = if (isHistoryView) historyOrder else order
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var selected by remember { mutableStateOf(setOf<VideoId>()) }
    var searchFocused by remember { mutableStateOf(false) }
    var dismissedResumeId by rememberSaveable { mutableStateOf("") }
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
    val requestAccess: () -> Unit = {
        runCatching { permissions.launch(mediaPermissions()) }
            .onFailure { vm.message(context.getString(R.string.error_action_not_saved)) }
    }
    val importFile: () -> Unit = {
        runCatching { openFile.launch(Unit) }
            .onFailure { vm.message(context.getString(R.string.notice_import_failed)) }
    }
    val encryptBatch: (List<VideoItem>) -> Unit = { batch ->
        val targets = batch.filter { it.accessible }
        when {
            targets.isEmpty() -> vm.message(context.getString(R.string.notice_need_regrant_all))
            targets.size < batch.size -> {
                vm.message(context.getString(R.string.notice_skipped_inaccessible))
                vm.graph.vault.show(targets)
            }
            else -> vm.graph.vault.show(targets)
        }
    }

    val showSearch = page.isEmpty() && (tab == 0 || (tab == 1 && folder != null))
    val hideNavForKeyboard = showSearch && searchFocused && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val wideScreen = LocalConfiguration.current.screenWidthDp >= 600
    SideEffect { onNavigationVisible(!hideNavForKeyboard) }
    DisposableEffect(Unit) {
        onDispose {
            onNavigationVisible(true)
            onFloatingBarVisible(false)
        }
    }

    BackHandler(selected.isNotEmpty() || query.isNotEmpty() || searchFocused || page.isNotEmpty() || folder != null || (tab == 0 && filter != 0)) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            query.isNotEmpty() || searchFocused -> { onQuery(""); searchFocused = false; focusManager.clearFocus() }
            folder != null -> onFolder(null)
            page.isNotEmpty() -> onPage("")
            tab == 0 && filter != 0 -> onFilter(0)
        }
    }

    // Remembered off-composition-loop filtering
    val current = remember(videos, hidden, page, tab, folder, managedKeys) {
        when {
            page == "hidden" -> hidden
            page == "history" -> videos.filter { it.lastPlayedAt > 0 }
            folder != null -> videos.filter { it.folderKey == folder || it.folderKey in managedKeys[folder].orEmpty() || belongsToFolder(it, folder) }
            else -> videos
        }
    }
    val searchMatched = remember(current, query) {
        if (query.isBlank()) current else current.filter { matchesLibraryQuery(it.displayTitle(), it.name, it.folder, query) }
    }
    val visible = remember(searchMatched, effectiveOrder, filter, page, tab) {
        val filtered = if (tab == 0 && page.isEmpty()) {
            when (filter) {
                2 -> searchMatched.filter { it.positionMs > 0 && it.durationMs - it.positionMs > 5000 }
                1 -> searchMatched.filter { it.favorite }
                4 -> searchMatched.filter { it.lastPlayedAt > 0 }
                else -> searchMatched
            }
        } else searchMatched
        effectiveOrder.apply(filtered)
    }

    LaunchedEffect(visible) {
        if (selected.isNotEmpty()) {
            val visibleIds = visible.mapTo(HashSet()) { it.id }
            val pruned = selected.filterTo(LinkedHashSet()) { it in visibleIds }
            if (pruned.size != selected.size) selected = pruned
        }
    }

    // 3-Tab Navigation (媒体库 | 文件夹 | 空间)
    val titles = listOf(
        stringResource(R.string.nav_library),
        stringResource(R.string.nav_folders),
        stringResource(R.string.nav_space),
    )
    val icons = listOf(Icons.Outlined.GridView, Icons.Outlined.Folder, Icons.Outlined.Shield)
    val selectedIcons = listOf(Icons.Filled.FilledGridView, Icons.Filled.FilledFolder, Icons.Filled.FilledShield)

    val switchTab: (Int) -> Unit = { i ->
        onTab(i); onPage(""); onFolder(null); onQuery(""); selected = emptySet(); onFilter(0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!hideNavForKeyboard && !wideScreen) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        titles.forEachIndexed { i, title ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { switchTab(i) },
                                icon = { Icon(if (tab == i) selectedIcons[i] else icons[i], title) },
                                label = { Text(title, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp) },
                                modifier = Modifier.testTag("nav-$i"),
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wideScreen) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                ) {
                    Spacer(Modifier.height(12.dp))
                    titles.forEachIndexed { i, title ->
                        NavigationRailItem(
                            selected = tab == i,
                            onClick = { switchTab(i) },
                            icon = { Icon(if (tab == i) selectedIcons[i] else icons[i], title) },
                            label = { Text(title, fontSize = 11.sp) },
                            modifier = Modifier.testTag("nav-$i"),
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }

            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    val nestedTitle = when (page) {
                        "hidden" -> stringResource(R.string.title_hidden_manager)
                        "history" -> stringResource(R.string.title_history)
                        "bookmarks" -> stringResource(R.string.title_bookmarks)
                        "settings" -> stringResource(R.string.title_settings)
                        "backup" -> stringResource(R.string.title_backup)
                        "scan" -> stringResource(R.string.title_scan)
                        else -> if (folder != null) managedFolders.firstOrNull { it.uri == folder }?.name ?: current.firstOrNull()?.folder ?: stringResource(R.string.title_folder_fallback) else ""
                    }
                    val topLevel = page.isEmpty() && folder == null

                    // Minimalist Top App Header (no duplicated + across irrelevant tabs!)
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(start = if (topLevel) 20.dp else 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!topLevel) {
                            IconButton(onClick = { onPage(""); onFolder(null); selected = emptySet(); onQuery("") }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                            }
                        }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                nestedTitle.ifEmpty { if (tab == 0) stringResource(R.string.app_name) else titles[tab] },
                                style = if (topLevel) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (topLevel && tab == 0) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Text(
                                        stringResource(if (vm.graph.sync.enabled) R.string.badge_sync_edition else R.string.badge_zero_net),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                        when {
                            topLevel && tab == 0 -> {
                                Surface(
                                    onClick = importFile,
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Add, stringResource(R.string.action_open_local_video), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.action_import_short), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            topLevel && tab == 1 -> {
                                Surface(
                                    onClick = { newFolder = true },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.folder_new), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            topLevel && tab == 2 -> {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Text(
                                        stringResource(R.string.space_version_badge, BuildConfig.VERSION_NAME),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            folder != null && page.isEmpty() && visible.isNotEmpty() -> {
                                IconButton(onClick = { encryptBatch(visible) }, modifier = Modifier.testTag("folder-encrypt-current")) {
                                    Icon(Icons.Outlined.Lock, stringResource(R.string.folder_action_encrypt_current), tint = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }

                    val screen = when {
                        page.isNotEmpty() -> page
                        folder != null -> "folder:$folder"
                        else -> "tab$tab"
                    }
                    AnimatedContent(
                        screen,
                        Modifier.weight(1f),
                        transitionSpec = { fadeIn(motionTween(180)) togetherWith fadeOut(motionTween(120)) },
                        label = "library-screen",
                    ) { currentScreen ->
                        when (currentScreen) {
                            "settings" -> SettingsPage(vm)
                            "backup" -> BackupPage(vm)
                            "scan" -> LibraryScanPage(vm)
                            "bookmarks" -> BookmarksPage(vm, videos, bookmarks)
                            "tab2" -> SpacePage(
                                vm = vm,
                                syncState = syncState,
                                onPage = { if (it == "vault") vm.graph.vault.show() else onPage(it) },
                            )
                            "tab1" -> FolderPage(
                                vm = vm,
                                videos = videos,
                                managed = managedFolders,
                                keys = managedKeys,
                                syncState = syncState,
                                onFolder = { onFolder(it); onQuery("") },
                                onOpenSpaceSync = { switchTab(2) },
                                onEncryptFolder = encryptBatch,
                            )
                            else -> {
                                val openedFolder = currentScreen.removePrefix("folder:").takeIf { currentScreen.startsWith("folder:") }
                                val listKind = when {
                                    currentScreen == "hidden" -> "hidden"
                                    currentScreen == "history" -> "history"
                                    openedFolder != null -> "folder"
                                    filter == 1 -> "favorites"
                                    filter == 4 -> "history"
                                    else -> "videos"
                                }
                                Column(Modifier.fillMaxSize()) {
                                    if (listKind != "hidden") {
                                        SearchField(query, onQuery, { searchFocused = it }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                                    } else {
                                        Text(
                                            stringResource(R.string.hidden_banner_hint),
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (currentScreen == "tab0" && filter == 3) {
                                        FilterCapsuleRow(
                                            totalMatchingCount = searchMatched.size,
                                            filter = filter,
                                            onFilter = { selected = emptySet(); onFilter(it) },
                                            scanning = scanning,
                                            order = effectiveOrder,
                                            onRefresh = vm::refresh,
                                            onOrder = { scope.launch { vm.graph.settings.setSort(it) } },
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                        )
                                        BookmarksPage(vm, videos, bookmarks, searchFilter = query)
                                    } else {
                                        VideoGrid(
                                            vm = vm,
                                            items = visible,
                                            totalMatchingCount = searchMatched.size,
                                            access = access,
                                            scanning = scanning,
                                            error = scanError,
                                            order = effectiveOrder,
                                            onOrder = { next ->
                                                if (isHistoryView) onHistoryOrder(next) else scope.launch { vm.graph.settings.setSort(next) }
                                            },
                                            query = query,
                                            selected = selected,
                                            onSelect = { selected = it },
                                            listKind = listKind,
                                            filter = filter,
                                            onFilter = { selected = emptySet(); onFilter(it) },
                                            showFilterPills = currentScreen == "tab0",
                                            requestAccess = requestAccess,
                                            importFile = importFile,
                                            onFileAction = { video, move -> moving = move; fileItems = listOf(video) },
                                            onRename = { renaming = it },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Floating Continue-Watching Obsidian Capsule (on Library root when not multi-selecting)
                val showContinueCapsule = tab == 0 && page.isEmpty() && folder == null && selected.isEmpty() &&
                    query.isBlank() && filter == 0 && continueItem != null && continueItem?.id?.value != dismissedResumeId
                val hasFloatingBar = selected.isNotEmpty() || (showContinueCapsule && continueItem != null)
                SideEffect { onFloatingBarVisible(hasFloatingBar) }
                DisposableEffect(Unit) { onDispose { onFloatingBarVisible(false) } }
                if (showContinueCapsule && continueItem != null) {
                    val last = continueItem!!
                    Surface(
                        onClick = { vm.open(last, videos) },
                        shape = RoundedCornerShape(14.dp),
                        color = ObsidianSurface,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(shape = CircleShape, color = SlateAccent, modifier = Modifier.size(26.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.FilledPlayArrow, stringResource(R.string.continue_watching_icon), tint = Color.White, modifier = Modifier.size(15.dp))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${stringResource(R.string.continue_watching_prefix)}${last.displayTitle()}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color(0xFFF3F4F6),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                durationText(last.positionMs),
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            IconButton(
                                onClick = { dismissedResumeId = last.id.value },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.continue_watching_dismiss), tint = Color(0xFF9CA3AF), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // 2.0 Floating Multi-Select Action Island (replaces cluttered 9-button horizontal scroll strip)
                if (selected.isNotEmpty()) {
                    MultiSelectActionIsland(
                        selected = selected,
                        visible = visible,
                        hiddenMode = page == "hidden",
                        onClearSelection = { selected = emptySet() },
                        onSelectAllToggle = {
                            val allSelected = visible.isNotEmpty() && visible.all { it.id in selected }
                            selected = if (allSelected) selected - visible.map { it.id }.toSet() else selected + visible.map { it.id }
                        },
                        onPlaySelected = {
                            val chosen = visible.filter { it.id in selected }
                            val targets = chosen.filter { it.accessible }
                            selected = emptySet()
                            when {
                                targets.isEmpty() -> vm.message(context.getString(R.string.notice_need_regrant_all))
                                targets.size < chosen.size -> {
                                    vm.message(context.getString(R.string.notice_skipped_inaccessible))
                                    vm.command(PlayerCommand.Enqueue(targets.map { it.id }))
                                }
                                else -> vm.command(PlayerCommand.Enqueue(targets.map { it.id }))
                            }
                        },
                        onWatchWithoutHistory = {
                            visible.firstOrNull { it.id in selected }?.let { vm.open(it, listOf(it), recordHistory = false) }
                            selected = emptySet()
                        },
                        onFavorite = { fav -> vm.favorite(selected, fav); selected = emptySet() },
                        onHide = { hide -> vm.hide(selected, hide); selected = emptySet() },
                        onEncrypt = { encryptBatch(visible.filter { it.id in selected }); selected = emptySet() },
                        onMove = { val picked = visible.filter { it.id in selected }; if (picked.isNotEmpty()) { moving = true; fileItems = picked } },
                        onDelete = { val picked = visible.filter { it.id in selected }; if (picked.isNotEmpty()) { moving = false; fileItems = picked } },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable private fun MultiSelectActionIsland(
    selected: Set<VideoId>,
    visible: List<VideoItem>,
    hiddenMode: Boolean,
    onClearSelection: () -> Unit,
    onSelectAllToggle: () -> Unit,
    onPlaySelected: () -> Unit,
    onWatchWithoutHistory: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onHide: (Boolean) -> Unit,
    onEncrypt: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreOpen by remember { mutableStateOf(false) }
    val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selected }
    Surface(
        shape = CircleShape,
        color = ObsidianSurface,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.title_selected_count, selected.size),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (hiddenMode) {
                TextButton(onClick = { onHide(false) }) {
                    Text(stringResource(R.string.action_restore_visible), color = SlateAccentDark, fontSize = 12.sp)
                }
            } else {
                TextButton(onClick = onPlaySelected, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.FilledPlayArrow, null, tint = SlateAccentDark, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.multi_action_play), color = SlateAccentDark, fontSize = 12.sp)
                }
                TextButton(onClick = onEncrypt, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = VaultEmeraldBright, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.multi_action_vault), color = VaultEmeraldBright, fontSize = 12.sp)
                }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.action_delete), color = Color(0xFFF87171), fontSize = 12.sp)
                }
            }
            Box {
                IconButton(onClick = { moreOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.multi_action_more), tint = Color(0xFFD1D5DB), modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, shape = RoundedCornerShape(12.dp)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (allVisibleSelected) R.string.action_deselect_all else R.string.action_select_all)) },
                        onClick = { moreOpen = false; onSelectAllToggle() },
                    )
                    if (!hiddenMode) {
                        if (selected.size == 1) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_watch_without_history)) },
                                onClick = { moreOpen = false; onWatchWithoutHistory() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_favorite)) },
                            onClick = { moreOpen = false; onFavorite(true) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_unfavorite)) },
                            onClick = { moreOpen = false; onFavorite(false) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_move)) },
                            onClick = { moreOpen = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_hide)) },
                            onClick = { moreOpen = false; onHide(true) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_cancel)) },
                        onClick = { moreOpen = false; onClearSelection() },
                    )
                }
            }
        }
    }
}

@Composable private fun SearchField(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("library-search")
            .onFocusChanged { onFocus(it.isFocused) },
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_placeholder_v2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close,
                        stringResource(R.string.search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { onQuery(""); focusManager.clearFocus() },
                    )
                }
            }
        },
    )
}

@Composable private fun FilterCapsuleRow(
    totalMatchingCount: Int,
    filter: Int,
    onFilter: (Int) -> Unit,
    scanning: Boolean,
    order: SortOrder,
    onRefresh: () -> Unit,
    onOrder: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortMenu by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background == CinemaDarkCanvas
    val pills = listOf(
        0 to "${stringResource(R.string.filter_all_videos)} $totalMatchingCount",
        2 to stringResource(R.string.filter_in_progress),
        1 to stringResource(R.string.filter_favorites),
        3 to stringResource(R.string.filter_bookmarks),
        4 to stringResource(R.string.filter_history),
    )
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pills.forEach { (idx, label) ->
                val active = filter == idx
                val bg = when {
                    active && dark -> Color(0xFFF3F4F6)
                    active -> ObsidianSurface
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                }
                val fg = when {
                    active && dark -> ObsidianSurface
                    active -> Color.White
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    onClick = { onFilter(idx) },
                    shape = CircleShape,
                    color = bg,
                    modifier = Modifier.testTag("filter-pill-$idx"),
                ) {
                    Text(
                        label,
                        color = fg,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        if (scanning) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.action_refresh_videos), modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { sortMenu = true }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.action_sort), modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(3.dp))
                Text(stringResource(order.labelRes()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            DropdownMenu(sortMenu, { sortMenu = false }, shape = RoundedCornerShape(12.dp)) {
                SortOrder.entries.forEach { o ->
                    DropdownMenuItem(text = { Text(stringResource(o.labelRes())) }, onClick = { onOrder(o); sortMenu = false })
                }
            }
        }
    }
}

private fun SortOrder.labelRes(): Int = when (this) {
    SortOrder.NEWEST -> R.string.sort_newest
    SortOrder.OLDEST -> R.string.sort_oldest
    SortOrder.RECENT_PLAY -> R.string.sort_recent_play
    SortOrder.NAME_ASC -> R.string.sort_name_asc
    SortOrder.NAME_DESC -> R.string.sort_name_desc
    SortOrder.SIZE_DESC -> R.string.sort_size_desc
    SortOrder.SIZE_ASC -> R.string.sort_size_asc
    SortOrder.DURATION -> R.string.sort_duration
}

@Composable private fun VideoGrid(
    vm: AppViewModel,
    items: List<VideoItem>,
    totalMatchingCount: Int,
    access: LibraryAccess,
    scanning: Boolean,
    error: String?,
    order: SortOrder,
    onOrder: (SortOrder) -> Unit,
    query: String,
    selected: Set<VideoId>,
    onSelect: (Set<VideoId>) -> Unit,
    listKind: String,
    filter: Int,
    onFilter: (Int) -> Unit,
    showFilterPills: Boolean,
    requestAccess: () -> Unit,
    importFile: () -> Unit,
    onFileAction: (VideoItem, Boolean) -> Unit,
    onRename: (VideoItem) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current // Hoisted outside LazyVerticalGrid!
    val hidden = listKind == "hidden"
    val history = listKind == "history"
    val selecting = selected.isNotEmpty()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(162.dp),
        modifier = Modifier.fillMaxSize().testTag("video-grid"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 84.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!hidden && access != LibraryAccess.FULL) {
            item(key = "banner-access", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                        Text(stringResource(if (access == LibraryAccess.PARTIAL) R.string.access_partial_banner else R.string.access_none_banner), modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = requestAccess) { Text(stringResource(if (access == LibraryAccess.PARTIAL) R.string.access_manage else R.string.access_grant)) }
                    }
                }
            }
        }
        if (error != null) {
            item(key = "banner-error", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::refresh) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
        }
        item(key = "header-filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filter-bar") {
            if (showFilterPills) {
                FilterCapsuleRow(
                    totalMatchingCount = totalMatchingCount,
                    filter = filter,
                    onFilter = onFilter,
                    scanning = scanning,
                    order = order,
                    onRefresh = vm::refresh,
                    onOrder = onOrder,
                )
            } else {
                val section = stringResource(
                    when (listKind) {
                        "hidden" -> R.string.section_hidden
                        "history" -> R.string.section_history
                        "favorites" -> R.string.section_favorites
                        "folder" -> R.string.section_folder_videos
                        else -> R.string.section_my_videos
                    }
                )
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(section, style = MaterialTheme.typography.titleMedium)
                    Text("  ${items.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { sortMenu = true }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.action_sort), modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(3.dp))
                            Text(stringResource(order.labelRes()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        DropdownMenu(sortMenu, { sortMenu = false }, shape = RoundedCornerShape(12.dp)) {
                            SortOrder.entries.forEach { o ->
                                DropdownMenuItem(text = { Text(stringResource(o.labelRes())) }, onClick = { onOrder(o); sortMenu = false })
                            }
                        }
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item(key = "empty-state", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                val (emptyTitleRes, emptyMessageRes, allowImport) = when {
                    scanning -> Triple(R.string.empty_scanning_title, R.string.empty_scanning_desc, false)
                    query.isNotEmpty() -> Triple(R.string.empty_search_title, R.string.empty_search_desc, false)
                    hidden -> Triple(R.string.empty_hidden_title, R.string.empty_hidden_desc, false)
                    history -> Triple(R.string.empty_history_title, R.string.empty_history_desc, false)
                    listKind == "favorites" -> Triple(R.string.empty_favorites_title, R.string.empty_favorites_desc, false)
                    listKind == "folder" -> Triple(R.string.empty_folder_title, R.string.empty_folder_desc, false)
                    filter == 2 -> Triple(R.string.empty_in_progress_title, R.string.empty_in_progress_desc, false)
                    else -> Triple(R.string.empty_library_title, R.string.empty_library_desc, true)
                }
                EmptyState(stringResource(emptyTitleRes), stringResource(emptyMessageRes), if (allowImport) importFile else null, if (listKind == "videos" && access == LibraryAccess.NONE && query.isEmpty() && !scanning) requestAccess else null)
            }
        }
        items(items, key = { it.id.value }, contentType = { "video-card" }) { video ->
            VideoCard(
                context = context,
                video = video,
                isSelected = { video.id in selected },
                selecting = selecting,
                hidden = hidden,
                modifier = Modifier.animateItem(),
                onClick = {
                    if (selecting || hidden) onSelect(if (video.id in selected) selected - video.id else selected + video.id)
                    else vm.open(video, items)
                },
                onLong = { onSelect(selected + video.id) },
                onFavorite = { vm.favorite(setOf(video.id), !video.favorite) },
                onRename = { onRename(video) },
                onHide = { vm.hide(setOf(video.id), !hidden) },
                onQueue = { vm.open(video, listOf(video)) },
                onPlayWithoutHistory = { vm.open(video, listOf(video), recordHistory = false) },
                onMove = { onFileAction(video, true) },
                onDelete = { onFileAction(video, false) },
                onEncrypt = { vm.graph.vault.show(listOf(video)) },
            )
        }
    }
}

@Composable fun WatchProgress(video: VideoItem, modifier: Modifier = Modifier) {
    if (video.positionMs > 0 && video.durationMs > 0) {
        LinearProgressIndicator(
            progress = { (video.positionMs.toFloat() / video.durationMs).coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().height(2.5.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = .22f),
            drawStopIndicator = {},
        )
    }
}

private object LocalVideoThumbnails {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 10).toInt().coerceAtLeast(4096)
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun peek(uri: String): android.graphics.Bitmap? = cache.get(uri)

    suspend fun load(context: Context, uriString: String): android.graphics.Bitmap? {
        cache.get(uriString)?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ensureActive()
            cache.get(uriString)?.let { return@withContext it }
            val bmp = decode(context, Uri.parse(uriString))
            if (bmp != null) cache.put(uriString, bmp)
            bmp
        }
    }

    private fun decode(context: Context, uri: Uri): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(480, 270), null)
            }.getOrNull()?.let { return it }
        }
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
            } else {
                retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@Composable fun Thumbnail(video: VideoItem, modifier: Modifier = Modifier, context: Context = LocalContext.current) {
    val initial: ImageBitmap? = remember(video.uri) {
        if (video.accessible && video.source != "ktor-remote") LocalVideoThumbnails.peek(video.uri)?.asImageBitmap() else null
    }
    var bitmap by remember(video.uri) { mutableStateOf(initial) }
    if (bitmap == null && video.accessible && video.source != "ktor-remote") {
        LaunchedEffect(video.uri) {
            bitmap = LocalVideoThumbnails.load(context, video.uri)?.asImageBitmap()
        }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .2f), modifier = Modifier.size(26.dp))
        bitmap?.let { img ->
            Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable private fun VideoCard(
    context: Context,
    video: VideoItem,
    isSelected: () -> Boolean,
    selecting: Boolean,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onHide: () -> Unit,
    onQueue: () -> Unit,
    onPlayWithoutHistory: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEncrypt: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val selected = isSelected()
    val selectedState = stringResource(if (selected) R.string.state_selected else R.string.state_unselected)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .testTag("video-${video.id.value}")
            .semantics { if (selecting || hidden) stateDescription = selectedState },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            Thumbnail(video, context = context)
            Surface(color = Color.Black.copy(alpha = .6f), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                Text(durationText(video.durationMs), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
            if (selecting || hidden) {
                Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    Surface(color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .45f), shape = CircleShape, modifier = Modifier.size(22.dp)) {
                        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp))
                        else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White, modifier = Modifier.padding(2.dp))
                    }
                }
            } else if (video.favorite) {
                Icon(
                    Icons.Filled.FilledFavorite,
                    stringResource(R.string.action_unfavorite),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(15.dp).clickable(onClick = onFavorite),
                )
            }
            WatchProgress(video, Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    if (!video.accessible) stringResource(R.string.status_regrant_needed) else "${video.folder} · ${fileSize(video.sizeBytes)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (!video.accessible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        stringResource(R.string.action_more_for_video, video.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                        modifier = Modifier.size(15.dp),
                    )
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(12.dp)) {
                    if (!hidden) {
                        DropdownMenuItem(text = { Text(stringResource(if (video.favorite) R.string.action_unfavorite else R.string.action_favorite_video)) }, onClick = { menu = false; onFavorite() }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_play_alone)) }, onClick = { menu = false; onQueue() }, leadingIcon = { Icon(Icons.Outlined.PlayCircleOutline, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_watch_without_history)) }, onClick = { menu = false; onPlayWithoutHistory() }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
                        if (video.source != "ktor-remote") {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_move_to_folder)) }, onClick = { menu = false; onMove() }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_move_to_vault)) }, onClick = { menu = false; onEncrypt() }, leadingIcon = { Icon(Icons.Outlined.Lock, null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_original), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
                        }
                    }
                    DropdownMenuItem(text = { Text(stringResource(if (hidden) R.string.action_restore_visible else R.string.action_hide_video)) }, onClick = { menu = false; onHide() }, leadingIcon = { Icon(if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) })
                }
            }
        }
    }
}

@Composable fun RenameVideoDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    val trimmed = text.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 80 && trimmed.none { it == '\n' || it == '\r' }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.rename_dialog_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(text, { text = it.take(80) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text(stringResource(R.string.rename_field_label)) }, singleLine = true, isError = text.isNotEmpty() && !valid)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable fun EmptyState(title: String, message: String, onImport: (() -> Unit)? = null, onAccess: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.VideoLibrary, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp)) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onImport != null) Button(onClick = onImport, modifier = Modifier.padding(top = 20.dp).heightIn(min = 42.dp), shape = CircleShape) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_open_local_video)) }
        if (onAccess != null) TextButton(onClick = onAccess, modifier = Modifier.heightIn(min = 42.dp)) { Text(stringResource(R.string.access_allow_gallery)) }
    }
}

@Composable private fun FolderPage(
    vm: AppViewModel,
    videos: List<VideoItem>,
    managed: List<StorageFolder>,
    keys: Map<String, Set<String>>,
    syncState: cn.yingxia.player.sync.SyncStatusState,
    onFolder: (String) -> Unit,
    onOpenSpaceSync: () -> Unit,
    onEncryptFolder: (List<VideoItem>) -> Unit,
) {
    val context = LocalContext.current
    val localVideos = remember(videos) { videos.filter { it.source != "ktor-remote" } }
    val remoteVideos = remember(videos) { videos.filter { it.source == "ktor-remote" } }
    val folders = remember(localVideos, managed, keys) {
        localVideos.filterNot { video ->
            managed.any { target -> video.folderKey in keys[target.uri].orEmpty() || belongsToFolder(video, target.uri) }
        }.groupBy { it.folderKey }.entries.sortedBy { it.value.first().folder }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "local-header", contentType = "section-header") {
            Text(stringResource(R.string.folder_section_local), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 2.dp))
        }
        items(managed, key = { "managed:${it.uri}" }, contentType = { "managed-folder" }) { target ->
            val folderVideos = localVideos.filter { it.folderKey in keys[target.uri].orEmpty() || belongsToFolder(it, target.uri) }
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { onFolder(target.uri) }.padding(start = 14.dp, top = 11.dp, bottom = 11.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.FolderOpen, size = 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(target.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.folder_managed_count, folderVideos.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (folderVideos.isNotEmpty()) {
                        IconButton(onClick = { onEncryptFolder(folderVideos) }) {
                            Icon(Icons.Outlined.Lock, stringResource(R.string.folder_action_encrypt, target.name), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(19.dp))
                        }
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (folders.isEmpty() && managed.isEmpty()) {
            item(key = "folders-empty", contentType = "empty") {
                EmptyState(stringResource(R.string.folder_empty_title), stringResource(R.string.folder_empty_desc))
            }
        }
        items(folders, key = { it.key }, contentType = { "folder-row" }) { group ->
            val folderName = group.value.first().folder
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { onFolder(group.key) }.padding(start = 12.dp, top = 11.dp, bottom = 11.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = group.value.firstOrNull { it.accessible }
                    Box(Modifier.width(64.dp).height(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                        if (cover != null) Thumbnail(cover, context = context)
                        else Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(folderName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.folder_video_count_size, group.value.size, fileSize(group.value.sumOf { it.sizeBytes })), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                    IconButton(onClick = { onEncryptFolder(group.value) }, modifier = Modifier.testTag("folder-encrypt-${group.key}")) {
                        Icon(Icons.Outlined.Lock, stringResource(R.string.folder_action_encrypt, folderName), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(19.dp))
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Sync-edition only: Self-Hosted Ktor HTTP 206 Streaming Directory
        if (vm.graph.sync.enabled) {
            item(key = "remote-section", contentType = "remote-card") {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.folder_section_remote), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(stringResource(R.string.folder_remote_badge), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                    Surface(
                        onClick = {
                            if (syncState.configured && remoteVideos.isNotEmpty()) onFolder("ktor:remote")
                            else onOpenSpaceSync()
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Outlined.Dns, size = 44.dp, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Ktor NAS · Private Stream", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (syncState.configured) stringResource(R.string.folder_remote_configured_desc, syncState.serverUrl, remoteVideos.size)
                                    else stringResource(R.string.folder_remote_unconfigured),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SpacePage(
    vm: AppViewModel,
    syncState: cn.yingxia.player.sync.SyncStatusState,
    onPage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vaultState by vm.graph.vault.state.collectAsStateWithLifecycle()
    var configDialog by remember { mutableStateOf(false) }
    var passphraseMode by remember { mutableStateOf<String?>(null) } // "push", "pull", or "vault-restore"
    val vaultStatusLabel = stringResource(
        when {
            !vaultState.configured -> R.string.space_vault_unconfigured
            vaultState.biometricEnabled -> R.string.space_vault_biometric_ready
            else -> R.string.space_vault_locked
        }
    )

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Hero Obsidian Encrypted Vault Card
        item(key = "space-vault-hero", contentType = "vault-hero") {
            Surface(
                onClick = { onPage("vault") },
                shape = RoundedCornerShape(18.dp),
                color = ObsidianSurface,
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = VaultEmeraldContainerDark, modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Lock, null, tint = VaultEmeraldBright, modifier = Modifier.size(19.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(stringResource(R.string.profile_vault_title), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.space_vault_subtitle), color = Color(0xFF9CA3AF), fontSize = 11.sp)
                        }
                        Surface(shape = CircleShape, color = VaultEmeraldContainerDark) {
                            Text(vaultStatusLabel, color = VaultEmeraldBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.space_vault_security_note), color = Color(0xFF9CA3AF), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Button(
                            onClick = { onPage("vault") },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = SlateAccent, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 34.dp),
                        ) {
                            Text(stringResource(R.string.space_vault_open_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Sync Edition Only: Private Ktor Zero-Knowledge Sync Card (physically absent in offline flavor!)
        if (vm.graph.sync.enabled) {
            item(key = "space-sync-card", contentType = "sync-card") {
                SectionCard(padding = 16.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.space_sync_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Text(stringResource(R.string.space_sync_only_badge), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        stringResource(R.string.space_sync_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            syncState.statusMessage ?: if (syncState.configured) stringResource(R.string.space_sync_last_time, syncState.remoteMediaCount) else stringResource(R.string.space_sync_not_configured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { configDialog = true }, enabled = !syncState.busy) {
                            Text(stringResource(R.string.space_sync_configure), fontSize = 12.sp)
                        }
                    }
                    if (syncState.configured) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(onClick = { passphraseMode = "push" }, enabled = !syncState.busy, shape = CircleShape, modifier = Modifier.heightIn(min = 36.dp)) {
                                Text(stringResource(R.string.space_sync_push), fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { passphraseMode = "pull" }, enabled = !syncState.busy, shape = CircleShape, modifier = Modifier.heightIn(min = 36.dp)) {
                                Text(stringResource(R.string.space_sync_pull), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        vm.graph.sync.backupVaultBlobs()
                                            .onSuccess { count -> vm.message(context.getString(R.string.space_sync_vault_cold_ok, count)) }
                                            .onFailure { err -> vm.message(err.message ?: context.getString(R.string.space_sync_failed)) }
                                    }
                                },
                                enabled = !syncState.busy,
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 36.dp),
                            ) {
                                Text(stringResource(R.string.space_sync_vault_cold), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { passphraseMode = "vault-restore" },
                                enabled = !syncState.busy,
                                shape = CircleShape,
                                modifier = Modifier.heightIn(min = 36.dp),
                            ) {
                                Text(stringResource(R.string.space_sync_vault_restore), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3. Monochrome Minimalist Utility List (No Candy Colors!)
        item(key = "space-utilities", contentType = "utility-list") {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    MonochromeMenuRow(Icons.Outlined.VisibilityOff, stringResource(R.string.title_hidden_manager), stringResource(R.string.profile_hidden_desc)) { onPage("hidden") }
                    MenuDivider()
                    MonochromeMenuRow(Icons.AutoMirrored.Outlined.FactCheck, stringResource(R.string.title_scan), stringResource(R.string.profile_scan_desc)) { onPage("scan") }
                    MenuDivider()
                    MonochromeMenuRow(Icons.Outlined.Inventory2, stringResource(R.string.title_backup), stringResource(R.string.profile_backup_desc)) { onPage("backup") }
                    MenuDivider()
                    MonochromeMenuRow(Icons.Outlined.Tune, stringResource(R.string.title_settings), stringResource(R.string.profile_settings_desc)) { onPage("settings") }
                }
            }
        }

        item(key = "space-footer", contentType = "footer") {
            Text(
                stringResource(R.string.profile_footer, BuildConfig.VERSION_NAME),
                Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (configDialog) {
        var url by remember { mutableStateOf(syncState.serverUrl.ifBlank { "https://192.168.1.10:8443" }) }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { configDialog = false },
            title = { Text(stringResource(R.string.space_sync_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(url, { url = it.trim() }, label = { Text(stringResource(R.string.space_sync_url_label)) }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it.trim() }, label = { Text(stringResource(R.string.space_sync_token_label)) }, singleLine = true, shape = RoundedCornerShape(12.dp), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.startsWith("http://") || url.startsWith("https://"),
                    onClick = {
                        configDialog = false
                        scope.launch {
                            runCatching { vm.graph.sync.updateConfig(url, token) }
                                .onSuccess {
                                    vm.graph.sync.refreshRemoteMedia()
                                        .onFailure { err -> vm.message(err.message ?: context.getString(R.string.space_sync_failed)) }
                                    vm.refresh()
                                }
                                .onFailure { err ->
                                    vm.message(err.message ?: context.getString(R.string.space_sync_failed))
                                }
                        }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { configDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    passphraseMode?.let { mode ->
        var pass by remember { mutableStateOf("") }
        val isVaultRestore = mode == "vault-restore"
        AlertDialog(
            onDismissRequest = { passphraseMode = null },
            title = { Text(stringResource(if (isVaultRestore) R.string.space_sync_vault_restore_title else R.string.space_sync_passphrase_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(if (isVaultRestore) R.string.space_sync_vault_restore_desc else R.string.space_sync_passphrase_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it.take(128) },
                        label = { Text(stringResource(if (isVaultRestore) R.string.vault_password_label else R.string.space_sync_passphrase_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pass.length >= 8,
                    onClick = {
                        val chars = pass.toCharArray()
                        pass = ""
                        passphraseMode = null
                        scope.launch {
                            when (mode) {
                                "push" -> vm.graph.sync.pushEncryptedSnapshot(chars)
                                    .onSuccess { vm.graph.sync.state.value.statusMessage?.let(vm::message) }
                                    .onFailure { vm.message(it.message ?: context.getString(R.string.space_sync_failed)) }
                                "vault-restore" -> vm.graph.sync.restoreVaultBlobs(chars)
                                    .onSuccess { count -> vm.message(context.getString(R.string.space_sync_vault_restore_ok, count)) }
                                    .onFailure { vm.message(it.message ?: context.getString(R.string.space_sync_failed)) }
                                else -> vm.graph.sync.pullEncryptedSnapshot(chars)
                                    .onSuccess {
                                        vm.refresh()
                                        vm.graph.sync.state.value.statusMessage?.let(vm::message)
                                    }
                                    .onFailure { vm.message(it.message ?: context.getString(R.string.space_sync_failed)) }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { passphraseMode = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable private fun MenuDivider() {
    HorizontalDivider(Modifier.padding(start = 62.dp, end = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun MonochromeMenuRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 36.dp, container = MaterialTheme.colorScheme.surfaceContainerLow, tint = MaterialTheme.colorScheme.onSurface)
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
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }
    var currentLang by remember { mutableStateOf(AppLocale.current(context)) }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "set-lang", contentType = "card") {
            SectionCard(padding = 16.dp) {
                SettingsTitle(stringResource(R.string.settings_language))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = currentLang == lang,
                            onClick = {
                                if (currentLang != lang) {
                                    currentLang = lang
                                    AppLocale.set(context, lang)
                                    activity?.recreate()
                                }
                            },
                            label = {
                                Text(stringResource(when (lang) {
                                    AppLanguage.SYSTEM -> R.string.settings_language_system
                                    AppLanguage.ZH -> R.string.settings_language_zh
                                    AppLanguage.EN -> R.string.settings_language_en
                                }))
                            },
                            shape = CircleShape,
                        )
                    }
                }
            }
        }
        item(key = "set-theme", contentType = "card") {
            SectionCard(padding = 16.dp) {
                SettingsTitle(stringResource(R.string.settings_appearance))
                FlowRow {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            settings.theme == mode,
                            { scope.launch { vm.graph.settings.setTheme(mode) } },
                            label = { Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })) },
                            shape = CircleShape,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        }
        item(key = "set-speed", contentType = "card") {
            SectionCard(padding = 16.dp) {
                SettingsTitle(stringResource(R.string.settings_default_speed))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaybackRules.speeds.forEach { speed -> FilterChip(settings.speed == speed, { vm.command(PlayerCommand.SetSpeed(speed)) }, label = { Text(speedText(speed)) }, shape = CircleShape) }
                }
                Spacer(Modifier.height(12.dp))
                SettingsTitle(stringResource(R.string.settings_hold_speed))
                Row {
                    listOf(2f, 3f).forEach { speed -> FilterChip(settings.holdSpeed == speed, { scope.launch { vm.graph.settings.setHoldSpeed(speed) } }, label = { Text(speedText(speed)) }, shape = CircleShape, modifier = Modifier.padding(end = 8.dp)) }
                }
                Text(stringResource(R.string.settings_hold_speed_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "set-guard", contentType = "card") {
            SectionCard(padding = 16.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.settings_touch_guard), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.settings_touch_guard_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.touchGuard, { scope.launch { vm.graph.settings.setTouchGuard(it) } })
                }
            }
        }
        item(key = "set-perms", contentType = "card") {
            SectionCard(padding = 16.dp) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }.onFailure { vm.message(context.getString(R.string.error_action_not_saved)) }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                    shape = CircleShape,
                ) { Text(stringResource(R.string.settings_manage_permissions)) }
                Text(stringResource(R.string.settings_manage_permissions_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item(key = "set-tips", contentType = "card") {
            SectionCard(padding = 16.dp) {
                SettingsTitle(stringResource(R.string.settings_tips_title))
                Text(stringResource(R.string.settings_tips_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "set-clear", contentType = "button") {
            OutlinedButton(
                onClick = { clearConfirm = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_clear_history)) }
        }
    }
    if (clearConfirm) AlertDialog(
        onDismissRequest = { clearConfirm = false },
        title = { Text(stringResource(R.string.settings_clear_history_title)) },
        text = { Text(stringResource(R.string.settings_clear_history_desc)) },
        confirmButton = { TextButton(onClick = { scope.launch { vm.graph.repository.clearHistory() }; vm.message(context.getString(R.string.settings_clear_history_done)); clearConfirm = false }) { Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable private fun SettingsTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable private fun BookmarksPage(vm: AppViewModel, videos: List<VideoItem>, bookmarks: List<Bookmark>, searchFilter: String = "") {
    val context = LocalContext.current
    val byId = remember(videos) { videos.associateBy { it.id } }
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<Bookmark?>(null) }
    var note by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    val untitled = stringResource(R.string.bookmark_untitled)
    val visibleBookmarks = remember(bookmarks, byId, searchFilter) {
        bookmarks.mapNotNull { bookmark -> byId[bookmark.videoId]?.let { bookmark to it } }
            .filter { (bm, vid) ->
                searchFilter.isBlank() || bm.note.contains(searchFilter, ignoreCase = true) || matchesLibraryQuery(vid.displayTitle(), vid.name, vid.folder, searchFilter)
            }
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (visibleBookmarks.isEmpty()) item(key = "bm-empty", contentType = "empty") { EmptyState(stringResource(R.string.bookmarks_empty_title), stringResource(R.string.bookmarks_empty_desc)) }
        items(visibleBookmarks, key = { it.first.id }, contentType = { "bookmark-row" }) { (bookmark, video) ->
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { vm.open(video, videos, bookmark.positionMs) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Text(durationText(bookmark.positionMs), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(bookmark.note.ifBlank { untitled }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(video.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { edit = bookmark; note = bookmark.note }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Edit, stringResource(R.string.bookmark_edit), modifier = Modifier.size(19.dp)) }
                    IconButton(onClick = { pendingDelete = bookmark }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.bookmark_delete), modifier = Modifier.size(19.dp)) }
                }
            }
        }
    }
    edit?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(stringResource(R.string.bookmark_edit)) },
            text = { OutlinedTextField(note, { if (it.length <= 200) note = it }, shape = RoundedCornerShape(12.dp), label = { Text(stringResource(R.string.bookmark_note)) }) },
            confirmButton = { TextButton(onClick = { scope.launch { runCatching { vm.graph.repository.saveBookmark(bookmark.videoId, bookmark.positionMs, note, bookmark.id) }.onFailure { vm.message(it.message ?: context.getString(R.string.error_save_failed)) } }; edit = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    pendingDelete?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.bookmark_delete_title)) },
            text = { Text(stringResource(R.string.bookmark_delete_desc, bookmark.note.ifBlank { untitled }, durationText(bookmark.positionMs))) },
            confirmButton = { TextButton(onClick = { scope.launch { vm.graph.repository.deleteBookmark(bookmark.id) }; pendingDelete = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
