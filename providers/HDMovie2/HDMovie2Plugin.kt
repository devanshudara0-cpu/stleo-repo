package com.leovonix.providers.hdmovie2

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
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

@LeoPlugin
class HDMovie2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDMovie2())
    }
}

class HDMovie2 : MainAPI() {
    override var mainUrl = "https://hdmovie2a.asia"
    override var name = "HDMovie2"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies/" to "Latest Movies",
        "series/" to "Web Series",
        "genre/bollywood/" to "Bollywood",
        "genre/hindi-dubbed/" to "Hindi Dubbed",
        "genre/hollywood/" to "Hollywood",
    )

    private suspend fun activeBase(): String {
        val base = DomainManager.getDomain("hdmovie2", mainUrl)
        mainUrl = base
        return base
    }

    private fun Element.toCard(base: String): SearchResponse? {
        val a = selectFirst("div.poster a[href], h3 a[href], div.title a[href], a[href]") ?: return null
        val href = DomainManager.normalizeWithBase(a.attr("href"), base)
        val rawTitle = selectFirst("h3, .title, h2")?.text()?.ifBlank { null }
            ?: a.attr("title").ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
        val title = DomainManager.cleanTitle(rawTitle)
        if (title.isBlank()) return null
        val poster = DomainManager.extractPoster(this, base)
        val isSeries = href.contains("/series/", true) || href.contains("/tvshows/", true)
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
        val url = if (page <= 1) "$base/$path" else "$base/${path.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("article.card, article.item, div.items article").mapNotNull { it.toCard(base) }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val base = activeBase()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$base/?s=$q" else "$base/page/$page/?s=$q"
        val doc = app.get(url, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val items = doc.select("div.result-item article, article.card, article.item").mapNotNull { it.toCard(base) }
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun extractPlayerTargets(doc: Document, base: String, pageUrl: String): List<String> {
        val targets = mutableListOf<String>()
        for (a in doc.select("li.dooplay_player_option a[data-source], a[data-source]")) {
            val src = a.attr("data-source").trim()
            if (src.isNotBlank() && !src.contains("youtube.com", true) && !src.contains("youtu.be", true)) {
                DomainManager.fixUrl(src, base)?.let { targets += it }
            }
        }
        for (li in doc.select("li.dooplay_player_option[data-post][data-nume]")) {
            val post = li.attr("data-post").trim()
            val nume = li.attr("data-nume").trim()
            val type = li.attr("data-type").ifBlank { "movie" }
            if (post.isNotBlank() && nume.isNotBlank() && nume != "trailer") {
                targets += "ajax::$base:::$post:::$nume:::$type:::$pageUrl"
            }
        }
        for (a in doc.select("a[href]")) {
            val href = DomainManager.fixUrl(a.attr("href"), base) ?: continue
            val hl = href.lowercase()
            if (hl.contains("gdflix") || hl.contains("hubcloud") || hl.contains("hdm") ||
                hl.contains("abyss") || hl.contains("ok.ru") || hl.contains("molop")
            ) {
                targets += href
            }
        }
        return targets.distinct()
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = activeBase()
        val normalized = DomainManager.normalizeWithBase(url, base)
        val doc = app.get(normalized, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
        val title = DomainManager.cleanTitle(
            doc.selectFirst("h1, div.data h1")?.text().orEmpty()
        ).ifBlank { "HDMovie2 Title" }
        val poster = DomainManager.extractPoster(doc.selectFirst("div.poster") ?: doc.body(), base)
        val plotText = doc.selectFirst("div.wp-content p, .synopsis p")?.text()
        val yearVal = Regex("""\b(19\d\d|20[0-3]\d)\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val epLinks = doc.select("ul.episodios li, .episodios a[href]")
        if (epLinks.isNotEmpty() && normalized.contains("/series/", true)) {
            val episodes = mutableListOf<Episode>()
            doc.select("ul.episodios li").forEachIndexed { idx, li ->
                val a = li.selectFirst("a[href]") ?: return@forEachIndexed
                val epUrl = DomainManager.normalizeWithBase(a.attr("href"), base)
                val epTitle = a.text().trim().ifBlank { "Episode ${idx + 1}" }
                val epNum = Regex("""(\d+)""").find(li.selectFirst(".numerando")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: (idx + 1)
                episodes += newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = DomainManager.extractPoster(li, base) ?: poster
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

        val targets = extractPlayerTargets(doc, base, normalized)
        return newMovieLoadResponse(title, normalized, TvType.Movie, JSONArray(targets).toString()) {
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
        val base = activeBase()
        val items = if (data.startsWith("http")) {
            val doc = app.get(data, headers = mapOf("User-Agent" to LeovonixExtractors.UA)).document
            extractPlayerTargets(doc, base, data)
        } else {
            runCatching {
                val arr = JSONArray(data)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }.getOrElse { listOf(data) }
        }
        if (items.isEmpty()) return false

        items.amap { entry ->
            runCatching {
                if (entry.startsWith("ajax::")) {
                    val parts = entry.removePrefix("ajax::").split(":::")
                    if (parts.size >= 5) {
                        val b = parts[0]
                        val res = app.post(
                            "$b/wp-admin/admin-ajax.php",
                            headers = mapOf("User-Agent" to LeovonixExtractors.UA, "Referer" to parts[4]),
                            data = mapOf("action" to "doo_player_ajax", "post" to parts[1], "nume" to parts[2], "type" to parts[3]),
                        ).text
                        val embedUrl = JSONObject(res).optString("embed_url").trim()
                        if (embedUrl.isNotBlank()) {
                            LeovonixExtractors.resolveLinks(embedUrl, name, parts[4], null, subtitleCallback, callback)
                        }
                    }
                } else {
                    LeovonixExtractors.resolveLinks(entry, name, mainUrl, null, subtitleCallback, callback)
                }
            }.onFailure {
                Log.e("HDMovie2", "Link failed $entry: ${it.message}")
            }
        }
        return true
    }
}
