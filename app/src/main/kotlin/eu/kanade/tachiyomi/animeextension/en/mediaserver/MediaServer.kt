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

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/index.php/categories/movies/?orderby=views&order=DESC&paged=$page")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.post-item")
        val animeList = elements.map { element ->
            SAnime.create().apply {
                val aTag = element.selectFirst("a.post-permalink") ?: element.selectFirst("a")!!
                setUrlWithoutDomain(aTag.attr("href"))
                title = aTag.attr("title").ifEmpty { aTag.text() }
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
        val hasNextPage = document.selectFirst("a.next") != null || document.selectFirst("li.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/index.php/categories/movies/?orderby=date&order=DESC&paged=$page")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/index.php/".toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query)
                addQueryParameter("paged", page.toString())
            }.build().toString()
        } else {
            val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.let {
                val pair = categories[it.state]
                pair.second
            } ?: "categories/movies/"
            
            "$baseUrl/index.php/$category".toHttpUrl().newBuilder().apply {
                addQueryParameter("paged", page.toString())
                addQueryParameter("orderby", "date")
                addQueryParameter("order", "DESC")
            }.build().toString()
        }
        return GET(url)
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

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        
        val episode = SEpisode.create().apply {
            name = "Full Movie"
            episode_number = 1f
            url = response.request.url.toString()
        }
        episodes.add(episode)
        
        return episodes
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
