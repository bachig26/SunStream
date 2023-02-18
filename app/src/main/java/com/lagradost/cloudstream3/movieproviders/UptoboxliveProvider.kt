/*
package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.lang.invoke.MethodHandles.Lookup
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor


class UptoboxliveProvider : MainAPI() {
    override var name = "UpToBoxLive"
    override val hasQuickSearch = false
    override val hasMainPage = false
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false
    override var mainUrl = "https://uptobox.live/"

    //override val providerType = ProviderType.ArrProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)


    data class mediaItem(
        @JsonProperty("id") var id: Int? = null,
        @JsonProperty("like_count") var likeCount : Int? = null,
        @JsonProperty("link") var link: String? = null,
        // @JsonProperty("size") var size: Double? = null,
        @JsonProperty("title") var title: String? = null
    )


    data class items(
        @JsonProperty("items") var listMediaItem: ArrayList<mediaItem> = arrayListOf()
    )

    private suspend fun findWorkingEmbeds(itemList: List<mediaItem>): List<String> {
        val toReturnList =  itemList.take(10).apmap{
            val link = it.link
            if (link != null && app.post(link).code == 200) {
                it.link
            } else {
                null
            }
        }.filterNotNull()
        return toReturnList
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("RECEIVED LOADLINKS !!!!!!!")
        val mappedData = tryParseJson<TmdbLink>(data)
        val re = Regex("[^A-Za-z0-9 ]")
        val mediaTitle = re.replace(mappedData?.movieName.toString(), "") // works

        val query = if (mappedData?.episode != null && mappedData?.season != null) { // tv show
            val episode = if (mappedData?.episode.toString().length == 1 ){// episode 1 becomes e01
                "e0" + mappedData?.episode.toString()
            } else {
                "e" + mappedData?.episode.toString()
            }

            val season = if (mappedData?.season.toString().length == 1 ){ // season 1 becomes S01
                "S0" + mappedData?.season.toString()
            } else {
                "S" + mappedData?.season.toString()
            }

            "$mediaTitle $season$episode"
        } else { // just a movie
            mediaTitle
        }


        val homepage = app.get(mainUrl).text
        val AuthBearer = Regex("'Authorization': 'Bearer (.*)',").find(homepage)?.groupValues?.get(1)
        println("using auth: $AuthBearer")
        val searchResponse =
            app.get("$mainUrl/search?start-with=&q=$query&sort=id&order=desc", referer = mainUrl, headers = mapOf("Authorization" to ("Bearer $AuthBearer"))).text
        val results: items = mapper.readValue<items>(searchResponse) // .listMediaItem.sortedBy { it.listMediaItem[0].size }
        val validEmbeds = mutableListOf<String>()
        validEmbeds.addAll(findWorkingEmbeds(results.listMediaItem.take(10))) // take the first 20
        if (validEmbeds.size < 3) {
            validEmbeds.addAll(findWorkingEmbeds(results.listMediaItem.takeLast(90).take(20))) // take the next 20
        }

        // TODO like the working embeds on the website

        validEmbeds.forEach {
            loadExtractor(it, "https://uptostream.com/", subtitleCallback, callback)
        }
//        extractor.getSafeUrl(embedUrl, null, subtitleCallback, callback)

        return true
    }

}
*/