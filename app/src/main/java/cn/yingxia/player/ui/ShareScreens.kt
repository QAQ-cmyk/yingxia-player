package cn.yingxia.player.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yingxia.player.AppViewModel
import cn.yingxia.player.core.displayTitle
import cn.yingxia.player.transfer.LanPeer
import cn.yingxia.player.transfer.RemoteClip
import kotlinx.coroutines.launch

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
        Text("接收页需要保持打开。视频只在这两台手机之间、通过当前 Wi-Fi 传递，不经过映匣的服务器。口令只在这一次接收里有效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

