package cn.yingxia.player

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yingxia.player.core.PlayerCommand
import cn.yingxia.player.core.VideoId
import cn.yingxia.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SyncEditionEndToEndTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private class EmbeddedLoopbackKtorTestServer(
        private val token: String,
        private val mediaName: String,
        private val mediaBytes: ByteArray,
    ) : AutoCloseable {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        private val workerThread: Thread
        val streamRangeHits = AtomicInteger(0)
        val snapshotPayloads = mutableListOf<String>()
        var currentEnvelope: EncryptedSyncEnvelopeDto? = null
        val vaultBlobs = ConcurrentHashMap<String, Pair<VaultBlobMetaDto, ByteArray>>()

        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

        init {
            workerThread = Thread {
                while (running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread { handleClient(client) }.start()
                }
            }.apply { isDaemon = true; start() }
        }

        private fun handleClient(client: Socket) {
            client.use { s ->
                s.soTimeout = 10_000
                val bis = BufferedInputStream(s.getInputStream())
                val headerBytes = ByteArrayOutputStream()
                var matchedEnd = 0
                while (true) {
                    val b = bis.read()
                    if (b < 0) return
                    headerBytes.write(b)
                    val arr = headerBytes.toByteArray()
                    val len = arr.size
                    if (len >= 4 && arr[len - 4] == '\r'.code.toByte() && arr[len - 3] == '\n'.code.toByte() &&
                        arr[len - 2] == '\r'.code.toByte() && arr[len - 1] == '\n'.code.toByte()
                    ) {
                        matchedEnd = len
                        break
                    }
                    if (len > 32 * 1024) return
                }
                val headerText = String(headerBytes.toByteArray(), 0, matchedEnd, Charsets.US_ASCII)
                val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                if (lines.isEmpty()) return
                val parts = lines.first().split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = linkedMapOf<String, String>()
                lines.drop(1).forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val body = if (contentLength > 0) {
                    ByteArray(contentLength).also { DataInputStream(bis).readFully(it) }
                } else {
                    ByteArray(0)
                }

                val out = s.getOutputStream()
                val auth = headers["authorization"].orEmpty()
                if (auth != "Bearer $token") {
                    writeResponse(out, 401, "Unauthorized", "application/json", """{"error":"unauthorized"}""".toByteArray())
                    return
                }

                val mediaId = "loopmedia1"
                when {
                    method == "GET" && path == "/api/v1/media" -> {
                        val resp = RemoteMediaListResponse(
                            items = listOf(
                                RemoteMediaDto(
                                    id = mediaId,
                                    name = mediaName,
                                    folder = "Study",
                                    sizeBytes = mediaBytes.size.toLong(),
                                    mimeType = "video/mp4",
                                )
                            )
                        )
                        writeResponse(out, 200, "OK", "application/json", json.encodeToString(resp).toByteArray(Charsets.UTF_8))
                    }
                    method == "GET" && path == "/api/v1/media/$mediaId/stream" -> {
                        val total = mediaBytes.size.toLong()
                        val range = headers["range"]
                        if (range == null || !range.startsWith("bytes=")) {
                            val hdr = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: video/mp4\r\n")
                                append("Content-Length: ${mediaBytes.size}\r\n")
                                append("Accept-Ranges: bytes\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            runCatching {
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                val burst = minOf(256 * 1024, mediaBytes.size)
                                out.write(mediaBytes, 0, burst)
                                out.flush()
                                var offset = burst
                                while (offset < mediaBytes.size && running.get()) {
                                    Thread.sleep(25)
                                    val len = minOf(16 * 1024, mediaBytes.size - offset)
                                    out.write(mediaBytes, offset, len)
                                    out.flush()
                                    offset += len
                                }
                            }
                        } else {
                            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
                            val dash = spec.indexOf('-')
                            val start = spec.substring(0, dash).toLongOrNull() ?: 0L
                            val rawEnd = spec.substring(dash + 1).toLongOrNull() ?: (total - 1)
                            val end = rawEnd.coerceAtMost(total - 1)
                            if (start < 0 || start >= total || end < start) {
                                writeResponse(out, 416, "Range Not Satisfiable", "text/plain", ByteArray(0), mapOf("Content-Range" to "bytes */$total"))
                            } else {
                                streamRangeHits.incrementAndGet()
                                val slice = mediaBytes.copyOfRange(start.toInt(), end.toInt() + 1)
                                writeResponse(
                                    out, 206, "Partial Content", "video/mp4", slice,
                                    mapOf(
                                        "Accept-Ranges" to "bytes",
                                        "Content-Range" to "bytes $start-$end/$total",
                                    ),
                                )
                            }
                        }
                    }
                    method == "GET" && path == "/api/v1/sync/snapshot" -> {
                        val env = synchronized(this) { currentEnvelope }
                        if (env == null) {
                            writeResponse(out, 404, "Not Found", "application/json", """{"error":"none"}""".toByteArray())
                        } else {
                            writeResponse(out, 200, "OK", "application/json", json.encodeToString(env).toByteArray(Charsets.UTF_8))
                        }
                    }
                    method == "PUT" && path == "/api/v1/sync/snapshot" -> {
                        val text = String(body, Charsets.UTF_8)
                        val incoming = json.decodeFromString<EncryptedSyncEnvelopeDto>(text)
                        val conflict = synchronized(this) {
                            val cur = currentEnvelope
                            if (cur != null && incoming.revision < cur.revision) true
                            else {
                                currentEnvelope = incoming
                                snapshotPayloads += text
                                false
                            }
                        }
                        if (conflict) {
                            writeResponse(out, 409, "Conflict", "application/json", """{"error":"stale"}""".toByteArray())
                        } else {
                            writeResponse(out, 200, "OK", "application/json", json.encodeToString(incoming).toByteArray(Charsets.UTF_8))
                        }
                    }
                    method == "GET" && path == "/api/v1/vault/blobs" -> {
                        val list = VaultBlobListResponse(blobs = vaultBlobs.values.map { it.first }.sortedBy { it.blobName })
                        writeResponse(out, 200, "OK", "application/json", json.encodeToString(list).toByteArray(Charsets.UTF_8))
                    }
                    method == "PUT" && path.startsWith("/api/v1/vault/blobs/") -> {
                        val name = path.removePrefix("/api/v1/vault/blobs/")
                        val actualSha = sha256Hex(body)
                        val expectedSha = headers["x-content-sha256"]?.lowercase()
                        if (expectedSha != null && expectedSha != actualSha) {
                            writeResponse(out, 400, "Bad Request", "application/json", """{"error":"sha"}""".toByteArray())
                        } else {
                            val meta = VaultBlobMetaDto(name, body.size.toLong(), actualSha, System.currentTimeMillis())
                            vaultBlobs[name] = meta to body
                            writeResponse(out, 200, "OK", "application/json", json.encodeToString(meta).toByteArray(Charsets.UTF_8))
                        }
                    }
                    method == "GET" && path.startsWith("/api/v1/vault/blobs/") -> {
                        val name = path.removePrefix("/api/v1/vault/blobs/")
                        val entry = vaultBlobs[name]
                        if (entry == null) {
                            writeResponse(out, 404, "Not Found", "application/json", """{"error":"missing"}""".toByteArray())
                        } else {
                            writeResponse(out, 200, "OK", "application/octet-stream", entry.second)
                        }
                    }
                    else -> writeResponse(out, 404, "Not Found", "text/plain", ByteArray(0))
                }
            }
        }

        private fun writeResponse(
            out: java.io.OutputStream,
            status: Int,
            reason: String,
            contentType: String,
            body: ByteArray,
            extraHeaders: Map<String, String> = emptyMap(),
        ) {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status $reason\r\n")
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${body.size}\r\n")
            sb.append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            if (body.isNotEmpty()) out.write(body)
            out.flush()
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
        }
    }

    @Test
    fun syncUiTlsRejectionHttp206StreamingE2eeBookmarksAndVaultColdRestoreEndToEnd(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrument.targetContext
        val graph = (context.applicationContext as YingXiaApplication).graph
        Assume.assumeTrue("This test must run against the sync flavor", graph.sync.enabled)
        Assume.assumeFalse("Preserve an existing vault", graph.vault.store.configured)

        val tag = UUID.randomUUID().toString().take(6)
        val fixtureBytes = instrument.context.assets.open("fixture.mp4").use { it.readBytes() }
        val localFile = File(context.filesDir, "test-media/sync-local-$tag.mp4").apply { parentFile!!.mkdirs() }
        localFile.writeBytes(fixtureBytes)
        val token = "yx-e2e-loopback-secret-token-2026"
        val server = EmbeddedLoopbackKtorTestServer(token, "RemoteLecture-$tag.mp4", fixtureBytes)
        val vaultTempFile = File(context.filesDir, "test-media/sync-vault-$tag.mp4").apply { parentFile!!.mkdirs() }
        vaultTempFile.writeBytes(fixtureBytes)
        var createdVault = false

        try {
            // 1. UI validation: entering non-loopback http:// must NOT crash and must surface localized TLS notice!
            ui.onNodeWithTag("nav-2").performClick()
            ui.onNodeWithText(context.getString(R.string.space_sync_configure)).performClick()
            ui.onNodeWithText(context.getString(R.string.space_sync_url_label)).performTextReplacement("http://192.168.1.99:8080")
            ui.onNodeWithText(context.getString(R.string.space_sync_token_label)).performTextInput(token)
            ui.onNodeWithText(context.getString(R.string.action_save)).performClick()
            val tlsError = context.getString(R.string.space_sync_tls_required)
            ui.waitUntil(5_000) { ui.onAllNodesWithText(tlsError, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(graph.sync.state.value.configured)

            // 2. Configure valid loopback HTTP 127.0.0.1 node and stream remote video via Media3 + HTTP 206!
            graph.sync.updateConfig(server.baseUrl, token)
            val remoteCount = graph.sync.refreshRemoteMedia().getOrThrow()
            assertEquals(1, remoteCount)
            graph.repository.refresh()
            ui.waitUntil(10_000) { runBlocking { graph.repository.allVideos().any { it.source == "ktor-remote" } } }
            val remoteVideo = graph.repository.allVideos().first { it.source == "ktor-remote" }

            graph.player.execute(PlayerCommand.Open(remoteVideo.id, listOf(remoteVideo.id))).getOrThrow()
            ui.waitUntil(15_000) { graph.player.state.value.isPlaying && graph.player.state.value.durationMs >= 25_000L }
            graph.player.execute(PlayerCommand.Seek(22_000L)).getOrThrow()
            ui.waitUntil(10_000) {
                server.streamRangeHits.get() > 0 &&
                    graph.player.state.value.isPlaying &&
                    graph.player.state.value.positionMs >= 20_000L
            }
            assertTrue("Expected HTTP 206 range requests while streaming", server.streamRangeHits.get() > 0)
            assertTrue(graph.player.state.value.positionMs >= 20_000L)
            withContext(Dispatchers.Main) { graph.player.execute(PlayerCommand.Close) }
            ui.waitUntil(10_000) { graph.player.state.value.video == null }

            // 3. E2EE snapshot push + bookmark restoration + 409 Conflict guard!
            val localUri = FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", localFile).toString()
            val localVideo = graph.repository.importVideo(localUri)
            val customTitle = "SyncedStudy-$tag"
            val markNote = "KeyFormula-$tag"
            graph.repository.setFavorite(setOf(localVideo.id), true)
            graph.repository.rename(localVideo.id, customTitle)
            graph.repository.saveProgress(localVideo.id, 6_000L)
            graph.repository.saveBookmark(localVideo.id, 5_000L, markNote)

            val syncPass = "SyncMasterPass#2026"
            graph.sync.pushEncryptedSnapshot(syncPass.toCharArray()).getOrThrow()
            assertTrue(server.snapshotPayloads.isNotEmpty())
            val rawPayload = server.snapshotPayloads.last()
            assertFalse("Server ciphertext must not leak display title", rawPayload.contains(customTitle))
            assertFalse("Server ciphertext must not leak bookmark note", rawPayload.contains(markNote))

            // Wipe local state + bookmarks before pulling snapshot back.
            graph.repository.setFavorite(setOf(localVideo.id), false)
            graph.repository.rename(localVideo.id, localVideo.name.substringBeforeLast('.'))
            graph.repository.clearHistory()
            val bmToDelete = graph.repository.bookmarks.first().filter { it.videoId == localVideo.id }
            bmToDelete.forEach { graph.repository.deleteBookmark(it.id) }

            val pulledCount = graph.sync.pullEncryptedSnapshot(syncPass.toCharArray()).getOrThrow()
            assertTrue(pulledCount >= 1)
            val restoredVideo = graph.repository.get(localVideo.id)!!
            assertTrue("Snapshot pull must restore favorite", restoredVideo.favorite)
            assertEquals("Snapshot pull must restore displayName", customTitle, restoredVideo.displayName)
            assertEquals("Snapshot pull must restore positionMs", 6_000L, restoredVideo.positionMs)
            val restoredBookmarks = graph.repository.bookmarks.first().filter { it.videoId == localVideo.id }
            assertEquals(1, restoredBookmarks.size)
            assertEquals(markNote, restoredBookmarks.single().note)
            assertEquals(5_000L, restoredBookmarks.single().positionMs)

            // Simulate higher revision on server -> pushing local revision must hit 409 conflict with localized error.
            synchronized(server) {
                server.currentEnvelope = server.currentEnvelope!!.copy(revision = 99L)
            }
            val conflictResult = graph.sync.pushEncryptedSnapshot(syncPass.toCharArray())
            assertTrue(conflictResult.isFailure)
            assertEquals(context.getString(R.string.space_sync_conflict_error), conflictResult.exceptionOrNull()?.message)

            // 4. Vault ciphertext cold backup upload & SHA-256 verified restore into empty vault!
            val vaultPass = "SyncVaultColdPass#2026"
            val vaultSrcVideo = graph.repository.importVideo(
                FileProvider.getUriForFile(context, "${context.packageName}.fixture-provider", vaultTempFile).toString()
            )
            withContext(Dispatchers.IO) { graph.vault.store.setup(vaultPass.toCharArray()) }
            createdVault = true
            val entry = graph.vault.store.encrypt(vaultSrcVideo) {}
            val backedCount = graph.sync.backupVaultBlobs().getOrThrow()
            assertTrue("Expected at least 3 blobs (keyset.bin, .meta, .vault)", backedCount >= 3)
            assertTrue(server.vaultBlobs.containsKey("keyset.bin"))
            assertTrue(server.vaultBlobs.containsKey("${entry.id}.vault"))
            assertTrue(server.vaultBlobs.containsKey("${entry.id}.meta"))

            // Simulate uninstall / wipe local vault directory, then restore from server!
            withContext(Dispatchers.Main) { graph.vault.close() }
            graph.vault.store.directory.deleteRecursively()
            assertFalse(graph.vault.store.configured)

            val restoredVaultCount = graph.sync.restoreVaultBlobs(vaultPass.toCharArray()).getOrThrow()
            assertEquals(1, restoredVaultCount)
            assertTrue(graph.vault.store.configured)
            withContext(Dispatchers.IO) { graph.vault.store.unlock(vaultPass.toCharArray()) }
            val entriesAfterRestore = graph.vault.store.entries()
            assertEquals(1, entriesAfterRestore.size)
            assertEquals(entry.name, entriesAfterRestore.single().name)
            graph.vault.store.verifyPayload(entriesAfterRestore.single())
            withContext(Dispatchers.IO) {
                graph.repository.forgetEncryptedSource(localVideo)
                graph.repository.forgetEncryptedSource(vaultSrcVideo)
            }
        } finally {
            server.close()
            withContext(Dispatchers.Main) {
                graph.player.execute(PlayerCommand.Close)
                graph.vault.close()
            }
            if (createdVault) graph.vault.store.directory.deleteRecursively()
            localFile.delete()
            vaultTempFile.delete()
            context.deleteSharedPreferences("yingxia_sync_v2")
            runCatching {
                graph.repository.clearHistory()
                graph.repository.refresh()
            }
        }
    }
}
