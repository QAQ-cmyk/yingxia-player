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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import cn.yingxia.player.AppLanguage
import cn.yingxia.player.AppLocale
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.BuildConfig
import cn.yingxia.player.R
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
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var page by rememberSaveable { mutableStateOf("") }
        var folder by rememberSaveable { mutableStateOf<String?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        var filter by rememberSaveable { mutableIntStateOf(0) }
        var historyOrder by rememberSaveable { mutableStateOf(SortOrder.RECENT_PLAY) }
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
                    if (library) key(homeGeneration) {
                        LibraryScaffold(
                            vm = vm,
                            tab = tab, onTab = { tab = it },
                            page = page, onPage = { page = it },
                            folder = folder, onFolder = { folder = it },
                            query = query, onQuery = { query = it },
                            filter = filter, onFilter = { filter = it },
                            historyOrder = historyOrder, onHistoryOrder = { historyOrder = it },
                            onNavigationVisible = { libraryNav = it },
                        )
                    }
                    else key(player.video?.source == "share") { PlayerPage(vm, player, settings) }
                }
                FileOperationHost(vm)
                VaultHost(vm, onLockedToHome = {
                    tab = 0; page = ""; folder = null; query = ""; filter = 0
                    homeGeneration++
                })
                GalleryShareDialog(vm)
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = snackbarLift))
            }
        }
    }
}

