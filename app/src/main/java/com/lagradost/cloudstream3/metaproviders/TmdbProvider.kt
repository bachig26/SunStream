package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.uwetrottmann.tmdb2.Tmdb
import com.uwetrottmann.tmdb2.entities.*
import com.uwetrottmann.tmdb2.enumerations.AppendToResponseItem
import com.uwetrottmann.tmdb2.enumerations.ReleaseType
import com.uwetrottmann.tmdb2.enumerations.VideoType
import retrofit2.awaitResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * episode and season starting from 1
 * they are null if movie
 * */
data class TmdbLink(
    @JsonProperty("imdbID") val imdbID: String?,
    @JsonProperty("tmdbID") val tmdbID: Int?,
    @JsonProperty("episode") val episode: Int?,
    @JsonProperty("season") val season: Int?,
    @JsonProperty("movieName") val movieName: String? = null,
    @JsonProperty("alternativeTitles") val alternativeTitles: List<AlternativeTitle>? = null,
)

open class TmdbProvider : MainAPI() {
    // This should always be false, but might as well make it easier for forks

    data class CrossSearch(
        @JsonProperty("query") val query: String? = null,
        @JsonProperty("network_filter") val network_filter: Int? = null,
        @JsonProperty("watch_provider_filter") val watch_provider_filter: Int? = null,
    )

    open val includeAdult = false

    // Use the LoadResponse from the metadata provider
    open val useMetaLoadResponse = false
    open val apiName = "TMDB"

    // As some sites doesn't support s0
    open val disableSeasonZero = true

    override val providerType = ProviderType.MetaProvider

    override val hasSearch: Boolean = false // only CrossTmdbProvider has search

