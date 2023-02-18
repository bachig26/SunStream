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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.roundToInt

/**
 * episode and season starting from 1
 * they are null if movie
 * */

data class LinkData( // FROM SORASTREAM
    val id: Int? = null,
    val imdbId: String? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val aniId: String? = null,
    val animeId: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val orgTitle: String? = null,
    val isAnime: Boolean = false,
    val airedYear: Int? = null,
    val lastSeason: Int? = null,
    val epsTitle: String? = null,
)

data class SunStreamExtendedLinkData(
    val link: LinkData,
    val alternativeTitles: List<Translations.Translation>? = null,
    val dubStatus: DubStatus? = null, // movie name = orgTitle
)


open class TmdbProvider : MainAPI() {
    // This should always be false, but might as well make it easier for forks

    companion object {
        var localeLang: String? = null
    }

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
    override var mainUrl = "https://api.themoviedb.org/3"
    private val apiKey = "1dbf84763e0115344e61effd0743d694" // PLEASE DON'T STEAL
    // Fuck it, public private api key because github actions won't co-operate.
    // Please no stealy.
    private val tmdb = Tmdb(apiKey) // sarlay's api key

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
            rating = this.vote_average?.round(),
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
            rating = this.vote_average?.round(),
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


    private suspend fun getEpisodesAnime(anime: TvShow, dubStatus: DubStatus?): List<Episode>? {
        val lastSeason = anime.seasons?.lastOrNull()?.season_number
        val episodes = anime.seasons?.mapNotNull { seasonObject ->
            tmdb.tvSeasonsService().season(anime.id, seasonObject.season_number, localeLang)?.awaitResponse()?.body()?.episodes?.map { eps: TvEpisode ->
                Episode(
                    SunStreamExtendedLinkData(
                        LinkData(
                            anime.id,
                            anime.external_ids?.imdb_id,
                            "tv",
                            eps.season_number,
                            eps.episode_number,
                            title = eps.name,
                            year = eps.air_date?.let {
                                Calendar.getInstance().apply {
                                    time = it
                                }.get(Calendar.YEAR)
                            },
                            orgTitle = eps.name,
                            isAnime = true,
                            airedYear = eps.air_date?.let {
                                Calendar.getInstance().apply {
                                    time = it
                                }.get(Calendar.YEAR)
                            },
                            lastSeason = lastSeason,
                            epsTitle = eps.name,
                        ),
                        alternativeTitles = anime.translations?.translations,
                        dubStatus = dubStatus
                    ).toJson(),
                    name = eps.name,
                    // season = eps.seasonNumber,
                    episode = eps.episode_number,
                    posterUrl = getImageUrl(eps.still_path),
                    rating = eps.vote_average?.times(10)?.roundToInt(),
                    description = eps.overview
                ).apply {
                    this.addDate(eps.air_date)
                }


            }
        }?.flatten()
        return episodes
    }

