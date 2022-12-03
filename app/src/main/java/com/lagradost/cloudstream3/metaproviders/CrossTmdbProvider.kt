package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.movieproviders.FilmxProvider
import com.lagradost.cloudstream3.movieproviders.HDMovieBox
import com.lagradost.cloudstream3.movieproviders.UptoboxliveProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.uwetrottmann.tmdb2.entities.AlternativeTitle
import com.uwetrottmann.tmdb2.entities.Translations

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


    private fun getAllEnabledMetaProviders(): List<MainAPI?> {
            return apis.filter { it::class.java != this::class.java && it.providerType == ProviderType.MetaProvider }
        //return listOf(HDMovieBox(),FilmxProvider(),)
    // it.lang == this.lang &&
    }

    private fun getAllEnabledDirectProviders(): List<MainAPI?> {
        return apis.filter { it::class.java != this::class.java && it.providerType == ProviderType.DirectProvider}
    }

    data class CrossMetaData(
        @JsonProperty("isSuccess") val isSuccess: Boolean,
        @JsonProperty("directApis") val directApis: List<Pair<String?, String?>?>? = null, // list<Pair<apiName, dataUrl>>
        @JsonProperty("metaUrl") val metaUrl: String?,
        @JsonProperty("enabledMetaProviders") val enabledMetaProviderNames: List<String>?,
    )

    data class TmdbLinkLocal(
        @JsonProperty("alternativeTitles") val alternativeTitles: List<Translations.Translation>?, // TODO ADD NULL
    )

    override suspend fun loadLinks(
        data: String, // CrossMetaData
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataTmdbLink = tryParseJson<TmdbLink>(data)
        getAllEnabledMetaProviders().amap {api ->
            try {
                api?.loadLinks(data, isCasting, subtitleCallback, callback) // load all meta providers
            } catch (e: Exception) {
                logError(e)
            }
        }

        getAllEnabledDirectProviders().amap { api ->
            if (api != null && dataTmdbLink != null) {
                searchForMediaDirectProvider(api, dataTmdbLink)?.let { searchResponse ->
                    api.load(searchResponse.url)
                }.let { loadContentUrl ->
                    suspendSafeApiCall {
                        loadContentUrl?.url?.let {
                            api.loadLinks(it, isCasting, subtitleCallback,)
                            {
                                callback.invoke(
                                    ExtractorLink(
                                        api.name + it.source.replace(api.name, ""),
                                        api.name + it.name.replace(api.name, ""),
                                        it.url,
                                        it.referer,
                                        it.quality
                                    )
                                )
                            }
                        }
                    }
                }
            }

        }

        return true
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (!query.startsWith("{") && !query.endsWith("}")) { // if it is not json (direct query by the user)
            return super.search(CrossSearch(
                query
            ).toJson())
        }
        return super.search(query)
    }


    /**
     * Will return the media if the name matches (main api)
     */
    private suspend fun searchForMediaDirectProvider(api: MainAPI, filter: TmdbLink): SearchResponse? {
        val foundTranslation = filter.alternativeTitles?.firstOrNull{ translation ->// idk if its working
            translation.iso_639_1?.equals(api.lang) == true
        }
        val title = foundTranslation?.data?.name ?: foundTranslation?.data?.title ?: filter.movieName ?: return null
        return suspendSafeApiCall {
            api.search(title)?.firstOrNull{ element -> // search in any language ?
                    filterName(element.name).equals(
                        filterName(title), // search the provider language's title
                        ignoreCase = true
                    ) || filterName(element.name).equals(
                        filterName(filter.movieName ?: title), // search in vo (official title in tmdb)
                        ignoreCase = true)
                }
        }
    }

    private fun loadMainApiProvidersTvSerie(response: LoadResponse?, filter: TmdbLink): List<TvSeriesLoadResponse?>? {// TODO merge those two
        val additionalProvider = getAllEnabledDirectProviders()
        if (additionalProvider.isEmpty()) return null
        return additionalProvider.apmap { api ->
            if (api == null) return@apmap null
            try {
                if (api.supportedTypes.contains(response?.type) && api.name != "") {
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
        }.filterNotNull()
    }

    private fun loadMainApiProvidersMovie(response: LoadResponse?, filter: TmdbLink): List<MovieLoadResponse?>? { // TODO merge those two
            val additionalProvider = getAllEnabledDirectProviders()
            if (additionalProvider.isEmpty()) return null
            return additionalProvider.apmap { api ->
                if (api == null) return@apmap null
                try {
                    if (api.supportedTypes.contains(response?.type)) {
                        val foundMovie = searchForMediaDirectProvider(api, filter)// raise not implemented error if api doesn't have search
                        foundMovie?.let { search ->
                            val loadedResult = api.load(search.url)
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
            }.filterNotNull()
        }
    /*
    override suspend fun load(url: String): LoadResponse? { // TODO MOVE DIRECT PROVIDER FINDING TO
        val base = super.load(url)?.apply {
            this.recommendations = this.recommendations
            //val matchName = filterName(this.name)
            val validApisName = getAllEnabledMetaProviders().map { api ->
                api.name
            }
            val filter = TmdbProviderSearchFilter(
                this.name,
                this.year,
                this.plot,
                this.duration,
                this.type,
            )
            when(this) {
                is MovieLoadResponse -> {
                    filter.alternativeTitles = tryParseJson<TmdbLinkLocal>(this.dataUrl)?.alternativeTitles
                    this.dataUrl = {"filter" to filter.toJson()}.toJson() // sent to loadLinks
                    // (old content of data before update): {"isSuccess":true,"movies":[{"first":"VidSrc","second":"{\"imdbID\":\"tt9419884\",\"tmdbID\":453395,\"episode\":null,\"season\":null,\"movieName\":\"Doctor Strange in the Multiverse of Madness\"}"},{"first":"OpenVids","second":"{\"imdbID\":\"tt9419884\",\"tmdbID\":453395,\"episode\":null,\"season\":null,\"movieName\":\"Doctor Strange in the Multiverse of Madness\"}"}]}
                }
                is TvSeriesLoadResponse -> {
                    filter.alternativeTitles = tryParseJson<TmdbLinkLocal>(this.episodes.first().data)?.alternativeTitles // alternative titles of the tv show itself
                    //TODO FIX NULL
                    //val providerLoadResponses = loadMainApiProvidersTvSerie(this, filter)
                    this.episodes.forEachIndexed { Index, Episode ->
                        Episode.data = {"filter" to filter.toJson(); "episode" to Episode.episode; "season" to Episode.season}.toJson()
                    }
                }
            }
        }

        return base
    }*/
}
