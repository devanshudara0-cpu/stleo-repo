package com.leovonix.providers

import com.stleo.api.Log
import com.stleo.ott.SubtitleFile
import com.stleo.ott.app
import com.stleo.ott.base64Decode
import com.stleo.ott.extractors.ByseSX
import com.stleo.ott.utils.ExtractorLink
import com.stleo.ott.utils.ExtractorLinkType
import com.stleo.ott.utils.JsUnpacker
import com.stleo.ott.utils.Qualities
import com.stleo.ott.utils.getQualityFromName
import com.stleo.ott.utils.loadExtractor
import com.stleo.ott.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object LeovonixExtractors {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    private const val LINK_RESOLVE_TIMEOUT_MS = 18_000L

    suspend fun <T> List<T>.safeForEach(block: suspend (T) -> Unit) = supervisorScope {
        if (isEmpty()) return@supervisorScope
        val workerCount = minOf(6, size).coerceAtLeast(1)
        val channel = Channel<T>(16)
        repeat(workerCount) {
            launch {
                for (item in channel) {
                    runCatching { block(item) }
                        .onFailure { Log.e("LeovonixExtractors", "Task failed: ${it.message}") }
                }
            }
        }
        this@safeForEach.forEach { channel.send(it) }
        channel.close()
    }

    private fun logStep(step: String, url: String, code: Int? = null, detail: String? = null) {
        val status = if (code != null) "HTTP $code " else ""
        Log.e("LeovonixExtractors", "[$step] $status${detail ?: ""} :: $url")
    }

    fun parsePayloadUrls(data: String): List<String> {
        val trimmed = data.trim()
        val jsonCandidate = when {
            trimmed.startsWith("[") -> trimmed
            trimmed.contains("[\"http") -> "[" + trimmed.substringAfter("[")
            else -> null
        }
        if (jsonCandidate != null) {
            runCatching {
                val arr = JSONArray(jsonCandidate)
                val out = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                if (out.isNotEmpty()) return out
            }
        }
        return if (trimmed.isNotBlank()) listOf(trimmed) else emptyList()
    }

    fun linkPayload(links: List<String>, fallbackUrl: String): String =
        if (links.isEmpty()) fallbackUrl else JSONArray(links).toString()

    private val REDIRECT_REGEX = Regex(
        """s\(\s*['"]o['"]\s*,\s*['"]([A-Za-z0-9+/=]+)['"]|ck\(\s*['"]_wp_http_\d+['"]\s*,\s*['"]([^'"]+)['"]"""
    )
    private val LP_FORM_REGEX = Regex("""(?is)<form[^>]*action=["']([^"']+)["'][^>]*>(.*?)</form>""")
    private val LP_INPUT_TAG_REGEX = Regex("""(?is)<input\b[^>]*>""")
    private val INPUT_NAME_REGEX = Regex("""(?is)\bname=["']([^"']+)["']""")
    private val INPUT_VALUE_REGEX = Regex("""(?is)\bvalue=["']([^"']*)["']""")
    private val M3U8_OR_VIDEO_REGEX = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv)(?:\?[^\s"'<>]*)?""")
    private val SWITCH_SERVER_REGEX = Regex("""switchServer\('([^']+)','([^']+)','([^']+)','([^']+)','([^']*)'\)""")

    private val GARBAGE_URL_TOKENS = listOf(
        "disk.yandex.", "yadi.sk", "yandex.ru", "yandex.com",
        "how-to-download", "how_to_download", "/tutorial", "zipdisk",
        "t.me/", "t.me", "telegram.", "telegram.dog", "whatsapp", "facebook.com", "twitter.com", "x.com/",
        "instagram.com", "discord.", "reddit.com", "mailto:", "javascript:",
        "youtube.com", "youtu.be", "imdb.com", "themoviedb.org",
        "wordpress.org", "templatelens.com", "gravatar.com", "google.com/search",
        "vegamovies-apk", "vglist.", "modlist.", "mmodlist.", "joshmovies.",
        "altmovies.", "rogmovies.", "gokuhd.", "xprimehub.", "rsmovies.",
        "tellyhd.", "hdmovie20.", "snvhost.", "levelbook.", "filesgram.",
        "rockybhai.", "khelostar.", "winexch", "tinyurl.com", "bit.ly/",
        "linkskit.", "moviesdrives.bid", "moviedrive.org", "moviesdrives.cfd",
        "hdhub4u.ec", "hdhub4u.hot", "bollyflix.to", "uhdmovies.mov",
        "moviesmod.org", "hdmovie2.training", "hmovie2a.beer", "movies4u.foo",
        "vegamovies.lease", "4khdhub.one",
    )

    private val GARBAGE_TEXT_TOKENS = listOf(
        "how to download", "kaise download", "tutorial", "join telegram",
        "official app", "comment section", "batch", "zip", "pack",
        "zip extractor", "create an account", "official website",
        "telegram group", "join official", "follow us",
    )

    private val KNOWN_STREAM_OR_REDIRECT_HOSTS = listOf(
        "playdrive.", "fastdl.", "fastdlserver.", "vcloud.", "hubcloud.",
        "hcloud.", "gdirect.", "gdflix.", "only-apis.", "driveseed.",
        "driveleech.", "hubdrive.", "hubcdn.", "gdshine.", "hshare.",
        "mvlink.", "nexdrive.", "mdrive.", "modpro.", "linksmod.",
        "fxlinks.", "m4ulinks.", "hdm.", "dotflix.", "gdtot",
        "vegadrive.", "filebee.", "boldb.", "hblinks.", "gofile.",
        "pixeldrain.", "greenmotors.", "gadgetsweb.", "thenaukriadda.",
        "unblocked", "modiplay.", "vidara.", "byse", "iqsmartgames.",
        "vidbolt.", "vsembed.", "todayfiles", "minochinos.", "vidrift.",
        ".workers.dev", ".r2.dev", "r2.cloudflarestorage", "googleusercontent.com",
    )

    private fun rot13(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            out.append(
                when (ch) {
                    in 'A'..'Z' -> ('A'.code + (ch.code - 'A'.code + 13) % 26).toChar()
                    in 'a'..'z' -> ('a'.code + (ch.code - 'a'.code + 13) % 26).toChar()
                    else -> ch
                }
            )
        }
        return out.toString()
    }

    private fun isArchiveUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore("?")
        return path.endsWith(".zip") || path.endsWith(".rar") || path.endsWith(".7z") ||
            lower.contains(".zip&") || lower.contains(".zip%22") || lower.contains(".zip\"") ||
            lower.contains(".rar%22") || lower.contains(".7z%22")
    }

    fun isCandidateLink(url: String): Boolean = isCandidateLink(url, null)

    fun isCandidateLink(url: String, anchorText: String?): Boolean {
        val l = url.lowercase().trim()
        if (!l.startsWith("http") || isArchiveUrl(url)) return false
        // Allow 4khdhub.one ONLY when it's a deep movie/series page, never root promo banner
        for (token in GARBAGE_URL_TOKENS) {
            if (token == "4khdhub.one") {
                val stripped = l.removePrefix("https://").removePrefix("http://").trimEnd('/')
                if (stripped == "4khdhub.one") return false
                continue
            }
            if (l.contains(token)) return false
        }
        if (!anchorText.isNullOrBlank()) {
            val tl = anchorText.lowercase().trim()
            if (tl == "idm" || tl == "ida") return false
            if (GARBAGE_TEXT_TOKENS.any { tl.contains(it) }) return false
        }
        return true
    }

    private fun hostPriorityScore(url: String): Int {
        val l = url.lowercase()
        return when {
            l.contains(".m3u8") || l.contains(".mp4") || l.contains(".mkv") -> 110
            l.contains("playdrive.") -> 105
            l.contains("fastdl.") || l.contains("fastdlserver.") -> 95
            l.contains("hubcloud.") || l.contains("vcloud.") -> 90
            l.contains("gdflix.") || l.contains("only-apis.") -> 85
            l.contains("gdirect.") || l.contains("hcloud.") || l.contains("gdshine.") -> 80
            l.contains("driveseed.") || l.contains("driveleech.") || l.contains("hubdrive.") -> 75
            l.contains("mvlink.") || l.contains("hshare.") || l.contains("m4ulinks.") ||
                l.contains("linksmod.") || l.contains("fxlinks.") || l.contains("modpro.") ||
                l.contains("nexdrive.") || l.contains("mdrive.") || l.contains("hdm.") -> 70
            l.contains("modiplay.") || l.contains("vidara.") || l.contains("byse") || l.contains("vidbolt.") -> 65
            l.contains("greenmotors.") || l.contains("gadgetsweb.") || l.contains("thenaukriadda.") || l.contains("unblocked") -> 60
            l.contains("vegadrive.") || l.contains("pixeldrain.") || l.contains("gofile.") || l.contains("filebee.") -> 55
            l.contains("dotflix.") || l.contains("gdtot") -> 40
            else -> 10
        }
    }

    fun isKnownStreamOrRedirectHost(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".mkv") ||
            KNOWN_STREAM_OR_REDIRECT_HOSTS.any { l.contains(it) }
    }

    fun collectCandidateLinks(scope: Element, base: String): List<String> =
        scope.select("a[href], button[data-url], [data-source], [data-stream-url]")
            .mapNotNull { el ->
                val raw = el.attr("href").ifBlank { el.attr("data-url") }
                    .ifBlank { el.attr("data-source") }
                    .ifBlank { el.attr("data-stream-url") }
                val fixed = DomainManager.fixUrl(raw, base) ?: return@mapNotNull null
                if (!isCandidateLink(fixed, el.text())) return@mapNotNull null
                fixed
            }
            .distinct()

    fun collectDownloadLinks(scope: Element, base: String, maxLinks: Int = 10): List<String> {
        val baseHost = DomainManager.originOf(base).substringAfter("://").lowercase()
        val candidates = scope.select("a[href], button[data-url], [data-source], [data-stream-url]")
            .mapNotNull { el ->
                val raw = el.attr("href").ifBlank { el.attr("data-url") }
                    .ifBlank { el.attr("data-source") }
                    .ifBlank { el.attr("data-stream-url") }
                val fixed = DomainManager.fixUrl(raw, base) ?: return@mapNotNull null
                val fixedHost = DomainManager.originOf(fixed).substringAfter("://").lowercase()
                if (baseHost.isNotEmpty() && fixedHost == baseHost && !fixed.contains("embed", true)) {
                    return@mapNotNull null
                }
                if (!isCandidateLink(fixed, el.text())) return@mapNotNull null
                fixed
            }
            .distinct()

        val known = candidates.filter { isKnownStreamOrRedirectHost(it) }
        val pool = if (known.isNotEmpty()) known else candidates
        return pool
            .sortedByDescending { hostPriorityScore(it) }
            .take(maxLinks)
    }

    private fun shouldStripReferer(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("video-downloads.googleusercontent.com") ||
            u.contains("googleusercontent.com") ||
            u.contains("r2.cloudflarestorage.com") ||
            u.contains(".r2.dev") ||
            u.contains(".workers.dev") ||
            u.contains("pixeldrain.com/api/file/") ||
            u.contains("pixeldrain.dev/api/file/") ||
            u.contains("busycdn.") ||
            u.contains("instadl.") ||
            u.contains("hubcdn.")
    }

    private fun isArchiveMagic(prefix: String): Boolean {
        return prefix.startsWith("PK\u0003\u0004") ||
            prefix.startsWith("PK\u0005\u0006") ||
            prefix.startsWith("PK\u0007\u0008") ||
            prefix.startsWith("Rar!\u001A\u0007") ||
            prefix.startsWith("7z\u00BC\u00AF'\u001C")
    }

    private fun decodeBase64Safe(raw: String): String? {
        return runCatching {
            val cleaned = raw.trim().replace('-', '+').replace('_', '/')
            val pad = (4 - cleaned.length % 4) % 4
            base64Decode(cleaned + "=".repeat(pad)).trim()
        }.getOrNull()
    }

    private fun unsealPaUrl(raw: String, sealKey: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (!trimmed.startsWith("pa!~")) return null
        return runCatching {
            val b64 = trimmed.removePrefix("pa!~").replace('-', '+').replace('_', '/')
            val pad = (4 - b64.length % 4) % 4
            val bytes = android.util.Base64.decode(b64 + "=".repeat(pad), android.util.Base64.DEFAULT)
            val keyBytes = sealKey.toByteArray(Charsets.UTF_8)
            val out = ByteArray(bytes.size)
            for (i in bytes.indices) {
                out[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            val decoded = String(out, Charsets.UTF_8).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        }.getOrNull()
    }

    private fun JSONObject.optCleanString(key: String): String {
        if (isNull(key)) return ""
        val v = optString(key).trim()
        return if (v.equals("null", ignoreCase = true)) "" else v
    }

    private fun unwrapRedirectQuery(url: String): String {
        var cur = url.replace("&amp;", "&").trim()
        for (i in 0 until 8) {
            if (cur.contains("dl.php?link=")) {
                val inner = cur.substringAfter("dl.php?link=")
                if (inner.startsWith("http")) {
                    cur = inner
                    continue
                }
            }
            if (cur.contains("/dl/?link=")) {
                val inner = cur.substringAfter("/dl/?link=")
                if (inner.startsWith("http")) {
                    cur = inner
                    continue
                }
            }
            val urlParamMatch = Regex("""[?&](?:url|link|r)=([^&]+)""").find(cur) ?: break
            val decoded = runCatching { URLDecoder.decode(urlParamMatch.groupValues[1], "UTF-8") }.getOrNull()?.trim()
            if (!decoded.isNullOrBlank()) {
                if (decoded.startsWith("http")) {
                    cur = decoded
                    continue
                }
                val b64 = decodeBase64Safe(decoded)
                if (!b64.isNullOrBlank() && b64.startsWith("http")) {
                    cur = b64
                    continue
                }
            }
            break
        }
        return cur.replace(" ", "%20")
    }

    private data class ProbeResult(
        val code: Int,
        val location: String?,
        val contentType: String,
        val disposition: String,
        val prefix: String,
    )

    private suspend fun probeRange(url: String, headers: Map<String, String>): ProbeResult = withContext(Dispatchers.IO) {
        val client = app.baseClient.newBuilder()
            .followRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-511")
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            val ct = resp.header("Content-Type").orEmpty().lowercase()
            val disp = resp.header("Content-Disposition").orEmpty().lowercase()
            val loc = resp.header("Location")?.trim()
            val bytes = if (resp.code in 200..299) {
                val source = resp.body?.source()
                if (source != null) {
                    source.request(512)
                    val len = minOf(source.buffer.size, 512L).toInt()
                    val arr = ByteArray(len)
                    if (len > 0) source.readFully(arr)
                    String(arr, Charsets.ISO_8859_1)
                } else ""
            } else ""
            ProbeResult(resp.code, loc, ct, disp, bytes)
        }
    }

    private suspend fun fetchLimited(url: String, referer: String?): Pair<String, String> = withContext(Dispatchers.IO) {
        val client = app.baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val reqBuilder = Request.Builder().url(url).header("User-Agent", UA)
        if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)
        runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val landed = resp.request.url.toString()
                val src = resp.body?.source() ?: return@use "" to landed
                val max = 1_500_000
                src.request(max.toLong())
                val size = minOf(src.buffer.size, max.toLong()).toInt()
                val bytes = ByteArray(size)
                if (size > 0) src.readFully(bytes)
                String(bytes, Charsets.UTF_8) to landed
            }
        }.getOrDefault("" to url)
    }

    suspend fun emitDirectLink(
        source: String,
        label: String,
        url: String,
        referer: String = "",
        quality: Int = Qualities.Unknown.value,
        isM3u8: Boolean = url.contains(".m3u8", ignoreCase = true),
        headers: Map<String, String> = emptyMap(),
        verifyHttp: Boolean = !isM3u8,
        callback: (ExtractorLink) -> Unit,
    ) {
        var cleanUrl = unwrapRedirectQuery(url)
        if (!cleanUrl.startsWith("http") || isArchiveUrl(cleanUrl)) return

        var effectiveReferer = if (shouldStripReferer(cleanUrl)) "" else referer

        if (verifyHttp) {
            for (step in 0 until 5) {
                val stripNow = shouldStripReferer(cleanUrl)
                if (stripNow) effectiveReferer = ""
                val probeHeaders = mutableMapOf("User-Agent" to UA)
                if (effectiveReferer.isNotBlank()) probeHeaders["Referer"] = effectiveReferer
                headers.forEach { (k, v) ->
                    if (!(stripNow && k.equals("Referer", ignoreCase = true))) {
                        probeHeaders[k] = v
                    }
                }

                val probe = runCatching { probeRange(cleanUrl, probeHeaders) }.getOrElse {
                    logStep("probe-range", cleanUrl, detail = it.message)
                    return
                }

                if (probe.code in 300..399 && !probe.location.isNullOrBlank()) {
                    val nextUrl = unwrapRedirectQuery(DomainManager.fixUrl(probe.location, cleanUrl) ?: probe.location)
                    if (isArchiveUrl(nextUrl)) return
                    if (nextUrl.startsWith("http")) {
                        cleanUrl = nextUrl
                        continue
                    }
                }
                if (probe.code == 416 || probe.code == 405) {
                    break
                }
                if (probe.code >= 400) {
                    logStep("probe", cleanUrl, code = probe.code)
                    return
                }
                if (probe.disposition.contains(".zip") || probe.disposition.contains(".rar") || probe.disposition.contains(".7z")) {
                    return
                }
                if (probe.contentType.contains("application/zip") ||
                    probe.contentType.contains("application/x-zip") ||
                    probe.contentType.contains("application/x-rar") ||
                    probe.contentType.contains("application/vnd.rar") ||
                    probe.contentType.contains("application/x-7z-compressed") ||
                    isArchiveMagic(probe.prefix)
                ) {
                    logStep("archive", cleanUrl)
                    return
                }
                val looksHtml = probe.contentType.contains("text/html") ||
                    probe.prefix.trimStart().startsWith("<!", ignoreCase = true) ||
                    probe.prefix.trimStart().startsWith("<html", ignoreCase = true)
                if (looksHtml) {
                    val body = runCatching { app.get(cleanUrl, headers = probeHeaders, timeout = 10L).text }.getOrDefault("")
                    val nested = Regex("""https?://video-downloads\.googleusercontent\.com/[^\s"'<>]+""")
                        .find(body)?.value
                        ?: Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                        ?: Regex("""[?&]url=([^"'\s<>]+)""").find(body)?.groupValues?.get(1)?.let {
                            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                        }
                        ?: Jsoup.parse(body, cleanUrl).selectFirst("a[href*=workers.dev], a[href*=r2.cloudflarestorage], a[href*=r2.dev], a[href*=googleusercontent]")?.attr("href")
                    val resolvedNested = nested?.let { unwrapRedirectQuery(it) }
                    if (!resolvedNested.isNullOrBlank() && resolvedNested.startsWith("http") && !isArchiveUrl(resolvedNested)) {
                        cleanUrl = resolvedNested
                        continue
                    }
                    logStep("html", cleanUrl, code = probe.code, detail = probe.contentType)
                    return
                }
                if (probe.contentType.contains("application/json") || probe.prefix.trimStart().startsWith("{\"error")) {
                    logStep("json-error", cleanUrl, code = probe.code)
                    return
                }
                break
            }
        }

        if (isArchiveUrl(cleanUrl)) return
        val finalStrip = shouldStripReferer(cleanUrl)
        val finalReferer = if (finalStrip) "" else effectiveReferer
        val finalHeaders = buildMap<String, String> {
            put("User-Agent", UA)
            headers.forEach { (k, v) ->
                if (!(finalStrip && k.equals("Referer", ignoreCase = true))) {
                    put(k, v)
                }
            }
        }

        val link = newExtractorLink(
            source = source,
            name = label,
            url = cleanUrl,
            type = if (isM3u8 || cleanUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        ) {
            this.quality = quality
            this.referer = finalReferer
            this.headers = finalHeaders
        }
        callback(link)
    }

    suspend fun resolveRedirect(url: String, referer: String? = null): String {
        if (!url.startsWith("http")) return url
        val lower = url.lowercase()

        if ((lower.contains("hubcloud.") || lower.contains("vcloud.")) && !lower.contains("search-recover") ||
            lower.contains("gdflix.") || lower.contains("driveseed.") || lower.contains("driveleech.") ||
            lower.contains("hubdrive.") || lower.contains("gdshine.") || lower.contains("gofile.io") ||
            lower.contains("pixeldrain.") || lower.contains("hshare.") || lower.contains("m4ulinks.") ||
            lower.contains("dotflix.") || lower.contains("gdtot") || lower.contains("vegadrive.") ||
            lower.contains("filebee.")
        ) {
            return url
        }

        val cookies = mutableMapOf<String, String>()
        var currentUrl = url
        var currentRef = referer ?: url
        var postData: Map<String, String>? = null

        for (hop in 0 until 7) {
            val hdrs = mutableMapOf("User-Agent" to UA, "Referer" to currentRef)
            if (cookies.isNotEmpty()) {
                hdrs["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            val requestData = postData
            postData = null
            val res = runCatching {
                if (requestData == null) {
                    app.get(currentUrl, headers = hdrs, referer = currentRef, timeout = 10L)
                } else {
                    app.post(currentUrl, headers = hdrs, referer = currentRef, data = requestData, timeout = 10L)
                }
            }.onFailure { logStep("redirect-hop-$hop", currentUrl, detail = it.message) }.getOrNull() ?: break

            res.headers.values("set-cookie").forEach { raw ->
                val pair = raw.substringBefore(";").trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = pair.substring(0, eq).trim()
                    val v = pair.substring(eq + 1).trim()
                    if (v.isNotEmpty() && !v.equals("deleted", ignoreCase = true)) {
                        cookies[k] = v
                    }
                }
            }

            val html = res.text
            val landedUrl = res.url.ifBlank { currentUrl }

            // 1) ROT13 / double-base64 WP HTTP redirect (greenmotors / gadgetsweb)
            val combined = StringBuilder()
            for (m in REDIRECT_REGEX.findAll(html)) {
                val g1 = m.groupValues[1]
                val g2 = m.groupValues[2]
                if (g1.isNotEmpty()) combined.append(g1) else if (g2.isNotEmpty()) combined.append(g2)
            }
            if (combined.isNotEmpty()) {
                val out = runCatching {
                    val step1 = base64Decode(combined.toString())
                    val step2 = base64Decode(step1)
                    val decoded = base64Decode(rot13(step2))
                    val json = JSONObject(decoded)
                    val directO = base64Decode(json.optString("o")).trim()
                    if (directO.startsWith("http")) {
                        directO
                    } else {
                        val dataParam = base64Decode(json.optString("data")).trim()
                        val blogUrl = json.optString("blog_url").trim()
                        if (blogUrl.startsWith("http") && dataParam.isNotEmpty()) {
                            app.get("$blogUrl?re=$dataParam", headers = hdrs, timeout = 10L).document.text().trim()
                        } else ""
                    }
                }.getOrDefault("")
                if (out.startsWith("http")) return out
            }

            // 1b) Double base64 atob(atob('...')) redirect (vcloud / shortlinks)
            val atob2 = Regex("""atob\(\s*atob\(\s*["']([^"']+)["']\s*\)\s*\)""").find(html)?.groupValues?.get(1)?.let {
                runCatching { base64Decode(base64Decode(it)).trim() }.getOrNull()
            }
            if (!atob2.isNullOrBlank() && atob2.startsWith("http") && atob2 != landedUrl) {
                return atob2
            }

            // 2) _lp_ safelink Cookie + ?lp_go= bypass (thenaukriadda etc.)
            val scMatch = Regex("""sc\(\s*["'](lp-[^"']+)["']\s*,\s*["']([^"']+)["']""").find(html)
            val goMatch = Regex("""["'](https?:\\/\\/[^"'\s]+\?lp_go=lp-[^"'\s]+|https?://[^"'\s]+\?lp_go=lp-[^"'\s]+)["']""").find(html)
            if (scMatch != null && goMatch != null) {
                cookies[scMatch.groupValues[1]] = scMatch.groupValues[2]
                val goUrl = goMatch.groupValues[1].replace("\\/", "/")
                val goHdrs = mutableMapOf("User-Agent" to UA, "Referer" to landedUrl)
                goHdrs["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val finalRes = runCatching { app.get(goUrl, headers = goHdrs, allowRedirects = false, timeout = 10L) }.getOrNull()
                val loc = finalRes?.headers?.get("location")?.trim()?.replace("\\/", "/")
                if (!loc.isNullOrBlank() && loc.startsWith("http")) return loc
                val finalUrl = finalRes?.url?.trim()
                if (!finalUrl.isNullOrBlank() && finalUrl != goUrl && !finalUrl.contains("lp_go=")) return finalUrl
                val body = finalRes?.text.orEmpty()
                val locJs = Regex("""(?:location\.href|window\.location(?:\.href)?|location\.replace)\s*(?:=|\()\s*["']([^"']+)["']""")
                    .find(body)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
                if (!locJs.isNullOrBlank() && locJs.startsWith("http")) return locJs
                val directA = Jsoup.parse(body, goUrl).selectFirst("a[href*=hubcloud], a[href*=driveseed], a[href*=driveleech], a[href*=gdflix], a[href*=fastdl], a[href*=workers.dev], a[href*=r2.dev], a.btn[href]")?.attr("href")
                if (!directA.isNullOrBlank() && directA.startsWith("http")) return directA
            }

            // 3) Multi-hop _lp_ form POST (attribute-order independent)
            val formMatch = LP_FORM_REGEX.findAll(html).firstOrNull { "_lp_" in it.groupValues[2] }
            if (formMatch != null) {
                val action = DomainManager.fixUrl(formMatch.groupValues[1], landedUrl) ?: landedUrl
                val fields = mutableMapOf<String, String>()
                for (tag in LP_INPUT_TAG_REGEX.findAll(formMatch.groupValues[2])) {
                    val tagHtml = tag.value
                    val n = INPUT_NAME_REGEX.find(tagHtml)?.groupValues?.get(1)?.trim() ?: continue
                    val v = INPUT_VALUE_REGEX.find(tagHtml)?.groupValues?.get(1) ?: ""
                    fields[n] = v
                }
                if (fields.isNotEmpty()) {
                    currentRef = landedUrl
                    currentUrl = action
                    postData = fields
                    continue
                }
            }

            // 4) JS / meta redirect inside body
            val jsRedir = Regex("""(?:window\.location(?:\.href)?|location\.replace)\s*(?:=|\()\s*["'](https?[^"']+)["']""")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
            if (!jsRedir.isNullOrBlank() && jsRedir.startsWith("http") && jsRedir != landedUrl && !jsRedir.contains("cloudflare") && !jsRedir.contains("google")) {
                return jsRedir
            }

            val anchor = Jsoup.parse(html, landedUrl).selectFirst(
                "a[href*=hubcloud], a[href*=gdflix], a[href*=driveseed], a[href*=driveleech], " +
                    "a[href*=hubdrive], a[href*=gdshine], a[href*=fastdl], " +
                    "a[href*=workers.dev], a[href*=r2.cloudflarestorage], a[href*=r2.dev], " +
                    "a[href*=googleusercontent]"
            )?.attr("href")?.trim()?.replace("&amp;", "&")?.replace("\\/", "/")
            if (!anchor.isNullOrBlank() && anchor.startsWith("http") &&
                anchor != url && anchor != currentUrl && anchor != landedUrl && !isArchiveUrl(anchor) &&
                !anchor.contains("t.me") && !anchor.contains("telegram") && !anchor.contains("whatsapp")
            ) {
                return anchor
            }

            if (landedUrl != url && !landedUrl.contains("?sid=") && !landedUrl.contains("?id=")) {
                return landedUrl
            }
            break
        }
        logStep("redirect", url, detail = "no final link resolved")
        return currentUrl
    }

    suspend fun resolveLinks(
        rawUrl: String,
        sourceName: String,
        referer: String?,
        qualityHint: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ) {
        if (depth == 0) {
            withTimeoutOrNull(LINK_RESOLVE_TIMEOUT_MS) {
                resolveLinksInternal(rawUrl, sourceName, referer, qualityHint, subtitleCallback, callback, 0)
            } ?: logStep("timeout", rawUrl, detail = "exceeded ${LINK_RESOLVE_TIMEOUT_MS}ms")
        } else {
            resolveLinksInternal(rawUrl, sourceName, referer, qualityHint, subtitleCallback, callback, depth)
        }
    }

    private suspend fun resolveLinksInternal(
        rawUrl: String,
        sourceName: String,
        referer: String?,
        qualityHint: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ) {
        if (depth > 3 || rawUrl.isBlank()) return
        val cleanInput = DomainManager.fixUrl(rawUrl.replace("&amp;", "&").replace("\\/", "/"), referer ?: rawUrl) ?: return
        if (!isCandidateLink(cleanInput)) return

        val cleanLower = cleanInput.lowercase()
        val url = if (
            cleanLower.contains("greenmotors") || cleanLower.contains("gadgetsweb") ||
            cleanLower.contains("thenaukriadda") || cleanLower.contains("unblocked") ||
            cleanLower.contains("?sid=") ||
            (cleanLower.contains("?id=") &&
                !cleanLower.contains("fastdl") && !cleanLower.contains("boldb") &&
                !cleanLower.contains("hshare") && !cleanLower.contains("gdirect") &&
                !cleanLower.contains("modiplay") && !cleanLower.contains("iqsmartgames"))
        ) {
            resolveRedirect(cleanInput, referer)
        } else {
            cleanInput
        }

        val q = qualityHint ?: getQualityFromName(url)
        val lower = url.lowercase()

        try {
            when {
                lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") ||
                    lower.contains(".workers.dev/") || lower.contains("video-downloads.googleusercontent.com") ||
                    lower.contains(".r2.dev/") || lower.contains("r2.cloudflarestorage.com") -> {
                    emitDirectLink(
                        source = sourceName,
                        label = "$sourceName Direct",
                        url = url,
                        referer = referer.orEmpty(),
                        quality = q,
                        isM3u8 = lower.contains(".m3u8"),
                        callback = callback,
                    )
                }

                lower.contains("m4ulinks.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).document
                    val base = DomainManager.originOf(url)
                    val links = doc.select("a[href]")
                        .filter { a ->
                            val t = a.text()
                            !t.contains("zip", true) && !t.contains("batch", true)
                        }
                        .mapNotNull { DomainManager.fixUrl(it.attr("href").trim(), base) }
                        .filter { h ->
                            val hl = h.lowercase()
                            isCandidateLink(h) && !hl.contains("m4ulinks.") &&
                                (hl.contains("hubcloud") || hl.contains("gdflix") || hl.contains("vcloud") ||
                                    hl.contains("fastdl") || hl.contains("driveseed") || hl.contains("driveleech") ||
                                    hl.contains("hubdrive"))
                        }
                        .distinct()
                        .take(8)
                    links.safeForEach { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("linksmod.") || lower.contains("fxlinks.") || lower.contains("hdm.im/") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).document
                    val base = DomainManager.originOf(url)
                    val links = doc.select("a[href]")
                        .mapNotNull { DomainManager.fixUrl(it.attr("href").trim(), base) }
                        .filter { h ->
                            val hl = h.lowercase()
                            isCandidateLink(h) && !hl.contains("linksmod") && !hl.contains("fxlinks") &&
                                !hl.contains("uploadmaza") && !hl.contains("hdm.im")
                        }
                        .distinct()
                        .sortedByDescending { hostPriorityScore(it) }
                        .take(8)
                    links.safeForEach { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("joshapp.") || lower.contains("mvlink.") || lower.contains("modpro.") ||
                    lower.contains("nexdrive.") || lower.contains("mdrive.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).document
                    val base = DomainManager.originOf(url)
                    val links = doc.select("a[href]")
                        .filter { a ->
                            val t = a.text()
                            !t.contains("zip", true) && !t.contains("batch", true) && !t.contains("pack", true)
                        }
                        .mapNotNull { el ->
                            val href = DomainManager.fixUrl(el.attr("href"), base) ?: return@mapNotNull null
                            val hl = href.lowercase()
                            if (isCandidateLink(href, el.text()) &&
                                (hl.contains("hubcloud") || hl.contains("vcloud") || hl.contains("hubdrive") ||
                                    hl.contains("gdflix") || hl.contains("only-apis") || hl.contains("driveseed") ||
                                    hl.contains("driveleech") || hl.contains("boldb") || hl.contains("hshare") ||
                                    hl.contains("gdshine") || hl.contains("fastdl") || hl.contains("vegadrive") ||
                                    hl.contains("gofile") || hl.contains("pixeldrain") ||
                                    hl.contains("thenaukriadda") || hl.contains("greenmotors") ||
                                    hl.contains("gadgetsweb") || hl.contains("?sid=") || hl.contains("?id="))
                            ) href else null
                        }
                        .distinct()
                        .sortedByDescending { hostPriorityScore(it) }
                        .take(8)
                    links.safeForEach { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("vegadrive.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).document
                    val base = DomainManager.originOf(url)
                    val targets = doc.select("a[href]")
                        .mapNotNull { DomainManager.fixUrl(it.attr("href"), base) }
                        .filter { h ->
                            val hl = h.lowercase()
                            (hl.contains("/d/") && (hl.contains("skydrop") || hl.contains("gofile") || hl.contains("buzzheavier") || hl.contains("vikingfile"))) ||
                                hl.contains(".workers.dev") || hl.contains("r2.dev") || hl.contains("googleusercontent")
                        }
                        .distinct()
                        .take(4)
                    targets.safeForEach { t ->
                        val sub = runCatching {
                            app.get(t, referer = url, headers = mapOf("User-Agent" to UA), allowRedirects = false, timeout = 10L)
                        }.getOrNull()
                        val loc = sub?.headers?.get("location")?.trim()
                        if (!loc.isNullOrBlank() && loc.startsWith("http") && !loc.contains("vegadrive.")) {
                            resolveLinks(loc, sourceName, t, q, subtitleCallback, callback, depth + 1)
                        } else if (sub != null && sub.code in 200..299 && !sub.url.contains("vegadrive.")) {
                            resolveLinks(sub.url, sourceName, t, q, subtitleCallback, callback, depth + 1)
                        } else if (!t.contains("vegadrive.")) {
                            resolveLinks(t, sourceName, url, q, subtitleCallback, callback, depth + 1)
                        }
                    }
                }

                lower.contains("fastdl.beer") || (lower.contains("fastdl.") && !lower.contains("fastdlserver")) -> {
                    val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).text
                    val reurl = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                        ?: Jsoup.parse(html, url).selectFirst("a[href*=googleusercontent], a[href*=workers.dev], a[href*=r2.dev], a.btn[href]")?.attr("href")
                    if (!reurl.isNullOrBlank()) {
                        emitDirectLink(sourceName, "$sourceName FastDL", unwrapRedirectQuery(reurl), url, q, callback = callback)
                    }
                }

                lower.contains("hcloud.") -> {
                    val decoded = unwrapRedirectQuery(url)
                    if (decoded != url && decoded.startsWith("http") && !decoded.lowercase().contains("hcloud.")) {
                        emitDirectLink(sourceName, "$sourceName Cloud", decoded, url, q, callback = callback)
                    } else {
                        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L)
                        val landed = res.url.ifBlank { url }
                        val landedDecoded = unwrapRedirectQuery(landed)
                        if (landedDecoded != landed && landedDecoded.startsWith("http") && !landedDecoded.lowercase().contains("hcloud.")) {
                            emitDirectLink(sourceName, "$sourceName Cloud", landedDecoded, landed, q, callback = callback)
                        }
                        val links = res.document.select("a[href]")
                            .mapNotNull { DomainManager.fixUrl(it.attr("href"), landed) }
                            .filter { h ->
                                val hl = h.lowercase()
                                (hl.contains(".workers.dev") || hl.contains("googleusercontent") || hl.contains("r2.")) &&
                                    !isArchiveUrl(h)
                            }
                            .distinct()
                            .take(4)
                        if (links.isNotEmpty()) {
                            links.safeForEach { target ->
                                emitDirectLink(sourceName, "$sourceName Cloud", target, landed, q, callback = callback)
                            }
                        } else if (landedDecoded == landed) {
                            extractGenericHtmlMedia(landed, sourceName, referer, q, subtitleCallback, callback, depth)
                        }
                    }
                }

                lower.contains("gdirect.") -> {
                    val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L)
                    val landed = res.url.ifBlank { url }
                    val links = res.document.select("a[href*=googleusercontent], a[href*=workers.dev], a[href*=r2.], a.button[href], a.btn[href]")
                        .mapNotNull { DomainManager.fixUrl(it.attr("href"), landed) }
                        .filter { h -> h.startsWith("http") && !h.lowercase().contains("gdirect.") && !isArchiveUrl(h) }
                        .distinct()
                        .take(4)
                    links.safeForEach { target ->
                        emitDirectLink(sourceName, "$sourceName GDirect", target, landed, q, callback = callback)
                    }
                }

                lower.contains("playdrive.") -> {
                    val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L)
                    val landed = res.url.ifBlank { url }
                    val base = DomainManager.originOf(landed).ifBlank { DomainManager.originOf(url) }
                    val html = res.text
                    val sealKey = Regex("""window\.__PA_SEAL_KEY__\s*=\s*["']([^"']+)["']""")
                        .find(html)?.groupValues?.get(1)?.ifBlank { null } ?: "pa4u-stream-seal-v1"
                    val playRaw = Regex("""window\.__PA_PLAY__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                        .find(html)?.groupValues?.get(1)
                    var emitted = false
                    if (!playRaw.isNullOrBlank()) {
                        runCatching {
                            val obj = JSONObject(playRaw)
                            val candidates = mutableListOf<Pair<String, String>>()
                            val streams = obj.optJSONArray("streams")
                            if (streams != null) {
                                for (i in 0 until streams.length()) {
                                    val st = streams.optJSONObject(i) ?: continue
                                    val rawStreamUrl = st.optCleanString("m3u8_url").ifBlank { st.optCleanString("video_url") }
                                    val lbl = st.optCleanString("label").ifBlank { st.optCleanString("version_slug") }
                                    if (rawStreamUrl.isNotBlank()) candidates += rawStreamUrl to lbl
                                }
                            }
                            val itemUrl = obj.optJSONObject("item")?.optCleanString("video_url").orEmpty()
                            if (itemUrl.isNotBlank()) candidates += itemUrl to ""
                            for ((rawStreamUrl, lbl) in candidates) {
                                val unsealed = unsealPaUrl(rawStreamUrl, sealKey) ?: continue
                                emitted = true
                                emitDirectLink(
                                    source = sourceName,
                                    label = if (lbl.isNotBlank()) "$sourceName $lbl" else "$sourceName Stream",
                                    url = unsealed,
                                    referer = landed,
                                    quality = q,
                                    isM3u8 = true,
                                    headers = mapOf("User-Agent" to UA, "Origin" to base),
                                    verifyHttp = false,
                                    callback = callback,
                                )
                            }
                        }
                    }
                    if (!emitted) {
                        extractGenericHtmlMedia(landed, sourceName, referer, q, subtitleCallback, callback, depth)
                    }
                }

                lower.contains("boldb.") || lower.contains("hshare.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).document
                    val links = doc.select("a[href]")
                        .mapNotNull { it.attr("href").trim() }
                        .filter { h ->
                            val hl = h.lowercase()
                            hl.startsWith("http") && (
                                hl.contains(".workers.dev") || hl.contains("gdshine") ||
                                    hl.contains("hubcloud") || hl.contains("hcloud") ||
                                    hl.contains("gdirect") || hl.contains("fastdl") ||
                                    hl.contains("only-apis") || hl.contains("driveseed") ||
                                    hl.contains("driveleech") || hl.contains("gdflix") ||
                                    hl.contains(".mkv") || hl.contains(".mp4")
                                )
                        }
                        .distinct()
                        .sortedByDescending { hostPriorityScore(it) }
                        .take(5)
                    links.safeForEach { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("gdshine.") -> {
                    extractGdshine(url, sourceName, q, callback)
                }

                lower.contains("fastdlserver.") || lower.contains("gdflix.") || lower.contains("only-apis.") -> {
                    extractGdflix(url, sourceName, referer, q, subtitleCallback, callback, depth)
                }

                lower.contains("driveseed.") || lower.contains("driveleech.") -> {
                    extractDriveseed(url, sourceName, referer, q, subtitleCallback, callback, depth)
                }

                lower.contains("hblinks.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).document
                    val links = doc.select("h3 a[href], div.entry-content a[href], a.btn[href]")
                        .mapNotNull { it.attr("href").trim().takeIf { h -> isCandidateLink(h) && !h.contains("hblinks") } }
                        .distinct()
                        .take(6)
                    links.safeForEach { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("hubdrive.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).document
                    val target = doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1, a[href*=hubcloud], a[href*=vcloud], a.btn[href]")?.attr("href")?.trim()
                    if (!target.isNullOrBlank()) {
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("hubcdn.") -> {
                    extractHubCdn(url, sourceName, referer, q, callback)
                }

                lower.contains("hubcloud.") || lower.contains("vcloud.") -> {
                    extractHubCloud(url, sourceName, referer, q, subtitleCallback, callback, depth)
                }

                lower.contains("hdm2") -> {
                    extractGenericHtmlMedia(url, sourceName, referer, q, subtitleCallback, callback, depth)
                }

                lower.contains("byse") -> {
                    ByseSX().getUrl(url, referer, subtitleCallback, callback)
                }

                lower.contains("pixeldrain.") -> {
                    val id = url.substringAfterLast("/").substringBefore("?")
                    if (id.isNotBlank()) {
                        emitDirectLink(
                            source = sourceName,
                            label = "$sourceName PixelDrain",
                            url = "https://pixeldrain.dev/api/file/$id?download",
                            referer = "",
                            quality = q,
                            verifyHttp = false,
                            callback = callback,
                        )
                    }
                }

                lower.contains("modiplay.") -> {
                    extractModiplay(url, sourceName, referer, q, subtitleCallback, callback, depth)
                }

                lower.contains("vidbolt.") -> {
                    val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).text
                    val found = M3U8_OR_VIDEO_REGEX.findAll(html).map { it.value }.distinct().toList()
                    for (u in found) {
                        emitDirectLink(sourceName, "$sourceName VidBolt", u, url, q, callback = callback)
                    }
                }

                lower.contains("iqsmartgames.") -> {
                    val base = DomainManager.originOf(url)
                    val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).text
                    val embeds = Regex("""https?://[^\s"'`<>]+""").findAll(html).map { it.value }
                        .filter { u ->
                            val ul = u.lowercase()
                            !ul.contains("iqsmartgames") && !ul.contains("tmdb") &&
                                !ul.contains(".css") && !ul.contains(".js") &&
                                !ul.contains("\${") && !ul.contains("cloudflare")
                        }
                        .distinct()
                        .take(5)
                        .toList()
                    if (embeds.isNotEmpty()) {
                        embeds.safeForEach { emb ->
                            loadExtractor(emb, base, subtitleCallback, callback)
                        }
                    } else {
                        loadExtractor(url, referer, subtitleCallback, callback)
                    }
                }

                else -> {
                    val clean = if (url.startsWith("//")) "https:$url" else url
                    val ok = loadExtractor(clean, referer, subtitleCallback, callback)
                    if (!ok) {
                        extractGenericHtmlMedia(clean, sourceName, referer, q, subtitleCallback, callback, depth)
                    }
                }
            }
        } catch (e: Throwable) {
            logStep("resolve", url, detail = e.message)
        }
    }

    private suspend fun extractGdshine(
        url: String,
        sourceName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = DomainManager.originOf(url).ifBlank { "https://gdshine.org" }
        val id = url.substringAfterLast("/").substringBefore("?")
        val infoText = runCatching { app.get("$base/api/files/s/$id", headers = mapOf("User-Agent" to UA), timeout = 10L).text }
            .onFailure { logStep("gdshine-info", "$base/api/files/s/$id", detail = it.message) }
            .getOrNull() ?: return
        val infoJson = JSONObject(infoText)
        val fileId = infoJson.optJSONObject("data")?.optString("id").orEmpty()
        if (fileId.isBlank()) return
        val workerText = runCatching { app.post("$base/api/downloads/$fileId/via-worker", headers = mapOf("User-Agent" to UA), timeout = 10L).text }
            .onFailure { logStep("gdshine-worker", "$base/api/downloads/$fileId/via-worker", detail = it.message) }
            .getOrNull() ?: return
        val workerJson = JSONObject(workerText)
        val dataObj = workerJson.optJSONObject("data")
        val workerUrl = listOf(
            dataObj?.optString("copyUrl"),
            dataObj?.optString("worker_url"),
            dataObj?.optString("url"),
            workerJson.optString("copyUrl"),
            workerJson.optString("url"),
        ).firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }
        if (!workerUrl.isNullOrBlank()) {
            emitDirectLink(
                source = sourceName,
                label = "$sourceName Gdshine",
                url = workerUrl,
                referer = "",
                quality = quality,
                verifyHttp = false,
                callback = callback,
            )
        }
    }

    private suspend fun extractGdflix(
        url: String,
        sourceName: String,
        referer: String?,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int,
    ) {
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L)
        val doc = res.document
        val finalUrl = res.url.ifBlank { url }
        val base = DomainManager.originOf(finalUrl)
        val fileTitle = doc.selectFirst("li.list-group-item")?.text().orEmpty()
        if (isArchiveUrl(fileTitle)) return
        val q = getQualityFromName(fileTitle).takeIf { it != Qualities.Unknown.value } ?: quality

        val links = doc.select("a.btn[href], a[href*=workers.dev], a[href*=drivebot], a[href*=pixeldrain], a[href*=hubcloud], a[href*=gofile], a[href*=/cflare/]")
        links.toList().safeForEach { a ->
            val href = DomainManager.fixUrl(a.attr("href"), base) ?: return@safeForEach
            val text = a.text()
            val hl = href.lowercase()
            when {
                hl.contains("/cflare/") || text.contains("fast cloud", ignoreCase = true) -> {
                    val cfDoc = runCatching { app.get(href, referer = finalUrl, headers = mapOf("User-Agent" to UA), timeout = 10L).document }.getOrNull()
                    val direct = cfDoc?.select("a[href]")
                        ?.mapNotNull { el -> DomainManager.fixUrl(el.attr("href"), base)?.replace(" ", "%20") }
                        ?.firstOrNull { u ->
                            val ul = u.lowercase()
                            (ul.contains(".workers.dev") || ul.contains("googleusercontent") || ul.contains("r2.")) && !isArchiveUrl(u)
                        }
                    if (!direct.isNullOrBlank()) {
                        emitDirectLink(sourceName, "$sourceName FastCloud", direct, href, q, callback = callback)
                    }
                }
                hl.contains("?url=") || hl.contains("&url=") || hl.contains("fastcdn-dl.") -> {
                    emitDirectLink(sourceName, "$sourceName Cloud", unwrapRedirectQuery(href), finalUrl, q, callback = callback)
                }
                hl.contains(".workers.dev") || hl.contains("busycdn") || hl.contains("instadl") -> {
                    emitDirectLink(sourceName, "$sourceName Instant", href, finalUrl, q, callback = callback)
                }
                hl.contains("drivebot") -> {
                    val dDoc = runCatching { app.get(href, referer = finalUrl, headers = mapOf("User-Agent" to UA), timeout = 10L).document }.getOrNull()
                    val direct = dDoc?.selectFirst("a.btn[href*=workers], a.btn[href*=googleusercontent], a#download[href]")?.attr("href")
                    if (!direct.isNullOrBlank()) {
                        emitDirectLink(sourceName, "$sourceName DriveBot", direct, href, q, callback = callback)
                    }
                }
                hl.contains("pixeldrain") || hl.contains("gofile") || hl.contains("hubcloud") -> {
                    resolveLinks(href, sourceName, finalUrl, q, subtitleCallback, callback, depth + 1)
                }
                text.contains("Instant", ignoreCase = true) || text.contains("Direct", ignoreCase = true) || text.contains("Cloud", ignoreCase = true) -> {
                    val sub = runCatching { app.head(href, referer = finalUrl, allowRedirects = false, timeout = 8L) }.getOrNull()
                    val loc = sub?.headers?.get("location")?.trim()
                    if (!loc.isNullOrBlank() && loc.startsWith("http") && loc != href) {
                        emitDirectLink(sourceName, "$sourceName $text", loc, finalUrl, q, callback = callback)
                    }
                }
            }
        }
    }

    private suspend fun extractDriveseed(
        url: String,
        sourceName: String,
        referer: String?,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int,
    ) {
        val base = DomainManager.originOf(url)
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L)
        var doc = res.document
        var curUrl = res.url.ifBlank { url }

        val jsPath = Regex("""(?:window\.location\.replace|location\.replace)\(\s*["']([^"']+)["']\s*\)""")
            .find(res.text)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!jsPath.isNullOrBlank()) {
            val next = DomainManager.fixUrl(jsPath, base)
            if (next != null) {
                curUrl = next
                doc = app.get(next, referer = url, headers = mapOf("User-Agent" to UA), timeout = 10L).document
            }
        }

        val links = doc.select("a.btn[href], div.text-center a[href]")
        links.toList().safeForEach { a ->
            val rawHref = a.attr("href").trim()
            val href = DomainManager.fixUrl(rawHref, base) ?: return@safeForEach
            val text = a.text()
            val hl = href.lowercase()
            when {
                text.contains("Instant", ignoreCase = true) || text.contains("Direct", ignoreCase = true) || hl.contains("video-gen") -> {
                    emitDirectLink(sourceName, "$sourceName Instant", href, curUrl, quality, callback = callback)
                }
                text.contains("Resume Cloud", ignoreCase = true) || hl.contains("/zfile/") -> {
                    val rDoc = runCatching { app.get(href, referer = curUrl, headers = mapOf("User-Agent" to UA), timeout = 10L).document }.getOrNull()
                    val dLink = rDoc?.selectFirst("a.btn-success[href], a[href*=workers], a[href*=r2], a[href*=googleusercontent]")?.attr("href")
                    if (!dLink.isNullOrBlank()) {
                        emitDirectLink(sourceName, "$sourceName Cloud", dLink, href, quality, callback = callback)
                    }
                }
                hl.contains("pixeldrain") || hl.contains("gofile") || hl.contains("hubcloud") -> {
                    resolveLinks(href, sourceName, curUrl, quality, subtitleCallback, callback, depth + 1)
                }
                hl.contains(".workers.dev") || hl.contains("googleusercontent") -> {
                    emitDirectLink(sourceName, "$sourceName Drive", href, curUrl, quality, callback = callback)
                }
            }
        }
    }

    private suspend fun extractHubCdn(
        url: String,
        sourceName: String,
        referer: String?,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).text
        val reurl = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Jsoup.parse(html).selectFirst("a#vd[href], a.btn[href*=.mkv], a.btn[href*=.mp4]")?.attr("href")
        if (!reurl.isNullOrBlank() && reurl.startsWith("http")) {
            val unwrapped = unwrapRedirectQuery(reurl)
            val resolved = if (unwrapped != reurl && unwrapped.startsWith("http")) {
                unwrapped
            } else if (reurl.contains("r?key=") || reurl.contains("hubcdn")) {
                unwrapRedirectQuery(
                    app.get(reurl, referer = url, allowRedirects = false, timeout = 10L).headers["location"] ?: reurl
                )
            } else {
                reurl
            }
            emitDirectLink(sourceName, "$sourceName HubCDN", resolved, url, quality, callback = callback)
        }
    }

    private suspend fun extractHubCloud(
        url: String,
        sourceName: String,
        referer: String?,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int,
    ) {
        val base = DomainManager.originOf(url)
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L)
        val html = res.text
        val doc = res.document
        val headerTitle = doc.selectFirst(".card-header")?.text().orEmpty()
        val q = getQualityFromName(headerTitle).takeIf { it != Qualities.Unknown.value } ?: quality

        val atob2 = Regex("""atob\(\s*atob\(\s*["']([^"']+)["']\s*\)\s*\)""").find(html)?.groupValues?.get(1)?.let {
            runCatching { base64Decode(base64Decode(it)).trim() }.getOrNull()
        }
        val atob1 = if (atob2.isNullOrBlank()) {
            Regex("""var\s+url\s*=\s*atob\(\s*["']([^"']+)["']\s*\)""").find(html)?.groupValues?.get(1)?.let {
                runCatching { base64Decode(it).trim() }.getOrNull()
            }
        } else null
        val varUrl = Regex("""var\s+url\s*=\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)

        val dlBtn = atob2?.takeIf { it.startsWith("http") }
            ?: atob1?.takeIf { it.startsWith("http") }
            ?: varUrl
            ?: doc.selectFirst("#download[href], a.btn-primary[href*=/drive/], a[href*=hubcloud.php]")?.attr("href")
        val targetUrl = if (!dlBtn.isNullOrBlank()) DomainManager.fixUrl(dlBtn, base) ?: url else url
        val targetDoc = if (targetUrl != url) {
            app.get(targetUrl, referer = url, headers = mapOf("User-Agent" to UA), timeout = 10L).document
        } else {
            doc
        }

        targetDoc.select("a.btn[href], a[href*=r2.dev], a[href*=r2.cloudflarestorage], a[href*=workers.dev], a[href*=googleusercontent], a[href*=pixeldrain]").toList().safeForEach { a ->
            val href = DomainManager.fixUrl(a.attr("href").trim(), base) ?: return@safeForEach
            val text = a.text().trim()
            val hl = href.lowercase()
            val tl = text.lowercase()
            if (isArchiveUrl(href) || tl.contains("zipdisk") || tl.contains("telegram")) return@safeForEach

            when {
                hl.contains("pixeldrain") -> {
                    resolveLinks(href, sourceName, targetUrl, q, subtitleCallback, callback, depth + 1)
                }
                hl.contains("gofile") || hl.contains("hubcdn") -> {
                    resolveLinks(href, sourceName, targetUrl, q, subtitleCallback, callback, depth + 1)
                }
                tl.contains("fsl") || tl.contains("10gbps") || tl.contains("server") || tl.contains("download") ||
                    hl.contains("t3dl") || hl.contains("r2.dev") || hl.contains("r2.cloudflarestorage") ||
                    hl.contains("workers.dev") || hl.contains("googleusercontent") -> {
                    emitDirectLink(sourceName, "$sourceName $text".trim(), href, targetUrl, q, callback = callback)
                }
            }
        }
    }

    private suspend fun extractModiplay(
        url: String,
        sourceName: String,
        referer: String?,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int,
    ) {
        val base = DomainManager.originOf(url)
        val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA), timeout = 10L).text
        val doc = Jsoup.parse(html, url)

        val chipEmbeds = doc.select("[data-url], button[data-url], li[data-url]")
            .mapNotNull { DomainManager.fixUrl(it.attr("data-url"), base) }
            .filter { u -> !u.contains("modiplay", true) && isCandidateLink(u) }
            .distinct()
            .take(6)
        if (chipEmbeds.isNotEmpty()) {
            chipEmbeds.safeForEach { embed ->
                resolveLinks(embed, sourceName, url, quality, subtitleCallback, callback, depth + 1)
            }
            return
        }

        val matches = SWITCH_SERVER_REGEX.findAll(html).toList()
        if (matches.isNotEmpty()) {
            matches.take(6).safeForEach { m ->
                val embed = DomainManager.fixUrl(m.groupValues[1], base)
                if (embed != null && !embed.contains("modiplay")) {
                    resolveLinks(embed, sourceName, url, quality, subtitleCallback, callback, depth + 1)
                }
            }
            return
        }
        val embeds = Regex("""https?://[^\s"'<>]+""").findAll(html).map { it.value }
            .filter { u ->
                val ul = u.lowercase()
                !ul.contains("modiplay") && !ul.contains("tmdb") && !ul.contains("fonts.") &&
                    !ul.contains("cloudflare") && !ul.contains("multimoviesshg") &&
                    !ul.contains("big-bathroom") && !ul.contains("acscdn") &&
                    !ul.contains(".css") && !ul.contains(".js") &&
                    !ul.contains(".png") && !ul.contains(".jpg") && !ul.contains("\${")
            }
            .distinct()
            .take(6)
            .toList()
        if (embeds.isNotEmpty()) {
            embeds.safeForEach { emb ->
                resolveLinks(emb, sourceName, url, quality, subtitleCallback, callback, depth + 1)
            }
        } else {
            extractGenericHtmlMedia(url, sourceName, referer, quality, subtitleCallback, callback, depth)
        }
    }

    private suspend fun extractGenericHtmlMedia(
        url: String,
        sourceName: String,
        referer: String?,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int,
    ) {
        val fetched = fetchLimited(url, referer)
        val html = fetched.first
        if (html.isBlank()) return
        val landedUrl = fetched.second.ifBlank { url }
        val landedBase = DomainManager.originOf(landedUrl).ifBlank { DomainManager.originOf(url) }
        val unpacked = Regex("""eval\(function\(p,a,c,k,e,[rd]\).*?\.split\('\|'\)[^;)]*\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .mapNotNull { JsUnpacker(it.value).unpack() }
            .joinToString("\n")
        val combined = "$html\n$unpacked"
        val found = M3U8_OR_VIDEO_REGEX.findAll(combined).map { it.value }.distinct().take(4).toList()
        if (found.isNotEmpty()) {
            for (u in found) {
                emitDirectLink(
                    source = sourceName,
                    label = "$sourceName Stream",
                    url = u,
                    referer = landedUrl,
                    quality = quality,
                    headers = mapOf("User-Agent" to UA, "Origin" to landedBase),
                    callback = callback,
                )
            }
            return
        }
        val streamUrlAttr = Regex("""data-stream-url=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!streamUrlAttr.isNullOrBlank()) {
            val resolved = DomainManager.fixUrl(streamUrlAttr.replace("&amp;", "&"), landedBase)
            if (resolved != null) {
                emitDirectLink(
                    source = sourceName,
                    label = "$sourceName Stream",
                    url = resolved,
                    referer = landedUrl,
                    quality = quality,
                    isM3u8 = true,
                    headers = mapOf("User-Agent" to UA, "Origin" to landedBase),
                    verifyHttp = false,
                    callback = callback,
                )
            }
            return
        }
        val doc = Jsoup.parse(html, landedUrl)
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank() && iframeSrc != landedUrl && !iframeSrc.contains("about:blank") && depth < 2) {
            resolveLinks(iframeSrc, sourceName, landedUrl, quality, subtitleCallback, callback, depth + 1)
        }
        if (depth < 1) {
            val candidates = collectDownloadLinks(doc, landedBase, maxLinks = 6)
                .filter { it != landedUrl && isKnownStreamOrRedirectHost(it) }
            candidates.safeForEach { link ->
                resolveLinks(link, sourceName, landedUrl, quality, subtitleCallback, callback, depth + 1)
            }
        }
    }
}
