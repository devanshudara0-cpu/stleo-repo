package com.leovonix.providers

import com.stleo.api.Log
import com.stleo.ott.SubtitleFile
import com.stleo.ott.amap
import com.stleo.ott.app
import com.stleo.ott.base64Decode
import com.stleo.ott.utils.ExtractorLink
import com.stleo.ott.utils.ExtractorLinkType
import com.stleo.ott.utils.JsUnpacker
import com.stleo.ott.utils.Qualities
import com.stleo.ott.utils.getQualityFromName
import com.stleo.ott.utils.loadExtractor
import com.stleo.ott.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

object LeovonixExtractors {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    private val REDIRECT_REGEX = Regex("""s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_\d+','([^']+)'""")
    private val LP_FORM_REGEX = Regex("""(?is)<form[^>]*action=["']([^"']+)["'][^>]*>(.*?)</form>""")
    private val LP_INPUT_REGEX = Regex("""(?is)<input[^>]+name=["']([^"']+)["'][^>]+value=["']([^"']*)["']""")
    private val M3U8_OR_VIDEO_REGEX = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv)(?:\?[^\s"'<>]*)?""")
    private val SWITCH_SERVER_REGEX = Regex("""switchServer\('([^']+)','([^']+)','([^']+)','([^']+)','([^']*)'\)""")

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

    suspend fun emitDirectLink(
        source: String,
        label: String,
        url: String,
        referer: String = "",
        quality: Int = Qualities.Unknown.value,
        isM3u8: Boolean = url.contains(".m3u8", ignoreCase = true),
        headers: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = url.replace("&amp;", "&").trim()
        if (!cleanUrl.startsWith("http")) return
        val link = newExtractorLink(
            source = source,
            name = label,
            url = cleanUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        ) {
            this.quality = quality
            this.referer = referer
            if (headers.isNotEmpty()) {
                this.headers = headers
            }
        }
        callback(link)
    }

    suspend fun resolveRedirect(url: String, referer: String? = null): String {
        if (!url.startsWith("http")) return url
        val lower = url.lowercase()

        // Fast-path: already a final host
        if (lower.contains("hubcloud.") && !lower.contains("search-recover") ||
            lower.contains("gdflix.") || lower.contains("driveseed.") || lower.contains("driveleech.") ||
            lower.contains("hubdrive.") || lower.contains("gdshine.") || lower.contains("gofile.io") ||
            lower.contains("pixeldrain.")
        ) {
            return url
        }

        // Handle _lp_ safelinks (thenaukriadda, tech.*, unblocked*) or ROT13 JS redirectors (greenmotors, gadgetsweb)
        val cookies = mutableMapOf<String, String>()
        var currentUrl = url
        var currentRef = referer ?: url
        var postData: Map<String, String>? = null

        for (hop in 0 until 6) {
            val hdrs = mutableMapOf("User-Agent" to UA, "Referer" to currentRef)
            if (cookies.isNotEmpty()) {
                hdrs["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            val res = runCatching {
                if (postData == null) {
                    app.get(currentUrl, headers = hdrs, referer = currentRef)
                } else {
                    app.post(currentUrl, headers = hdrs, referer = currentRef, data = postData)
                }
            }.getOrNull() ?: break

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
                            app.get("$blogUrl?re=$dataParam", headers = hdrs).document.text().trim()
                        } else ""
                    }
                }.getOrDefault("")
                if (out.startsWith("http")) return out
            }

            // 2) _lp_ safelink Cookie + ?lp_go= bypass (thenaukriadda etc.)
            val scMatch = Regex("""sc\(\s*["'](lp-[^"']+)["']\s*,\s*["']([^"']+)["']""").find(html)
            val goMatch = Regex("""["'](https?:\\/\\/[^"'\s]+\?lp_go=lp-[^"'\s]+|https?://[^"'\s]+\?lp_go=lp-[^"'\s]+)["']""").find(html)
            if (scMatch != null && goMatch != null) {
                cookies[scMatch.groupValues[1]] = scMatch.groupValues[2]
                val goUrl = goMatch.groupValues[1].replace("\\/", "/")
                val goHdrs = mutableMapOf("User-Agent" to UA, "Referer" to landedUrl)
                goHdrs["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val finalRes = runCatching { app.get(goUrl, headers = goHdrs, allowRedirects = false) }.getOrNull()
                val loc = finalRes?.headers?.get("location")?.trim()
                if (!loc.isNullOrBlank() && loc.startsWith("http")) return loc
                val finalUrl = finalRes?.url?.trim()
                if (!finalUrl.isNullOrBlank() && finalUrl != goUrl && !finalUrl.contains("lp_go=")) return finalUrl
                val body = finalRes?.text.orEmpty()
                val locJs = Regex("""(?:location\.href|window\.location(?:\.href)?|location\.replace)\s*(?:=|\()\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                if (!locJs.isNullOrBlank() && locJs.startsWith("http")) return locJs
                val directA = Jsoup.parse(body, goUrl).selectFirst("a[href*=hubcloud], a[href*=driveseed], a[href*=driveleech], a[href*=gdflix], a[href*=fastdl], a[href*=workers.dev], a.btn[href]")?.attr("href")
                if (!directA.isNullOrBlank() && directA.startsWith("http")) return directA
            }

            // 3) Multi-hop _lp_ form POST
            val formMatch = LP_FORM_REGEX.findAll(html).firstOrNull { "_lp_" in it.groupValues[2] }
            if (formMatch != null) {
                val action = DomainManager.fixUrl(formMatch.groupValues[1], landedUrl) ?: landedUrl
                val fields = mutableMapOf<String, String>()
                for (inp in LP_INPUT_REGEX.findAll(formMatch.groupValues[2])) {
                    fields[inp.groupValues[1]] = inp.groupValues[2]
                }
                if (fields.isNotEmpty()) {
                    currentRef = landedUrl
                    currentUrl = action
                    postData = fields
                    continue
                }
            }

            // 4) JS / meta redirect inside body
            val jsRedir = Regex("""(?:window\.location(?:\.href)?|location\.replace)\s*(?:=|\()\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!jsRedir.isNullOrBlank() && jsRedir != landedUrl && !jsRedir.contains("cloudflare") && !jsRedir.contains("google")) {
                return jsRedir
            }

            // 5) Check landedUrl change
            if (landedUrl != url && !landedUrl.contains("?sid=") && !landedUrl.contains("?id=")) {
                return landedUrl
            }
            break
        }
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
        if (depth > 4 || rawUrl.isBlank()) return
        val cleanInput = DomainManager.fixUrl(rawUrl.replace("&amp;", "&"), referer ?: rawUrl) ?: return
        val url = if (
            cleanInput.contains("greenmotors") || cleanInput.contains("gadgetsweb") ||
            cleanInput.contains("thenaukriadda") || cleanInput.contains("unblocked") ||
            cleanInput.contains("?sid=") || (cleanInput.contains("?id=") && !cleanInput.contains("fastdl") && !cleanInput.contains("boldb"))
        ) {
            resolveRedirect(cleanInput, referer)
        } else {
            cleanInput
        }

        val q = qualityHint ?: getQualityFromName(url)
        val lower = url.lowercase()

        try {
            when {
                lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".workers.dev/") -> {
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

                lower.contains("linksmod.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).document
                    val links = doc.select("a[href]")
                        .mapNotNull { it.attr("href").trim().takeIf { h -> h.startsWith("http") && !h.contains("linksmod") && !h.contains("uploadmaza") && !h.contains("t.me") } }
                        .distinct()
                    links.amap { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("joshapp.") || lower.contains("modpro.") || lower.contains("nexdrive.") || lower.contains("mdrive.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).document
                    val base = DomainManager.originOf(url)
                    val links = doc.select("a[href]")
                        .mapNotNull { el ->
                            val href = DomainManager.fixUrl(el.attr("href"), base) ?: return@mapNotNull null
                            val hl = href.lowercase()
                            if (hl.startsWith("http") &&
                                !hl.contains("t.me") && !hl.contains("telegram") && !hl.contains("whatsapp") &&
                                !hl.contains("wordpress.org") && !hl.contains("templatelens") &&
                                (hl.contains("hubcloud") || hl.contains("hubdrive") || hl.contains("gdflix") ||
                                    hl.contains("only-apis") || hl.contains("driveseed") || hl.contains("driveleech") ||
                                    hl.contains("boldb") || hl.contains("gdshine") || hl.contains("fastdl") ||
                                    hl.contains("vegadrive") || hl.contains("gofile") || hl.contains("pixeldrain") ||
                                    hl.contains("thenaukriadda") || hl.contains("greenmotors") || hl.contains("gadgetsweb") ||
                                    hl.contains("?sid=") || hl.contains("?id="))
                            ) href else null
                        }
                        .distinct()
                    links.amap { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("boldb.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).document
                    val links = doc.select("a[href]")
                        .mapNotNull { it.attr("href").trim() }
                        .filter { h ->
                            val hl = h.lowercase()
                            hl.startsWith("http") && (hl.contains(".workers.dev") || hl.contains("gdshine") || hl.contains("hubcloud") || hl.contains("gdflix") || hl.contains(".mkv") || hl.contains(".mp4"))
                        }
                        .distinct()
                    links.amap { target ->
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
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).document
                    val links = doc.select("h3 a[href], div.entry-content a[href], a.btn[href]")
                        .mapNotNull { it.attr("href").trim().takeIf { h -> h.startsWith("http") && !h.contains("hblinks") && !h.contains("t.me") } }
                        .distinct()
                    links.amap { target ->
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("hubdrive.") -> {
                    val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).document
                    val target = doc.selectFirst(".btn.btn-primary.btn-user.btn-success1.m-1, a[href*=hubcloud], a.btn[href]")?.attr("href")?.trim()
                    if (!target.isNullOrBlank()) {
                        resolveLinks(target, sourceName, url, q, subtitleCallback, callback, depth + 1)
                    }
                }

                lower.contains("hubcdn.") -> {
                    extractHubCdn(url, sourceName, referer, q, callback)
                }

                lower.contains("hubcloud.") -> {
                    val ok = loadExtractor(url, referer, subtitleCallback, callback)
                    if (!ok) {
                        extractHubCloudFallback(url, sourceName, referer, q, subtitleCallback, callback, depth)
                    }
                }

                lower.contains("modiplay.") -> {
                    extractModiplay(url, sourceName, referer, q, subtitleCallback, callback, depth)
                }

                lower.contains("iqsmartgames.") -> {
                    val id = url.substringAfterLast("embed/")
                    val base = DomainManager.originOf(url)
                    val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).text
                    val embeds = Regex("""https?://[^\s"'`<>]+""").findAll(html).map { it.value }
                        .filter { u ->
                            val ul = u.lowercase()
                            !ul.contains("iqsmartgames") && !ul.contains("tmdb") &&
                                !ul.contains(".css") && !ul.contains(".js") &&
                                !ul.contains("\${")
                        }
                        .distinct()
                        .toList()
                    if (embeds.isNotEmpty()) {
                        embeds.amap { emb ->
                            loadExtractor(emb, base, subtitleCallback, callback)
                        }
                    } else {
                        loadExtractor(url, referer, subtitleCallback, callback)
                    }
                    Log.d("LeovonixExtractors", "Resolved iqsmartgames id=$id (${embeds.size} embeds)")
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
            Log.e("LeovonixExtractors", "Failed extracting $url: ${e.message}")
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
        val infoJson = JSONObject(app.get("$base/api/files/s/$id", headers = mapOf("User-Agent" to UA)).text)
        val fileId = infoJson.optJSONObject("data")?.optString("id").orEmpty()
        if (fileId.isBlank()) return
        val workerJson = JSONObject(app.post("$base/api/downloads/$fileId/via-worker", headers = mapOf("User-Agent" to UA)).text)
        val workerUrl = workerJson.optJSONObject("data")?.optString("worker_url")
            ?: workerJson.optJSONObject("data")?.optString("url")
            ?: workerJson.optString("url")
        if (!workerUrl.isNullOrBlank() && workerUrl.startsWith("http")) {
            emitDirectLink(sourceName, "$sourceName Gdshine", workerUrl, base, quality, callback = callback)
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
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA))
        val doc = res.document
        val finalUrl = res.url.ifBlank { url }
        val base = DomainManager.originOf(finalUrl)
        val fileTitle = doc.selectFirst("li.list-group-item")?.text().orEmpty()
        val q = getQualityFromName(fileTitle).takeIf { it != com.stleo.ott.utils.Qualities.Unknown.value } ?: quality

        val links = doc.select("a.btn[href], a[href*=workers.dev], a[href*=drivebot], a[href*=pixeldrain], a[href*=hubcloud], a[href*=gofile]")
        links.toList().amap { a ->
            val href = DomainManager.fixUrl(a.attr("href"), base) ?: return@amap
            val text = a.text()
            val hl = href.lowercase()
            when {
                hl.contains(".workers.dev") || hl.contains("busycdn") || hl.contains("instadl") -> {
                    emitDirectLink(sourceName, "$sourceName Instant", href, finalUrl, q, callback = callback)
                }
                hl.contains("drivebot") -> {
                    val dDoc = runCatching { app.get(href, referer = finalUrl, headers = mapOf("User-Agent" to UA)).document }.getOrNull()
                    val direct = dDoc?.selectFirst("a.btn[href*=workers], a.btn[href*=googleusercontent], a#download[href]")?.attr("href")
                    if (!direct.isNullOrBlank()) {
                        emitDirectLink(sourceName, "$sourceName DriveBot", direct, href, q, callback = callback)
                    }
                }
                hl.contains("pixeldrain") || hl.contains("gofile") || hl.contains("hubcloud") -> {
                    resolveLinks(href, sourceName, finalUrl, q, subtitleCallback, callback, depth + 1)
                }
                text.contains("Instant", ignoreCase = true) || text.contains("Direct", ignoreCase = true) || text.contains("Cloud", ignoreCase = true) -> {
                    val sub = runCatching { app.get(href, referer = finalUrl, allowRedirects = false) }.getOrNull()
                    val loc = sub?.headers?.get("location") ?: sub?.url
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
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA))
        var doc = res.document
        var curUrl = res.url.ifBlank { url }

        // Handle JS redirect window.location.replace("/zfile/...") or "/file/..."
        val jsPath = Regex("""window\.location\.replace\(["']([^"']+)["']\)""").find(res.text)?.groupValues?.get(1)
        if (!jsPath.isNullOrBlank()) {
            val next = DomainManager.fixUrl(jsPath, base)
            if (next != null) {
                curUrl = next
                doc = app.get(next, referer = url, headers = mapOf("User-Agent" to UA)).document
            }
        }

        val links = doc.select("a.btn[href], div.text-center a[href]")
        links.toList().amap { a ->
            val rawHref = a.attr("href").trim()
            val href = DomainManager.fixUrl(rawHref, base) ?: return@amap
            val text = a.text()
            val hl = href.lowercase()
            when {
                text.contains("Instant", ignoreCase = true) || text.contains("Direct", ignoreCase = true) -> {
                    val inst = runCatching { app.get(href, referer = curUrl, allowRedirects = false) }.getOrNull()
                    val loc = inst?.headers?.get("location") ?: Regex("""["'](https?://[^"']+\.(?:workers\.dev|r2\.dev)[^"']*)["']""").find(inst?.text.orEmpty())?.groupValues?.get(1)
                    if (!loc.isNullOrBlank()) {
                        emitDirectLink(sourceName, "$sourceName Instant", loc, curUrl, quality, callback = callback)
                    }
                }
                text.contains("Resume Cloud", ignoreCase = true) || hl.contains("/zfile/") -> {
                    val rDoc = runCatching { app.get(href, referer = curUrl, headers = mapOf("User-Agent" to UA)).document }.getOrNull()
                    val dLink = rDoc?.selectFirst("a.btn-success[href], a[href*=workers], a[href*=r2]")?.attr("href")
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
        val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).text
        val reurl = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Jsoup.parse(html).selectFirst("a#vd[href], a.btn[href*=.mkv], a.btn[href*=.mp4]")?.attr("href")
        if (!reurl.isNullOrBlank() && reurl.startsWith("http")) {
            val resolved = if (reurl.contains("r?key=") || reurl.contains("hubcdn")) {
                app.get(reurl, referer = url, allowRedirects = false).headers["location"] ?: reurl
            } else {
                reurl
            }
            emitDirectLink(sourceName, "$sourceName HubCDN", resolved, url, quality, callback = callback)
        }
    }

    private suspend fun extractHubCloudFallback(
        url: String,
        sourceName: String,
        referer: String?,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int,
    ) {
        val doc = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).document
        val dlBtn = doc.selectFirst("#download[href], a.btn-primary[href*=/drive/], a[href*=hubcloud.php]")?.attr("href")
        val targetDoc = if (!dlBtn.isNullOrBlank() && dlBtn != url) {
            app.get(DomainManager.fixUrl(dlBtn, DomainManager.originOf(url)) ?: url, referer = url, headers = mapOf("User-Agent" to UA)).document
        } else {
            doc
        }
        targetDoc.select("a.btn[href]").toList().amap { a ->
            val href = a.attr("href").trim()
            val text = a.text()
            val hl = href.lowercase()
            when {
                hl.contains("pixeldrain") || hl.contains("gofile") || hl.contains("hubcdn") -> {
                    resolveLinks(href, sourceName, url, quality, subtitleCallback, callback, depth + 1)
                }
                hl.contains("fsl") || hl.contains("r2.dev") || hl.contains("workers.dev") || text.contains("FSL", true) || text.contains("10Gbps", true) -> {
                    emitDirectLink(sourceName, "$sourceName $text", href, url, quality, callback = callback)
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
        val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).text
        val matches = SWITCH_SERVER_REGEX.findAll(html).toList()
        if (matches.isNotEmpty()) {
            matches.amap { m ->
                val embed = DomainManager.fixUrl(m.groupValues[1], base)
                if (embed != null && !embed.contains("modiplay")) {
                    resolveLinks(embed, sourceName, url, quality, subtitleCallback, callback, depth + 1)
                }
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
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to UA))
        val html = res.text
        val unpacked = Regex("""eval\(function\(p,a,c,k,e,[rd].*?</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .mapNotNull { JsUnpacker(it.value).unpack() }
            .joinToString("\n")
        val combined = "$html\n$unpacked"
        val found = M3U8_OR_VIDEO_REGEX.findAll(combined).map { it.value }.distinct().toList()
        if (found.isNotEmpty()) {
            for (u in found) {
                emitDirectLink(sourceName, "$sourceName Stream", u, url, quality, callback = callback)
            }
            return
        }
        val streamUrlAttr = Regex("""data-stream-url=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!streamUrlAttr.isNullOrBlank()) {
            val resolved = DomainManager.fixUrl(streamUrlAttr, DomainManager.originOf(url))
            if (resolved != null) {
                emitDirectLink(sourceName, "$sourceName Stream", resolved, url, quality, isM3u8 = true, callback = callback)
            }
            return
        }
        val doc = Jsoup.parse(html, url)
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank() && iframeSrc != url && !iframeSrc.contains("about:blank") && depth < 3) {
            resolveLinks(iframeSrc, sourceName, url, quality, subtitleCallback, callback, depth + 1)
        }
    }
}
