package com.leovonix.providers

import com.stleo.api.Log
import com.stleo.ott.SearchQuality
import com.stleo.ott.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object DomainManager {
    private val DOMAINS_URLS = listOf(
        "https://raw.githubusercontent.com/devanshudara0-cpu/stleo-repo/main/domains.json",
        "https://cdn.jsdelivr.net/gh/devanshudara0-cpu/stleo-repo@main/domains.json",
    )
    private const val TTL_MS = 6L * 60L * 60L * 1000L
    private const val BLOCKED_TTL_MS = 15L * 60L * 1000L

    private val mutex = Mutex()

    @Volatile
    private var cachedDomains: Map<String, String> = emptyMap()

    @Volatile
    private var cachedMirrors: Map<String, List<String>> = emptyMap()

    @Volatile
    private var lastFetchedAt: Long = 0L

    private val liveOverrides = ConcurrentHashMap<String, String>()
    private val blockedOrigins = ConcurrentHashMap<String, Long>()

    private val BUILTIN_MIRRORS: Map<String, List<String>> = mapOf(
        "4khdhub" to listOf("https://4khdhub.one"),
        "hdhub4u" to listOf("https://new17.hdhub4u.cl", "https://hdhub4u.ec", "https://hdhub4u.hot"),
        "uhdmovies" to listOf("https://uhdmovies.ceo", "https://uhdmovies.mov"),
        "hdmovie2" to listOf("https://hdmovie2a.asia", "https://hmovie2a.beer", "https://hdmovie2.training"),
        "hindmoviez" to listOf("https://v5.hindmovie.im"),
        "moviesdrive" to listOf("https://new5.moviesdrive.cyou", "https://moviesdrives.bid", "https://moviedrive.org", "https://moviesdrives.cfd"),
        "bollyflix" to listOf("https://new1.bollyflix.tours", "https://bollyflix.to"),
        "moviesmod" to listOf("https://moviesmod.free", "https://moviesmod.org"),
        "vegamovies" to listOf("https://vegamovies.latino", "https://vegamovies.lease"),
        "multimovies" to listOf("https://m1.multimovies.courses"),
        "rogmovies" to listOf("https://rogmovies.beauty"),
        "movies4u" to listOf("https://new9.movies4u.garden", "https://movies4u.foo"),
        "zinkmovies" to listOf("https://zinkmovies.casa"),
    )

    private suspend fun refreshDomains(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && cachedDomains.isNotEmpty() && now - lastFetchedAt < TTL_MS) return
        mutex.withLock {
            val recheck = System.currentTimeMillis()
            if (!force && cachedDomains.isNotEmpty() && recheck - lastFetchedAt < TTL_MS) return@withLock
            for (endpoint in DOMAINS_URLS) {
                val parsed = runCatching {
                    val text = app.get(endpoint, timeout = 8L).text
                    val obj = JSONObject(text)
                    val map = mutableMapOf<String, String>()
                    val mirrors = mutableMapOf<String, List<String>>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "mirrors") {
                            val mObj = obj.optJSONObject("mirrors") ?: continue
                            val mKeys = mObj.keys()
                            while (mKeys.hasNext()) {
                                val mk = mKeys.next()
                                val arr = mObj.optJSONArray(mk) ?: JSONArray()
                                val list = (0 until arr.length())
                                    .mapNotNull { arr.optString(it).trim().trimEnd('/').takeIf { u -> u.startsWith("http") } }
                                    .distinct()
                                if (list.isNotEmpty()) mirrors[mk] = list
                            }
                        } else {
                            val v = obj.optString(k).trim().trimEnd('/')
                            if (v.startsWith("http")) {
                                map[k] = v
                            }
                        }
                    }
                    if (map.isNotEmpty()) {
                        cachedDomains = map
                        if (mirrors.isNotEmpty()) cachedMirrors = mirrors
                        lastFetchedAt = recheck
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (parsed) break
            }
            if (lastFetchedAt == 0L) lastFetchedAt = recheck
        }
    }

    suspend fun getDomain(key: String, fallback: String): String {
        liveOverrides[key]?.let { override ->
            if (!isOriginTemporarilyBlocked(override)) return override
        }
        refreshDomains(force = false)
        val primary = cachedDomains[key]?.trimEnd('/')?.takeIf { it.startsWith("http") }
            ?: fallback.trimEnd('/')
        if (!isOriginTemporarilyBlocked(primary)) return primary
        return getCandidateMirrors(key, fallback).firstOrNull { !isOriginTemporarilyBlocked(it) } ?: primary
    }

    private fun isOriginTemporarilyBlocked(origin: String): Boolean {
        val ts = blockedOrigins[origin.trimEnd('/')] ?: return false
        if (System.currentTimeMillis() - ts > BLOCKED_TTL_MS) {
            blockedOrigins.remove(origin.trimEnd('/'))
            return false
        }
        return true
    }

    private fun markOriginBlocked(origin: String) {
        val clean = origin.trimEnd('/')
        if (clean.startsWith("http")) {
            blockedOrigins[clean] = System.currentTimeMillis()
        }
    }

    private fun getCandidateMirrors(key: String, fallback: String): List<String> {
        val list = linkedSetOf<String>()
        liveOverrides[key]?.let { list += it.trimEnd('/') }
        cachedDomains[key]?.trimEnd('/')?.takeIf { it.startsWith("http") }?.let { list += it }
        cachedMirrors[key]?.forEach { list += it.trimEnd('/') }
        BUILTIN_MIRRORS[key]?.forEach { list += it.trimEnd('/') }
        if (fallback.startsWith("http")) list += fallback.trimEnd('/')
        return list.toList()
    }

    private fun looksBlockedOrError(code: Int, body: String): Boolean {
        if (code !in 200..299) return true
        if (body.length < 250) return true
        val lower = body.lowercase()
        return lower.contains("just a moment...") && lower.contains("cf-browser-verification") ||
            lower.contains("attention required! | cloudflare") ||
            lower.contains("error 521") ||
            lower.contains("error 522") ||
            lower.contains("error 523") ||
            lower.contains("the requested url was blocked") ||
            lower.contains("department of telecommunications") ||
            lower.contains("access to this website has been blocked")
    }

    suspend fun fetchText(
        key: String,
        urlOrPath: String,
        fallbackBase: String,
        headers: Map<String, String> = mapOf("User-Agent" to LeovonixExtractors.UA),
        timeout: Long = 15L,
    ): Pair<String, String> {
        val active = getDomain(key, fallbackBase)
        val firstUrl = normalizeWithBase(urlOrPath, active)
        val firstRes = runCatching {
            app.get(firstUrl, headers = headers, timeout = timeout, allowRedirects = true)
        }.getOrNull()

        if (firstRes != null) {
            val body = firstRes.text
            if (!looksBlockedOrError(firstRes.code, body)) {
                val landed = firstRes.url.ifBlank { firstUrl }
                val landedOrigin = originOf(landed)
                if (landedOrigin.startsWith("http") && landedOrigin != active) {
                    liveOverrides[key] = landedOrigin
                    Log.i("DomainManager", "Auto-updated redirect domain for $key -> $landedOrigin")
                }
                return body to landed
            }
        }

        // Primary domain failed or looks blocked -> force refresh domains.json and probe mirrors
        markOriginBlocked(active)
        Log.w("DomainManager", "Domain $active failed/blocked for $key; probing live mirrors...")
        refreshDomains(force = true)

        val candidates = getCandidateMirrors(key, fallbackBase)
            .filter { it != active && !isOriginTemporarilyBlocked(it) }

        for (mirror in candidates) {
            val candidateUrl = normalizeWithBase(urlOrPath, mirror)
            val res = runCatching {
                app.get(candidateUrl, headers = headers, timeout = minOf(timeout, 12L), allowRedirects = true)
            }.getOrNull() ?: continue
            val body = res.text
            if (!looksBlockedOrError(res.code, body)) {
                val landed = res.url.ifBlank { candidateUrl }
                val workingOrigin = originOf(landed).ifBlank { mirror }
                liveOverrides[key] = workingOrigin
                Log.i("DomainManager", "Auto-recovered $key via mirror -> $workingOrigin")
                return body to landed
            } else {
                markOriginBlocked(mirror)
            }
        }

        // If firstRes returned something usable even with odd status/small body, return it instead of crashing
        if (firstRes != null && firstRes.text.isNotBlank()) {
            return firstRes.text to firstRes.url.ifBlank { firstUrl }
        }
        throw IllegalStateException("All domains unreachable for $key (tried $active and ${candidates.size} mirrors)")
    }

    suspend fun fetchDocument(
        key: String,
        urlOrPath: String,
        fallbackBase: String,
        headers: Map<String, String> = mapOf("User-Agent" to LeovonixExtractors.UA),
        timeout: Long = 15L,
    ): Pair<Document, String> {
        val (html, landedUrl) = fetchText(key, urlOrPath, fallbackBase, headers, timeout)
        return Jsoup.parse(html, landedUrl) to landedUrl
    }

    fun normalizeWithBase(rawUrl: String, activeBase: String): String {
        val base = activeBase.trimEnd('/')
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return base
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (!trimmed.startsWith("http")) {
            return if (trimmed.startsWith("/")) "$base$trimmed" else "$base/$trimmed"
        }
        return runCatching {
            val uri = URI(trimmed)
            val path = uri.rawPath.orEmpty()
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$base$path$query"
        }.getOrElse { trimmed }
    }

    fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.authority}"
        }.getOrDefault("")
    }

    fun fixUrl(raw: String?, base: String): String? {
        val u = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanBase = base.trimEnd('/')
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$cleanBase$u"
            else -> "$cleanBase/$u"
        }
    }

    fun extractPoster(el: Element?, base: String): String? {
        if (el == null) return null
        val img = el.selectFirst("img") ?: el
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-wpfc-original-src"),
            img.attr("src"),
            img.attr("srcset").substringBefore(" ").substringBefore(","),
        )
        for (c in candidates) {
            val trimmed = c.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("data:image")) {
                return fixUrl(trimmed, base)
            }
        }
        return null
    }

    fun guessQuality(text: String?): SearchQuality? {
        val s = text?.uppercase() ?: return null
        return when {
            "2160P" in s || "4K" in s -> SearchQuality.FourK
            "UHD" in s -> SearchQuality.UHD
            "HDR" in s -> SearchQuality.HDR
            "BLURAY" in s || "BLU-RAY" in s -> SearchQuality.BlueRay
            "1080P" in s || "720P" in s -> SearchQuality.HD
            "WEBRIP" in s || "WEB-DL" in s -> SearchQuality.WebRip
            "HDCAM" in s || "CAMRIP" in s -> SearchQuality.HdCam
            else -> null
        }
    }

    fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("(?i)^download\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
