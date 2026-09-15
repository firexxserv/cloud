package com.kraptor

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class Sinemetrik : MainAPI() {
    override var mainUrl              = "https://sinemetrik.com"
    override var name                 = "Sinemetrik"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/kategori/film/page/" to "Filmler",
        "${mainUrl}/kategori/dizi/page/" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        // Temel.kt'deki ve Sinemetrik'teki muhtemel CSS seçicileri birleştirdik[cite: 12]
        val home = document.select("div.items article, div.movies-list div.movie-item, div.result-item").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div.flbaslik, h3 a, div.title a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        
        // Linkin içinde /dizi/ geçiyorsa bunu otomatik TvSeries olarak algıla
        val isTvSeries = href.contains("/dizi/")
        val type = if(isTvSeries) TvType.TvSeries else TvType.Movie

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = if (page == 1) {
            app.get("${mainUrl}/?s=${query}").document
        } else {
            app.get("${mainUrl}/page/$page/?s=${query}").document
        }

        val aramaCevap = document.select("div.icerik div, div.search-results div.movie-item, div.result-item").mapNotNull { it.toMainPageResult() }
        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "Load aşaması: $url")
        val document = app.get(url).document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description     = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year            = document.selectFirst("div.extra span.C a, span.year")?.text()?.trim()?.toIntOrNull()
        val tags            = document.select("div.sgeneros a, div.genres a").map { it.text() }
        val scoreText       = document.selectFirst("span.dt_rating_vgs, span.imdb")?.text()?.trim()
        val duration        = document.selectFirst("span.runtime, span.duration")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val actors          = document.select("span.valor a, div.cast a").map { Actor(it.text()) }
        val trailer         = Regex("""embed\/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        // Bölüm listesinin olup olmadığını kontrol ediyoruz[cite: 12]
        val isTvSeries = document.select("div.episodios li, div.season-list, table.episodes, ul.episodios li").isNotEmpty()

        return if (isTvSeries) {
            val episodes = document.select("div.episodios li a, div.season-list a, ul.episodios li a").mapNotNull { epElement ->
                val epUrl = fixUrlNull(epElement.attr("href")) ?: return@mapNotNull null
                val epName = epElement.text()?.trim() ?: "Bölüm"
                val season = epElement.selectFirst(".se-t, .season")?.text()?.trim()?.toIntOrNull() ?: 1
                val episode = epElement.selectFirst(".num-ep, .episode")?.text()?.trim()?.toIntOrNull() ?: 1

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
        val document = app.get(data).document
        
        // Sitedeki iframe'i (videoyu) buluyoruz
        val iframe = document.selectFirst("iframe")?.attr("src")
        
        if (iframe != null) {
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }
        return true
    }
}