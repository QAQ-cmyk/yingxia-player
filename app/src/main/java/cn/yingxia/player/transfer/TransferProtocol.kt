package cn.yingxia.player.transfer

/** Line format: id, display name, size. Tabs and newlines in names are flattened. */
object TransferProtocol {
    const val SERVICE_TYPE = "_yingxia._tcp."
    fun encodeList(items: List<Triple<String, String, Long>>): String = items.joinToString("\n") { (id, name, size) ->
        listOf(flatten(id), flatten(name), size.toString()).joinToString("\t")
    }
    fun decodeList(body: String): List<Triple<String, String, Long>> = body.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val parts = line.split('\t')
        if (parts.size < 3) null else Triple(parts[0], parts[1], parts[2].toLongOrNull() ?: return@mapNotNull null)
    }.toList()
    fun safeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim('_', '.', ' ').take(80)
        val base = cleaned.ifBlank { "映匣视频" }
        return if ('.' in base) base else "$base.mp4"
    }
    private fun flatten(value: String) = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
