package cn.yingxia.server

import cn.yingxia.protocol.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class YingXiaServerConfig(
    val mediaDir: File,
    val dataDir: File,
    val bearerToken: String,
    val maxSyncEnvelopeBytes: Long = 16L * 1024 * 1024,
    val maxVaultBlobBytes: Long = 8L * 1024 * 1024 * 1024,
)

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi")
private val SAFE_VAULT_BLOB_REGEX = Regex("^(keyset\\.bin|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(vault|meta|playback))$")

fun Application.configureYingXiaServer(config: YingXiaServerConfig) {
    require(config.bearerToken.length >= 16) { "YINGXIA_BEARER_TOKEN must be at least 16 characters" }
    config.mediaDir.mkdirs()
    val syncDir = File(config.dataDir, "sync").apply { mkdirs() }
    val vaultDir = File(config.dataDir, "vault-blobs").apply { mkdirs() }
    val snapshotFile = File(syncDir, "snapshot.enc.json")
    val syncMutex = Mutex()
    val jsonFormat = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    val expectedTokenBytes = config.bearerToken.toByteArray(Charsets.UTF_8)

    install(ContentNegotiation) {
        json(jsonFormat)
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
    }

    fun ApplicationCall.isAuthorized(): Boolean {
        val header = request.headers[HttpHeaders.Authorization] ?: return false
        if (!header.startsWith("Bearer ")) return false
        val provided = header.removePrefix("Bearer ").trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(provided, expectedTokenBytes)
    }

    fun indexMediaFiles(): Map<String, Pair<RemoteMediaDto, File>> {
        val canonicalRoot = config.mediaDir.canonicalFile
        val rootPath = canonicalRoot.toPath()
        val result = linkedMapOf<String, Pair<RemoteMediaDto, File>>()
        canonicalRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }
            .sortedBy { it.name.lowercase() }
            .forEach { file ->
                val canon = file.canonicalFile
                if (!canon.toPath().startsWith(rootPath)) return@forEach
                val rel = rootPath.relativize(canon.toPath()).toString().replace('\\', '/')
                val id = sha256Hex(rel.toByteArray(Charsets.UTF_8)).take(24)
                val folder = canon.parentFile?.takeIf { it != canonicalRoot }?.name ?: "NAS"
                val mime = when (canon.extension.lowercase()) {
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "mov" -> "video/quicktime"
                    "avi" -> "video/x-msvideo"
                    else -> "video/mp4"
                }
                result[id] = RemoteMediaDto(
                    id = id,
                    name = canon.name,
                    folder = folder,
                    sizeBytes = canon.length(),
                    mimeType = mime,
                ) to canon
            }
        return result
    }

    routing {
        get("/healthz") {
            call.respond(mapOf("status" to "ok", "version" to "2.0.0"))
        }

        route("/api/v1") {
            intercept(ApplicationCallPipeline.Call) {
                if (!call.isAuthorized()) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing Bearer token"))
                    finish()
                }
            }

            // 1. Media Library Catalog
            get("/media") {
                val catalog = withContext(Dispatchers.IO) { indexMediaFiles() }
                call.respond(RemoteMediaListResponse(items = catalog.values.map { it.first }))
            }

            // 2. HTTP 206 Partial Content Video Streaming (Instant Seek for Media3 ExoPlayer)
            get("/media/{id}/stream") {
                val id = call.parameters["id"].orEmpty()
                val entry = withContext(Dispatchers.IO) { indexMediaFiles()[id] }
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Media not found"))
                    return@get
                }
                val (dto, file) = entry
                val totalLength = file.length()
                val contentType = ContentType.parse(dto.mimeType)
                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                val rangeHeader = call.request.headers[HttpHeaders.Range]
                if (rangeHeader.isNullOrBlank()) {
                    call.response.header(HttpHeaders.ContentLength, totalLength.toString())
                    call.respondOutputStream(contentType = contentType, status = HttpStatusCode.OK) {
                        file.inputStream().use { it.copyTo(this, 64 * 1024) }
                    }
                    return@get
                }

                val parsedRange = parseSingleByteRange(rangeHeader, totalLength)
                if (parsedRange == null) {
                    call.response.header(HttpHeaders.ContentRange, "bytes */$totalLength")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                    return@get
                }

                val (start, end) = parsedRange
                val sliceLength = end - start + 1
                call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalLength")
                call.response.header(HttpHeaders.ContentLength, sliceLength.toString())
                call.respondOutputStream(contentType = contentType, status = HttpStatusCode.PartialContent) {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(64 * 1024)
                        var remaining = sliceLength
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = raf.read(buf, 0, toRead)
                            if (n <= 0) break
                            write(buf, 0, n)
                            remaining -= n
                        }
                    }
                }
            }

            // 3. Zero-Knowledge E2EE Sync Snapshot (Server never sees keys or plaintext)
            get("/sync/snapshot") {
                val envelope = syncMutex.withLock {
                    withContext(Dispatchers.IO) {
                        if (!snapshotFile.isFile) null
                        else jsonFormat.decodeFromString<EncryptedSyncEnvelopeDto>(snapshotFile.readText(Charsets.UTF_8))
                    }
                }
                if (envelope == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No sync snapshot stored yet"))
                } else {
                    call.respond(envelope)
                }
            }

            put("/sync/snapshot") {
                val rawBody = call.receiveText()
                require(rawBody.toByteArray(Charsets.UTF_8).size <= config.maxSyncEnvelopeBytes) { "Sync snapshot too large" }
                val incoming = jsonFormat.decodeFromString<EncryptedSyncEnvelopeDto>(rawBody)
                require(incoming.schemaVersion == 1 && incoming.ciphertextBase64.isNotBlank()) { "Invalid encrypted envelope" }

                syncMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val current = if (snapshotFile.isFile) {
                            runCatching { jsonFormat.decodeFromString<EncryptedSyncEnvelopeDto>(snapshotFile.readText(Charsets.UTF_8)) }.getOrNull()
                        } else null
                        if (current != null && incoming.revision < current.revision) {
                            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Stale revision", "currentRevision" to current.revision.toString()))
                            return@withContext
                        }
                        val tmp = File(syncDir, "snapshot.enc.json.tmp")
                        tmp.writeText(jsonFormat.encodeToString(incoming), Charsets.UTF_8)
                        Files.move(tmp.toPath(), snapshotFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        call.respond(HttpStatusCode.OK, incoming)
                    }
                }
            }

            // 4. Encrypted Vault Ciphertext Cold-Backup Blobs
            get("/vault/blobs") {
                val blobs = withContext(Dispatchers.IO) {
                    vaultDir.listFiles().orEmpty()
                        .filter { it.isFile && SAFE_VAULT_BLOB_REGEX.matches(it.name) }
                        .sortedBy { it.name }
                        .map { f ->
                            VaultBlobMetaDto(
                                blobName = f.name,
                                sizeBytes = f.length(),
                                sha256Hex = sha256File(f),
                                updatedAtEpochMs = f.lastModified(),
                            )
                        }
                }
                call.respond(VaultBlobListResponse(blobs = blobs))
            }

            put("/vault/blobs/{name}") {
                val name = call.parameters["name"].orEmpty()
                require(SAFE_VAULT_BLOB_REGEX.matches(name)) { "Disallowed vault blob name" }
                val expectedSha = call.request.headers["X-Content-SHA256"]?.lowercase()
                val channel = call.receiveChannel()
                val tmp = File(vaultDir, "$name.tmp")
                val target = File(vaultDir, name)
                val meta = withContext(Dispatchers.IO) {
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var written = 0L
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (!channel.isClosedForRead) {
                                val n = channel.readAvailable(buf, 0, buf.size)
                                if (n < 0) break
                                if (n == 0) continue
                                written += n
                                require(written <= config.maxVaultBlobBytes) { "Vault blob exceeds size limit" }
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                            out.fd.sync()
                        }
                        require(written > 0) { "Empty vault blob" }
                        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!expectedSha.isNullOrBlank()) {
                            require(actualSha == expectedSha) { "Vault blob SHA-256 mismatch" }
                        }
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        VaultBlobMetaDto(name, written, actualSha, target.lastModified())
                    } finally {
                        tmp.delete()
                    }
                }
                call.respond(HttpStatusCode.OK, meta)
            }

            get("/vault/blobs/{name}") {
                val name = call.parameters["name"].orEmpty()
                require(SAFE_VAULT_BLOB_REGEX.matches(name)) { "Disallowed vault blob name" }
                val file = File(vaultDir, name)
                if (!file.isFile) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Vault blob not found"))
                    return@get
                }
                call.response.header(HttpHeaders.ContentLength, file.length().toString())
                call.respondOutputStream(contentType = ContentType.Application.OctetStream, status = HttpStatusCode.OK) {
                    file.inputStream().use { it.copyTo(this, 64 * 1024) }
                }
            }
        }
    }
}

internal fun parseSingleByteRange(header: String, totalLength: Long): Pair<Long, Long>? {
    if (totalLength <= 0 || !header.startsWith("bytes=")) return null
    val spec = header.removePrefix("bytes=").substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startPart = spec.substring(0, dash).trim()
    val endPart = spec.substring(dash + 1).trim()
    return if (startPart.isEmpty()) {
        val suffix = endPart.toLongOrNull() ?: return null
        if (suffix <= 0) return null
        val start = (totalLength - suffix).coerceAtLeast(0L)
        start to (totalLength - 1)
    } else {
        val start = startPart.toLongOrNull() ?: return null
        val end = if (endPart.isEmpty()) totalLength - 1 else (endPart.toLongOrNull() ?: return null)
        if (start < 0 || start >= totalLength || end < start) return null
        start to end.coerceAtMost(totalLength - 1)
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun sha256File(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
