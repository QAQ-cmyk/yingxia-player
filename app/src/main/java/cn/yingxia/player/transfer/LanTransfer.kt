package cn.yingxia.player.transfer

import android.content.ContentValues
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cn.yingxia.player.core.VideoItem
import cn.yingxia.player.core.displayTitle
import cn.yingxia.player.data.LocalVideoRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class LanPeer(val name: String, val host: String, val port: Int, val pin: String)
data class RemoteClip(val id: String, val name: String, val size: Long)
data class LanStatus(
    val hosting: Boolean = false,
    val pin: String = "",
    val peers: List<LanPeer> = emptyList(),
    val busy: Boolean = false,
    val progress: Float = 0f,
    val message: String = "两台手机连接同一 Wi-Fi 后，一台点「开始接收」，另一台点「查找设备」。",
)

/** Same-LAN send and receive. The receive page should stay open while hosting. */
class LanTransfer(private val context: Context, private val repository: LocalVideoRepository, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow(LanStatus())
    val status = _status.asStateFlow()
    private val mutex = Mutex()
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var pin = ""

    fun host() {
        if (_status.value.hosting) return
        pin = (1000..9999).random().toString()
        val socket = ServerSocket(0)
        server = socket
        _status.value = _status.value.copy(hosting = true, pin = pin, message = "接收已开启。请另一台手机查找「YX$pin」。")
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) }.onFailure { client.close() } }
            }
        }
        val info = NsdServiceInfo().apply { serviceName = "YX$pin"; serviceType = TransferProtocol.SERVICE_TYPE; port = socket.localPort }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {}
            override fun onRegistrationFailed(service: NsdServiceInfo, error: Int) { publish("无法让附近设备发现本机（$error）") }
            override fun onServiceUnregistered(service: NsdServiceInfo) {}
            override fun onUnregistrationFailed(service: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { publish("无法让附近设备发现本机") }
    }

    fun discover() {
        stopDiscovery()
        _status.value = _status.value.copy(peers = emptyList(), message = "正在查找同一 Wi-Fi 里的映匣…")
        val found = mutableListOf<LanPeer>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) { publish("查找失败（$error）") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName == "YX$pin") return
                runCatching { nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val code = info.serviceName.removePrefix("YX").take(4)
                        if (code.length != 4) return
                        val peer = LanPeer(info.serviceName, host, info.port, code)
                        synchronized(found) {
                            found.removeAll { it.host == peer.host && it.port == peer.port }
                            found += peer
                            _status.value = _status.value.copy(peers = found.toList(), message = "找到 ${found.size} 台设备")
                        }
                    }
                }) }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
        }
        discovery = listener
        runCatching { nsd.discoverServices(TransferProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { publish("无法查找附近设备") }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { server?.close() }
        server = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        stopDiscovery()
        _status.value = LanStatus(message = "已停止接收。")
    }

    suspend fun remoteClips(peer: LanPeer): List<RemoteClip> = withContext(Dispatchers.IO) {
        val body = exchange(peer, "GET", "/list", null, null, null) { }
        TransferProtocol.decodeList(body.decodeToString()).map { RemoteClip(it.first, it.second, it.third) }
    }

    fun send(peer: LanPeer, videos: List<VideoItem>) = transfer("发送") {
        videos.forEachIndexed { index, video ->
            publish("正在发送 ${index + 1}/${videos.size}：${video.displayTitle()}", busy = true)
            context.contentResolver.openInputStream(android.net.Uri.parse(video.uri))?.use { input ->
                exchange(peer, "POST", "/upload", input, video.sizeBytes, video.displayTitle()) { sent ->
                    val frac = if (video.sizeBytes > 0) sent.toFloat() / video.sizeBytes else 0f
                    _status.value = _status.value.copy(progress = (index + frac) / videos.size)
                }
            } ?: error("无法读取 ${video.displayTitle()}")
        }
        publish("已发送 ${videos.size} 个视频")
    }

    fun download(peer: LanPeer, clip: RemoteClip) = transfer("下载") {
        publish("正在下载 ${clip.name}", busy = true)
        val socket = Socket()
        socket.connect(InetSocketAddress(peer.host, peer.port), 8_000)
        socket.soTimeout = 30_000
        socket.use { client ->
            val header = "GET /file?id=${URLEncoder.encode(clip.id, "UTF-8")} HTTP/1.1\r\nX-Pin: ${peer.pin}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().write(header.toByteArray(StandardCharsets.ISO_8859_1))
            val input = client.getInputStream()
            val response = readHeader(input)
            val status = response.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
            if (status !in 200..299) error("对方拒绝了下载")
            val length = response.split("\r\n").firstNotNullOfOrNull { line ->
                line.substringBefore(':').trim().lowercase().takeIf { it == "content-length" }?.let { line.substringAfter(':').trim().toLongOrNull() }
            } ?: clip.size
            if (!saveIncoming(clip.name, input, length) { sent -> if (length > 0) _status.value = _status.value.copy(progress = sent.toFloat() / length) }) error("无法保存到相册")
        }
        repository.refresh()
        publish("已保存到相册：${clip.name}")
    }

    private fun transfer(label: String, block: suspend () -> Unit) {
        if (_status.value.busy) return
        scope.launch {
            mutex.withLock {
                _status.value = _status.value.copy(busy = true, progress = 0f)
                runCatching { block() }.onFailure { publish("$label 失败：${it.message ?: "连接中断"}") }
                _status.value = _status.value.copy(busy = false, progress = 0f)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            val input = client.getInputStream()
            val header = readHeader(input)
            val lines = header.split("\r\n")
            val request = lines.firstOrNull()?.split(" ") ?: return
            val headers = lines.drop(1).mapNotNull { line ->
                val cut = line.indexOf(':'); if (cut <= 0) null else line.substring(0, cut).trim().lowercase() to line.substring(cut + 1).trim()
            }.toMap()
            if (headers["x-pin"] != pin) { writeStatus(client, 403, "禁止"); return }
            when (request.getOrNull(0)) {
                "GET" -> when {
                    request.getOrNull(1)?.startsWith("/list") == true -> {
                        val clips = runBlocking { repository.videos.first() }.map { Triple(it.id.value, it.displayTitle(), it.sizeBytes) }
                        writeStatus(client, 200, TransferProtocol.encodeList(clips))
                    }
                    request.getOrNull(1)?.startsWith("/file") == true -> {
                        val id = URLDecoder.decode(request[1].substringAfter("id="), "UTF-8")
                        val video = runBlocking { repository.videos.first().firstOrNull { it.id.value == id } } ?: run { writeStatus(client, 404, "没有"); return }
                        val stream = context.contentResolver.openInputStream(android.net.Uri.parse(video.uri)) ?: run { writeStatus(client, 404, "没有"); return }
                        stream.use { writeStream(client, it, video.sizeBytes) }
                    }
                    else -> writeStatus(client, 404, "没有")
                }
                "POST" -> {
                    val length = headers["content-length"]?.toLongOrNull() ?: run { writeStatus(client, 400, "缺少长度"); return }
                    val name = URLDecoder.decode(headers["x-name"].orEmpty(), "UTF-8").ifBlank { "映匣视频.mp4" }
                    val saved = saveIncoming(name, input, length) {}
                    if (saved) { runBlocking { repository.refresh() }; writeStatus(client, 200, "OK") } else writeStatus(client, 500, "无法保存")
                }
                else -> writeStatus(client, 405, "不支持")
            }
        }
    }

    private fun exchange(peer: LanPeer, method: String, path: String, body: InputStream?, length: Long?, name: String?, progress: (Long) -> Unit): ByteArray {
        val socket = Socket()
        socket.connect(InetSocketAddress(peer.host, peer.port), 8_000)
        socket.soTimeout = 30_000
        socket.use { client ->
            val writer = client.getOutputStream()
            val headers = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("X-Pin: ${peer.pin}\r\n")
                append("Connection: close\r\n")
                if (length != null) append("Content-Length: $length\r\n")
                if (name != null) append("X-Name: ${URLEncoder.encode(name, "UTF-8")}\r\n")
                append("\r\n")
            }
            writer.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            if (body != null && length != null) copyStream(body, writer, length, progress)
            writer.flush()
            val input = client.getInputStream()
            val header = readHeader(input)
            val status = header.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
            val responseLength = header.split("\r\n").mapNotNull { line ->
                val cut = line.indexOf(':'); if (cut <= 0) null else line.substring(0, cut).trim().lowercase() to line.substring(cut + 1).trim()
            }.toMap()["content-length"]?.toLongOrNull() ?: 0L
            if (status !in 200..299) error(header.lineSequence().firstOrNull() ?: "对方拒绝了请求")
            return if (responseLength > 0) readExact(input, responseLength) else ByteArray(0)
        }
    }

    private fun saveIncoming(name: String, input: InputStream, length: Long, progress: (Long) -> Unit) =
        GalleryWriter.save(context, name, input, length, progress)

    private fun writeStatus(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray()
        val text = "HTTP/1.1 $code \r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(text.toByteArray(StandardCharsets.ISO_8859_1)); out.write(bytes); out.flush()
    }
    private fun writeStream(socket: Socket, input: InputStream, length: Long) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 200 \r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        copyStream(input, out, length) {}
        out.flush()
    }
    private fun copyStream(input: InputStream, output: OutputStream, length: Long, progress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var sent = 0L
        while (sent < length) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - sent).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            sent += read
            progress(sent)
        }
    }
    private fun readExact(input: InputStream, length: Long): ByteArray {
        val out = ByteArray(length.toInt())
        var offset = 0
        while (offset < out.size) {
            val read = input.read(out, offset, out.size - offset)
            if (read < 0) break
            offset += read
        }
        return out
    }
    private fun readHeader(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        var previous = 0
        while (bytes.size() < 16_384) {
            val value = input.read(); if (value < 0) break
            bytes.write(value)
            if (previous == '\r'.code && value == '\n'.code && bytes.toString(StandardCharsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) break
            previous = value
        }
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }
    private fun stopDiscovery() { discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null }
    private fun publish(text: String, busy: Boolean = _status.value.busy) { _status.value = _status.value.copy(message = text, busy = busy) }
}
