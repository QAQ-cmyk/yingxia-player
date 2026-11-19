@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.yingxia.player.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Favorite as FilledFavorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.R
import cn.yingxia.player.core.*
import cn.yingxia.player.playback.Media3VideoSurface
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class GestureFeedback(val brightness: Boolean, val text: String)

@Composable fun PlayerPage(vm: AppViewModel, state: PlaybackState, settings: UserSettings) {
    val session = remember(vm) { PlayerUiSession.ordinary(vm) }
    PlayerPage(session, state, settings)
}

@Composable fun PlayerPage(vm: PlayerUiSession, state: PlaybackState, settings: UserSettings, onLockVault: (() -> Unit)? = null) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val fullscreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controls by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Long?>(null) }
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var brightness by remember { mutableFloatStateOf(activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: .5f) }
    val audio = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume) }
    var verticalStart by remember { mutableFloatStateOf(0f) }
    val videos by vm.videos.collectAsStateWithLifecycle()
    val video = state.video ?: return
    var zoom by remember(video.id) { mutableFloatStateOf(1f) }
    var panX by remember(video.id) { mutableFloatStateOf(0f) }
    var panY by remember(video.id) { mutableFloatStateOf(0f) }
    var guardDismissed by remember(video.id) { mutableStateOf(false) }
    val shielded = locked || (settings.touchGuard && state.isPlaying && !guardDismissed)
    val shared = video.source == "share"
    val current = if (shared) video else videos.firstOrNull { it.id == video.id } ?: video
    val remaining by vm.timer.remainingMs.collectAsStateWithLifecycle()
    val untilEnd by vm.timer.untilEnd.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    val close = {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        vm.command(PlayerCommand.Close)
    }
    val playRelative = { delta: Int ->
        val queue = state.queue
        val index = queue.indexOf(video.id)
        when {
            index < 0 || queue.isEmpty() -> vm.message(context.getString(R.string.player_no_switchable))
            delta < 0 && index == 0 -> vm.message(context.getString(R.string.player_first_item))
            delta > 0 && index == queue.lastIndex && state.repeatMode != RepeatMode.ALL -> vm.message(context.getString(R.string.player_last_item))
            else -> vm.command(if (delta < 0) PlayerCommand.Previous else PlayerCommand.Next)
        }
    }
    val touchExploration = (activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    val toggleFull = {
        activity.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    BackHandler {
        if (fullscreen) { locked = false; toggleFull() } else close()
    }
    DisposableEffect(activity) {
        val originalBrightness = activity.window.attributes.screenBrightness
        onDispose {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            vm.command(PlayerCommand.EndBoost)
        }
    }
    SideEffect {
        if (state.isPlaying) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(state.video?.id) { preview = null; controls = true; locked = false }
    LaunchedEffect(controls, state.isPlaying, sheet, preview, touchExploration) {
        if (controls && state.isPlaying && sheet == null && preview == null && !touchExploration) { delay(4000); controls = false }
    }
    LaunchedEffect(feedback) { if (feedback != null) { delay(1000); feedback = null } }
    Column(Modifier.fillMaxSize().background(Color.Black).then(if (fullscreen) Modifier else Modifier.statusBarsPadding())) {
        Box(Modifier.fillMaxWidth().then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f)).testTag("player-frame")) {
            Media3VideoSurface(vm.player, current.cropBars, zoom, panX, panY, Modifier.fillMaxSize())
            key(video.id) {
                AndroidView(factory = { PlayerGestureView(it) }, modifier = Modifier.fillMaxSize(), update = { view ->
                    view.snapshot = { latestState }; view.fullscreen = fullscreen; view.locked = shielded; view.holdSpeed = settings.holdSpeed
                    view.zoom = zoom; view.panX = panX; view.panY = panY
                    view.onTransform = { next, x, y -> zoom = next; panX = x; panY = y }
                    view.command = vm::command; view.tap = { controls = !controls }; view.preview = { preview = it }
                    view.vertical = { mode, delta, starting ->
                        if (starting) {
                            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                            verticalStart = if (mode == GestureMode.BRIGHTNESS) brightness else volume
                        } else {
                            val value = (verticalStart + delta * 1.2f).coerceIn(0f, 1f)
                            if (mode == GestureMode.BRIGHTNESS) {
                                brightness = value.coerceAtLeast(.02f)
                                activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                                feedback = GestureFeedback(true, context.getString(R.string.player_brightness_value, (brightness * 100).roundToInt()))
                            } else {
                                volume = value
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVolume).roundToInt(), 0)
                                feedback = GestureFeedback(false, context.getString(R.string.player_volume_value, (volume * 100).roundToInt()))
                            }
                        }
                    }
                })
            }
            if (state.buffering && state.failure == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = BrandPink, strokeWidth = 3.dp)
            if (state.boosting && preview == null && feedback == null) {
                Surface(Modifier.align(androidx.compose.ui.BiasAlignment(0f, -.72f)).testTag("boost-hint"),
                    shape = CircleShape, color = Color.Black.copy(alpha = .55f)) {
                    Text(stringResource(R.string.player_boosting, speedText(state.effectiveSpeed)), Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (preview != null || feedback != null) {
                Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = .7f)) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val hint = when {
                            preview != null -> Icons.Outlined.Schedule
                            feedback?.brightness == true -> Icons.Outlined.LightMode
                            else -> Icons.AutoMirrored.Outlined.VolumeUp
                        }
                        Icon(hint, null, tint = BrandPink, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                        Text(if (preview != null) "${durationText(preview!!)} / ${durationText(state.durationMs)}" else feedback?.text.orEmpty(),
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            val chrome = !shielded && (controls || !state.isPlaying)
            val edge = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) else Modifier
            FrameVisibility(chrome, Modifier.align(Alignment.TopCenter),
                fadeIn(motionTween(180)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 4 },
                fadeOut(motionTween(140)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { -it / 4 }) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent)))
                    .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier).then(edge).padding(start = 2.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), { if (fullscreen) toggleFull() else close() }, size = 22)
                    Text(current.displayTitle(), Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White, fontSize = if (fullscreen) 15.sp else 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!state.recordHistory) Icon(Icons.Outlined.VisibilityOff, stringResource(R.string.action_watch_without_history), tint = Color.White, modifier = Modifier.size(18.dp))
                    if (fullscreen && state.queue.size > 1) {
                        PlayerIcon(Icons.Outlined.SkipPrevious, stringResource(R.string.player_previous), { playRelative(-1) }, size = 20, compact = true)
                        PlayerIcon(Icons.Outlined.SkipNext, stringResource(R.string.player_next), { playRelative(1) }, size = 20, compact = true)
                    }
                    Surface(onClick = { sheet = "speed" }, shape = CircleShape, color = Color.White.copy(alpha = .18f),
                        modifier = Modifier.testTag("speed-button").padding(horizontal = 4.dp).heightIn(min = 28.dp)) {
                        Box(Modifier.heightIn(min = 28.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text(speedText(state.baseSpeed), color = Color.White, maxLines = 1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    PlayerIcon(Icons.Outlined.MoreHoriz, stringResource(R.string.player_tools), { sheet = "tools" }, size = 22)
                }
            }
            FrameVisibility(chrome, Modifier.align(Alignment.BottomCenter),
                fadeIn(motionTween(180)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 5 },
                fadeOut(motionTween(140)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { it / 5 }) {
                Row(Modifier.fillMaxWidth().then(edge)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f))))
                    .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play), { vm.command(PlayerCommand.Toggle); controls = true }, size = 25, compact = true)
                    AndroidView(factory = { CommitSeekBar(it) }, modifier = Modifier.weight(1f).height(32.dp).testTag("seek-bar"), update = {
                        it.durationMs = state.durationMs
                        if (!it.tracking) it.progress = if (state.durationMs > 0) (state.positionMs * it.max / state.durationMs).toInt() else 0
                        it.commit = { target -> vm.command(PlayerCommand.Seek(target)); controls = true }
                        it.preview = { value -> preview = value }
                    })
                    Text("${durationText(state.positionMs)}/${durationText(state.durationMs)}", color = Color.White.copy(alpha = .92f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
                    PlayerIcon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, stringResource(if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen), toggleFull, size = 22, compact = true)
                }
            }
            if (fullscreen || settings.touchGuard || locked) Column(Modifier.align(Alignment.CenterStart).padding(start = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerIcon(if (shielded) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    stringResource(if (shielded) R.string.player_unlock_screen else R.string.player_lock_screen), {
                        if (shielded) { locked = false; guardDismissed = true } else { locked = true; guardDismissed = false }
                        controls = true
                    }, size = 20, filled = true)
                if (shielded) Text(stringResource(if (locked) R.string.player_locked_badge else R.string.player_guard_badge), color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
            state.failure?.let { error ->
                Column(Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha = .82f), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Row(Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = { vm.command(if (shared) PlayerCommand.OpenShared(video) else PlayerCommand.Open(video.id, state.queue, state.positionMs, state.recordHistory)) }) {
                            Text(stringResource(R.string.action_retry), color = BrandPink)
                        }
                        TextButton(onClick = close) { Text(stringResource(R.string.player_back_to_list), color = Color.White) }
                    }
                }
            }
            if (onLockVault != null) {
                TextButton(onClick = onLockVault, modifier = Modifier.align(Alignment.CenterEnd).then(edge).padding(end = 6.dp)
                    .heightIn(min = 48.dp).testTag("lock-vault-now"),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White, containerColor = Color.Black.copy(alpha = .58f))) {
                    Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.player_lock_vault_short), fontSize = 12.sp)
                }
            }
        }
        if (!fullscreen) {
            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.background) {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.navigationBarsPadding()) {
                    item {
                        SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(current.displayTitle(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (!shared) IconButton(onClick = { renameText = current.displayTitle(); renaming = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Edit, stringResource(R.string.action_rename), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                            val encryptedLabel = stringResource(R.string.player_encrypted_video_label)
                            val noHistoryLabel = stringResource(R.string.action_watch_without_history)
                            val meta = buildString {
                                append(if (vm.privateSession) encryptedLabel else video.folder)
                                if (!state.recordHistory) append(" · $noHistoryLabel")
                                append(" · ")
                                append(fileSize(video.sizeBytes))
                                if (video.width > 0 && video.height > 0) append(" · ${video.width}×${video.height}")
                            }
                            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (!shared) ToolShortcut(if (current.favorite) Icons.Filled.FilledFavorite else Icons.Outlined.FavoriteBorder, stringResource(if (current.favorite) R.string.player_favorited else R.string.action_favorite), { vm.favorite(setOf(video.id), !current.favorite) }, current.favorite)
                                ToolShortcut(Icons.Outlined.Replay, stringResource(R.string.player_restart), { vm.command(PlayerCommand.Seek(0)) })
                                if (!shared) ToolShortcut(Icons.Outlined.BookmarkBorder, stringResource(R.string.player_shortcut_bookmark), { sheet = "bookmarks" })
                                ToolShortcut(Icons.Outlined.Tune, stringResource(R.string.player_tools), { sheet = "tools" })
                            }
                        }
                    }
                    if (state.loop != null || remaining > 0) item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loop != null) AssistChip(onClick = { sheet = "loop" }, label = { Text(stringResource(R.string.player_loop_chip)) }, shape = CircleShape)
                            if (remaining > 0) AssistChip(onClick = { sheet = "sleep" }, label = { Text(if (untilEnd) stringResource(R.string.player_sleep_until_end_chip) else stringResource(R.string.player_sleep_remaining_chip, durationText(remaining))) }, shape = CircleShape)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.player_playlist), style = MaterialTheme.typography.titleMedium)
                            Text("  ${state.queue.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (state.queue.size > 1) {
                                IconButton(onClick = { playRelative(-1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton(onClick = { playRelative(1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.SkipNext, stringResource(R.string.player_next), Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            TextButton(onClick = { vm.command(PlayerCommand.SetRepeat(RepeatMode.entries[(state.repeatMode.ordinal + 1) % 3])) }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 34.dp)) {
                                Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, null, Modifier.size(16.dp))
                                Text(" ${stringResource(repeatLabelRes(state.repeatMode))}", fontSize = 12.sp)
                            }
                        }
                    }
                    val queue = if (shared) listOf(video) else state.queue.mapNotNull { id -> videos.firstOrNull { it.id == id } }
                    items(queue, key = { it.id.value }) { item ->
                        val playing = item.id == video.id
                        Surface(
                            onClick = { vm.open(item, queue) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (playing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp))) {
                                    if (vm.privateSession) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else if (!shared) Thumbnail(item, Modifier.fillMaxSize())
                                    if (playing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.GraphicEq, stringResource(R.string.player_now_playing), tint = BrandPink)
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(item.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
                                    Text("${durationText(item.durationMs)} · ${fileSize(item.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val closeSheet = { sheet = null }
        if (onLockVault != null) {
            TextButton(onClick = onLockVault, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.player_lock_vault_home))
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            when (sheet) {
                "speed" -> {
                    PanelTitle(stringResource(R.string.player_speed_title), stringResource(R.string.player_speed_desc))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlaybackRules.speeds.forEach { speed ->
                            FilterChip(state.baseSpeed == speed, { vm.command(PlayerCommand.SetSpeed(speed)) }, label = { Text(speedText(speed)) }, shape = CircleShape, modifier = Modifier.heightIn(min = 44.dp))
                        }
                    }
                    Text(stringResource(R.string.player_hold_speed_hint, speedText(settings.holdSpeed)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                }
                "tools" -> {
                    PanelTitle(stringResource(R.string.player_tools), stringResource(R.string.player_tools_desc))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.tools.filter { it.feature.available(state) && (!shared || it.feature.id != "bookmarks") }.forEach { tool ->
                            FilledTonalButton(onClick = { sheet = tool.feature.id }, shape = CircleShape, modifier = Modifier.heightIn(min = 44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary)) {
                                Icon(tool.icon, null, Modifier.size(18.dp)); Text("  ${localizedFeatureTitle(tool.feature)}")
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth()) {
                        if (zoom > 1.01f) OutlinedButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }, shape = CircleShape, modifier = Modifier.padding(bottom = 8.dp).heightIn(min = 44.dp)) { Text(stringResource(R.string.player_reset_zoom)) }
                        if (!shared) ToolSwitch(stringResource(R.string.player_crop_bars), current.cropBars, stringResource(R.string.player_crop_bars_desc)) {
                            vm.savePicture(video.id, it)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text(stringResource(R.string.player_repeat_order), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RepeatMode.entries.forEach { mode -> FilterChip(state.repeatMode == mode, { vm.command(PlayerCommand.SetRepeat(mode)) }, label = { Text(stringResource(repeatLabelRes(mode))) }, shape = CircleShape) } }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        val brightnessPct = (brightness * 100).roundToInt()
                        val brightnessState = stringResource(R.string.player_brightness_value, brightnessPct)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.player_brightness), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("$brightnessPct%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(brightness, { brightness = it.coerceAtLeast(.02f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness } }, valueRange = .02f..1f,
                            modifier = Modifier.semantics { stateDescription = brightnessState })
                        val volumePct = (volume * 100).roundToInt()
                        val volumeState = stringResource(R.string.player_volume_value, volumePct)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.player_volume), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("$volumePct%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVolume).roundToInt(), 0) },
                            modifier = Modifier.semantics { stateDescription = volumeState })
                    }
                    Spacer(Modifier.height(10.dp))
                    if (vm.privateSession) {
                        OutlinedButton(onClick = close, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp)); Text("  ${stringResource(R.string.player_return_to_vault)}") }
                    } else if (!shared) {
                        OutlinedButton(onClick = { sheet = null; vm.hide(setOf(video.id), true) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp)); Text("  ${stringResource(R.string.player_hide_current)}") }
                    }
                }
                else -> vm.tools.firstOrNull { it.feature.id == sheet }?.panel?.invoke(vm, state, closeSheet)
            }
        }
    }
    if (renaming) RenameVideoDialog(renameText, { renaming = false }) { name ->
        vm.rename(current.id, name); renaming = false
    }
}

@Composable private fun ToolSwitch(title: String, checked: Boolean, description: String, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}
@Composable private fun BoxScope.FrameVisibility(visible: Boolean, modifier: Modifier, enter: androidx.compose.animation.EnterTransition, exit: androidx.compose.animation.ExitTransition, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter, exit) { content() }
}

@Composable internal fun PanelTitle(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
}
private fun repeatLabelRes(mode: RepeatMode) = when (mode) { RepeatMode.SEQUENCE -> R.string.repeat_sequence; RepeatMode.ONE -> R.string.repeat_one; RepeatMode.ALL -> R.string.repeat_all }
@Composable private fun PlayerIcon(icon: ImageVector, label: String, click: () -> Unit, modifier: Modifier = Modifier, size: Int = 24, filled: Boolean = false, compact: Boolean = false) {
    IconButton(onClick = click, modifier = modifier.size(if (compact) 38.dp else 44.dp),
        colors = if (filled) IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = .4f)) else IconButtonDefaults.iconButtonColors()) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}
@Composable private fun RowScope.ToolShortcut(icon: ImageVector, text: String, click: () -> Unit, selected: Boolean = false) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = click).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Text(text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
