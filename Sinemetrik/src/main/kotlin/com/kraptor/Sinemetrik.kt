package com.kraptor

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class Sinemetrik : MainAPI() {
    override var mainUrl              = "https://www.sinemetrik.com"
    override var name                 = "Sinemetrik"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // Cloudflare Anti-Bot korumasını geçebilmek için:
    override val requiresReferer      = true

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler?sort=popular&page=" to "Popüler Filmler",
        "${mainUrl}/filmler?sort=recent&page="  to "Yeni Eklenen Filmler",
        "${mainUrl}/diziler?sort=popular&page=" to "Popüler Diziler",
        "${mainUrl}/diziler?sort=recent&page="  to "Yeni Eklenen Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, referer = mainUrl).document
        
        // Sitenin genel film/dizi kartları (Eğer kart yapısı farklıysa yedek class'lar eklendi)
        val home = document.select("a.similar-card, a.sd-item, div.movie-item, div.poster-item").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div.similar-title, div.sd-name, h3")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        
        // Linkin içinde /dizi/ geçiyorsa TvSeries, geçmiyorsa Movie
        val isTvSeries = href.contains("/dizi/")
        val type = if(isTvSeries) TvType.TvSeries else TvType.Movie

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Sitenin kendi Search API'sine istek atıyoruz
        val searchUrl = "$mainUrl/api/search?q=$query"
        val document = app.get(searchUrl, referer = mainUrl).text
        
        // JSON döndüğü için basit bir regex ile içerikleri yakalayabiliriz (Cloudstream'in kütüphanesini kasmamak için)
        val regex = """"slug":"(.*?)".*?"name":"(.*?)".*?"type":"(.*?)".*?"poster":"(.*?)"""".toRegex()
        val results = regex.findAll(document).mapNotNull { match ->
            val slug = match.groupValues[1]
            val nameStr = match.groupValues[2]
            val typeStr = match.groupValues[3]
            val poster = match.groupValues[4].replace("\\/", "/")

            val isMovie = typeStr == "movie"
            val type = if (isMovie) TvType.Movie else TvType.TvSeries
            val href = if (isMovie) "$mainUrl/film/$slug" else "$mainUrl/dizi/$slug"

            if (isMovie) {
                newMovieSearchResponse(nameStr, href, type) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(nameStr, href, type) { this.posterUrl = poster }
            }
        }.toList()

        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "Load aşaması: $url")
        val document = app.get(url, referer = mainUrl).document

        // Siteden Gelen Veriler
        val title           = document.selectFirst("h1.movie-title")?.text()?.trim() ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - Film İzle | Sinemetrik", "")?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description     = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: document.selectFirst("p.movie-overview")?.text()?.trim()
        val tags            = document.select("div.movie-genres a.genre-badge").map { it.text().trim() }
        val actors          = document.select("div.cast-grid a.cast-card").mapNotNull { 
            val actorName = it.selectFirst("div.cast-name")?.text()
            val actorRole = it.selectFirst("div.cast-character")?.text()
            val actorImg = it.selectFirst("img")?.attr("src")
            if (actorName != null) {
                Actor(actorName, actorImg, actorRole)
            } else null
        }
        
        // Puan, Yıl, Süre
        val metaRows        = document.select("div.movie-meta-row span.meta-item").map { it.text() }
        var scoreText: String? = null
        var year: Int? = null
        var duration: Int? = null
        
        metaRows.forEach { item ->
            if (item.contains("⭐")) scoreText = item.substringAfter("⭐").substringBefore("(").trim()
            if (item.contains("📅")) year = item.replace("📅", "").trim().toIntOrNull()
            if (item.contains("⏱️")) duration = item.replace("⏱️", "").replace("dk", "").trim().toIntOrNull()
        }

        val trailer = Regex("""embed\/(.*)\"""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = document.select("div.episode-list a").mapNotNull { epElement ->
                val epUrl = fixUrlNull(epElement.attr("href")) ?: return@mapNotNull null
                val epName = epElement.text()?.trim() ?: "Bölüm"
                val season = Regex("s(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("e(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epUrl) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.plot            = description
                this.year            = year
                this.tags            = tags
                this.score           = Score.from10(scoreText)
                this.duration        = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.plot            = description
                this.year            = year
                this.tags            = tags
                this.score           = Score.from10(scoreText)
                this.duration        = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = mainUrl).document
        
        // Sinemetrik iframe player yakalama (VidAPI veya diğerleri)
        val iframeElements = document.select("iframe")
        
        iframeElements.forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-vidapi-src") }
            if (src.isNotEmpty() && src != "about:blank") {
                val fixedUrl = if (src.startsWith("//")) "https:$src" else src
                Log.d("kraptor_Sinemetrik", "Oynatıcı bulundu: $fixedUrl")
                loadExtractor(fixedUrl, referer = "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }
}