@Composable private fun GalleryShareDialog(vm: AppViewModel) {
    val pending by vm.share.collectAsStateWithLifecycle()
    val share = pending ?: return
    AlertDialog(onDismissRequest = vm::dismissShare, title = { Text(stringResource(R.string.share_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    share.error != null -> Text(share.error)
                    share.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.share_loading)) }
                    else -> {
                        Text(if (share.uris.size == 1) stringResource(R.string.share_choose_single) else stringResource(R.string.share_selected_count, share.uris.size))
                        if (share.uris.size == 1) Button(onClick = { vm.useShare(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_watch_without_history)) }
                        OutlinedButton(onClick = { vm.useShare(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.share_action_vault)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::dismissShare) { Text(stringResource(if (share.error != null) R.string.action_got_it else R.string.action_cancel)) } })
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
) {
    val videos by vm.videos.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val scanning by vm.graph.repository.scanning.collectAsStateWithLifecycle()
    val access by vm.graph.repository.access.collectAsStateWithLifecycle()
    val scanError by vm.graph.repository.scanError.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val order = settings.sort
    val effectiveOrder = if (page == "history") historyOrder else order
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
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
    val showSearch = page != "hidden" && page != "settings" && page != "bookmarks" && page != "backup" && page != "scan" && !(tab == 3 && page.isEmpty()) && !(tab == 1 && folder == null && page.isEmpty())
    val hideNavForKeyboard = showSearch && searchFocused && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    SideEffect { onNavigationVisible(!hideNavForKeyboard) }
    DisposableEffect(Unit) { onDispose { onNavigationVisible(true) } }
    BackHandler(selected.isNotEmpty() || query.isNotEmpty() || searchFocused || page.isNotEmpty() || folder != null) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            query.isNotEmpty() || searchFocused -> { onQuery(""); searchFocused = false; focusManager.clearFocus() }
            folder != null -> onFolder(null)
            else -> onPage("")
        }
    }
    val current = when {
        page == "hidden" -> hidden
        page == "history" -> videos.filter { it.lastPlayedAt > 0 }
        tab == 2 -> videos.filter { it.favorite }
        folder != null -> videos.filter { it.folderKey == folder || it.folderKey in managedKeys[folder].orEmpty() || belongsToFolder(it, folder!!) }
        else -> videos
    }
    val searchMatched = remember(current, query) {
        current.filter { matchesLibraryQuery(it.displayTitle(), it.name, it.folder, query) }
    }
    val visible = remember(searchMatched, effectiveOrder, filter, page, tab) {
        val filtered = searchMatched
            .filter { tab != 0 || page.isNotEmpty() || filter != 2 || it.positionMs > 0 && it.durationMs - it.positionMs > 5000 }
        effectiveOrder.apply(filtered)
    }
    LaunchedEffect(visible) {
        if (selected.isNotEmpty()) {
            val visibleIds = visible.mapTo(HashSet()) { it.id }
            val pruned = selected.filterTo(LinkedHashSet()) { it in visibleIds }
            if (pruned.size != selected.size) selected = pruned
        }
    }
    val titles = listOf(
        stringResource(R.string.nav_home),
        stringResource(R.string.nav_folders),
        stringResource(R.string.nav_favorites),
        stringResource(R.string.nav_profile),
    )
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Folder, Icons.Outlined.FavoriteBorder, Icons.Outlined.PersonOutline)
    val selectedIcons = listOf(Icons.Filled.FilledHome, Icons.Filled.FilledFolder, Icons.Filled.FilledFavorite, Icons.Filled.FilledPerson)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!hideNavForKeyboard) Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    titles.forEachIndexed { i, title ->
                        NavigationBarItem(selected = tab == i, onClick = { onTab(i); onPage(""); onFolder(null); onQuery(""); selected = emptySet(); onFilter(0) },
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
            val nestedTitle = when (page) {
                "hidden" -> stringResource(R.string.title_hidden_manager)
                "history" -> stringResource(R.string.title_history)
                "bookmarks" -> stringResource(R.string.title_bookmarks)
                "settings" -> stringResource(R.string.title_settings)
                "backup" -> stringResource(R.string.title_backup)
                "scan" -> stringResource(R.string.title_scan)
                else -> if (folder != null) managedFolders.firstOrNull { it.uri == folder }?.name ?: current.firstOrNull()?.folder ?: stringResource(R.string.title_folder_fallback) else ""
            }
            val topLevel = selected.isEmpty() && page.isEmpty() && folder == null
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (topLevel) 16.dp else 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.isNotEmpty() || folder != null) IconButton(onClick = { onPage(""); onFolder(null); selected = emptySet(); onQuery("") }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                Text(if (selected.isNotEmpty()) stringResource(R.string.title_selected_count, selected.size) else nestedTitle.ifEmpty { if (tab == 0) stringResource(R.string.app_name) else titles[tab] },
                    style = if (topLevel) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_cancel)) }
                else {
                    if (folder != null && page.isEmpty() && visible.isNotEmpty()) {
                        IconButton(onClick = { encryptBatch(visible) }, modifier = Modifier.testTag("folder-encrypt-current")) {
                            Icon(Icons.Outlined.Lock, stringResource(R.string.folder_action_encrypt_current), tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    IconButton(onClick = importFile) { Icon(Icons.Outlined.Add, stringResource(R.string.action_open_local_video), tint = MaterialTheme.colorScheme.onSurface) }
                }
            }
            if (selected.isNotEmpty()) {
                val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selected }
                val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                val dangerColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selected = if (allVisibleSelected) selected - visible.map { it.id }.toSet() else selected + visible.map { it.id }
                    }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(if (allVisibleSelected) R.string.action_deselect_all else R.string.action_select_all)) }
                    if (page == "hidden") TextButton(onClick = { vm.hide(selected, false); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(R.string.action_restore_visible)) }
                    else {
                        if (selected.size == 1) TextButton(onClick = {
                            visible.firstOrNull { it.id in selected }?.let { vm.open(it, listOf(it), recordHistory = false) }
                            selected = emptySet()
                        }, colors = actionColors) { Text(stringResource(R.string.action_watch_without_history)) }
                        TextButton(onClick = { vm.favorite(selected, true); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(R.string.action_favorite)) }
                        TextButton(onClick = { vm.favorite(selected, false); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(R.string.action_unfavorite)) }
                        TextButton(onClick = {
                            val chosen = visible.filter { it.id in selected }
                            val targets = chosen.filter { it.accessible }
                            selected = emptySet()
                            when {
                                targets.isEmpty() -> vm.message(context.getString(R.string.notice_need_regrant_all))
                                targets.size < chosen.size -> { vm.message(context.getString(R.string.notice_skipped_inaccessible)); vm.command(PlayerCommand.Enqueue(targets.map { it.id })) }
                                else -> vm.command(PlayerCommand.Enqueue(targets.map { it.id }))
                            }
                        }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(R.string.action_play_selected)) }
                        TextButton(onClick = { vm.hide(selected, true); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(R.string.action_hide)) }
                        TextButton(onClick = { encryptBatch(visible.filter { it.id in selected }); selected = emptySet() }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(R.string.action_encrypt)) }
                        TextButton(onClick = { val picked = visible.filter { it.id in selected }; if (picked.isNotEmpty()) { moving = true; fileItems = picked } }, modifier = Modifier.heightIn(min = 42.dp), colors = actionColors) { Text(stringResource(R.string.action_move)) }
                        TextButton(onClick = { val picked = visible.filter { it.id in selected }; if (picked.isNotEmpty()) { moving = false; fileItems = picked } }, modifier = Modifier.heightIn(min = 42.dp), colors = dangerColors) { Text(stringResource(R.string.action_delete)) }
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
                "tab3" -> ProfilePage(videos, bookmarks.size, { if (it == "vault") vm.graph.vault.show() else onPage(it) }, importFile)
                "tab1" -> FolderPage(videos, managedFolders, managedKeys, { newFolder = true }, { onFolder(it); onQuery("") }, encryptBatch)
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
                        SearchField(query, onQuery, { searchFocused = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    } else Text(stringResource(R.string.hidden_banner_hint), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    VideoGrid(vm, visible, searchMatched.size, videos, access, scanning, scanError, effectiveOrder, { next ->
                        if (page == "history") onHistoryOrder(next) else scope.launch { vm.graph.settings.setSort(next) }
                    }, query,
                        selected, { selected = it }, listKind, currentScreen == "tab0" && query.isBlank() && filter != 2,
                        filter, onFilter, currentScreen == "tab0",
                        requestAccess, importFile, { video, move -> moving = move; fileItems = listOf(video) }, { renaming = it })
                    }
                }
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
        modifier = modifier.fillMaxWidth().height(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("library-search").onFocusChanged { onFocus(it.isFocused) },
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_placeholder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.search_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { onQuery(""); focusManager.clearFocus() })
                }
            }
        }
    )
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

