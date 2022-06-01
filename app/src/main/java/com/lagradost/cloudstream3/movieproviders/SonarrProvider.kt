package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.net.MediaType
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject


class SonarrProvider : MainAPI() {
    override var name = "Sonarr"
    override val hasQuickSearch = false
    override val hasMainPage = false
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false
    //override val providerType = ProviderType.ArrProvider
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon)


    data class lookupJson (
        @JsonProperty("tmdbId") var tmdbId:String,
        @JsonProperty("title") var title: String,
        @JsonProperty("remotePoster") var posterUrl: String?,
        @JsonProperty("year") var year: Int?,
    )

    data class Images (
        @JsonProperty("coverType") var coverType : String,
        @JsonProperty("url") var url : String
    )

    data class loadJson (
        @JsonProperty("title") var title: String,
        @JsonProperty("overview") var plot: String,
        @JsonProperty("year") var year: Int?,
        //@JsonProperty("hasFile") var hasFile: Boolean?,
        @JsonProperty("images") var posterUrl: List<Images?>?,
        //@JsonProperty("genres") var genres: List<String?>?,
    )

    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url.replace(mainUrl, "").replace("/", "")
        val loadResponse = app.get("$mainUrl/api/v3/movie/lookup/tmdb?tmdbId=$tmdbId&apikey=$storedCredentials").text
        val resultsResponse: loadJson = mapper.readValue(loadResponse)
        val returnedPoster = resultsResponse.posterUrl?.first { it?.coverType == "poster" }?.url
        return newMovieLoadResponse(resultsResponse.title, tmdbId, TvType.Movie, loadResponse) {  // here url = loadResponse
            this.year = resultsResponse.year
            this.plot = resultsResponse.plot
            this.posterUrl = returnedPoster
        }
    }




    // Searching returns a SearchResponse, which can be one of the following: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    // Each of the classes requires some different data, but always has some critical things like name, poster and url.
    override suspend fun search(query: String): List<SearchResponse> {

        if (storedCredentials == null || mainUrl == "NONE") {
            throw ErrorLoadingException("No radarr url specified in the settings: Radarr Settigns > Radarr server url, try again in a few seconds")
        }


        // Simply looking at devtools network is enough to spot a request like:
        // https://vidembed.cc/search.html?keyword=neverland where neverland is the query, can be written as below.
        val searchResponse = app.get("$mainUrl/api/v3/movie/lookup?term=$query&apikey=$storedCredentials").text
        val results: List<lookupJson> = mapper.readValue(searchResponse)

        return results.map {
            newMovieSearchResponse(it.title, it.tmdbId, TvType.Movie, fix=false) {  // here url = tmdbId
                // this.year = it.year
                this.posterUrl = it.posterUrl
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        // These callbacks are functions you should call when you get a link to a subtitle file or media file.
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val movieObject = JSONObject(data)
        movieObject.put("qualityProfileId", "1")
        movieObject.put("monitored", true)
        movieObject.put("rootFolderPath", rootFolderPath)
        val body = movieObject.toString().toRequestBody("application/json;charset=UTF-8".toMediaTypeOrNull())


        val postRequest = app.post(
            "$mainUrl/api/v3/movie?apikey=$storedCredentials",
            headers= mapOf(
                "Accept" to "application/json",
                "X-Api-Key" to storedCredentials.toString(),
            ),
            requestBody=body)
        /*
        if (postRequest.isSuccessful) {
            println("working well")
        } else {
            println("ERROR HERE:")
            println(postRequest.document)
            println(postRequest.okhttpResponse)
            println(postRequest.body)
            println(postRequest.headers)
            println(postRequest.code)
            println(postRequest.isSuccessful)
        }
         */
        return false
    }


}