@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.yingxia.player.ui

import android.app.Activity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    val current = videos.firstOrNull { it.id == video.id } ?: video
    val remaining by vm.graph.timer.remainingMs.collectAsStateWithLifecycle()
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
            Media3VideoSurface(vm.graph.player, settings.subtitleScale, Modifier.fillMaxSize())
            key(video.id) {
                AndroidView(factory = { PlayerGestureView(it) }, modifier = Modifier.fillMaxSize(), update = { view ->
                    view.snapshot = { latestState }; view.fullscreen = fullscreen; view.locked = locked; view.holdSpeed = settings.holdSpeed
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
                    shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = .22f)) {
                    Text("▶▶ ${speedText(state.effectiveSpeed)} 加速中", Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = Color.White.copy(alpha = .72f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (preview != null || feedback != null) {
                Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = .75f)) {
                    Text(if (preview != null) "${durationText(preview!!)} / ${durationText(state.durationMs)}" else feedback.orEmpty(),
                        Modifier.padding(horizontal = 22.dp, vertical = 14.dp), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
            val chrome = !locked && (controls || !state.isPlaying)
            val edge = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) else Modifier
            FrameVisibility(chrome, Modifier.align(Alignment.TopCenter),
                fadeIn(motionTween(180)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 4 },
                fadeOut(motionTween(140)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { -it / 4 }) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .42f), Color.Transparent)))
                    .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier).then(edge).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", { if (fullscreen) toggleFull() else close() }, size = 22)
                    PlayerIcon(Icons.Outlined.Home, "回到列表", close, size = 22)
                    Text(current.displayTitle(), Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White.copy(alpha = .92f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { sheet = "speed" }, modifier = Modifier.testTag("speed-button").heightIn(min = 40.dp)) { Text(speedText(state.baseSpeed), color = Color.White, maxLines = 1) }
                    PlayerIcon(Icons.Outlined.MoreHoriz, "播放工具", { sheet = "tools" }, size = 22)
                }
            }
            FrameVisibility(chrome, Modifier.align(Alignment.BottomCenter),
                fadeIn(motionTween(180)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 5 },
                fadeOut(motionTween(140)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { it / 5 }) {
                Row(Modifier.fillMaxWidth().then(edge)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .48f))))
                    .padding(start = 2.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(Icons.Outlined.SkipPrevious, "上一条", { playRelative(-1) }, size = 20)
                    PlayerIcon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "暂停" else "播放", { vm.command(PlayerCommand.Toggle); controls = true }, size = 26)
                    PlayerIcon(Icons.Outlined.SkipNext, "下一条", { playRelative(1) }, size = 20)
                    AndroidView(factory = { CommitSeekBar(it) }, modifier = Modifier.weight(1f).height(32.dp).testTag("seek-bar"), update = {
                        it.durationMs = state.durationMs
                        if (!it.tracking) it.progress = if (state.durationMs > 0) (state.positionMs * it.max / state.durationMs).toInt() else 0
                        it.commit = { target -> vm.command(PlayerCommand.Seek(target)); controls = true }
                        it.preview = { value -> preview = value }
                    })
                    Text("${durationText(state.positionMs)}/${durationText(state.durationMs)}", color = Color.White, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                    PlayerIcon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, if (fullscreen) "退出全屏" else "全屏", toggleFull, size = 22)
                }
            }
            if (fullscreen && (controls || locked)) Column(Modifier.align(Alignment.CenterStart).padding(start = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerIcon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    if (locked) "解除锁定" else "锁定屏幕", { locked = !locked; controls = true })
                if (locked) Text("已锁定", color = Color.White, fontSize = 11.sp)
            }
            state.failure?.let { error ->
                Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f), RoundedCornerShape(12.dp)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White, fontSize = 13.sp)
                    Row { TextButton(onClick = { vm.open(video, videos, state.positionMs) }) { Text("重试", color = BrandPink) }; TextButton(onClick = close) { Text("返回列表", color = Color.White) } }
                }
            }
        }
        if (!fullscreen) {
            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.background) {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.navigationBarsPadding()) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text("本地视频", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                Text("  ${fileSize(video.sizeBytes)} · ${video.width} × ${video.height}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(current.displayTitle(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(top = 12.dp, bottom = 7.dp))
                                IconButton(onClick = { renameText = current.displayTitle(); renaming = true }, modifier = Modifier.padding(top = 4.dp)) { Icon(Icons.Outlined.Edit, "重命名") }
                            }
                            Text(video.folder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                ToolShortcut(if (current.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (current.favorite) "已收藏" else "收藏", { vm.favorite(setOf(video.id), !current.favorite) }, current.favorite)
                                ToolShortcut(Icons.Outlined.Replay, "从头播放", { vm.command(PlayerCommand.Seek(0)) })
                                ToolShortcut(Icons.Outlined.BookmarkBorder, "书签", { sheet = "bookmarks" })
                                ToolShortcut(Icons.Outlined.Tune, "播放工具", { sheet = "tools" })
                            }
                            Text("点击画面显示控制，双击播放或暂停，左右滑动调节进度。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    item {
                        if (state.loop != null || remaining > 0) FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loop != null) AssistChip(onClick = { sheet = "loop" }, label = { Text("A-B 循环中") })
                            if (remaining > 0) AssistChip(onClick = { sheet = "sleep" }, label = { Text("${durationText(remaining)} 后暂停") })
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("播放列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.command(PlayerCommand.SetRepeat(RepeatMode.entries[(state.repeatMode.ordinal + 1) % 3])) }) {
                                Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, null, Modifier.size(18.dp))
                                Text("  ${repeatLabel(state.repeatMode)}")
                            }
                        }
                    }
                    val queue = state.queue.mapNotNull { id -> videos.firstOrNull { it.id == id } }
                    items(queue, key = { it.id.value }) { item ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { vm.open(item, queue) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(104.dp).height(60.dp)) {
                                Thumbnail(item, Modifier.fillMaxSize())
                                if (item.id == video.id) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.GraphicEq, "正在播放", tint = BrandPink)
                                }
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(item.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (item.id == video.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                Text(durationText(item.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                    item { Text("全屏后，左侧上下滑动调节亮度，右侧上下滑动调节音量。长按画面可以临时加速。", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { PlaybackRules.speeds.forEach { speed -> FilterChip(state.baseSpeed == speed, { vm.command(PlayerCommand.SetSpeed(speed)) }, label = { Text(speedText(speed)) }, modifier = Modifier.heightIn(min = 48.dp)) } }
                    Text("长按画面临时 ${speedText(settings.holdSpeed)}，松手恢复。可在「我的 → 播放与外观」修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                }
                "tools" -> {
                    PanelTitle("播放工具", "为观影，也为每一次认真学习")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.graph.features.features.filter { it.available(state) }.forEach { feature ->
                            BuiltInFeaturePanels[feature.id]?.let { ui -> OutlinedButton(onClick = { sheet = feature.id }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(ui.icon, null, Modifier.size(20.dp)); Text("  ${feature.title}") } }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("播放顺序", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RepeatMode.entries.forEach { mode -> FilterChip(state.repeatMode == mode, { vm.command(PlayerCommand.SetRepeat(mode)) }, label = { Text(repeatLabel(mode)) }) } }
                    Text("亮度", modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
                    Slider(brightness, { brightness = it.coerceAtLeast(.02f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness } }, valueRange = .02f..1f,
                        modifier = Modifier.semantics { stateDescription = "亮度 ${(brightness * 100).roundToInt()}%" })
                    Text("音量", style = MaterialTheme.typography.titleSmall)
                    Slider(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVolume).roundToInt(), 0) },
                        modifier = Modifier.semantics { stateDescription = "音量 ${(volume * 100).roundToInt()}%" })
                    Text("从列表中隐藏", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    Text("不会删除文件。如果正在播放这条视频，播放会结束。可以在底部提示里撤销。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { sheet = null; vm.hide(setOf(video.id), true) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(20.dp)); Text("  隐藏当前视频") }
                }
                else -> BuiltInFeaturePanels[sheet]?.panel?.invoke(vm, state, closeSheet)
            }
        }
    }
    if (renaming) RenameVideoDialog(renameText, { renaming = false }) { name ->
        vm.rename(current.id, name); renaming = false
    }
}

@Composable private fun BoxScope.FrameVisibility(visible: Boolean, modifier: Modifier, enter: androidx.compose.animation.EnterTransition, exit: androidx.compose.animation.ExitTransition, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter, exit) { content() }
}

@Composable internal fun PanelTitle(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
}
private fun repeatLabel(mode: RepeatMode) = when (mode) { RepeatMode.SEQUENCE -> "顺序播放"; RepeatMode.ONE -> "单集循环"; RepeatMode.ALL -> "列表循环" }
@Composable private fun PlayerIcon(icon: ImageVector, label: String, click: () -> Unit, modifier: Modifier = Modifier, size: Int = 24) {
    IconButton(onClick = click, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size.dp)) }
}
@Composable private fun RowScope.ToolShortcut(icon: ImageVector, text: String, click: () -> Unit, selected: Boolean = false) {
    Column(Modifier.weight(1f).heightIn(min = 64.dp).clickable(onClick = click).padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Text(text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}
