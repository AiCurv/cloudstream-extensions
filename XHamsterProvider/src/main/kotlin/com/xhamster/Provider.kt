package com.xhamster

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

data class XhamsterVideoThumb(
    val id: Int,
    val duration: Int,
    val created: Long,
    val title: String,
    val thumbId: Int,
    val videoType: String,
    val pageURL: String,
    val thumbURL: String,
    val imageURL: String,
    val previewThumbURL: String?,
    val trailerURL: String?,
    val views: Long,
    val landing: XhamsterLanding?
)

data class XhamsterLanding(
    val type: String,
    val id: Int,
    val name: String,
    val logo: String?,
    val link: String
)

data class XhamsterVideoPage(
    val videoModelComponent: XhamsterVideoModel?
)

data class XhamsterVideoModel(
    val id: Int?,
    val title: String?,
    val duration: Int?,
    val views: Long?,
    val created: Long?,
    val upVotePercentage: Int?,
    val downVoteCount: Int?,
    val isPremiumOnly: Boolean?,
    val isPremiumDownload: Boolean?,
    val isLiked: Boolean?,
    val isDisliked: Boolean?,
    val isSaved: Boolean?,
    val isDownloadable: Boolean?,
    val isPurchased: Boolean?,
    val comments: Int?,
    val pictures: List<String>?,
    val model: XhamsterModel?,
    val tags: List<XhamsterTag>?,
    val categories: List<XhamsterCategory>?,
    val hls: XhamsterHls?
)

data class XhamsterModel(
    val id: Int?,
    val name: String?,
    val username: String?,
    val logo: String?,
    val link: String?,
    val subscribers: Int?,
    val totalViews: Long?,
    val totalVideos: Int?,
    val isVerified: Boolean?
)

data class XhamsterTag(
    val name: String,
    val link: String
)

data class XhamsterCategory(
    val name: String,
    val link: String
)

data class XhamsterHls(
    val sources: Map<String, String>?,
    val poster: String?,
    val trailer: XhamsterTrailer?
)

data class XhamsterTrailer(
    val url: String?,
    val poster: String?,
    val duration: Int?
)

