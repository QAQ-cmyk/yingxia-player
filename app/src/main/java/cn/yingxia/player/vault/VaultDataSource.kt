package cn.yingxia.player.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import cn.yingxia.player.R
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Random access decrypts authenticated segments in memory, never into a temporary video file. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultDataSource(private val store: VaultStore, private val entryId: String) : BaseDataSource(false) {
    private var reader: SeekableByteChannel? = null
    private var session: VaultSession? = null
    private var remaining = 0L
    private var currentUri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val active = store.requireSession()
            // Encrypted metadata must authenticate too; a bare file path is not a playback capability.
            val entry = store.load(entryId)
            val channel = active.open(entryId, store.payload(entryId))
            session = active; reader = channel
            if (dataSpec.position > entry.bytes) throw IOException(store.str(R.string.vault_ds_seek_oob))
            channel.position(dataSpec.position)
            remaining = entry.bytes - dataSpec.position
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
            currentUri = dataSpec.uri
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) { close(); throw IOException(store.str(R.string.vault_ds_open_failed), e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            session?.requireOpen() ?: throw IOException(store.str(R.string.vault_ds_locked))
            val n = reader!!.read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), remaining).toInt()))
            if (n < 0) throw IOException(store.str(R.string.vault_ds_truncated))
            remaining -= n; if (n > 0) bytesTransferred(n)
            return n
        } catch (e: Exception) { throw IOException(store.str(R.string.vault_ds_read_failed), e) }
    }
    override fun getUri(): Uri? = currentUri
    override fun close() {
        val active = reader; reader = null
        try { if (active != null) session?.closeReader(active) }
        finally { session = null; if (currentUri != null) transferEnded(); currentUri = null }
    }
}
