package cn.yingxia.player

import cn.yingxia.player.transfer.TransferProtocol
import org.junit.Assert.*
import org.junit.Test

class TransferProtocolTest {
    @Test fun listRoundTripKeepsIdNameAndSize() {
        val encoded = TransferProtocol.encodeList(listOf(Triple("ms:primary:7", "海边.mp4", 2048L), Triple("uri:abc", "笔记", 3L)))
        assertEquals(listOf(Triple("ms:primary:7", "海边.mp4", 2048L), Triple("uri:abc", "笔记", 3L)), TransferProtocol.decodeList(encoded))
    }
    @Test fun fileNameDropsPathCharactersAndKeepsAnExtension() {
        assertEquals("海边_夜.mp4", TransferProtocol.safeFileName("../海边:夜.mp4"))
        assertEquals("映匣视频.mp4", TransferProtocol.safeFileName("   "))
        assertEquals("课堂.mov", TransferProtocol.safeFileName("课堂.mov"))
    }
}