    private suspend fun TvShow.toLoadResponse(): LoadResponse? {
        val isAnime = this.keywords?.keywords?.any { it.id == 210024 } // true if has anime keyword  (210024)
        if (isAnime == true) {
            return newAnimeLoadResponse(this.name ?: this.original_name,
                getUrl(id, true),
                TvType.Anime,
            ) {
                addEpisodes(DubStatus.Subbed, getEpisodesAnime(this@toLoadResponse, DubStatus.Subbed))
                addEpisodes(DubStatus.Dubbed, getEpisodesAnime(this@toLoadResponse, DubStatus.Dubbed))
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
                rating = this@toLoadResponse.vote_average?.round()?.toInt()
                addTrailer(videos.toTrailers())
                recommendations = (this@toLoadResponse.recommendations
                    ?: this@toLoadResponse.similar)?.results?.map { it.toSearchResponse() }
                addActors(credits?.cast?.toList().toActors())
                // backdropUrl = getBackdropUrl(images?.backdrops?.first()?.file_path) get all backdrops
                backgroundPosterUrl = getBackdropUrl(backdrop_path)
            }
        }


        val lastSeason = this.seasons?.lastOrNull()?.season_number
        val episodeList = this.seasons?.mapNotNull { seasonObject ->
            tmdb.tvSeasonsService().season(this.id, seasonObject.season_number, localeLang)?.awaitResponse()?.body()?.episodes?.map { eps: TvEpisode ->
                Episode(
                    SunStreamExtendedLinkData(
                        LinkData(
                            this.id,
                            this.external_ids?.imdb_id,
                            "tv",
                            eps.season_number,
                            eps.episode_number,
                            title = eps.name,
                            year = eps.air_date?.let {
                                Calendar.getInstance().apply {
                                    time = it
                                }.get(Calendar.YEAR)
                            },
                            orgTitle = eps.name,
                            isAnime = false,
                            airedYear = eps.air_date?.let {
                                Calendar.getInstance().apply {
                                    time = it
                                }.get(Calendar.YEAR)
                            },
                            lastSeason = lastSeason,
                            epsTitle = eps.name,
                        ),
                        alternativeTitles = this.translations?.translations,
                    ).toJson(),
                    name = eps.name,
                    season = eps.season_number,
                    episode = eps.episode_number,
                    posterUrl = getImageUrl(eps.still_path),
                    rating = eps.vote_average?.toString()?.replace(",", ".")?.toDouble()?.times(10)?.toInt(),
                    description = eps.overview,
                ).apply {
                    this.addDate(eps.air_date)
                }

            }
        }?.flatten()
        episodeList ?: return null


        // todo fix tv show episode
        // ?.filter { !disableSeasonZero || (it.season_number ?: 0) != 0 } TODO add it back

        return newTvSeriesLoadResponse(
            this.name ?: this.original_name,
            getUrl(id, true),
            TvType.TvSeries,
            episodeList
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
            rating = this@toLoadResponse.vote_average?.round()?.toInt()
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
            SunStreamExtendedLinkData(
                LinkData(
                    this.id,
                    this.external_ids.imdb_id,
                    "movie",
                    title = this.title,
                    year = this.release_date?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()?.year,
                    orgTitle = this.original_title,
                    isAnime = false,
                ),
                alternativeTitles = this.translations?.translations,
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
            rating = this@toLoadResponse.vote_average?.round()?.toInt()
            //rating = this@toLoadResponse.rating
            addTrailer(videos.toTrailers())
            recommendations = (this@toLoadResponse.recommendations
                ?: this@toLoadResponse.similar)?.results?.map { it.toSearchResponse() }
            addActors(credits?.cast?.toList().toActors())
            //backdropUrl = getBackdropUrl(images?.backdrops?.first()?.file_path)
            backgroundPosterUrl = getBackdropUrl(backdrop_path)
        }
    }



    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/day?api_key=$apiKey&region=&page=" to "Trending Today",
        "$mainUrl/movie/popular?api_key=$apiKey&region=&page=" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$apiKey&region=&page=" to "Popular TV Shows",
        "$mainUrl/tv/on_the_air?api_key=$apiKey&region=&page=" to "On The Air TV Shows",
        //"$mainUrl/tv/airing_today?api_key=$apiKey&region=&page=" to "Airing Today TV Shows",
        "$mainUrl/movie/top_rated?api_key=$apiKey&region=&page=" to "Top Rated Movies",
        "$mainUrl/tv/top_rated?api_key=$apiKey&region=&page=" to "Top Rated TV Shows",
        "$mainUrl/discover/tv?api_key=$apiKey&with_original_language=ko&page=" to "Korean Shows",
        "$mainUrl/discover/tv?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime",
        "$mainUrl/discover/movie?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime Movies",
    )

    private fun Double.round(): Int {
        return (this * 10).roundToInt()
    }

