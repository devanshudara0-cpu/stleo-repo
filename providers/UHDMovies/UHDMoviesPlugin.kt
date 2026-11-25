package com.leovonix.providers.uhdmovies

import android.content.Context
import com.leovonix.providers.DomainManager
import com.leovonix.providers.LeovonixExtractors
import com.stleo.api.Log
import com.stleo.ott.Episode
import com.stleo.ott.HomePageResponse
import com.stleo.ott.LoadResponse
import com.stleo.ott.MainAPI
import com.stleo.ott.MainPageRequest
import com.stleo.ott.SearchResponse
import com.stleo.ott.SearchResponseList
import com.stleo.ott.SubtitleFile
import com.stleo.ott.TvType
import com.stleo.ott.amap
import com.stleo.ott.app
import com.stleo.ott.mainPageOf
import com.stleo.ott.newEpisode
import com.stleo.ott.newHomePageResponse
import com.stleo.ott.newMovieLoadResponse
import com.stleo.ott.newMovieSearchResponse
import com.stleo.ott.newSearchResponseList
import com.stleo.ott.newTvSeriesLoadResponse
import com.stleo.ott.newTvSeriesSearchResponse
import com.stleo.ott.plugins.LeoPlugin
import com.stleo.ott.plugins.Plugin
import com.stleo.ott.utils.ExtractorLink
import org.json.JSONArray
import org.jsoup.nodes.Element
import java.net.URLEncoder

@LeoPlugin
class UHDMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(UHDMovies())
    }
}

class UHDMovies : MainAPI() {
    override var mainUrl = "https://uhdmovies.ceo"
    override var name = "UHDMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest 4K & UHD",
        "movies/" to "Movies",
        "tv-shows/" to "Web Series",
        "2160p-movies/" to "4K 2160p",
        "1080p-uhd/" to "1080p UHD",
    )

    private suspend fun activeBase(): String {
        val base = DomainManager.getDomain("uhdmovies", mainUrl)
        mainUrl = base
        return base
    }

    private fun Element.toCard(base: String): SearchResponse? {
        val a = selectFirst("h2.entry-title a[href], .entry-image a[href], a[href]") ?: return null
        val href = DomainManager.normalizeWithBase(a.attr("href"), base)
        val rawTitle = selectFirst("h2.entry-title a, h2.entry-title")?.text()?.ifBlank { null }
            ?: a.attr("title").ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
        val title = DomainManager.cleanTitle(rawTitle)
        if (title.isBlank()) return null
        val poster = DomainManager.extractPoster(this, base)
        val isSeries = title.contains("Season", true) || attr("class").contains("series", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                quality = DomainManager.guessQuality(title)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                quality = DomainManager.guessQuality(title)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = activeBase()
        val path = request.data.trimStart('/')
        val url = when {
            path.isBlank() && page <= 1 -> "$base/"
            path.isBlank() -> "$base/page/$page/"
            page <= 1 -> "$base/$path"
            else -> "$base/${path.trimEnd('/')}/page/$page/"
        }
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("article.gridlove-post").mapNotNull { it.toCard(base) }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val base = activeBase()
        val encodedPath = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$base/search/$encodedPath" else "$base/search/$encodedPath/page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("article.gridlove-post").mapNotNull { it.toCard(base) }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun isSupportedLink(u: String): Boolean {
        val l = u.lowercase()
        return l.startsWith("http") && (
            l.contains("thenaukriadda") || l.contains("unblocked") || l.contains("tech.") ||
                l.contains("driveseed") || l.contains("driveleech") ||
                l.contains("hubcloud") || l.contains("gdflix") ||
                l.contains("?sid=") || l.contains("?id=")
            )
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = activeBase()
        val normalized = DomainManager.normalizeWithBase(url, base)
        val doc = app.get(normalized, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val title = DomainManager.cleanTitle(
            doc.selectFirst("h1.entry-title, h1")?.text().orEmpty()
        ).ifBlank { "UHD Title" }
        val content = doc.selectFirst(".entry-content, main") ?: doc.body()
        val poster = DomainManager.extractPoster(content, base)
        val yearVal = Regex("""\b(19\d\d|20[0-3]\d)\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val epGrouped = linkedMapOf<Int, MutableList<String>>()
        for (a in content.select("a[href]")) {
            val href = DomainManager.fixUrl(a.attr("href"), base) ?: continue
            if (!isSupportedLink(href)) continue
            val epMatch = Regex("""(?i)Episode\s*0*(\d+)""").find(a.text())
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                epGrouped.getOrPut(epNum) { mutableListOf() }.add(href)
            }
        }
        if (epGrouped.isNotEmpty()) {
            val episodes = epGrouped.entries.sortedBy { it.key }.map { (epNum, urls) ->
                newEpisode(JSONArray(urls.distinct()).toString()) {
                    this.name = "Episode $epNum"
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(title, normalized, TvType.TvSeries, episodes) {
                posterUrl = poster
                year = yearVal
            }
        }

        val movieLinks = content.select("a[href]")
            .mapNotNull { DomainManager.fixUrl(it.attr("href"), base) }
            .filter { isSupportedLink(it) }
            .distinct()

        return newMovieLoadResponse(title, normalized, TvType.Movie, JSONArray(movieLinks).toString()) {
            posterUrl = poster
            year = yearVal
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val urls = runCatching {
            val arr = JSONArray(data)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrElse { listOf(data) }
        if (urls.isEmpty()) return false
        urls.amap { target ->
            runCatching {
                LeovonixExtractors.resolveLinks(target, name, mainUrl, null, subtitleCallback, callback)
            }.onFailure {
                Log.e("UHDMovies", "Link failed $target: ${it.message}")
            }
        }
        return true
    }
}
