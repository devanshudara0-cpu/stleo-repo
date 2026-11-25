package com.leovonix.providers.hindmoviez

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
import com.leovonix.providers.LeovonixExtractors.safeForEach
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
class HindMoviezPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HindMoviez())
    }
}

class HindMoviez : MainAPI() {
    override var mainUrl = "https://v5.hindmovie.im"
    override var name = "HindMoviez"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/bollywood/" to "Bollywood",
        "category/hollywood/" to "Hollywood",
        "category/south-indian/" to "South Indian",
        "category/web-series/" to "Web Series",
    )

    private suspend fun activeBase(): String {
        val base = DomainManager.getDomain("hindmoviez", mainUrl)
        mainUrl = base
        return base
    }

    private fun Element.toCard(base: String): SearchResponse? {
        val a = selectFirst("h2 a[href], h3 a[href], .entry-title a[href], a[href]") ?: return null
        val href = DomainManager.normalizeWithBase(a.attr("href"), base)
        val rawTitle = selectFirst("h2, h3, .entry-title")?.text()?.ifBlank { null }
            ?: a.attr("title").ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
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
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("article.post, div.items article, article").mapNotNull { it.toCard(base) }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val base = activeBase()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("article.post, div.items article, article").mapNotNull { it.toCard(base) }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun isSupportedLink(u: String): Boolean {
        val l = u.lowercase()
        return l.startsWith("http") && (
            l.contains("joshapp") || l.contains("mvlink") || l.contains("hshare") ||
                l.contains("gdshine") || l.contains("boldb") ||
                l.contains("hubcloud") || l.contains("gdflix") || l.contains("workers.dev")
            )
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = activeBase()
        val normalized = DomainManager.normalizeWithBase(url, base)
        val doc = app.get(normalized, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val title = DomainManager.cleanTitle(
            doc.selectFirst("h1.entry-title, h1")?.text().orEmpty()
        ).ifBlank { "HindMoviez Title" }
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
                newEpisode(JSONArray(urls.distinct()).toString(), fix = false) {
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

        val links = content.select("a[href]")
            .mapNotNull { DomainManager.fixUrl(it.attr("href"), base) }
            .filter { isSupportedLink(it) }
            .distinct()
        return newMovieLoadResponse(title, normalized, TvType.Movie, JSONArray(links).toString()) {
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
        val urls = LeovonixExtractors.parsePayloadUrls(data)
        if (urls.isEmpty()) return false
        urls.safeForEach { target ->
            runCatching {
                LeovonixExtractors.resolveLinks(target, name, mainUrl, null, subtitleCallback, callback)
            }.onFailure {
                Log.e("HindMoviez", "Link failed $target: ${it.message}")
            }
        }
        return true
    }
}