    private fun Media.toSearchResponse(type: TvType? = null): SearchResponse? {
        val newType = type ?: if(this.title != null) TvType.Movie else TvType.TvSeries // check again type if "/movie" was not in the request, only movies have title
        return MovieSearchResponse(
            this.title ?: this.name ?: this.originalTitle ?: return null,
            getUrl(id, newType == TvType.TvSeries), // type=="tv"
            apiName,
            if(newType == TvType.Movie) {TvType.Movie} else TvType.TvSeries,
            getImageUrl(this.posterPath),  // POSTER
            null, // YEAR
            this.id,
            rating = this.voteAverage?.round(), // will format to: 8.6
        )
    }


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val type =
            if (request.data.contains("/movie")) TvType.Movie
            else {
                if (request.data.contains("/tv")) TvType.TvSeries
                else null //unknown type
            }
        val language = "&language=${localeLang ?: "en"}"
        val home = app.get(request.data + page + language)
            .parsedSafe<Results>()?.results
            ?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home, true, type)
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
                        localeLang,
                        AppendToResponse(
                            AppendToResponseItem.EXTERNAL_IDS,
                            AppendToResponseItem.VIDEOS,
                            AppendToResponseItem.CREDITS,
                            AppendToResponseItem.KEYWORDS,
                            AppendToResponseItem.TRANSLATIONS,
                            AppendToResponseItem.SIMILAR
                            // AppendToResponseItem.IMAGES, // display all posters
                        ),
                        // mapOf("include_image_language" to "null"), // display all posters
                    )
                    .awaitResponse().body()
                val response = body?.toLoadResponse()
                if (response != null) {
                    if (response.recommendations.isNullOrEmpty())
                        tmdb.tvService().recommendations(id, 1, localeLang).awaitResponse().body()
                            ?.let {
                                it.results?.map { res -> res.toSearchResponse() }
                            }?.let { list ->
                                response.recommendations = list
                            }

                    if (response.actors.isNullOrEmpty())
                        tmdb.tvService().credits(id, localeLang).awaitResponse().body()?.let {
                            response.addActors(it.cast?.toActors())
                        }
                }

                response
            } else {
                val body = tmdb.moviesService()
                    .summary(
                        id,
                        localeLang,
                        AppendToResponse(
                            AppendToResponseItem.EXTERNAL_IDS,
                            AppendToResponseItem.VIDEOS,
                            AppendToResponseItem.CREDITS,
                            AppendToResponseItem.TRANSLATIONS,
                            // AppendToResponseItem.IMAGES, // display all posters
                        ),
                        // mapOf("include_image_language" to "null") //  display all posters
                    )
                    .awaitResponse().body()
                val response = body?.toLoadResponse()
                if (response != null) {
                    if (response.recommendations.isNullOrEmpty())
                        tmdb.moviesService().recommendations(id, 1, localeLang).awaitResponse().body()
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
                tmdb.tvService().externalIds(id, localeLang).awaitResponse().body()?.imdb_id?.let {
                    val fromImdb = loadFromImdb(it)
                    val result = if (fromImdb == null) {
                        val details = tmdb.tvService().tv(id, localeLang).awaitResponse().body()
                        loadFromImdb(it, details?.seasons ?: listOf())
                            ?: loadFromTmdb(id, details?.seasons ?: listOf())
                    } else {
                        fromImdb
                    }

                    result
                }
            } else {
                tmdb.moviesService().externalIds(id, localeLang).awaitResponse()
                    .body()?.imdb_id?.let { loadFromImdb(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchQuery = AppUtils.tryParseJson<CrossSearch>(query)
        val directSearch = searchQuery?.query // the searched content (like The Simpsons)
        val networkFilter = searchQuery?.network_filter // the network (like Netflix)
        val watchProviderFilter = searchQuery?.watch_provider_filter // the platform (like Netflix); for movies
        return if (networkFilter == null && directSearch != null) {
            tmdb.searchService().multi(directSearch, 1, localeLang, "US", includeAdult).awaitResponse()
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
    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null, // tv show
        @JsonProperty("title") val title: String? = null, // movie
        @JsonProperty("original_title") val originalTitle: String? = null, // movie
        @JsonProperty("original_name") val originalName: String? = null, // tv show
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_count") val voteCount: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )


}
