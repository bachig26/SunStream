package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject


class RadarrProvider : MainAPI() {
    override var name = "Radarr"
    override val hasQuickSearch = false
    override val hasMainPage = false
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false

    //override val providerType = ProviderType.ArrProvider
    override val supportedTypes =
        setOf(TvType.Movie, TvType.Documentary, TvType.AnimeMovie, TvType.AnimeMovie)


    companion object {
        var overrideUrl: String? = null
        var apiKey: String? = null
        var rootFolderPath: String? = null
        var qualityProfile: String? = null
        const val ERROR_STRING = "No valid radarr account ! Please check your configuration"

        suspend fun isInCollection(tmdbId: String, myCallback: (Boolean, String, String?) -> Unit?) {
            val response = app.get("$overrideUrl/api/v3/movie/lookup?term=tmdb:$tmdbId&apikey=$apiKey").text

            val resultsResponse: List<loadCollectionInfo> = mapper.readValue(response)

            if (resultsResponse[0].hasFile && resultsResponse[0].movieFile?.path != null) {
                // invoque callback to set status
                myCallback.invoke(true, tmdbId, resultsResponse[0].movieFile?.path)
            } else {
                myCallback.invoke(false, tmdbId, null)
            }
        }

        suspend fun addToCollection(tmdbId: String, handleOutput: (Boolean) -> Unit?) {
            val storedApiKey = apiKey
            val response = app.get("$overrideUrl/api/v3/movie/lookup?term=tmdb:$tmdbId&apikey=$storedApiKey").text

            val movieObject = JSONArray(response).getJSONObject(0) // nice
            movieObject.put("qualityProfileId", qualityProfile) // todo add an interactive selector but thats --hard-- work
            // GET:  /api/v3/qualityprofile
            movieObject.put("monitored", true)
            movieObject.put("rootFolderPath", rootFolderPath)

            val body = movieObject.toString()
                .toRequestBody("application/json;charset=UTF-8".toMediaTypeOrNull())

            if (storedApiKey == null) {
                handleOutput.invoke(false)
                return
            }

            val postRequest = app.post(
                "$overrideUrl/api/v3/movie?apikey=$apiKey",
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Api-Key" to storedApiKey,
                ),
                requestBody = body
            )
            handleOutput.invoke(postRequest.isSuccessful)

        }


    }


    fun getApiKeyAndPath(): Pair<String, String> { // refresh credentials, url and rootFolderPath
        val url = RadarrProvider.overrideUrl
            ?: throw ErrorLoadingException(RadarrProvider.ERROR_STRING)
        mainUrl = url
        if (mainUrl == "NONE" || mainUrl.isBlank()) {
            throw ErrorLoadingException(RadarrProvider.ERROR_STRING)
        }

        val apiKey = RadarrProvider.apiKey
            ?: throw ErrorLoadingException(RadarrProvider.ERROR_STRING)

        val path = RadarrProvider.rootFolderPath ?: throw ErrorLoadingException(RadarrProvider.ERROR_STRING)
        return Pair(apiKey, path)
    }

    data class lookupJson(
        @JsonProperty("tmdbId") var tmdbId: String,
        @JsonProperty("title") var title: String,
        @JsonProperty("remotePoster") var posterUrl: String?,
        @JsonProperty("year") var year: Int?,
    )

    data class Images(
        @JsonProperty("coverType") var coverType: String,
        @JsonProperty("url") var url: String
    )

    data class loadJson(
        @JsonProperty("title") var title: String,
        @JsonProperty("overview") var plot: String,
        @JsonProperty("year") var year: Int?,
        //@JsonProperty("hasFile") var hasFile: Boolean?,
        @JsonProperty("images") var posterUrl: List<Images?>?,
        //@JsonProperty("genres") var genres: List<String?>?,
    )

    data class MovieFile (
        @JsonProperty("path") var path: String?,
    )

    data class loadCollectionInfo(
        @JsonProperty("hasFile") var hasFile: Boolean,
        //@JsonProperty("path") var path: String?,
        @JsonProperty("movieFile") var movieFile: MovieFile?,
    )

    override suspend fun load(url: String): LoadResponse {
        val (apiKey, path) = getApiKeyAndPath()
        val tmdbId = url.replace(mainUrl, "").replace("/", "")
        val loadResponse =
            app.get("$mainUrl/api/v3/movie/lookup/tmdb?tmdbId=$tmdbId&apikey=$apiKey").text
        //println(loadResponse)
        val resultsResponse: loadJson = mapper.readValue(loadResponse)
        val returnedPoster = resultsResponse.posterUrl?.first { it?.coverType == "poster" }?.url
        return newMovieLoadResponse(
            resultsResponse.title,
            tmdbId,
            TvType.Movie,
            loadResponse
        ) {  // here url = loadResponse
            this.year = resultsResponse.year
            this.plot = resultsResponse.plot
            this.posterUrl = returnedPoster
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val (apiKey, path) = getApiKeyAndPath()
        val searchResponse =
            app.get("$mainUrl/api/v3/movie/lookup?term=$query&apikey=$apiKey").text
        val results: List<lookupJson> = mapper.readValue(searchResponse)

        return results.map {
            newMovieSearchResponse(
                it.title,
                it.tmdbId,
                TvType.Movie,
                fix = false
            ) {  // here url = tmdbId
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