@Composable private fun VideoGrid(vm: AppViewModel, items: List<VideoItem>, totalMatchingCount: Int, all: List<VideoItem>, access: LibraryAccess,
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
                    Text(stringResource(if (access == LibraryAccess.PARTIAL) R.string.access_partial_banner else R.string.access_none_banner), modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = requestAccess) { Text(stringResource(if (access == LibraryAccess.PARTIAL) R.string.access_manage else R.string.access_grant)) }
                }
            }
        }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::refresh) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
        if (showContinue && last != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(onClick = { vm.open(last, all) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FilledPlayArrow, stringResource(R.string.continue_watching_icon), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.continue_watching_prefix), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                    Text(last.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(durationText(last.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val section = stringResource(when (listKind) {
                "hidden" -> R.string.section_hidden
                "history" -> R.string.section_history
                "favorites" -> R.string.section_favorites
                "folder" -> R.string.section_folder_videos
                else -> R.string.section_my_videos
            })
            val allLabel = stringResource(R.string.filter_all_videos)
            val inProgressLabel = stringResource(R.string.filter_in_progress)
            Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showFilterPills) {
                    listOf(0 to allLabel, 2 to inProgressLabel).forEach { (index, label) ->
                        val on = if (index == 0) filter != 2 else filter == 2
                        Column(
                            Modifier.padding(end = 18.dp).clip(RoundedCornerShape(6.dp)).clickable { onFilter(index) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(if (index == 0) "$label $totalMatchingCount" else label,
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
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.action_refresh_videos), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
        if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            val (emptyTitleRes, emptyMessageRes, allowImport) = when {
                scanning -> Triple(R.string.empty_scanning_title, R.string.empty_scanning_desc, false)
                query.isNotEmpty() -> Triple(R.string.empty_search_title, R.string.empty_search_desc, false)
                hidden -> Triple(R.string.empty_hidden_title, R.string.empty_hidden_desc, false)
                history -> Triple(R.string.empty_history_title, R.string.empty_history_desc, false)
                listKind == "favorites" -> Triple(R.string.empty_favorites_title, R.string.empty_favorites_desc, false)
                listKind == "folder" -> Triple(R.string.empty_folder_title, R.string.empty_folder_desc, false)
                listKind == "videos" && filter == 2 -> Triple(R.string.empty_in_progress_title, R.string.empty_in_progress_desc, false)
                else -> Triple(R.string.empty_library_title, R.string.empty_library_desc, true)
            }
            EmptyState(stringResource(emptyTitleRes), stringResource(emptyMessageRes), if (allowImport) importFile else null, if (listKind == "videos" && access == LibraryAccess.NONE && query.isEmpty() && !scanning) requestAccess else null)
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
    val selectedState = stringResource(if (selected) R.string.state_selected else R.string.state_unselected)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).testTag("video-${video.id.value}")
        .semantics { if (selecting || hidden) stateDescription = selectedState }) {
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
                Icon(Icons.Filled.FilledFavorite, stringResource(R.string.action_unfavorite), tint = BrandPink,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp).clickable(onClick = onFavorite))
            }
            WatchProgress(video, Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(if (!video.accessible) stringResource(R.string.status_regrant_needed) else "${video.folder} · ${fileSize(video.sizeBytes)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (!video.accessible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more_for_video, video.name), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(12.dp)) {
                    if (!hidden) {
                        DropdownMenuItem(text = { Text(stringResource(if (video.favorite) R.string.action_unfavorite else R.string.action_favorite_video)) }, onClick = { menu = false; onFavorite() }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_play_alone)) }, onClick = { menu = false; onQueue() }, leadingIcon = { Icon(Icons.Outlined.PlayCircleOutline, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_watch_without_history)) }, onClick = { menu = false; onPlayWithoutHistory() }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_move_to_folder)) }, onClick = { menu = false; onMove() }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_move_to_vault)) }, onClick = { menu = false; onEncrypt() }, leadingIcon = { Icon(Icons.Outlined.Lock, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_original), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.rename_dialog_title)) },
        text = { Column {
            Text(stringResource(R.string.rename_dialog_desc), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it.take(80) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text(stringResource(R.string.rename_field_label)) }, singleLine = true, isError = text.isNotEmpty() && !valid)
        } },
        confirmButton = { TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable fun EmptyState(title: String, message: String, onImport: (() -> Unit)? = null, onAccess: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(68.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onImport != null) Button(onClick = onImport, modifier = Modifier.padding(top = 20.dp).heightIn(min = 42.dp), shape = CircleShape) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_open_local_video)) }
        if (onAccess != null) TextButton(onClick = onAccess, modifier = Modifier.heightIn(min = 42.dp)) { Text(stringResource(R.string.access_allow_gallery)) }
    }
}

