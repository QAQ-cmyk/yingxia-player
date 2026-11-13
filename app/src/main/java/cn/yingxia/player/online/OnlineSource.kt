package cn.yingxia.player.online

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** A compiled-in website adapter; never supplies access to native files or accounts. */
interface OnlineSource {
    val name: String
    val home: String
    val account: String
    val login: String
    fun permitsNavigation(url: String): Boolean
    fun blocksResource(url: String): Boolean
    fun permitsCosmetics(url: String): Boolean
}

object BilibiliSource : OnlineSource {
    override val name = "B 站"
    override val home = "https://m.bilibili.com/"
    override val account = "https://account.bilibili.com/account/home"
    // A bare login URL returns an existing session to its referrer or the video homepage.
    // Keep an explicit destination, including across the site's mobile login redirect.
    override val login = "https://passport.bilibili.com/login?gourl=${URLEncoder.encode(account, "UTF-8")}"
    val search = "https://m.bilibili.com/search"
    private fun parsed(url: String) = runCatching { URI(url) }.getOrNull()
    private fun site(host: String, domain: String) = host == domain || host.endsWith(".$domain")
    override fun permitsNavigation(url: String): Boolean {
        val uri = parsed(url) ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            (site(host, "bilibili.com") || host == "b23.tv")
    }
    override fun blocksResource(url: String): Boolean {
        val host = parsed(url)?.host?.lowercase() ?: return false
        return listOf("cm.bilibili.com", "ad.bilibili.com", "doubleclick.net", "googlesyndication.com").any { site(host, it) }
    }
    override fun permitsCosmetics(url: String) = permitsNavigation(url) && parsed(url)?.host in setOf("m.bilibili.com", "www.bilibili.com")
    fun isAccountPage(url: String) = permitsNavigation(url) && parsed(url)?.host?.lowercase() in
        setOf("passport.bilibili.com", "account.bilibili.com")
    fun isMobileLoginPage(url: String) = permitsNavigation(url) && parsed(url)?.let {
        it.host?.lowercase() == "passport.bilibili.com" && it.path.trimEnd('/') == "/h5-app/passport/login"
    } == true
    fun inputUrl(input: String): String? {
        val text = input.trim()
        val url = if (text.matches(Regex("BV[0-9A-Za-z]{10}"))) "https://www.bilibili.com/video/$text/" else text
        return url.takeIf(::permitsNavigation)
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').mapNotNull {
        val pair = it.split('=', limit = 2)
        if (pair.size == 2) decode(pair[0])?.let { key -> decode(pair[1])?.let { value -> key to value } } else null
    }.toMap()

    /** Resolve known App links to HTTPS. Never execute an Intent or trust its package/fallback. */
    fun appLink(url: String): String? {
        if (url.length > 8192) return null
        if (url.startsWith("intent://")) {
            val parts = url.split("#Intent;", limit = 2)
            if (parts.size != 2 || !parts[1].endsWith(";end")) return null
            val extras = parts[1].removeSuffix(";end").split(';').associate {
                val pair = it.split('=', limit = 2); pair[0] to pair.getOrElse(1) { "" }
            }
            if (extras["scheme"] == "bilibili") appLink(parts[0].replaceFirst("intent:", "bilibili:"))?.let { return it }
            return extras["S.browser_fallback_url"]?.let(::decode)?.takeIf(::permitsNavigation)
        }
        val uri = parsed(url) ?: return null
        if (uri.scheme != "bilibili" || uri.rawUserInfo != null || uri.port != -1) return null
        val path = uri.path.orEmpty().trim('/')
        val q = query(uri)
        return when (uri.host) {
            "video" -> {
                val id = q["bvid"] ?: path.ifEmpty { q["aid"].orEmpty() }
                val video = when {
                    id.matches(Regex("BV[0-9A-Za-z]{10}|av[1-9][0-9]*")) -> id
                    id.matches(Regex("[1-9][0-9]*")) -> "av$id"
                    else -> return null
                }
                // App page is zero-based; web p is one-based.
                val page = q["page"]?.toIntOrNull()?.takeIf { it in 0..9999 }?.plus(1)
                "https://m.bilibili.com/video/$video" + (page?.let { "?p=$it" } ?: "")
            }
            "space" -> path.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.let { "https://m.bilibili.com/space/$it" }
            "search" -> "$search?keyword=${URLEncoder.encode(q["keyword"].orEmpty(), "UTF-8")}"
            "bangumi" -> path.takeIf { it.matches(Regex("play/(ep|ss)[1-9][0-9]*")) }?.let { "https://m.bilibili.com/bangumi/$it" }
            "browser" -> q["url"]?.takeIf(::permitsNavigation)
            else -> null
        }
    }

    /** Ignore background App wake-ups and same-page attempts instead of reloading or notifying. */
    fun appNavigation(url: String, current: String?, hasGesture: Boolean, mainFrame: Boolean): String? {
        if (!hasGesture || !mainFrame) return null
        val target = appLink(url) ?: return null
        val next = parsed(target) ?: return null
        val previous = current?.let(::parsed)
        if (previous != null && next.host == previous.host && next.path.trimEnd('/') == previous.path.trimEnd('/')) {
            val sameParameters = if (next.path.startsWith("/video/"))
                query(next)["p"].orEmpty().ifEmpty { "1" } == query(previous)["p"].orEmpty().ifEmpty { "1" }
            else query(next) == query(previous)
            if (sameParameters) return null
        }
        return target
    }
}
