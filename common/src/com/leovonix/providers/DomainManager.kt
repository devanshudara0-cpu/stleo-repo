package com.leovonix.providers

import com.stleo.api.Log
import com.stleo.ott.SearchQuality
import com.stleo.ott.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

object DomainManager {
    private const val DOMAINS_URL =
        "https://raw.githubusercontent.com/devanshudara0-cpu/stleo-repo/main/domains.json"
    private const val TTL_MS = 6L * 60L * 60L * 1000L

    private val mutex = Mutex()
    @Volatile
    private var cachedDomains: Map<String, String> = emptyMap()
    @Volatile
    private var lastFetchedAt: Long = 0L

    suspend fun getDomain(key: String, fallback: String): String {
        val now = System.currentTimeMillis()
        if (lastFetchedAt != 0L && now - lastFetchedAt < TTL_MS) {
            return cachedDomains[key]?.trimEnd('/')?.takeIf { it.startsWith("http") } ?: fallback.trimEnd('/')
        }
        mutex.withLock {
            val recheck = System.currentTimeMillis()
            if (lastFetchedAt == 0L || recheck - lastFetchedAt >= TTL_MS) {
                runCatching {
                    val text = withTimeout(8_000L) {
                        app.get(DOMAINS_URL, timeout = 8L).text
                    }
                    val obj = JSONObject(text)
                    val map = mutableMapOf<String, String>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.optString(k).trim().trimEnd('/')
                        if (v.startsWith("http")) {
                            map[k] = v
                        }
                    }
                    if (map.isNotEmpty()) {
                        cachedDomains = map
                    }
                    lastFetchedAt = recheck
                }.onFailure {
                    Log.w("DomainManager", "Using fallback domain for $key: ${it.message}")
                    lastFetchedAt = recheck
                }
            }
        }
        return cachedDomains[key]?.trimEnd('/')?.takeIf { it.startsWith("http") } ?: fallback.trimEnd('/')
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
