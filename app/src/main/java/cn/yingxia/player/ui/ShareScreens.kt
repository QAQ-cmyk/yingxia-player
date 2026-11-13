package cn.yingxia.player.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.core.displayTitle
import cn.yingxia.player.transfer.LanPeer
import cn.yingxia.player.transfer.RemoteClip
import cn.yingxia.player.transfer.WebDavClient
import cn.yingxia.player.transfer.WebDavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun TransferPage(vm: AppViewModel) {
    val context = LocalContext.current
    val lan = vm.graph.lan
    val status by lan.status.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var peer by remember { mutableStateOf<LanPeer?>(null) }
    var remote by remember { mutableStateOf<List<RemoteClip>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
    }
    DisposableEffect(Unit) { onDispose { lan.stop() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("同一 Wi-Fi 互传", style = MaterialTheme.typography.titleLarge)
        Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (status.hosting) Text("本机口令 ${status.pin}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        if (status.busy) LinearProgressIndicator(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { lan.host() }, enabled = !status.hosting, modifier = Modifier.heightIn(min = 48.dp)) { Text("开始接收") }
            OutlinedButton(onClick = { lan.discover() }, enabled = !status.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("查找设备") }
        }
        status.peers.forEach { item ->
            OutlinedButton(onClick = {
                peer = item
                scope.launch { runCatching { remote = lan.remoteClips(item) }.onFailure { vm.message(it.message ?: "无法读取对方的视频") } }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(item.name) }
        }
        peer?.let { target ->
            Text("发给 ${target.name}", style = MaterialTheme.typography.titleMedium)
            videos.take(30).forEach { video ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(video.id.value in chosen, { checked -> chosen = if (checked) chosen + video.id.value else chosen - video.id.value })
                    Text(video.displayTitle(), maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
            Button(onClick = { lan.send(target, videos.filter { it.id.value in chosen }) }, enabled = chosen.isNotEmpty() && !status.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("发送所选") }
            remote.forEach { clip ->
                TextButton(onClick = { lan.download(target, clip) }, enabled = !status.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("下载 ${clip.name}") }
            }
        }
        Text("接收页需要保持打开。口令只在这一次接收里有效，用来避免同一网络里的别人随意写入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun CloudPage(vm: AppViewModel) {
    val context = LocalContext.current
    val saved by vm.graph.settings.webDav.collectAsStateWithLifecycle(cn.yingxia.player.data.WebDavConfig())
    val videos by vm.videos.collectAsStateWithLifecycle()
    var url by remember(saved.url) { mutableStateOf(saved.url) }
    var user by remember(saved.user) { mutableStateOf(saved.user) }
    var password by remember(saved.password) { mutableStateOf(saved.password) }
    var remote by remember { mutableStateOf<List<WebDavFile>>(emptyList()) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    fun client() = WebDavClient(url, user, password)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("云端传输", style = MaterialTheme.typography.titleLarge)
        Text("填写你自己的 WebDAV 地址。映匣没有云服务器，账号密码只保存在这台手机。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(url, { url = it }, label = { Text("地址，例如 https://example.com/dav") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { scope.launch { vm.graph.settings.setWebDav(url, user, password); vm.message("已保存云端地址") } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") }
        if (busy) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) { remote = client().list() } }.onSuccess { vm.message(if (remote.isEmpty()) "云端文件夹是空的" else "已读取云端列表") }
                    .onFailure { vm.message(it.message ?: "无法连接云端") }
                busy = false
            }
        }, enabled = url.startsWith("http") && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看云端视频") }
        videos.take(30).forEach { video ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(video.id.value in chosen, { checked -> chosen = if (checked) chosen + video.id.value else chosen - video.id.value })
                Text(video.displayTitle(), maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
        Button(onClick = {
            val picks = videos.filter { it.id.value in chosen }
            scope.launch {
                busy = true
                runCatching {
                    picks.forEachIndexed { index, video ->
                        context.contentResolver.openInputStream(android.net.Uri.parse(video.uri))?.use { input ->
                            withContext(Dispatchers.IO) { client().upload(video.displayTitle(), input, video.sizeBytes) { sent -> progress = (index + if (video.sizeBytes > 0) sent.toFloat() / video.sizeBytes else 0f) / picks.size } }
                        } ?: error("无法读取 ${video.displayTitle()}")
                    }
                }.onSuccess { vm.message("已上传 ${picks.size} 个视频") }.onFailure { vm.message(it.message ?: "上传失败") }
                busy = false
            }
        }, enabled = chosen.isNotEmpty() && url.startsWith("http") && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("上传所选") }
        remote.forEach { file ->
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            val (connection, length) = client().openDownload(file)
                            connection.inputStream.use { input ->
                                cn.yingxia.player.transfer.GalleryWriter.save(context, file.name, input, length) { sent -> if (length > 0) progress = sent.toFloat() / length }
                            }
                        }
                    }.getOrDefault(false)
                    if (ok) { vm.graph.repository.refresh(); vm.message("已下载 ${file.name}") } else vm.message("下载失败")
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("下载 ${file.name}") }
        }
    }
}

@Composable fun CastDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val router = remember { MediaRouter.getInstance(context) }
    var routes by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
    DisposableEffect(router) {
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
        fun snapshot() = router.routes.filter { it.id != router.defaultRoute.id && it.isEnabled }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { routes = snapshot() }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { routes = snapshot() }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { routes = snapshot() }
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) { routes = snapshot() }
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        routes = snapshot()
        onDispose { router.removeCallback(callback) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("投屏") },
        text = {
            if (routes.isEmpty()) Text("没有发现系统投屏设备。可以先在手机的系统投屏或无线显示里连接电视，再回到这里选择。映匣没有自建的 Google Cast 接收端，本地文件会跟着当前画面一起镜像，而不是单独推到 Chromecast 应用里。")
            else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(routes, key = { it.id }) { route ->
                    TextButton(onClick = { router.selectRoute(route); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(route.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { router.unselect(MediaRouter.UNSELECT_REASON_STOPPED); onDismiss() }) { Text("停止投屏") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