@Composable private fun FolderPage(
    videos: List<VideoItem>,
    managed: List<StorageFolder>,
    keys: Map<String, Set<String>>,
    onCreate: () -> Unit,
    onFolder: (String) -> Unit,
    onEncryptFolder: (List<VideoItem>) -> Unit,
) {
    val folders = videos.filterNot { video -> managed.any { target ->
        video.folderKey in keys[target.uri].orEmpty() || belongsToFolder(video, target.uri)
    } }.groupBy { it.folderKey }.entries.sortedBy { it.value.first().folder }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.folder_count_total, managed.size + folders.size), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCreate) { Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.folder_new)) }
            }
        }
        items(managed, key = { "managed:${it.uri}" }) { target ->
            val folderVideos = videos.filter { it.folderKey in keys[target.uri].orEmpty() || belongsToFolder(it, target.uri) }
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { onFolder(target.uri) }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.FolderOpen, size = 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(target.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.folder_managed_count, folderVideos.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (folderVideos.isNotEmpty()) {
                        IconButton(onClick = { onEncryptFolder(folderVideos) }) {
                            Icon(Icons.Outlined.Lock, stringResource(R.string.folder_action_encrypt, target.name), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (folders.isEmpty() && managed.isEmpty()) item { EmptyState(stringResource(R.string.folder_empty_title), stringResource(R.string.folder_empty_desc)) }
        items(folders, key = { it.key }) { group ->
            val folderName = group.value.first().folder
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { onFolder(group.key) }.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = group.value.firstOrNull { it.accessible }
                    Box(Modifier.width(76.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                        if (cover != null) Thumbnail(cover) else Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(folderName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.folder_video_count_size, group.value.size, fileSize(group.value.sumOf { it.sizeBytes })), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                    }
                    IconButton(onClick = { onEncryptFolder(group.value) }, modifier = Modifier.testTag("folder-encrypt-${group.key}")) {
                        Icon(Icons.Outlined.Lock, stringResource(R.string.folder_action_encrypt, folderName), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
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
                    ProfileStat(videos.size, stringResource(R.string.stat_local_videos))
                    ProfileStat(videos.count { it.favorite }, stringResource(R.string.stat_favorites))
                    ProfileStat(bookmarkCount, stringResource(R.string.stat_bookmarks))
                }
            }
        }
        item {
            SectionCard {
                MenuRow(Icons.Outlined.History, stringResource(R.string.title_history), stringResource(R.string.profile_history_desc), { onPage("history") }, BrandPink)
                MenuDivider()
                MenuRow(Icons.Outlined.Bookmarks, stringResource(R.string.title_bookmarks), stringResource(R.string.profile_bookmarks_desc), { onPage("bookmarks") }, BrandAmber)
                MenuDivider()
                MenuRow(Icons.Outlined.VisibilityOff, stringResource(R.string.title_hidden_manager), stringResource(R.string.profile_hidden_desc), { onPage("hidden") }, BrandViolet)
                MenuDivider()
                MenuRow(Icons.Outlined.Lock, stringResource(R.string.profile_vault_title), stringResource(R.string.profile_vault_desc), { onPage("vault") }, BrandViolet)
                MenuDivider()
                MenuRow(Icons.AutoMirrored.Outlined.FactCheck, stringResource(R.string.title_scan), stringResource(R.string.profile_scan_desc), { onPage("scan") }, BrandTeal)
                MenuDivider()
                MenuRow(Icons.Outlined.SaveAlt, stringResource(R.string.title_backup), stringResource(R.string.profile_backup_desc), { onPage("backup") }, BrandBlue)
            }
        }
        item {
            SectionCard {
                MenuRow(Icons.Outlined.Tune, stringResource(R.string.title_settings), stringResource(R.string.profile_settings_desc), { onPage("settings") }, BrandPinkDeep)
                MenuDivider()
                MenuRow(Icons.Outlined.AddCircleOutline, stringResource(R.string.action_open_local_video), stringResource(R.string.profile_import_desc), onImport, BrandTeal)
            }
        }
        item {
            Text(stringResource(R.string.profile_footer, BuildConfig.VERSION_NAME), Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }
    var currentLang by remember { mutableStateOf(AppLocale.current(context)) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
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
        item { SectionCard(padding = 16.dp) { SettingsTitle(stringResource(R.string.settings_appearance)); FlowRow { ThemeMode.entries.forEach { mode -> FilterChip(settings.theme == mode, { scope.launch { vm.graph.settings.setTheme(mode) } }, label = { Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })) }, shape = CircleShape, modifier = Modifier.padding(end = 8.dp)) } } } }
        item { SectionCard(padding = 16.dp) {
            SettingsTitle(stringResource(R.string.settings_default_speed)); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PlaybackRules.speeds.forEach { speed -> FilterChip(settings.speed == speed, { vm.command(PlayerCommand.SetSpeed(speed)) }, label = { Text(speedText(speed)) }, shape = CircleShape) } }
            Spacer(Modifier.height(12.dp))
            SettingsTitle(stringResource(R.string.settings_hold_speed)); Row { listOf(2f, 3f).forEach { speed -> FilterChip(settings.holdSpeed == speed, { scope.launch { vm.graph.settings.setHoldSpeed(speed) } }, label = { Text(speedText(speed)) }, shape = CircleShape, modifier = Modifier.padding(end = 8.dp)) } }; Text(stringResource(R.string.settings_hold_speed_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item {
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
        item { SectionCard(padding = 16.dp) { OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }.onFailure { vm.message(context.getString(R.string.error_action_not_saved)) }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp), shape = CircleShape) { Text(stringResource(R.string.settings_manage_permissions)) }; Text(stringResource(R.string.settings_manage_permissions_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) } }
        item { SectionCard(padding = 16.dp) { SettingsTitle(stringResource(R.string.settings_tips_title)); Text(stringResource(R.string.settings_tips_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { OutlinedButton(onClick = { clearConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp), shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.settings_clear_history)) } }
    }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text(stringResource(R.string.settings_clear_history_title)) }, text = { Text(stringResource(R.string.settings_clear_history_desc)) },
        confirmButton = { TextButton(onClick = { scope.launch { vm.graph.repository.clearHistory() }; vm.message(context.getString(R.string.settings_clear_history_done)); clearConfirm = false }) { Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.action_cancel)) } })
}
@Composable private fun SettingsTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)) }

