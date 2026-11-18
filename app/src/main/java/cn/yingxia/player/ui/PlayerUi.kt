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
import androidx.compose.ui.platform.testTag
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
import cn.yingxia.player.core.*
import cn.yingxia.player.playback.Media3VideoSurface
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable fun PlayerPage(vm: AppViewModel, state: PlaybackState, settings: UserSettings) {
    val session = remember(vm) { PlayerUiSession.ordinary(vm) }
    PlayerPage(session, state, settings)
}

@Composable fun PlayerPage(vm: PlayerUiSession, state: PlaybackState, settings: UserSettings) {
    val activity = LocalActivity.current ?: return
    val fullscreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controls by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Long?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
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
    val current = videos.firstOrNull { it.id == video.id } ?: video
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
            index < 0 || queue.isEmpty() -> vm.message("没有可切换的视频")
            delta < 0 && index == 0 -> vm.message("已经是第一条")
            delta > 0 && index == queue.lastIndex && state.repeatMode != RepeatMode.ALL -> vm.message("已经是最后一条")
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
                                feedback = "亮度 ${(brightness * 100).roundToInt()}%"
                            } else {
                                volume = value
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVolume).roundToInt(), 0)
                                feedback = "音量 ${(volume * 100).roundToInt()}%"
                            }
                        }
                    }
                })
            }
            if (state.buffering && state.failure == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = BrandPink, strokeWidth = 3.dp)
            if (state.boosting && preview == null && feedback == null) {
                Surface(Modifier.align(androidx.compose.ui.BiasAlignment(0f, -.72f)).testTag("boost-hint"),
                    shape = CircleShape, color = Color.Black.copy(alpha = .55f)) {
                    Text("▶▶ ${speedText(state.effectiveSpeed)} 加速中", Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (preview != null || feedback != null) {
                Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = .7f)) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val hint = when {
                            preview != null -> Icons.Outlined.Schedule
                            feedback.orEmpty().startsWith("亮度") -> Icons.Outlined.LightMode
                            else -> Icons.AutoMirrored.Outlined.VolumeUp
                        }
                        Icon(hint, null, tint = BrandPink, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                        Text(if (preview != null) "${durationText(preview!!)} / ${durationText(state.durationMs)}" else feedback.orEmpty(),
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
                    PlayerIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", { if (fullscreen) toggleFull() else close() }, size = 22)
                    Text(current.displayTitle(), Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White, fontSize = if (fullscreen) 15.sp else 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (fullscreen && state.queue.size > 1) {
                        PlayerIcon(Icons.Outlined.SkipPrevious, "上一条", { playRelative(-1) }, size = 20, compact = true)
                        PlayerIcon(Icons.Outlined.SkipNext, "下一条", { playRelative(1) }, size = 20, compact = true)
                    }
                    Surface(onClick = { sheet = "speed" }, shape = CircleShape, color = Color.White.copy(alpha = .18f),
                        modifier = Modifier.testTag("speed-button").padding(horizontal = 4.dp).heightIn(min = 28.dp)) {
                        Box(Modifier.heightIn(min = 28.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text(speedText(state.baseSpeed), color = Color.White, maxLines = 1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    PlayerIcon(Icons.Outlined.MoreHoriz, "播放工具", { sheet = "tools" }, size = 22)
                }
            }
            FrameVisibility(chrome, Modifier.align(Alignment.BottomCenter),
                fadeIn(motionTween(180)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 5 },
                fadeOut(motionTween(140)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { it / 5 }) {
                Row(Modifier.fillMaxWidth().then(edge)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f))))
                    .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "暂停" else "播放", { vm.command(PlayerCommand.Toggle); controls = true }, size = 25, compact = true)
                    AndroidView(factory = { CommitSeekBar(it) }, modifier = Modifier.weight(1f).height(32.dp).testTag("seek-bar"), update = {
                        it.durationMs = state.durationMs
                        if (!it.tracking) it.progress = if (state.durationMs > 0) (state.positionMs * it.max / state.durationMs).toInt() else 0
                        it.commit = { target -> vm.command(PlayerCommand.Seek(target)); controls = true }
                        it.preview = { value -> preview = value }
                    })
                    Text("${durationText(state.positionMs)}/${durationText(state.durationMs)}", color = Color.White.copy(alpha = .92f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
                    PlayerIcon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, if (fullscreen) "退出全屏" else "全屏", toggleFull, size = 22, compact = true)
                }
            }
            if (fullscreen || settings.touchGuard || locked) Column(Modifier.align(Alignment.CenterStart).padding(start = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerIcon(if (shielded) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    if (shielded) "解除锁定" else "锁定屏幕", {
                        if (shielded) { locked = false; guardDismissed = true } else { locked = true; guardDismissed = false }
                        controls = true
                    }, size = 20, filled = true)
                if (shielded) Text(if (locked) "已锁定" else "防误触", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
            state.failure?.let { error ->
                Column(Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha = .82f), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Row(Modifier.padding(top = 4.dp)) { TextButton(onClick = { vm.open(video, videos, state.positionMs) }) { Text("重试", color = BrandPink) }; TextButton(onClick = close) { Text("返回列表", color = Color.White) } }
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
                                IconButton(onClick = { renameText = current.displayTitle(); renaming = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Edit, "重命名", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                            val meta = buildString {
                                append(if (vm.privateSession) "加密视频" else video.folder)
                                append(" · ")
                                append(fileSize(video.sizeBytes))
                                if (video.width > 0 && video.height > 0) append(" · ${video.width}×${video.height}")
                            }
                            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                ToolShortcut(if (current.favorite) Icons.Filled.FilledFavorite else Icons.Outlined.FavoriteBorder, if (current.favorite) "已收藏" else "收藏", { vm.favorite(setOf(video.id), !current.favorite) }, current.favorite)
                                ToolShortcut(Icons.Outlined.Replay, "从头播放", { vm.command(PlayerCommand.Seek(0)) })
                                ToolShortcut(Icons.Outlined.BookmarkBorder, "书签", { sheet = "bookmarks" })
                                ToolShortcut(Icons.Outlined.Tune, "播放工具", { sheet = "tools" })
                            }
                        }
                    }
                    if (state.loop != null || remaining > 0) item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loop != null) AssistChip(onClick = { sheet = "loop" }, label = { Text("A-B 循环中") }, shape = CircleShape)
                            if (remaining > 0) AssistChip(onClick = { sheet = "sleep" }, label = { Text(if (untilEnd) "播完当前后暂停" else "${durationText(remaining)} 后暂停") }, shape = CircleShape)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("播放列表", style = MaterialTheme.typography.titleMedium)
                            Text("  ${state.queue.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (state.queue.size > 1) {
                                IconButton(onClick = { playRelative(-1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.SkipPrevious, "上一条", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton(onClick = { playRelative(1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.SkipNext, "下一条", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            TextButton(onClick = { vm.command(PlayerCommand.SetRepeat(RepeatMode.entries[(state.repeatMode.ordinal + 1) % 3])) }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 34.dp)) {
                                Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, null, Modifier.size(16.dp))
                                Text(" ${repeatLabel(state.repeatMode)}", fontSize = 12.sp)
                            }
                        }
                    }
                    val queue = state.queue.mapNotNull { id -> videos.firstOrNull { it.id == id } }
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
                                    } else Thumbnail(item, Modifier.fillMaxSize())
                                    if (playing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.GraphicEq, "正在播放", tint = BrandPink)
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
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            when (sheet) {
                "speed" -> {
                    PanelTitle("播放速度", "选择后会记住，下次播放继续使用")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlaybackRules.speeds.forEach { speed ->
                            FilterChip(state.baseSpeed == speed, { vm.command(PlayerCommand.SetSpeed(speed)) }, label = { Text(speedText(speed)) }, shape = CircleShape, modifier = Modifier.heightIn(min = 44.dp))
                        }
                    }
                    Text("长按画面临时 ${speedText(settings.holdSpeed)}，松手恢复。可在「我的 → 播放与外观」修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                }
                "tools" -> {
                    PanelTitle("播放工具", "为观影，也为每一次认真学习")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.tools.filter { it.feature.available(state) }.forEach { tool ->
                            FilledTonalButton(onClick = { sheet = tool.feature.id }, shape = CircleShape, modifier = Modifier.heightIn(min = 44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary)) {
                                Icon(tool.icon, null, Modifier.size(18.dp)); Text("  ${tool.feature.title}")
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionCard(padding = 16.dp, modifier = Modifier.fillMaxWidth()) {
                        if (zoom > 1.01f) OutlinedButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }, shape = CircleShape, modifier = Modifier.padding(bottom = 8.dp).heightIn(min = 44.dp)) { Text("还原缩放") }
                        ToolSwitch("裁掉黑边", current.cropBars, "按画面比例铺满播放区域，上下或左右多出来的边缘会被裁掉。") {
                            vm.savePicture(video.id, it)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text("播放顺序", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RepeatMode.entries.forEach { mode -> FilterChip(state.repeatMode == mode, { vm.command(PlayerCommand.SetRepeat(mode)) }, label = { Text(repeatLabel(mode)) }, shape = CircleShape) } }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("亮度", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${(brightness * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(brightness, { brightness = it.coerceAtLeast(.02f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness } }, valueRange = .02f..1f,
                            modifier = Modifier.semantics { stateDescription = "亮度 ${(brightness * 100).roundToInt()}%" })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("音量", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVolume).roundToInt(), 0) },
                            modifier = Modifier.semantics { stateDescription = "音量 ${(volume * 100).roundToInt()}%" })
                    }
                    Spacer(Modifier.height(10.dp))
                    if (vm.privateSession) {
                        OutlinedButton(onClick = close, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp)); Text("  返回保险箱") }
                    } else {
                        OutlinedButton(onClick = { sheet = null; vm.hide(setOf(video.id), true) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp)); Text("  隐藏当前视频") }
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
private fun repeatLabel(mode: RepeatMode) = when (mode) { RepeatMode.SEQUENCE -> "顺序播放"; RepeatMode.ONE -> "单集循环"; RepeatMode.ALL -> "列表循环" }
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
