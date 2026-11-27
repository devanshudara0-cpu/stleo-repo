package com.leovonix.providers.zinkmovies

import android.content.Context
import com.leovonix.providers.DomainManager
import com.leovonix.providers.LeovonixExtractors
import com.leovonix.providers.LeovonixExtractors.safeForEach
import com.stleo.api.Log
import com.stleo.ott.HomePageResponse
import com.stleo.ott.LoadResponse
import com.stleo.ott.MainAPI
import com.stleo.ott.MainPageRequest
import com.stleo.ott.SearchResponse
import com.stleo.ott.SearchResponseList
import com.stleo.ott.SubtitleFile
import com.stleo.ott.TvType
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
class ZinkMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ZinkMovies())
    }
}

class ZinkMovies : MainAPI() {
    override var mainUrl = "https://zinkmovies.casa"
    override var name = "ZinkMovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/bollywood/" to "Bollywood",
        "category/hollywood/" to "Hollywood",
        "category/south-indian/" to "South Hindi Dubbed",
        "category/web-series/" to "Web Series",
    )

    private suspend fun activeBase(): String {
        val base = DomainManager.getDomain("zinkmovies", mainUrl)
        mainUrl = base
        return base
    }

    private fun Element.toCard(base: String): SearchResponse? {
        val a = selectFirst("a[href]") ?: parent()?.takeIf { it.tagName() == "a" } ?: return null
        val href = DomainManager.normalizeWithBase(a.attr("href"), base)
        val rawTitle = selectFirst(".poster-title, h3, h2")?.text()?.ifBlank { null }
            ?: selectFirst("img")?.attr("alt").orEmpty()
        val title = DomainManager.cleanTitle(rawTitle)
        if (title.isBlank()) return null
        val poster = DomainManager.extractPoster(this, base)
        val isSeries = title.contains("Season", true) || href.contains("season", true)
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
        val (doc, landed) = DomainManager.fetchDocument("zinkmovies", url, base)
        val effectiveBase = DomainManager.originOf(landed).ifBlank { base }
        mainUrl = effectiveBase
        val items = doc.select("div.poster-card, article.post").mapNotNull { it.toCard(effectiveBase) }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val base = activeBase()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
        val (doc, landed) = DomainManager.fetchDocument("zinkmovies", url, base)
        val effectiveBase = DomainManager.originOf(landed).ifBlank { base }
        mainUrl = effectiveBase
        val items = doc.select("div.poster-card, article.post").mapNotNull { it.toCard(effectiveBase) }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = activeBase()
        val (doc, normalized) = DomainManager.fetchDocument("zinkmovies", url, base)
        val effectiveBase = DomainManager.originOf(normalized).ifBlank { base }
        mainUrl = effectiveBase
        val title = DomainManager.cleanTitle(
            doc.selectFirst("h1, h1.entry-title")?.text().orEmpty()
        ).ifBlank { "ZinkMovies Title" }
        val content = doc.selectFirst(".entry-content, .page-body, main") ?: doc.body()
        val poster = DomainManager.extractPoster(content, effectiveBase)
        val yearVal = Regex("""\b(19\d\d|20[0-3]\d)\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val epGrouped = linkedMapOf<Int, MutableList<String>>()
        for (a in content.select("a[href]")) {
            val href = DomainManager.fixUrl(a.attr("href"), effectiveBase) ?: continue
            if (!LeovonixExtractors.isCandidateLink(href, a.text())) continue
            val epMatch = Regex("""(?i)Episode\s*0*(\d+)""").find(a.text())
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                epGrouped.getOrPut(epNum) { mutableListOf() }.add(href)
            }
        }
        if (epGrouped.isNotEmpty()) {
            val episodes = epGrouped.entries.sortedBy { it.key }.map { (epNum, urls) ->
                newEpisode(
                    url = JSONArray(urls.distinct().take(10)).toString(),
                    initializer = {
                        this.name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = poster
                    },
                    fix = false,
                )
            }
            return newTvSeriesLoadResponse(title, normalized, TvType.TvSeries, episodes) {
                posterUrl = poster
                year = yearVal
            }
        }

        val links = LeovonixExtractors.collectDownloadLinks(content, effectiveBase, maxLinks = 10)
        return newMovieLoadResponse(title, normalized, TvType.Movie, LeovonixExtractors.linkPayload(links, normalized)) {
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
        val urls = LeovonixExtractors.parsePayloadUrls(data).take(10)
        if (urls.isEmpty()) return false
        urls.safeForEach { target ->
            runCatching {
                LeovonixExtractors.resolveLinks(target, name, mainUrl, null, subtitleCallback, callback)
            }.onFailure {
                Log.e("ZinkMovies", "Link failed $target: ${it.message}")
            }
        }
        return true
    }
}
