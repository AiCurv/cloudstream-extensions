package com.xhamster

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class XXDBXProvider : MainAPI() {
    override var mainUrl = "https://xxdbx.com"
    override var name = "XXDBX"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Newest",
        "$mainUrl/most-popular" to "Most Popular",
        "$mainUrl/longest" to "Longest",
        "$mainUrl/most-viewed" to "Most Viewed",
    )

    private fun String.fixUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> "$mainUrl$this"
        else -> this
    }

    private fun parseVideo(el: org.jsoup.nodes.Element): SearchResponse? {
        val a = el.selectFirst("a[href*=/view/]") ?: return null
        val href = a.attr("abs:href").ifEmpty { a.attr("href") }.fixUrl()
        val title = el.selectFirst(".v_title")?.text()?.trim() ?: return null
        val thumb = el.selectFirst(".v_pic img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.fixUrl()
        val duration = el.selectFirst(".v_dur")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = thumb
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url).document
        val videos = doc.select(".v").mapNotNull { parseVideo(it) }
        val hasNext = doc.select(".pagina a[href*=page]").first() != null
        return newHomePageResponse(listOf(HomePageList(request.name, videos)), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "-")
        val doc = app.get("$mainUrl/search/$q").document
        return doc.select(".v").mapNotNull { parseVideo(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("article h1")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("video[poster]")?.attr("poster")?.fixUrl()
        val desc = doc.selectFirst("#desc")?.text()?.trim()
        val tags = doc.select(".tags a[href*=/search/]").mapNotNull { it.text()?.trim() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("video source").forEach { src ->
            val url = src.attr("src").fixUrl()
            val label = src.attr("title")
            if (url.isNotEmpty() && url.contains(".mp4")) {
                callback(ExtractorLink(
                    source = name, name = "$name $label", url = url,
                    referer = data,
                    quality = when(label) {
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        "480p" -> Qualities.P480.value
                        "360p" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    },
                    type = ExtractorLinkType.VIDEO
                ))
            }
        }
        return true
    }
}