package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.uwetrottmann.tmdb2.Tmdb
import com.uwetrottmann.tmdb2.entities.*
import com.uwetrottmann.tmdb2.entities.DiscoverFilter
import com.uwetrottmann.tmdb2.enumerations.AppendToResponseItem
import com.uwetrottmann.tmdb2.enumerations.ReleaseType
import com.uwetrottmann.tmdb2.enumerations.VideoType
import retrofit2.awaitResponse
import java.util.*
import java.time.LocalDate // might be bad idk
import java.time.format.DateTimeFormatter

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
)

open class TmdbProvider : MainAPI() {
    // This should always be false, but might as well make it easier for forks
    open val includeAdult = false

    // Use the LoadResponse from the metadata provider
    open val useMetaLoadResponse = false
    open val apiName = "TMDB"

    // As some sites doesn't support s0
    open val disableSeasonZero = true

    override val providerType = ProviderType.MetaProvider

    override val hasSearch: Boolean = false // only CrossTmdbProvider has search (called MultiMedia)

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
            TvType.TvSeries,
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
            ?.mapNotNull { season ->
                season.episodes?.map { episode ->
                    Episode(
                        TmdbLink(
                            episode.external_ids?.imdb_id ?: this.external_ids?.imdb_id,
                            this.id,
                            episode.episode_number,
                            episode.season_number,
                        ).toJson(),
                        episode.name,
                        episode.season_number,
                        episode.episode_number,
                        getImageUrl(episode.still_path),
                        episode.rating,
                        episode.overview,
                        episode.air_date?.time,
                    )
                } ?: (1..(season.episode_count ?: 1)).map { episodeNum ->
                    Episode(
                        episode = episodeNum,
                        data = TmdbLink(
                            this.external_ids?.imdb_id,
                            this.id,
                            episodeNum,
                            season.season_number,
                        ).toJson(),
                        season = season.season_number
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
            rating = this@toLoadResponse.rating
            addTrailer(videos.toTrailers())
            recommendations = (this@toLoadResponse.recommendations
                ?: this@toLoadResponse.similar)?.results?.map { it.toSearchResponse() }
            addActors(credits?.cast?.toList().toActors())
            // backdropUrl = getBackdropUrl(images?.backdrops?.first()?.file_path) get all backdrops
            backdropUrl = getBackdropUrl(backdrop_path)
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
            this.title ?: this.original_title, getUrl(id, false), TvType.Movie, TmdbLink(
                this.imdb_id,
                this.id,
                null,
                null,
                this.title ?: this.original_title,
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
            rating = this@toLoadResponse.rating
            addTrailer(videos.toTrailers())
            recommendations = (this@toLoadResponse.recommendations
                ?: this@toLoadResponse.similar)?.results?.map { it.toSearchResponse() }
            addActors(credits?.cast?.toList().toActors())
            //backdropUrl = getBackdropUrl(images?.backdrops?.first()?.file_path)
            backdropUrl = getBackdropUrl(backdrop_path)
        }
    }

    override suspend fun getMainPage(page: Int, categoryName: String, categoryData: String): HomePageResponse {

        // SAME AS DISCOVER IT SEEMS
//        val popularSeries = tmdb.tvService().popular(1, "en-US").execute().body()?.results?.map {
//            it.toSearchResponse()
//        } ?: listOf()
//
//        val popularMovies =
//            tmdb.moviesService().popular(1, "en-US", "840").execute().body()?.results?.map {
//                it.toSearchResponse()
//            } ?: listOf()

        var discoverMovies: List<MovieSearchResponse> = listOf()
        var discoverSeries: List<TvSeriesSearchResponse> = listOf()
        var topMovies: List<MovieSearchResponse> = listOf()
        var topSeries: List<TvSeriesSearchResponse> = listOf()

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
        val releasedBeforeDate = TmdbDate(LocalDate.now().minusDays(5).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) // get date of 5 days ago
        argamap(
            {
                discoverMovies = tmdb.discoverMovie().with_release_type(releasedOnlineFilter).release_date_lte(releasedBeforeDate).build().awaitResponse().body()?.results?.map { // show movies released in physical or digital at least five days ago (time to upload rip)
                    it.toSearchResponse()
                } ?: listOf()
            }, {
                discoverSeries = tmdb.discoverTv().build().awaitResponse().body()?.results?.map { // no need for release type for tv show
                    it.toSearchResponse()
                } ?: listOf()
            }, {
                // https://en.wikipedia.org/wiki/ISO_3166-1
                topMovies =
                    tmdb.moviesService().topRated(1, "en-US", "US").awaitResponse() // TODO change region
                        .body()?.results?.map {
                            it.toSearchResponse()
                        } ?: listOf()
            }, {
                topSeries =
                    tmdb.tvService().topRated(1, "en-US").awaitResponse().body()?.results?.map {// TODO change region
                        it.toSearchResponse()
                    } ?: listOf()
            }
        )

        return HomePageResponse(
            listOf(
//                HomePageList("Popular Series", popularSeries),
//                HomePageList("Popular Movies", popularMovies),
                HomePageList("Popular Movies", discoverMovies),
                HomePageList("Popular Series", discoverSeries),
                HomePageList("Top Movies", topMovies),
                HomePageList("Top Series", topSeries),
            )
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
        return tmdb.searchService().multi(query, 1, "en-Us", "US", includeAdult).awaitResponse()
            .body()?.results?.mapNotNull {
                it.movie?.toSearchResponse() ?: it.tvShow?.toSearchResponse()
            }
    }
}