class XHamsterProvider : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "XHamster"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true

    private val jacksonMapper = ObjectMapper().registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Newest",
        "$mainUrl/most-popular" to "Most Popular",
        "$mainUrl/longest" to "Longest",
        "$mainUrl/most-viewed" to "Most Viewed",
        "$mainUrl/top" to "Top Rated",
        "$mainUrl/newest" to "Latest",
    )

    private fun String.fixUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> "$mainUrl$this"
        else -> this
    }

    private fun parseWindowInitials(html: String): Map<String, Any>? {
        val regex = Regex("""window\.initials\s*=\s*(\{.{1,500000})\s*;</script>""")
        val match = regex.find(html) ?: return null
        val jsonStr = match.groupValues[1]
        return try {
            jacksonMapper.readValue(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVideosFromInitials(initials: Map<String, Any>?): List<XhamsterVideoThumb> {
        if (initials == null) return emptyList()
        val videoListProps = initials["layoutPage"] as? Map<String, Any>
            ?: return emptyList()
        val thumbProps = videoListProps["videoListProps"] as? Map<String, Any>
            ?: return emptyList()
        val videos = thumbProps["videoThumbProps"] as? List<Map<String, Any>>
            ?: return emptyList()

        return videos.mapNotNull { v ->
            try {
                XhamsterVideoThumb(
                    id = (v["id"] as? Number)?.toInt() ?: return@mapNotNull null,
                    duration = (v["duration"] as? Number)?.toInt() ?: 0,
                    created = (v["created"] as? Number)?.toLong() ?: 0L,
                    title = v["title"] as? String ?: "",
                    thumbId = (v["thumbId"] as? Number)?.toInt() ?: 0,
                    videoType = v["videoType"] as? String ?: "video",
                    pageURL = (v["pageURL"] as? String)?.fixUrl() ?: "",
                    thumbURL = (v["thumbURL"] as? String)?.fixUrl() ?: "",
                    imageURL = (v["imageURL"] as? String)?.fixUrl() ?: "",
                    previewThumbURL = v["previewThumbURL"] as? String,
                    trailerURL = v["trailerURL"] as? String,
                    views = (v["views"] as? Number)?.toLong() ?: 0L,
                    landing = (v["landing"] as? Map<String, Any>)?.let { l ->
                        XhamsterLanding(
                            type = l["type"] as? String ?: "",
                            id = (l["id"] as? Number)?.toInt() ?: 0,
                            name = l["name"] as? String ?: "",
                            logo = l["logo"] as? String,
                            link = l["link"] as? String ?: ""
                        )
                    }
                )
            } catch (e: Exception) { null }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val html = app.get(url).text
        val initials = parseWindowInitials(html)
        val videos = extractVideosFromInitials(initials)

        val hasNext = page < 10

        val homePageVideos = videos.map { v ->
            newMovieSearchResponse(
                name = v.title,
                url = v.pageURL,
                type = TvType.NSFW,
                posterUrl = v.imageURL.fixUrl()
            ) {
                this.year = null
            }
        }

        return newHomePageResponse(listOf(HomePageList(request.name, homePageVideos)), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        val html = app.get("$mainUrl/search/$q").text
        val initials = parseWindowInitials(html)

        val videos = mutableListOf<XhamsterVideoThumb>()

        val searchVideoSuggestions = initials?.get("searchVideoSuggestions") as? Map<String, Any>
        val pageVideos = searchVideoSuggestions?.get("pageVideos") as? List<Map<String, Any>> ?: emptyList()

        val xhlMlSource = initials?.get("xhlMlSource") as? Map<String, Any>
        val mlPayload = xhlMlSource?.get("payload") as? Map<String, Any>
        val recommendedVideos = mlPayload?.get("pageVideos") as? List<Number> ?: emptyList()

        for (v in pageVideos) {
            try {
                videos.add(
                    XhamsterVideoThumb(
                        id = (v["id"] as? Number)?.toInt() ?: return@forEach,
                        duration = (v["duration"] as? Number)?.toInt() ?: 0,
                        created = (v["created"] as? Number)?.toLong() ?: 0L,
                        title = v["title"] as? String ?: "",
                        thumbId = (v["thumbId"] as? Number)?.toInt() ?: 0,
                        videoType = v["videoType"] as? String ?: "video",
                        pageURL = (v["pageURL"] as? String)?.fixUrl() ?: "",
                        thumbURL = (v["thumbURL"] as? String)?.fixUrl() ?: "",
                        imageURL = (v["imageURL"] as? String)?.fixUrl() ?: "",
                        previewThumbURL = v["previewThumbURL"] as? String,
                        trailerURL = v["trailerURL"] as? String,
                        views = (v["views"] as? Number)?.toLong() ?: 0L,
                        landing = null
                    )
                )
            } catch (e: Exception) { }
        }

        if (videos.isEmpty()) {
            val videoListProps = initials?.get("layoutPage") as? Map<String, Any>
            val vlp = videoListProps?.get("videoListProps") as? Map<String, Any>
            val thumbProps = vlp?.get("videoThumbProps") as? List<Map<String, Any>> ?: emptyList()
            for (v in thumbProps) {
                try {
                    videos.add(
                        XhamsterVideoThumb(
                            id = (v["id"] as? Number)?.toInt() ?: continue,
                            duration = (v["duration"] as? Number)?.toInt() ?: 0,
                            created = (v["created"] as? Number)?.toLong() ?: 0L,
                            title = v["title"] as? String ?: "",
                            thumbId = (v["thumbId"] as? Number)?.toInt() ?: 0,
                            videoType = v["videoType"] as? String ?: "video",
                            pageURL = (v["pageURL"] as? String)?.fixUrl() ?: "",
                            thumbURL = (v["thumbURL"] as? String)?.fixUrl() ?: "",
                            imageURL = (v["imageURL"] as? String)?.fixUrl() ?: "",
                            previewThumbURL = v["previewThumbURL"] as? String,
                            trailerURL = v["trailerURL"] as? String,
                            views = (v["views"] as? Number)?.toLong() ?: 0L,
                            landing = null
                        )
                    )
                } catch (e: Exception) { }
            }
        }

        return videos.map { v ->
            newMovieSearchResponse(
                name = v.title,
                url = v.pageURL,
                type = TvType.NSFW,
                posterUrl = v.imageURL.fixUrl()
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val initials = parseWindowInitials(html) ?: throw Error("No video data found")

        val videoPage = try {
            jacksonMapper.readValue<Map<String, Any>>(
                Regex("""window\.initials\s*=\s*(\{.{1,500000})\s*;</script>""")
                    .find(html)?.groupValues?.get(1) ?: "{}"
            )
        } catch (e: Exception) { null }

        val vmp = videoPage?.get("videoModelComponent") as? Map<String, Any>
        val hlsMap = vmp?.get("hls") as? Map<String, Any>
        val sources = hlsMap?.get("sources") as? Map<String, String> ?: emptyMap()
        val poster = hlsMap?.get("poster") as? String ?: ""
        val modelInfo = vmp?.get("model") as? Map<String, Any>
        val tagsList = vmp?.get("tags") as? List<Map<String, String>> ?: emptyList()
        val catsList = vmp?.get("categories") as? List<Map<String, String>> ?: emptyList()

        val title = vmp?.get("title") as? String ?: "Unknown"
        val desc = vmp?.get("description") as? String ?: ""

        val tags = tagsList.mapNotNull { it["name"] }
        val categories = catsList.mapNotNull { it["name"] }
        val allTags = (categories + tags).distinct()

        val actors = modelInfo?.let { m ->
            listOf(
                ActorData(
                    Actor(
                        name = m["name"] as? String ?: m["username"] as? String ?: "Unknown"
                    )
                )
            )
        } ?: emptyList()

        this.actors = actors

        val videoUrl = sources.entries.firstOrNull()?.value ?: ""

        return newMovieLoadResponse(
            name = title,
            url = url,
            dataUrl = videoUrl,
            type = TvType.NSFW,
            posterUrl = poster.fixUrl()
        ) {
            plot = desc
            this.tags = allTags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty()) return false

        callback(
            ExtractorLink(
                source = name,
                name = "$name HLS",
                url = data.fixUrl(),
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }
}