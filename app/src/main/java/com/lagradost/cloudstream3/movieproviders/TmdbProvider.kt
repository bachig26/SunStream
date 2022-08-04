/*
package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*


class TmdbProvider : MainAPI() {
    override var name = "Tmdb"
    override var mainUrl = "https://api.themoviedb.org/3"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false

    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.AnimeMovie,
            TvType.TvSeries,
            TvType.Cartoon,
            TvType.Anime,
            TvType.Documentary,
        )


    data class lookupJson(
        @JsonProperty("tmdbId") var tmdbId: String,
        @JsonProperty("title") var title: String,
        @JsonProperty("remotePoster") var posterUrl: String?,
        @JsonProperty("year") var year: Int?,
    )

    /*
    data class mainPageJson (
        @JsonProperty("page") var page: Long,
        @JsonProperty("results") var results: List<Result>
    )
    */
    data class mainPageJsonMovie (
        @JsonProperty("page") var page: Long,
        @JsonProperty("results") var results: List<ResultMovie>
    )

    data class mainPageJsonTv (
        @JsonProperty("page") var page: Long,
        @JsonProperty("results") var results: List<ResultTv>
    )

    data class ResultMovie (
        @JsonProperty("id") var tmdbid: Int,
        @JsonProperty("poster_path") var posterPath: String? = null,
        @JsonProperty("title") var title: String? = null,
    )

    data class ResultTv (
        @JsonProperty("id") var tmdbid: Int,
        @JsonProperty("poster_path") var posterPath: String? = null,
        @JsonProperty("original_name") var original_name: String? = null,
    )

    enum class OriginalLanguage {
        En,
        Zh
    }

    data class loadJson (
        @JsonProperty("genres") var genres: List<Genre>,
        @JsonProperty("release_date") var releaseDate: String,
        @JsonProperty("title") var title: String,
        @JsonProperty("vote_average") var rating: Int,
        @JsonProperty("tagline") var plot: String,
        @JsonProperty("homepage") var homepage: String,
        @JsonProperty("poster_path") var poster_path: String,
    )

    data class loadJsonSerie (
        @JsonProperty("genres") var genres: List<Genre>,
        @JsonProperty("release_date") var releaseDate: String,
        @JsonProperty("title") var title: String,
        @JsonProperty("vote_average") var rating: Int,
        @JsonProperty("tagline") var plot: String,
        @JsonProperty("homepage") var homepage: String,
        @JsonProperty("poster_path") var poster_path: String,
        @JsonProperty("seasons") var seasons: List<Season>,
    )


    data class Season (
        @JsonProperty("genres") var genres: List<Genre>,
        @JsonProperty("seasonNumber") val seasonNumber: Int,
        @JsonProperty("episode_count") val episode_count: Int,
    )

    data class Genre (
        val name: String
    )


    fun getApiKey(): String {
        return "1dbf84763e0115344e61effd0743d694"
    }

    override suspend fun load(url: String): LoadResponse {
        val loadResponse = app.get(url).text
        val isMovie = "/movie/" in url
        val end_url = url.replace(mainUrl, "")
        println(end_url)
        val tmdbid = end_url.substring(end_url.indexOf("/") + 1, end_url.indexOf("?"))

        if (isMovie) {
            val resultsResponse: loadJson = mapper.readValue(loadResponse)
            val returnedPoster = mainUrl + resultsResponse.poster_path
            println(returnedPoster)
            val year = resultsResponse.releaseDate.substring(0, 4).toInt()
            return newMovieLoadResponse(
                resultsResponse.title,
                tmdbid,
                TvType.Movie,
                tmdbid,
            ) {  // here url = loadResponse
                this.year = year
                this.plot = resultsResponse.plot
                this.posterUrl = returnedPoster
            }
        } else {
            val resultsResponse: loadJsonSerie = mapper.readValue(loadResponse)
            val returnedPoster = mainUrl + resultsResponse.poster_path
            val seasonsList = ArrayList<Episode>()
            val year = resultsResponse.releaseDate.substring(0, 4).toInt()
            resultsResponse.seasons.map { season ->
                val seasonNumber: Int = season.seasonNumber
                if (seasonNumber != 0) {
                    repeat(season.episode_count) { episodeNumber ->
                        seasonsList.add(
                            Episode(
                                "$tmdbid-$seasonNumber-$episodeNumber", // TODO("DIRTY")
                                episodeNumber.toString(),
                                seasonNumber,
                                episodeNumber,
                            )
                        )
                    }
                }
            }
            seasonsList.add(
                Episode(
                    loadResponse,
                    "Special episodes",
                )
            )

            return newTvSeriesLoadResponse(
                resultsResponse.title,
                tmdbid,
                TvType.Movie,
                seasonsList
            ) {  // here url = loadResponse
                this.year = year
                this.plot = resultsResponse.plot
                this.posterUrl = returnedPoster
            }
        }
    }


    override suspend fun getMainPage(
        page: Int,
        categoryName: String,
        categoryData: String
    ):  HomePageResponse {
        val apiKey = getApiKey()


        // TRENDING MOVIES
        val movieCollectionResponse = app.get("$mainUrl/trending/movie/week?api_key=$apiKey").text
        val resultsResponse: mainPageJsonMovie = mapper.readValue(movieCollectionResponse)
        val items = java.util.ArrayList<HomePageList>()
        val listOfMovie = ArrayList<MovieSearchResponse>()
        for (movie in resultsResponse.results) {
            listOfMovie.add(newMovieSearchResponse(
                movie.title.toString(),
                "$mainUrl/movie/" + movie.tmdbid.toString() + "?api_key=$apiKey&language=en-US",
                TvType.Movie,
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500" + movie.posterPath.toString()
            })
        }
        items.add(HomePageList("Trending Movies", listOfMovie)) // TODO("Add string for name")

        // TRENDING SERIES
        val tvCollectionResponse = app.get("$mainUrl/trending/tv/week?api_key=$apiKey").text
        val tvResultsResponse: mainPageJsonTv = mapper.readValue(tvCollectionResponse)
        val listOfSeries = ArrayList<TvSeriesSearchResponse>()
        for (show in tvResultsResponse.results) {
            listOfSeries.add(newTvSeriesSearchResponse(
                show.original_name.toString(),
                "$mainUrl/tv/" + show.tmdbid.toString() + "?api_key=$apiKey&language=en-US",
                TvType.TvSeries,
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/original" + show.posterPath.toString()
            })
        }
        items.add(HomePageList("Trending Series", listOfSeries))

        return HomePageResponse(items)
    }
}
*/