    // Fuck it, public private api key because github actions won't co-operate.
    // Please no stealy.
    private val tmdb = Tmdb("1dbf84763e0115344e61effd0743d694") // sarlay's api key

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getBackdropUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original$link" else link
    }


    private fun getUrl(id: Int?, tvShow: Boolean): String {
        return if (tvShow) "https://www.themoviedb.org/tv/${id ?: -1}"
        else "https://www.themoviedb.org/movie/${id ?: -1}"
    }

    private fun BaseTvShow.toSearchResponse(): TvSeriesSearchResponse {
        return TvSeriesSearchResponse(
            this.name ?: this.original_name,
            getUrl(id, true),
            apiName,
            TvType.TvSeries,
            getImageUrl(this.poster_path),
            this.first_air_date?.let {
                Calendar.getInstance().apply {
                    time = it
                }.get(Calendar.YEAR)
            },
            null,
            this.id,
            rating = this.vote_average,
        )
    }

    private fun BaseMovie.toSearchResponse(): MovieSearchResponse {
        return MovieSearchResponse(
            this.title ?: this.original_title,
            getUrl(id, false),
            apiName,
            TvType.Movie,
            getImageUrl(this.poster_path),  // POSTER
            this.release_date?.let {
                Calendar.getInstance().apply {
                    time = it
                }.get(Calendar.YEAR)
            },
            this.id,
            rating = this.vote_average,
        )
    }

    private fun List<CastMember?>?.toActors(): List<Pair<Actor, String?>>? {
        return this?.mapNotNull {
            Pair(
                Actor(it?.name ?: return@mapNotNull null, getImageUrl(it.profile_path)),
                it.character
            )
        }
    }

    private suspend fun TvShow.toLoadResponse(): TvSeriesLoadResponse {
        val episodes = this.seasons?.filter { !disableSeasonZero || (it.season_number ?: 0) != 0 }
            ?.apmapIndexed { seasonIndex, season ->
                season.episodes?.apmapIndexed { episodeIndex, episode ->
                    val episodeBody = this@toLoadResponse.id?.let {
                        tmdb.tvEpisodesService()
                            .episode(
                                it,
                                seasonIndex+1,
                                episodeIndex+1,
                                "en-US",
                            )
                    }?.awaitResponse()?.body()

                    Episode(
                        TmdbLink(
                            episode.external_ids?.imdb_id ?: this.external_ids?.imdb_id,
                            this.id,
                            episode.episode_number,
                            episode.season_number,
                            null,
                            this.alternative_titles?.titles,
                        ).toJson(),
                        episode.name,
                        episode.season_number,
                        episode.episode_number,
                        getImageUrl(episodeBody?.still_path),
                        episodeBody?.vote_average?.toInt(), // TODO Not working ??
                        episode.overview,
                        episode.air_date?.time,
                    )
                } ?: (1..(season.episode_count ?: 1)).toList().apmap { episodeNum: Int ->

                    val episodeBody = this@toLoadResponse.id?.let {
                        tmdb.tvEpisodesService()
                            .episode(
                                it,
                                seasonIndex+1, // 0 is special season
                                episodeNum, //
                                "en-US",
                            )
                    }?.awaitResponse()?.body()

                    Episode(
                        name = episodeBody?.name,
                        episode = episodeNum,
                        data = TmdbLink(
                            this.external_ids?.imdb_id,
                            this.id,
                            episodeNum,
                            seasonIndex+1,
                        ).toJson(),
                        season = seasonIndex+1,
                        posterUrl = getImageUrl(episodeBody?.still_path),
                        rating = (episodeBody?.vote_average?.times(10))?.toInt(), // TODO Not working ??
                    )
                }
            }?.flatten() ?: listOf()

        return newTvSeriesLoadResponse(
            this.name ?: this.original_name,
            getUrl(id, true),
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = getImageUrl(poster_path)
            year = first_air_date?.let {
                Calendar.getInstance().apply {
                    time = it
                }.get(Calendar.YEAR)
            }
            plot = overview
            addImdbId(external_ids?.imdb_id)

            tags = genres?.mapNotNull { it.name }
            duration = episode_run_time?.average()?.toInt()
            rating = this@toLoadResponse.vote_average?.toDouble()?.toInt()
            addTrailer(videos.toTrailers())
            recommendations = (this@toLoadResponse.recommendations
                ?: this@toLoadResponse.similar)?.results?.map { it.toSearchResponse() }
            addActors(credits?.cast?.toList().toActors())
            // backdropUrl = getBackdropUrl(images?.backdrops?.first()?.file_path) get all backdrops
            backgroundPosterUrl = getBackdropUrl(backdrop_path)
        }
    }

    private fun Videos?.toTrailers(): List<String>? {
        return this?.results?.filter { it.type != VideoType.OPENING_CREDITS && it.type != VideoType.FEATURETTE }
            ?.sortedBy { it.type?.ordinal ?: 10000 }
            ?.mapNotNull {
                when (it.site?.trim()?.lowercase()) {
                    "youtube" -> { // TODO FILL SITES
                        "https://www.youtube.com/watch?v=${it.key}"
                    }
                    else -> null
                }
            }
    }

    private suspend fun Movie.toLoadResponse(): MovieLoadResponse {
        return newMovieLoadResponse(
            this.title ?: this.original_title, getUrl(id, false), TvType.Movie,
                TmdbLink(
                    this.imdb_id,
                    this.id,
                    null,
                    null,
                    this.title ?: this.original_title,
                    this.alternative_titles?.titles,
                ).toJson()
        ) {
            posterUrl = getImageUrl(poster_path)
            year = release_date?.let {
                Calendar.getInstance().apply {
                    time = it
                }.get(Calendar.YEAR)
            }
            plot = overview
            addImdbId(external_ids?.imdb_id)
            tags = genres?.mapNotNull { it.name }
            duration = runtime
            rating = this@toLoadResponse.vote_average?.toDouble()?.toInt()
            //rating = this@toLoadResponse.rating
            // addTrailer(videos.toTrailers()) //TODO ADD BACK
            recommendations = (this@toLoadResponse.recommendations
                ?: this@toLoadResponse.similar)?.results?.map { it.toSearchResponse() }
            addActors(credits?.cast?.toList().toActors())
            //backdropUrl = getBackdropUrl(images?.backdrops?.first()?.file_path)
            backgroundPosterUrl = getBackdropUrl(backdrop_path)
        }
    }



    override val mainPage = mainPageOf(
        Pair("discoverMovies", "Popular Movies"),
        Pair("discoverSeries", "Popular Series"),
        Pair("animeseries", "Popular Anime"),
        // Pair("airingToday", "Series airing today"), // kinda garbage
        Pair("actionmovies", "Action Movies"),
        Pair("actionseries", "Action & Adventure Series"),
        Pair("animemovies", "Popular Anime Movies"),
        Pair("comedymovies", "Comedy Movies"),
        Pair("comedyseries", "Comedy Series"),
        Pair("horrormovies", "Horror Movies"),
        Pair("scifiseries", "Sci-Fi Series"),
        Pair("topMovies", "Top Movies"),
        Pair("topSeries", "Top Series"),
        Pair("documentaryseries", "Documentary Series"),
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {

        // SAME AS DISCOVER IT SEEMS
//        val popularSeries = tmdb.tvService().popular(1, "en-US").execute().body()?.results?.map {
//           it.toSearchResponse()
//        } ?: listOf()
//


        // tmdb java wrapper api exemple https://useof.org/java-open-source/com.uwetrottmann.tmdb2.entities.DiscoverFilter


        /*
        1st- Theater
        2dn- Physical
        3rd- Digital


         It's important to note the order of the release types that are used. Specifying "2|3" would return the limited theatrical release date as opposed to "3|2" which would return the theatrical date.



           1- Premiere
           2- Theatrical (limited)
           3- Theatrical
           4- Digital
           5- Physical
            TV

         */

        val releasedOnlineFilter = DiscoverFilter(DiscoverFilter.Separator.OR, ReleaseType.PHYSICAL, ReleaseType.DIGITAL) // only show physical release and digital (hide theater cams: those are really annoying)
        val releasedBeforeDate = TmdbDate(LocalDate.now().minusDays(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) // get date of 3 days ago



        suspend fun discoverMovie(genre: Int, page: Int): List<SearchResponse>{
            return tmdb.discoverMovie().page(page).with_genres(DiscoverFilter(genre)).build().awaitResponse().body()?.results?.map {// TODO change region
                it.toSearchResponse()
            } ?: listOf()
        }

        suspend fun discoverTv(genre: Int, page: Int): List<SearchResponse>{
            return tmdb.discoverTv().page(page).with_genres(DiscoverFilter(genre)).build().awaitResponse().body()?.results?.map {// TODO change region
                it.toSearchResponse()
            } ?: listOf()
        }

        val responseContent = when (request.data) {
            "discoverMovies" -> tmdb.discoverMovie().page(page).with_release_type(releasedOnlineFilter).release_date_lte(releasedBeforeDate).build().awaitResponse().body()?.results?.map { // show movies released in physical or digital at least three days ago (time to upload rip)
                it.toSearchResponse()
            } ?: listOf()

            "discoverSeries" ->  tmdb.discoverTv().page(page).build().awaitResponse().body()?.results?.map { // no need for release type for tv show
                it.toSearchResponse()
            } ?: listOf()

            "topMovies" -> tmdb.moviesService().topRated(page, "en-US", "US").awaitResponse() // TODO change region
                    .body()?.results?.map {
                        it.toSearchResponse()
                    } ?: listOf()


            "topSeries" -> tmdb.tvService().topRated(page, "en-US").awaitResponse().body()?.results?.map {// TODO change region
                it.toSearchResponse()
            } ?: listOf()

            "airingToday" -> tmdb.tvService().airingToday(page, "en-US").awaitResponse().body()?.results?.map {// TODO change region
                it.toSearchResponse()
            } ?: listOf()


            "actionmovies" -> discoverMovie(28, page)
            "actionseries" -> discoverTv(10759, page)

            "comedymovies" -> discoverMovie(35, page)
            "comedyseries" -> discoverTv(35, page)

            "horrormovies" -> discoverMovie(27, page)
            "documentaryseries" -> discoverTv(99, page)

            "scifimovies" -> discoverMovie(878, page)
            "scifiseries" -> discoverTv(10765, page)

            "animemovies" -> tmdb.discoverMovie().page(page).with_keywords(DiscoverFilter(DiscoverFilter.Separator.OR, 210024,222243)).build().awaitResponse().body()?.results?.map { // no need for release type for tv show
                it.toSearchResponse()
            } ?: listOf()
            "animeseries" ->  tmdb.discoverTv().page(page).with_keywords(DiscoverFilter(DiscoverFilter.Separator.OR, 210024,222243)).build().awaitResponse().body()?.results?.map {
                it.toSearchResponse()
            } ?: listOf()

            else -> throw ErrorLoadingException()
        }

        return newHomePageResponse(
            HomePageList(
                request.name,
                responseContent,
            ),
            true
        )
    }

    open fun loadFromImdb(imdb: String, seasons: List<TvSeason>): LoadResponse? {
        return null
    }

    open fun loadFromTmdb(tmdb: Int, seasons: List<TvSeason>): LoadResponse? {
        return null
    }

    open fun loadFromImdb(imdb: String): LoadResponse? {
        return null
    }

    open fun loadFromTmdb(tmdb: Int): LoadResponse? {
        return null
    }

    // Possible to add recommendations and such here.
    override suspend fun load(url: String): LoadResponse? {
        // https://www.themoviedb.org/movie/7445-brothers
        // https://www.themoviedb.org/tv/71914-the-wheel-of-time

        val idRegex = Regex("""themoviedb\.org/(.*)/(\d+)""")
        val found = idRegex.find(url)

        val isTvSeries = found?.groupValues?.getOrNull(1).equals("tv", ignoreCase = true)
        val id = found?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: throw ErrorLoadingException("No id found")

        return if (useMetaLoadResponse) {
            return if (isTvSeries) {
                val body = tmdb.tvService()
                    .tv(
                        id,
                        "en-US",
                        AppendToResponse(
                            AppendToResponseItem.EXTERNAL_IDS,
                            AppendToResponseItem.VIDEOS,
                            AppendToResponseItem.CREDITS,
                            AppendToResponseItem.ALTERNATIVE_TITLES,
                            // AppendToResponseItem.IMAGES, // display all posters
                        ),
                        // mapOf("include_image_language" to "null"), // display all posters
                    )
                    .awaitResponse().body()
                val response = body?.toLoadResponse()
                if (response != null) {
                    if (response.recommendations.isNullOrEmpty())
                        tmdb.tvService().recommendations(id, 1, "en-US").awaitResponse().body()
                            ?.let {
                                it.results?.map { res -> res.toSearchResponse() }
                            }?.let { list ->
                                response.recommendations = list
                            }

                    if (response.actors.isNullOrEmpty())
                        tmdb.tvService().credits(id, "en-US").awaitResponse().body()?.let {
                            response.addActors(it.cast?.toActors())
                        }
                }

                response
            } else {
                val body = tmdb.moviesService()
                    .summary(
                        id,
                        "en-US",
                        AppendToResponse(
                            AppendToResponseItem.EXTERNAL_IDS,
                            AppendToResponseItem.VIDEOS,
                            AppendToResponseItem.CREDITS,
                            AppendToResponseItem.ALTERNATIVE_TITLES,
                            // AppendToResponseItem.IMAGES, // display all posters
                        ),
                        // mapOf("include_image_language" to "null") //  display all posters
                    )
                    .awaitResponse().body()
                val response = body?.toLoadResponse()
                if (response != null) {
                    if (response.recommendations.isNullOrEmpty())
                        tmdb.moviesService().recommendations(id, 1, "en-US").awaitResponse().body()
                            ?.let {
                                it.results?.map { res -> res.toSearchResponse() }
                            }?.let { list ->
                                response.recommendations = list
                            }

                    if (response.actors.isNullOrEmpty())
                        tmdb.moviesService().credits(id).awaitResponse().body()?.let {
                            response.addActors(it.cast?.toActors())
                        }
                }
                response
            }
        } else {
            loadFromTmdb(id)?.let { return it }
            if (isTvSeries) {
                tmdb.tvService().externalIds(id, "en-US").awaitResponse().body()?.imdb_id?.let {
                    val fromImdb = loadFromImdb(it)
                    val result = if (fromImdb == null) {
                        val details = tmdb.tvService().tv(id, "en-US").awaitResponse().body()
                        loadFromImdb(it, details?.seasons ?: listOf())
                            ?: loadFromTmdb(id, details?.seasons ?: listOf())
                    } else {
                        fromImdb
                    }

                    result
                }
            } else {
                tmdb.moviesService().externalIds(id, "en-US").awaitResponse()
                    .body()?.imdb_id?.let { loadFromImdb(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println(query)
        val searchQuery = AppUtils.tryParseJson<CrossSearch>(query)
        val directSearch = searchQuery?.query // the searched content (like The Simpsons)
        val networkFilter = searchQuery?.network_filter // the network (like Netflix)
        val watchProviderFilter = searchQuery?.watch_provider_filter // the platform (like Netflix); for movies
        return if (networkFilter == null && directSearch != null) {
            tmdb.searchService().multi(directSearch, 1, "en-Us", "US", includeAdult).awaitResponse()
                .body()?.results?.sortedByDescending { it.movie?.popularity ?: it.tvShow?.popularity }?.mapNotNull {
                    it.movie?.toSearchResponse() ?: it.tvShow?.toSearchResponse()
                }
        } else if (networkFilter != null && directSearch == null) {
            val networkDiscoverFilter = DiscoverFilter(networkFilter) // netflix
            val watchProviderDiscoverFilter = DiscoverFilter(watchProviderFilter) // netflix
            val tvshowList = tmdb.discoverTv().page(1).with_networks(networkDiscoverFilter).build().awaitResponse().body()?.results?.map {
                it.toSearchResponse()
            } ?: listOf()
            val movieList = tmdb.discoverMovie().page(1).with_watch_providers(watchProviderDiscoverFilter).watch_region("US").build().awaitResponse().body()?.results?.map {
                it.toSearchResponse()
            } ?: listOf()

            sequence {
                val first = tvshowList.iterator()
                val second = movieList.iterator()
                while (first.hasNext() && second.hasNext()) {
                    yield(first.next())
                    yield(second.next())
                }

                yieldAll(first)
                yieldAll(second)
            }.toList() // merge both list and alternate tvshow / movie https://stackoverflow.com/questions/55404428/how-to-combine-two-different-length-lists-in-kotlin
        } else {
            null
        }

    }
}
