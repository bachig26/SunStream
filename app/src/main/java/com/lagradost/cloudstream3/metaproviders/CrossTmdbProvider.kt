package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

class CrossTmdbProvider : TmdbProvider() {
    override var name = "TheMovieDatabase"
    override val apiName = "TheMovieDatabase"
    override var lang = "en"
    override val useMetaLoadResponse = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasSearch = true

    private fun filterName(name: String): String {
        return Regex("""[^a-zA-Z0-9-]""").replace(name, "")
    }


    private fun getAllEnabledMetaProviders(): List<MainAPI> {
        return apis.filter { it::class.java != this::class.java && it.providerType == ProviderType.MetaProvider }
        // it.lang == this.lang &&
    }

    private fun getAllEnabledDirectProviders(): List<MainAPI>? {
        return apis.filter { it::class.java != this::class.java && it.providerType == ProviderType.DirectProvider}
    }

    data class CrossMetaData(
        @JsonProperty("isSuccess") val isSuccess: Boolean,
        @JsonProperty("directApis") val directApis: List<Pair<String?, String?>?>? = null, // list<Pair<apiName, dataUrl>>
        @JsonProperty("metaUrl") val metaUrl: String?,
        @JsonProperty("enabledMetaProviders") val enabledMetaProviderNames: List<String>?,
    )

    override suspend fun loadLinks(
        data: String, // CrossMetaData
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        tryParseJson<CrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            val dataUrl = metaData.metaUrl ?: return false
            metaData.enabledMetaProviderNames?.apmap { apiName ->
                getApiFromNameNull(apiName)?.let {
                    try {
                        it.loadLinks(dataUrl, isCasting, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            metaData.directApis?.apmap { content -> // content: <Pair<apiName?, dataUrl?>>
                val directLink = content?.second ?: return@apmap false
                getApiFromNameNull(content?.first)?.let {
                    try {
                        it.loadLinks(directLink, isCasting, subtitleCallback, callback)
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


    /**
     * Will return the media if the name matches
     */
    private suspend fun searchForMediaDirectProvider(api: MainAPI, filter: TmdbProviderSearchFilter): SearchResponse? {
        val searchResponseOutput = suspendSafeApiCall {
            api.search(filter.toJson())?.first()
        }
        return if (searchResponseOutput?.name?.let {
                filterName(it).equals(
                    filterName(filter.title),
                    ignoreCase = true) } == true
        ) {
            searchResponseOutput // found
        } else {
            null
        }
    }

    private fun loadMainApiProvidersTvSerie(response: LoadResponse?, filter: TmdbProviderSearchFilter): List<TvSeriesLoadResponse?>? {// TODO merge those two
        val additionalProvider = getAllEnabledDirectProviders()
        return additionalProvider?.apmap { api ->
            try {
                if (api.supportedTypes.contains(response?.type)) {
                    val foundTvShow = searchForMediaDirectProvider(api, filter)
                    foundTvShow?.let { search ->
                        val loadedResult = api.load(search.url)
                        if (loadedResult is TvSeriesLoadResponse) {
                            return@apmap loadedResult
                        } else {
                            return@apmap null
                        }
                    }
                }
                null
            } catch (e: Exception) {
                logError(e)
                null
            }
        }?.filterNotNull()
    }

    private fun loadMainApiProvidersMovie(response: LoadResponse?, filter: TmdbProviderSearchFilter): List<MovieLoadResponse?>? { // TODO merge those two
            val additionalProvider = getAllEnabledDirectProviders()
            return additionalProvider?.apmap { api ->
                try {
                    if (api.supportedTypes.contains(response?.type)) {
                        val foundMovie = searchForMediaDirectProvider(api, filter)
                        //println("foundMovie")
                        //println(foundMovie)
                        foundMovie?.let { search ->
                            //println("loading search now")
                            val loadedResult = api.load(search.url)
                            //println(loadedResult)
                            if (loadedResult is MovieLoadResponse) {
                                return@apmap loadedResult
                            } else {
                                return@apmap null
                            }
                        }
                    }
                    null
                } catch (e: Exception) {
                    logError(e)

                    null
                }
            }?.filterNotNull()
        }

    override suspend fun load(url: String): LoadResponse? {
        val base = super.load(url)?.apply {
            this.recommendations = this.recommendations
            //val matchName = filterName(this.name)
            val validApisName = getAllEnabledMetaProviders().apmap { it.name }
            val filter = TmdbProviderSearchFilter(
                this.name ?: return null,
                this.year,
                this.plot,
                this.duration,
                this.type,
            )
            when(this) {

                is MovieLoadResponse -> {
                    val directProvidersContent = loadMainApiProvidersMovie(this, filter)?.apmap {
                        it?.apiName to it?.dataUrl
                    }
                    this.dataUrl = CrossMetaData(
                        true,
                        directProvidersContent,
                        this.dataUrl,
                        validApisName
                    ).toJson() // sent to loadLinks
                    // (old content of data before update): {"isSuccess":true,"movies":[{"first":"VidSrc","second":"{\"imdbID\":\"tt9419884\",\"tmdbID\":453395,\"episode\":null,\"season\":null,\"movieName\":\"Doctor Strange in the Multiverse of Madness\"}"},{"first":"OpenVids","second":"{\"imdbID\":\"tt9419884\",\"tmdbID\":453395,\"episode\":null,\"season\":null,\"movieName\":\"Doctor Strange in the Multiverse of Madness\"}"}]}
                }
                is TvSeriesLoadResponse -> {
                    val providerLoadResponses = loadMainApiProvidersTvSerie(this, filter)
                    this.episodes.forEachIndexed { Index, Episode ->
                        val episodeData: List<Pair<String, String>>? = providerLoadResponses?.mapNotNull { providerResponse ->
                            val apiName = providerResponse?.apiName ?: return@mapNotNull null
                            Pair(apiName, providerResponse.episodes[Index].data)
                        }
                        try {
                            Episode.data = CrossMetaData(
                                true,
                                episodeData, // element must have the same number of episodes // TODO FIX
                                Episode.data,
                                validApisName
                            ).toJson() // sent to loadLinks
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }


            }
        }
        return base
    }
}