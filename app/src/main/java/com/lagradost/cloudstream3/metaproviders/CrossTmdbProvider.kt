package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.allEnabledMetaProviders
import com.lagradost.cloudstream3.APIHolder.allEnabledDirectProviders
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.movieproviders.SuperStream
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.uwetrottmann.tmdb2.entities.Movie

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
        return allEnabledMetaProviders.filter { it::class.java != this::class.java && it.providerType == ProviderType.MetaProvider }
        // it.lang == this.lang &&
    }

    private fun getAllEnabledDirectProviders(): List<MainAPI> {
        return allEnabledDirectProviders
    }

    data class CrossMetaData(
        @JsonProperty("isSuccess") val isSuccess: Boolean,
        @JsonProperty("directApis") val directApis: List<Pair<String?, String?>?>? = null, // list<Pair<apiName, dataUrl>>
        @JsonProperty("metaUrl") val metaUrl: String?,
        @JsonProperty("enabledMetaProviders") val enabledMetaProviderNames: List<String>?,
    )

    data class TmdbProviderSearchFilter(
        // to find a movie with specific elements
        @JsonProperty("title") val title: String,
        @JsonProperty("tmdbYear") val tmdbYear: Int?,
        @JsonProperty("tmdbPlot") val tmdbPlot: String?,
        @JsonProperty("duration") val duration: Int?,
        @JsonProperty("type") val type: TvType?,
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
            val directApis = metaData.directApis
            directApis?.apmap { content -> // content: <Pair<apiName?, dataUrl?>>
                val directLink = content?.second ?: return@apmap false
                getApiFromNameNull(content.first)?.let {
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


    private fun loadMainApiProvidersTvSerie(response: LoadResponse?): List<TvSeriesLoadResponse?>? {

        val filter = TmdbProviderSearchFilter(
            response?.name ?: return null,
            response.year,
            response.plot,
            response.duration,
            response.type,
        )

        val additionalProvider = getAllEnabledDirectProviders()
        val matchName = filterName(filter.title)

        return additionalProvider.apmap { api ->
            try {
                if (api.supportedTypes.contains(response.type)) {
                    return@apmap api.search(filter.toJson())?.first {
                        if (filterName(it.name).equals(
                                matchName,
                                ignoreCase = true
                            )
                        ) {
                            return@first true
                        }
                        false
                    }?.let { search ->
                        val loadedResult = api.load(search.url)
                        if (loadedResult is TvSeriesLoadResponse) {
                            loadedResult
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
    }

    private fun loadMainApiProvidersMovie(response: LoadResponse?): List<MovieLoadResponse?>? {

        val filter = TmdbProviderSearchFilter(
            response?.name ?: return null,
            response.year,
            response.plot,
            response.duration,
            response.type,
        )

        val additionalProvider = getAllEnabledDirectProviders()
        val matchName = filterName(filter.title)

        return additionalProvider.apmap { api ->
            try {
                if (api.supportedTypes.contains(response.type)) {
                    return@apmap api.search(filter.toJson())?.first {
                        if (filterName(it.name).equals(
                                matchName,
                                ignoreCase = true
                            )
                        ) {
                            return@first true
                        }
                        false
                    }?.let { search ->
                        val loadedResult = api.load(search.url)
                        if (loadedResult is MovieLoadResponse) {
                            loadedResult
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
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = super.load(url)?.apply {
            this.recommendations = this.recommendations
            //val matchName = filterName(this.name)
            val validApisName = getAllEnabledMetaProviders().apmap { it.name }
            when(this) {
                is MovieLoadResponse -> {

                    val directProvidersContent = loadMainApiProvidersMovie(this)?.apmap {
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
                    val providerLoadResponses = loadMainApiProvidersTvSerie(this)
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