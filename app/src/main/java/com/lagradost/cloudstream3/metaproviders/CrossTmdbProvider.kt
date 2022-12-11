package com.lagradost.cloudstream3.metaproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

class CrossTmdbProvider : TmdbProvider() {
    override var name = "TheMovieDatabase"
    override val apiName = "TheMovieDatabase"
    override var lang = "universal"
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

    private suspend fun getMovieLoadContentUrl(api: MainAPI, url: String): String? {
        val foundMovie = api.load(url)
        if(foundMovie is MovieLoadResponse) return foundMovie.dataUrl
        return null
    }

    private suspend fun getAnimeEpisodeUrl(api: MainAPI, url: String, wantedEpisode: Int?): String? {
        wantedEpisode ?: return null
        val foundAnime = api.load(url)
        if(foundAnime is AnimeLoadResponse) {
            val listOfEpisodeWithDubStatus = foundAnime.episodes
            listOfEpisodeWithDubStatus.toList().forEach { element ->
                val listOfEpisode: List<Episode> = element.second
                val test = listOfEpisode[wantedEpisode-1].data
                return listOfEpisode[wantedEpisode-1].data // first episode is number 0; episode 2 is number 1 ...
            }
        }
        return null
    }

    private suspend fun getTvShowEpisodeUrl(api: MainAPI, url: String, wantedEpisode: Int?, wantedSeason: Int?): String? {
        val foundTvShow = api.load(url)
        if(foundTvShow is AnimeLoadResponse) {
            val listOfEpisodeWithDubStatus = foundTvShow.episodes
            listOfEpisodeWithDubStatus.toList().forEach { element ->
                val listOfEpisode: List<Episode> = element.second
                return listOfEpisode.firstOrNull {
                    it.episode == wantedEpisode
                }?.data
            }
        }
        if(foundTvShow is TvSeriesLoadResponse) return foundTvShow.episodes.firstOrNull {
            it.episode == wantedEpisode && it.season == wantedSeason
        }?.data

        return null
    }

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
        val isTvShow = (dataTmdbLink?.season != null && dataTmdbLink.episode != null)
        val isAnime = (dataTmdbLink?.season == null && dataTmdbLink?.episode != null)
        getAllEnabledDirectProviders().amap { api ->
            if (api != null && dataTmdbLink != null) {
                searchForMediaDirectProvider(api, dataTmdbLink)?.let { searchResponse ->
                    suspendSafeApiCall {
                        if (isTvShow) {
                            getTvShowEpisodeUrl(
                                api, searchResponse.url,
                                dataTmdbLink.episode, dataTmdbLink.season
                            )
                        } else {
                            if (isAnime) {
                                getAnimeEpisodeUrl(
                                    api, searchResponse.url,
                                    dataTmdbLink.episode
                                )
                            } else {
                                getMovieLoadContentUrl(api, searchResponse.url)
                            }
                        }
                    }
                }.let { loadContentUrl ->
                    suspendSafeApiCall {
                        if (loadContentUrl != null) {
                            api.loadLinks(loadContentUrl, isCasting, subtitleCallback, callback)
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
            translation.iso_639_1?.equals(api.lang, ignoreCase = true) == true
        }
        val title =
            if(!foundTranslation?.data?.name.isNullOrEmpty()) {
                foundTranslation?.data?.name
            } else
            {
                if (!foundTranslation?.data?.title.isNullOrEmpty()){
                    foundTranslation?.data?.title
                } else {
                    filter.movieName
                }
            }
        title ?: return null
        return suspendSafeApiCall {
            api.search(title)?.firstOrNull{ element -> // search in any language ?
                filterName(title).contains(
                    filterName(element.name), // search the provider language's title
                        ignoreCase = true
                    )
            }
        }
    }

}
