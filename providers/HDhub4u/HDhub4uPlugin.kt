package com.leovonix.providers.hdhub4u

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
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

@LeoPlugin
class HDhub4uPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDhub4u())
    }
}

class HDhub4u : MainAPI() {
    override var mainUrl = "https://new16.hdhub4u.cl"
    override var name = "HDhub4u"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies/" to "Hollywood",
        "category/south-hindi-movies/" to "South Hindi",
        "category/web-series/" to "Web Series",
    )

    private suspend fun activeBase(): String {
        val base = DomainManager.getDomain("hdhub4u", mainUrl)
        mainUrl = base
        return base
    }

    private fun Element.toCard(base: String): SearchResponse? {
        val a = selectFirst("figcaption a[href], a[href]") ?: return null
        val href = DomainManager.normalizeWithBase(a.attr("href"), base)
        val rawTitle = selectFirst("figcaption, figcaption p, p")?.text()?.ifBlank { null }
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
        val items = doc.select("ul.recent-movies li.thumb").mapNotNull { it.toCard(base) }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val base = activeBase()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "https://search.pingora.fyi/collections/post/documents/search" +
            "?q=$q&query_by=post_title,category&query_by_weights=4,2&sort_by=sort_by_date:desc" +
            "&limit=15&highlight_fields=none&use_cache=true&page=$page"

        val results = mutableListOf<SearchResponse>()
        val ok = runCatching {
            val resText = app.get(
                searchUrl,
                headers = mapOf("User-Agent" to LeovonixExtractors.UA, "Referer" to "$base/"),
            ).text
            val hits = JSONObject(resText).optJSONArray("hits") ?: JSONArray()
            for (i in 0 until hits.length()) {
                val docObj = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                val title = DomainManager.cleanTitle(docObj.optString("post_title"))
                val rawPerm = docObj.optString("permalink")
                if (title.isBlank() || rawPerm.isBlank()) continue
                val href = DomainManager.normalizeWithBase(rawPerm, base)
                val poster = docObj.optString("post_thumbnail").takeIf { it.startsWith("http") }
                val isSeries = title.contains("Season", true) || rawPerm.contains("season", true)
                results += if (isSeries) {
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
        }.isSuccess

        if (!ok || results.isEmpty()) {
            val doc = app.get("$base/page/$page/?s=$q", headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
            doc.select("ul.recent-movies li.thumb").mapNotNull { it.toCard(base) }.let { results.addAll(it) }
        }
        return newSearchResponseList(results, results.size >= 15)
    }

    private fun isSupportedLink(u: String): Boolean = LeovonixExtractors.isCandidateLink(u)

    override suspend fun load(url: String): LoadResponse? {
        val base = activeBase()
        val normalized = DomainManager.normalizeWithBase(url, base)
        val doc = app.get(normalized, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val title = DomainManager.cleanTitle(
            doc.selectFirst("h1.page-title, h1.entry-title, h1")?.text().orEmpty()
        ).ifBlank { "Movie" }
        val content = doc.selectFirst(".page-body, .entry-content, main") ?: doc.body()
        val poster = DomainManager.extractPoster(content, base)
        val yearVal = Regex("""\b(19\d\d|20[0-3]\d)\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val episodeHeadings = content.select("h3, h4, h5").filter { h ->
            val t = h.text()
            t.contains("EPiSODE", true) || Regex("""Episode\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(t)
        }
        if (episodeHeadings.isNotEmpty() && content.select("h4 a[href], h3 a[href], p a[href]").any { isSupportedLink(it.attr("href")) }) {
            val grouped = linkedMapOf<String, MutableList<String>>()
            var currentEp: String? = null
            for (el in content.select("h3, h4, h5, p")) {
                val txt = el.text().trim()
                val epMatch = Regex("""(?i)EPiSODE\s*(\d+)|Episode\s*(\d+)""").find(txt)
                if (epMatch != null) {
                    val num = epMatch.groupValues[1].ifBlank { epMatch.groupValues[2] }
                    currentEp = "Episode $num"
                } else if (txt.contains("Pack", true) || txt.contains("Zip", true) || txt.contains("Batch", true)) {
                    currentEp = null
                }
                val epKey = currentEp ?: continue
                val hrefs = el.select("a[href]")
                    .filter { a -> !a.text().contains("Pack", true) && !a.text().contains("Zip", true) }
                    .mapNotNull { DomainManager.fixUrl(it.attr("href"), base) }
                    .filter { isSupportedLink(it) }
                if (hrefs.isNotEmpty()) {
                    grouped.getOrPut(epKey) { mutableListOf() }.addAll(hrefs)
                }
            }
            if (grouped.size > 1) {
                val episodes = grouped.entries.mapIndexed { i, (epName, urls) ->
                    val num = Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                    newEpisode(
                        url = JSONArray(urls.distinct()).toString(),
                        initializer = {
                            this.name = epName
                            this.season = 1
                            this.episode = num
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
        }

        val allLinks = content.select("a[href]")
            .mapNotNull { DomainManager.fixUrl(it.attr("href"), base) }
            .filter { isSupportedLink(it) }
            .distinct()

        return newMovieLoadResponse(title, normalized, TvType.Movie, JSONArray(allLinks).toString()) {
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
                Log.e("HDhub4u", "Link failed $target: ${it.message}")
            }
        }
        return true
    }
}
