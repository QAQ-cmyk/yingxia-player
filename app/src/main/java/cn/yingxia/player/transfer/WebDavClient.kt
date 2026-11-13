package cn.yingxia.player.transfer

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class WebDavFile(val path: String, val name: String, val size: Long)

/** Talks to a WebDAV folder the user already has. Nothing is uploaded to an 映匣 server. */
class WebDavClient(rawBase: String, private val user: String, private val password: String) {
    private val base = rawBase.trim().trimEnd('/')
    fun list(): List<WebDavFile> {
        val xml = """<?xml version="1.0" encoding="utf-8" ?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/></d:prop></d:propfind>"""
        val response = call("PROPFIND", "$base/", xml.byteInputStream(), xml.length.toLong(), mapOf("Depth" to "1"))
        val origin = URL(base).let { "${it.protocol}://${it.authority}" }
        return Regex("<[^>]*href>([^<]+)</[^>]*href>", RegexOption.IGNORE_CASE).findAll(response).map { it.groupValues[1].trim() }
            .filter { !it.endsWith("/") }.map { href ->
                val name = java.net.URLDecoder.decode(href.substringAfterLast('/').substringBefore('?'), "UTF-8")
                val path = when {
                    href.startsWith("http") -> href
                    href.startsWith("/") -> origin + href
                    else -> "$base/$href"
                }
                WebDavFile(path, name, 0L)
            }.filter { '.' in it.name }.distinctBy { it.path }.toList()
    }
    fun upload(name: String, input: InputStream, length: Long, progress: (Long) -> Unit) {
        val target = base + "/" + java.net.URLEncoder.encode(TransferProtocol.safeFileName(name), "UTF-8").replace("+", "%20")
        call("PUT", target, input, length, emptyMap(), progress)
    }
    fun openDownload(file: WebDavFile): Pair<HttpURLConnection, Long> {
        val connection = open("GET", file.path.ifBlank { "$base/${file.name}" }, null, null, emptyMap())
        if (connection.responseCode !in 200..299) error("云端返回 ${connection.responseCode}")
        return connection to connection.contentLengthLong.coerceAtLeast(0)
    }
    private fun call(method: String, url: String, body: InputStream?, length: Long?, headers: Map<String, String>, progress: (Long) -> Unit = {}): String {
        val connection = open(method, url, body, length, headers, progress)
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299 && connection.responseCode != 207) error("云端返回 ${connection.responseCode}")
        return text
    }
    private fun open(method: String, url: String, body: InputStream?, length: Long?, headers: Map<String, String>, progress: (Long) -> Unit = {}): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 60_000
            doInput = true
            if (user.isNotBlank()) setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray()))
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null && length != null) {
                doOutput = true
                setFixedLengthStreamingMode(length)
                outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (sent < length) {
                        val read = body.read(buffer, 0, minOf(buffer.size.toLong(), length - sent).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read); sent += read; progress(sent)
                    }
                }
            }
        }
        return connection
    }
}
