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

    override val requiresReferer      = true
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler?sort=popular&page=" to "Popüler Filmler",
        "${mainUrl}/filmler?sort=recent&page="  to "Yeni Eklenen Filmler",
        "${mainUrl}/diziler?sort=popular&page=" to "Popüler Diziler",
        "${mainUrl}/diziler?sort=recent&page="  to "Yeni Eklenen Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, referer = mainUrl).document
        
        val home = document.select("a.similar-card, a.sd-item, div.movie-item, div.poster-item").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div.similar-title, div.sd-name, h3")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        
        val isTvSeries = href.contains("/dizi/")
        val type = if(isTvSeries) TvType.TvSeries else TvType.Movie

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "$mainUrl/api/search?q=$query"
        val document = app.get(searchUrl, referer = mainUrl).text
        
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

        val title           = document.selectFirst("h1.movie-title")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - Film İzle | Sinemetrik", "")?.replace(" - Dizi İzle | Sinemetrik", "")?.trim() 
            ?: return null

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
            val episodes = mutableListOf<Episode>()
            val seriesId = document.selectFirst("button.season-tab")?.attr("data-series-id")
            
            if (seriesId != null) {
                val seasonCount = document.select("button.season-tab").size
                for (seasonNum in 1..seasonCount) {
                    val apiUrl = "$mainUrl/api/dizi/$seriesId/sezon/$seasonNum/bolumler"
                    try {
                        val apiResponse = app.get(apiUrl, referer = url).text
                        val epRegex = """"episode_number":(\d+).*?"name":"(.*?)"""".toRegex()
                        
                        epRegex.findAll(apiResponse).forEach { match ->
                            val epNum = match.groupValues[1].toIntOrNull() ?: 1
                            val epName = match.groupValues[2].replace("\\u0026", "&").replace("\\\"", "\"")
                            val epUrl = "$url/sezon/$seasonNum/bolum/$epNum"
                            
                            episodes.add(
                                newEpisode(epUrl) {
                                    this.name = epName
                                    this.season = seasonNum
                                    this.episode = epNum
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.d(name, "Sezon $seasonNum çekilemedi: ${e.message}")
                    }
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

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_Sinemetrik", "Link yükleniyor: $data")
        val document = app.get(data, referer = mainUrl).document
        val htmlContent = document.html()

        // 1. Doğrudan sayfadaki iframe'ler (Film ve Dizi Server 2 - VidAPI)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-vidapi-src").ifEmpty { iframe.attr("src") }.ifEmpty { iframe.attr("data-s5-src") }
            if (src.isNotBlank() && !src.contains("about:blank")) {
                val fixedUrl = if (src.startsWith("//")) "https:$src" else if (src.startsWith("/")) "$mainUrl$src" else src
                Log.d("kraptor_Sinemetrik", "Iframe bulundu: $fixedUrl")
                loadExtractor(fixedUrl, referer = "$mainUrl/", subtitleCallback, callback)
            }
        }

        // 2. Server 1 Gizli Yedek Oynatıcı (hdplayersystem.com vb.)
        document.select("[data-s1-yedek-raw]").forEach { el ->
            val raw = el.attr("data-s1-yedek-raw")
            val hiddenIframe = Regex("""src=["'](.*?)["']""").find(raw)?.groupValues?.get(1)
            if (!hiddenIframe.isNullOrBlank()) {
                Log.d("kraptor_Sinemetrik", "Server 1 Yedek bulundu: $hiddenIframe")
                loadExtractor(hiddenIframe, referer = "$mainUrl/", subtitleCallback, callback)
            }
        }

        // 3. JavaScript İçindeki Alternatif Hatlar (/test-player.php?...)
        val testPlayerRegex = """(?:\\/|/)test-player\.php\?[^"'\s\\]+""".toRegex()
        val playerPaths = testPlayerRegex.findAll(htmlContent).map {
            it.value.replace("""\/""", "/")
        }.distinct().toList()

        playerPaths.forEach { path ->
            try {
                val fullUrl = if (path.startsWith("http")) path else "$mainUrl$path"
                val playerDoc = app.get(fullUrl, referer = data).document

                // test-player içindeki asıl oynatıcı iframe'leri
                playerDoc.select("iframe").forEach { subIframe ->
                    val subSrc = subIframe.attr("src")
                    if (subSrc.isNotBlank() && !subSrc.contains("about:blank")) {
                        val fixedSub = if (subSrc.startsWith("//")) "https:$subSrc" else subSrc
                        Log.d("kraptor_Sinemetrik", "Test-player alt oynatıcı: $fixedSub")
                        loadExtractor(fixedSub, referer = fullUrl, subtitleCallback, callback)
                    }
                }

                // test-player içinde doğrudan video veya m3u8 varsa
                playerDoc.select("source, video").forEach { v ->
                    val vSrc = v.attr("src")
                    if (vSrc.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name Player",
                                url = fixUrl(vSrc),
                                type = INFER_TYPE
                            ) {
                                this.referer = fullUrl
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d("kraptor_Sinemetrik", "test-player hatası: ${e.message}")
            }
        }

        return true
    }
}