@Composable private fun BookmarksPage(vm: AppViewModel, videos: List<VideoItem>, bookmarks: List<Bookmark>) {
    val context = LocalContext.current
    val byId = videos.associateBy { it.id }
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<Bookmark?>(null) }
    var note by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    val untitled = stringResource(R.string.bookmark_untitled)
    val visibleBookmarks = remember(bookmarks, byId) {
        bookmarks.mapNotNull { bookmark -> byId[bookmark.videoId]?.let { bookmark to it } }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (visibleBookmarks.isEmpty()) item { EmptyState(stringResource(R.string.bookmarks_empty_title), stringResource(R.string.bookmarks_empty_desc)) }
        items(visibleBookmarks, key = { it.first.id }) { (bookmark, video) ->
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable { vm.open(video, videos, bookmark.positionMs) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Text(durationText(bookmark.positionMs), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(bookmark.note.ifBlank { untitled }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium); Text(video.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = { edit = bookmark; note = bookmark.note }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Edit, stringResource(R.string.bookmark_edit), modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = { pendingDelete = bookmark }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.bookmark_delete), modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
    edit?.let { bookmark -> AlertDialog(onDismissRequest = { edit = null }, title = { Text(stringResource(R.string.bookmark_edit)) },
        text = { OutlinedTextField(note, { if (it.length <= 200) note = it }, label = { Text(stringResource(R.string.bookmark_note)) }) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { vm.graph.repository.saveBookmark(bookmark.videoId, bookmark.positionMs, note, bookmark.id) }.onFailure { vm.message(it.message ?: context.getString(R.string.error_save_failed)) } }; edit = null }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    pendingDelete?.let { bookmark ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(stringResource(R.string.bookmark_delete_title)) },
            text = { Text(stringResource(R.string.bookmark_delete_desc, bookmark.note.ifBlank { untitled }, durationText(bookmark.positionMs))) },
            confirmButton = { TextButton(onClick = { scope.launch { vm.graph.repository.deleteBookmark(bookmark.id) }; pendingDelete = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
