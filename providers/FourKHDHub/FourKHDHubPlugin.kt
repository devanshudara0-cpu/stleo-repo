package com.leovonix.providers.fourkhdhub

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
class FourKHDHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FourKHDHub())
    }
}

class FourKHDHub : MainAPI() {
    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHDHub"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/movies-1080/" to "Movies",
        "category/new-series/" to "Web Series",
        "category/4k-hdr/" to "4K HDR & DV",
        "category/anime-404/" to "Anime",
    )

    private suspend fun activeBase(): String {
        val base = DomainManager.getDomain("4khdhub", mainUrl)
        mainUrl = base
        return base
    }

    private fun Element.toCard(base: String): SearchResponse? {
        val href = DomainManager.fixUrl(attr("href").ifBlank { selectFirst("a")?.attr("href") }, base) ?: return null
        val rawTitle = selectFirst("h3.movie-card-title, .movie-card-title, h2, h3")?.text()
            ?.ifBlank { null }
            ?: attr("aria-label").removeSuffix(" details").trim()
        val title = DomainManager.cleanTitle(rawTitle)
        if (title.isBlank()) return null
        val poster = DomainManager.extractPoster(this, base)
        val badges = select(".quality-badge").text()
        val isSeries = href.contains("-series-", ignoreCase = true) ||
            select(".movie-card-meta, .card-meta").text().contains("Season", ignoreCase = true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                quality = DomainManager.guessQuality("$title $badges")
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                quality = DomainManager.guessQuality("$title $badges")
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
            else -> "$base/$path/page/$page/".replace("//page", "/page")
        }
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("div.card-grid a.movie-card, a.movie-card").mapNotNull { it.toCard(base) }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val base = activeBase()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("div.card-grid a.movie-card, a.movie-card").mapNotNull { it.toCard(base) }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun collectHostLinks(container: Element, base: String): List<String> {
        return container.select("a[href]")
            .mapNotNull { DomainManager.fixUrl(it.attr("href"), base) }
            .filter { u ->
                val l = u.lowercase()
                l.contains("greenmotors") || l.contains("gadgetsweb") ||
                    l.contains("hubcloud") || l.contains("hubdrive") ||
                    l.contains("hblinks") || l.contains("gdflix") ||
                    l.contains("?id=")
            }
            .distinct()
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = activeBase()
        val normalized = DomainManager.normalizeWithBase(url, base)
        val doc = app.get(normalized, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val title = DomainManager.cleanTitle(
            doc.selectFirst("h1.page-title, h1.movie-title, h1")?.text().orEmpty()
        ).ifBlank { "Movie" }
        val poster = DomainManager.extractPoster(
            doc.selectFirst(".movie-poster, .poster, article img")?.parent() ?: doc.body(),
            base
        )
        val plotText = doc.selectFirst(".movie-synopsis, .synopsis, .entry-content p, p.description")?.text()
        val yearVal = Regex("""\b(19\d\d|20[0-3]\d)\b""").find("$title ${doc.select(".movie-meta, .meta-badges").text()}")?.groupValues?.get(1)?.toIntOrNull()

        val epBlocks = doc.select(".episode-download-item")
        if (epBlocks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            epBlocks.forEachIndexed { idx, block ->
                val epTitle = block.selectFirst(".episode-title, h4, h3, strong")?.text()?.trim().orEmpty()
                    .ifBlank { "Episode ${idx + 1}" }
                val links = collectHostLinks(block, base)
                if (links.isNotEmpty()) {
                    val seasonNum = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(block.parents().text())?.groupValues?.get(1)?.toIntOrNull()
                        ?: 1
                    val epNum = Regex("""E(?:pisode)?\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (idx + 1)
                    episodes += newEpisode(JSONArray(links).toString(), fix = false) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, normalized, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    year = yearVal
                    plot = plotText
                }
            }
        }

        val downloadRoot = doc.selectFirst(".download-items-grid, .download-section, main") ?: doc.body()
        val links = collectHostLinks(downloadRoot, base)
        return newMovieLoadResponse(title, normalized, TvType.Movie, JSONArray(links).toString()) {
            posterUrl = poster
            year = yearVal
            plot = plotText
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
                Log.e("FourKHDHub", "Link failed $target: ${it.message}")
            }
        }
        return true
    }
}
