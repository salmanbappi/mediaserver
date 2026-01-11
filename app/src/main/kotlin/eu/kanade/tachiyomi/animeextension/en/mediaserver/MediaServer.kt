package eu.kanade.tachiyomi.animeextension.en.mediaserver

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import extensions.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import uy.kohesive.injekt.injectLazy

class MediaServer : AnimeHttpSource() {

    override val name = "MediaServer"

    override val baseUrl = "http://103.225.94.27/mediaserver"

    override val lang = "en"

    override val supportsLatest = true

    override val id: Long = 3615736726452648083L

    private val json: Json by injectLazy()

    // Title normalization regex
    private val seriesRegex = Regex("(.+)\\s+S(\\d+)E(\\d+).*", RegexOption.IGNORE_CASE)

    override fun popularAnimeRequest(page: Int): Request {
        val path = if (page == 1) "" else "page/$page/"
        return GET("$baseUrl/index.php/categories/movies/$path?orderby=views&order=DESC")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.post-item")
        
        val seenTitles = mutableSetOf<String>()
        val animeList = mutableListOf<SAnime>()

        elements.forEach { element ->
            val aTag = element.selectFirst("a.post-permalink") ?: element.selectFirst("a") ?: return@forEach
            val rawTitle = aTag.attr("title").ifEmpty { aTag.text() }.trim()
            
            // Series Grouping
            val match = seriesRegex.find(rawTitle)
            val displayTitle = if (match != null) match.groupValues[1].trim() else rawTitle
            
            if (seenTitles.contains(displayTitle)) return@forEach
            seenTitles.add(displayTitle)

            animeList.add(SAnime.create().apply {
                val originalPath = aTag.attr("href").removePrefix(baseUrl)
                url = if (match != null) {
                    "$originalPath?is_series=true&base_title=${java.net.URLEncoder.encode(displayTitle, "UTF-8")}"
                } else {
                    originalPath
                }
                title = displayTitle
                thumbnail_url = element.selectFirst("img")?.attr("src")
            })
        }
        
        val hasNextPage = document.selectFirst("a.next") != null || document.selectFirst("li.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val path = if (page == 1) "" else "page/$page/"
        return GET("$baseUrl/index.php/categories/movies/$path?orderby=date&order=DESC")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            val path = if (page == 1) "" else "page/$page/"
            GET("$baseUrl/index.php/$path?s=$query")
        } else {
            val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.let {
                categories[it.state].second
            } ?: "categories/movies/"
            val path = if (page == 1) "" else "page/$page/"
            GET("$baseUrl/index.php/$category$path?orderby=date&order=DESC")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.post-title")?.text() ?: "Unknown"
            description = document.select("div.post-content").text()
            genre = document.select("div.categories a").joinToString { it.text() }
            status = SAnime.COMPLETED
            thumbnail_url = document.selectFirst("div.post-thumbnail img")?.attr("src")
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        // If it's a series, we need to search for all episodes
        if (anime.url.contains("is_series=true")) {
            val baseTitle = anime.url.substringAfter("base_title=").substringBefore("&")
            return GET("$baseUrl/index.php/?s=${baseTitle}")
        }
        return super.episodeListRequest(anime)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val document = response.asJsoup()
        
        if (url.contains("?s=")) {
            // Parsing episodes from search results (Series)
            val elements = document.select("div.post-item")
            return elements.mapIndexed { index, element ->
                val aTag = element.selectFirst("a.post-permalink") ?: element.selectFirst("a")!!
                val epTitle = aTag.attr("title").ifEmpty { aTag.text() }.trim()
                
                SEpisode.create().apply {
                    name = epTitle
                    setUrlWithoutDomain(aTag.attr("href"))
                    // Try to extract episode number from title (e.g. E05)
                    val epMatch = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(epTitle)
                    episode_number = epMatch?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
                    date_upload = 0L 
                }
            }.sortedByDescending { it.episode_number }
        } else {
            // Single Movie
            return listOf(
                SEpisode.create().apply {
                    name = "Full Movie"
                    episode_number = 1f
                    setUrlWithoutDomain(url)
                }
            )
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        
        document.select("video-js").forEach { videoTag ->
            val settingsJson = videoTag.attr("data-settings")
            if (settingsJson.isNotEmpty()) {
                try {
                    val settings = json.parseToJsonElement(settingsJson).jsonObject
                    val sources = settings["sources"]?.jsonArray
                    sources?.forEach { source ->
                        val src = source.jsonObject["src"]?.jsonPrimitive?.contentOrNull
                        if (src != null) {
                            videoList.add(Video(src, "Stream", src))
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        
        if (videoList.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("embed")) {
                    // Possible internal player
                }
            }
        }

        return videoList
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter()
    )

    private class CategoryFilter : AnimeFilter.Select<String>(
        "Category",
        categories.map { it.first }.toTypedArray()
    )

    companion object {
        private val categories = listOf(
            "Movies (All)" to "categories/movies/",
            "English" to "categories/movies/english/",
            "Hindi Movies" to "categories/movies/hindi-movies/",
            "South Indian (Hindi Dub)" to "categories/movies/southindianhindi-dubbed/",
            "Animated" to "categories/movies/animated/",
            "Bangla Kolkata" to "categories/movies/bangla-kolkata/",
            "Bangla BD" to "categories/movies/banglabd/",
            "Korean" to "categories/movies/korean/",
            "Chinese" to "categories/movies/chiness-movie/",
            "Pakistani" to "categories/movies/pakistani/",
            "Punjabi" to "categories/movies/punjabi/",
            "4K" to "categories/movies/4k/",
            "3D" to "categories/movies/3d/",
            "Documentaries" to "categories/movies/documentaried/",
            "TV Shows" to "categories/tv-show/",
            "Bangla Drama" to "categories/tv-show/bangla-drama/",
            "English Drama" to "categories/tv-show/english-drama/",
            "Hindi Drama" to "categories/tv-show/hindi-drama/",
            "Kids Cartoon" to "categories/kids/cartoon/",
            "Kids Science" to "categories/kids/science/",
            "Kids E-Book" to "categories/kids/e-book/"
        )
    }
}
