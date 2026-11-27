package cn.yingxia.server

import cn.yingxia.protocol.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class YingXiaServerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val token = "yx-secret-test-token-2026-private"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun enforcesAuthStreamsHttp206RangesAndSyncsZeroKnowledgeBlobs() = testApplication {
        val mediaDir = temp.newFolder("media")
        val subFolder = File(mediaDir, "Study").apply { mkdirs() }
        val sampleBytes = ByteArray(4096) { (it % 251).toByte() }
        File(subFolder, "demo-course.mp4").writeBytes(sampleBytes)
        val dataDir = temp.newFolder("data")

        application {
            configureYingXiaServer(YingXiaServerConfig(mediaDir, dataDir, token))
        }

        // 1. Unauthorized requests must be rejected
        val unauth = client.get("/api/v1/media")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        // 2. Authorized catalog listing
        val catalogResp = client.get("/api/v1/media") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, catalogResp.status)
        val catalog = json.decodeFromString<RemoteMediaListResponse>(catalogResp.bodyAsText())
        assertEquals(1, catalog.items.size)
        val item = catalog.items.single()
        assertEquals("demo-course.mp4", item.name)
        assertEquals("Study", item.folder)
        assertEquals(4096L, item.sizeBytes)

        // 3. HTTP 206 Partial Content instant seek verification
        val rangeResp = client.get("/api/v1/media/${item.id}/stream") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Range, "bytes=100-299")
        }
        assertEquals(HttpStatusCode.PartialContent, rangeResp.status)
        assertEquals("bytes 100-299/4096", rangeResp.headers[HttpHeaders.ContentRange])
        val slice = rangeResp.readRawBytes()
        assertEquals(200, slice.size)
        assertArrayEquals(sampleBytes.copyOfRange(100, 300), slice)

        // Out-of-bounds range returns 416
        val badRange = client.get("/api/v1/media/${item.id}/stream") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Range, "bytes=9000-9500")
        }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, badRange.status)

        // 4. Zero-Knowledge E2EE Snapshot Push & Pull
        val plain = PlainSyncSnapshotDto(
            exportedAtEpochMs = 1_795_680_000_000L,
            records = listOf(SyncVideoRecordDto("ms:1", "", "demo-course.mp4", 4096L, true, 15_000L, 1_795_679_000_000L, "Custom Name", false)),
            bookmarks = listOf(SyncBookmarkDto("demo-course.mp4", 4096L, 5_000L, "Important slide")),
        )
        val sealed = ZeroKnowledgeCrypto.sealSnapshot(plain, "PrivateSyncPass#2026".toCharArray(), revision = 2L)
        val putSync = client.put("/api/v1/sync/snapshot") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(sealed))
        }
        assertEquals(HttpStatusCode.OK, putSync.status)

        // Stale revision must be rejected with 409 Conflict
        val staleSync = client.put("/api/v1/sync/snapshot") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(sealed.copy(revision = 1L)))
        }
        assertEquals(HttpStatusCode.Conflict, staleSync.status)

        val getSync = client.get("/api/v1/sync/snapshot") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getSync.status)
        val fetchedEnv = json.decodeFromString<EncryptedSyncEnvelopeDto>(getSync.bodyAsText())
        val decrypted = ZeroKnowledgeCrypto.openSnapshot(fetchedEnv, "PrivateSyncPass#2026".toCharArray())
        assertEquals(plain, decrypted)

        // 5. Vault ciphertext cold-backup blob with SHA-256 check & traversal block
        val blobBytes = ByteArray(512) { (it xor 0x5A).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(blobBytes).joinToString("") { "%02x".format(it) }
        val blobId = "123e4567-e89b-12d3-a456-426614174000.vault"
        val putBlob = client.put("/api/v1/vault/blobs/$blobId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Content-SHA256", sha)
            setBody(blobBytes)
        }
        assertEquals(HttpStatusCode.OK, putBlob.status)

        val listBlobs = client.get("/api/v1/vault/blobs") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val blobList = json.decodeFromString<VaultBlobListResponse>(listBlobs.bodyAsText())
        assertEquals(1, blobList.blobs.size)
        assertEquals(blobId, blobList.blobs.single().blobName)

        val badName = client.put("/api/v1/vault/blobs/..%2Fevil.vault") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(blobBytes)
        }
        assertNotEquals(HttpStatusCode.OK, badName.status)
    }
}
