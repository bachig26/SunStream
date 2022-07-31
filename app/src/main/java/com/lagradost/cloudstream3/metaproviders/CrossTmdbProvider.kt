package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.allEnabledProviders
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

class CrossTmdbProvider : TmdbProvider() {
    override var name = "MultiMedia"
    override val apiName = "MultiMedia"
    override var lang = "en"
    override val useMetaLoadResponse = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasSearch = true

    private fun filterName(name: String): String {
        return Regex("""[^a-zA-Z0-9-]""").replace(name, "")
    }


    private fun getAllEnabledProviders(): List<MainAPI> {
        return allEnabledProviders.filter { it.lang == this.lang && it::class.java != this::class.java && this.providerType == ProviderType.MetaProvider }
    }

    data class CrossMetaData(  // movies and series
        @JsonProperty("isSuccess") val isSuccess: Boolean,
        @JsonProperty("dataUrl") val dataUrl: String,
        @JsonProperty("availableApis") val availableApis: List<String>? = null, // list<apiName, dataUrl>
    )

    override suspend fun loadLinks(
        data: String, // CrossMetaData
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        tryParseJson<CrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            val dataUrl = metaData.dataUrl
            metaData.availableApis?.map { apiName ->
                getApiFromNameNull(apiName)?.let {
                    try {
                        it.loadLinks(dataUrl, isCasting, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            return true
        }
        return false
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return super.search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = super.load(url)?.apply {
            this.recommendations = this.recommendations
            //val matchName = filterName(this.name)
            val validApisName = getAllEnabledProviders().map {it.name}
            when (this) {
                is MovieLoadResponse -> {

                    /*
                    val data = validApis.mapNotNull { api -> // TODO FIX

                    try {


                    // usefull when searching movie on other (non tmdb) providers
                        if (api.supportedTypes.contains(TvType.Movie)) { //|| api.supportedTypes.contains(TvType.AnimeMovie)
                            return@apmap api.search(this.name)?.first { movieSearchResponse -> // first result
                                if (filterName(movieSearchResponse.name).equals(
                                        matchName,
                                        ignoreCase = true
                                    )
                                ) {
                                    if (movieSearchResponse is MovieSearchResponse)
                                        if (movieSearchResponse.year != null && this.year != null && movieSearchResponse.year != this.year) // if year exist then do a check
                                            return@first false // could be false negative due to different release year according to resion and prerelease

                                    return@first true // same movie: Success
                                }
                                false // nope
                            }?.let { search ->
                                val response = api.load(search.url)
                                if (response is MovieLoadResponse) {
                                    response
                                } else {
                                    null
                                }
                            }
                        }
                        null

                    } catch (e: Exception) {
                        logError(e)
                        null
                    }
                    }
                    println(data)
                    this.dataUrl =
                    CrossMetaData(true, data.map { it.apiName to it.dataUrl }).toJson() // sent to loadLinks
                    // (old content of data before update): {"isSuccess":true,"movies":[{"first":"VidSrc","second":"{\"imdbID\":\"tt9419884\",\"tmdbID\":453395,\"episode\":null,\"season\":null,\"movieName\":\"Doctor Strange in the Multiverse of Madness\"}"},{"first":"OpenVids","second":"{\"imdbID\":\"tt9419884\",\"tmdbID\":453395,\"episode\":null,\"season\":null,\"movieName\":\"Doctor Strange in the Multiverse of Madness\"}"}]}
                                    }
*/



                    this.dataUrl = CrossMetaData(true, this.dataUrl, validApisName).toJson() // sent to loadLinks
                    //this.backdropUrl =

                }

                is TvSeriesLoadResponse -> {
                    /*
                    val data = validApis.apmap { api -> // all tvseries load response apis without a null response
                        try {
                            if (api.supportedTypes.contains(TvType.TvSeries) || api.supportedTypes.contains(
                                    TvType.Anime
                                )
                            ) {
                                return@apmap api.search(this.name)?.first {
                                    if (filterName(it.name).equals(
                                            matchName,
                                            ignoreCase = true
                                        )
                                    ) {
                                        if (it is TvSeriesSearchResponse)
                                            if (it.year != null && this.year != null && it.year != this.year) // if year exist then do a check
                                                return@first false

                                        return@first true // same tv show
                                    }
                                    false
                                }?.let { search ->
                                    val response = api.load(search.url)  // query provider with url
                                    if (response is TvSeriesLoadResponse) {
                                        response
                                    } else {
                                        null
                                    }
                                }
                            }
                            null
                        } catch (e: Exception) {
                            logError(e)
                            null
                        }
                    }.filterNotNull()
                    this.episodes.forEach() { Episode -> //MAY BE SLOW !
                        Episode.data = CrossMetaData(true, data.map { it.apiName to Episode.data }).toJson() // sent to loadLinks
                    }

                     */

                    this.episodes.forEach() { Episode -> //MIGHT BE SLOW !
                        Episode.data = CrossMetaData(true, Episode.data, validApisName).toJson() // sent to loadLinks
                    }
                }
            }
        }

        return base
    }
}