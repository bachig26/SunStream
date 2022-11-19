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
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.lagradost.cloudstream3.utils.*

import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session

import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import org.schabi.newpipe.extractor.utils.Utils.getBaseUrl


// Credit: hexated https://github.com/hexated/cloudstream-extensions-hexated/blob/master/SoraStream/src/main/kotlin/com/hexated/SoraExtractor.kt
class HDMovieBox : TmdbProvider() {
    override val apiName = "hdmoviebox"
    override var name = "HDMovieBox"
    override var mainUrl = "https://hdmoviebox.net"
    override val useMetaLoadResponse = true
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    data class HdMovieBoxSource(
        @JsonProperty("videoUrl") val videoUrl: String? = null,
        @JsonProperty("videoServer") val videoServer: String? = null,
        @JsonProperty("videoDisk") val videoDisk: Any? = null,
    )

    data class HdMovieBoxIframe(
        @JsonProperty("api_iframe") val apiIframe: String? = null,
    )

    private fun String?.fixTitle(): String? {
        return this?.replace(Regex("[!%:]|( &)"), "")?.replace(" ", "-")?.lowercase()
            ?.replace("-–-", "-")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mappedData = tryParseJson<TmdbLink>(data)
        val season = mappedData?.season
        val episode = mappedData?.episode
        val title = mappedData?.movieName
        val fixTitle = title.fixTitle()
        val url = "$mainUrl/watch/$fixTitle"
        val ref = if (season == null) {
            "$mainUrl/watch/$fixTitle"
        } else {
            "$mainUrl/watch/$fixTitle/season-$season/episode-$episode"
        }

        val doc = app.get(url).document
        val id = if (season == null) {
            doc.selectFirst("div.player div#not-loaded")?.attr("data-whatwehave")
        } else { // ERROR here
            doc.select("div.season-list-column div[data-season=$season] div.list div.item")[episode?.minus(
                1
            ) ?: 0].selectFirst("div.ui.checkbox")?.attr("data-episode")
        }

        val iframeUrl = app.post(
            "$mainUrl/ajax/service", data = mapOf(
                "e_id" to "$id",
                "v_lang" to "en",
                "type" to "get_whatwehave",
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<HdMovieBoxIframe>()?.apiIframe ?: return false

        delay(1000) // MAYBE REMOVE ?
        val iframe = app.get(iframeUrl, referer = "$mainUrl/").document.selectFirst("iframe")
            ?.attr("src")

        val script = app.get(
            iframe ?: return false,
            referer = "$mainUrl/"
        ).document.selectFirst("script:containsData(var vhash =)")?.data()
            ?.substringAfter("vhash, {")?.substringBefore("}, false")

        tryParseJson<HdMovieBoxSource>("{$script}").let { source ->
            val disk = if (source?.videoDisk == null) {
                ""
            } else {
                base64Encode(source.videoDisk.toString().toByteArray())
            }
            val link = getBaseUrl(iframe) + source?.videoUrl?.replace(
                "\\",
                ""
            ) + "?s=${source?.videoServer}&d=$disk"
            callback.invoke(
                ExtractorLink(
                    this@HDMovieBox.name,
                    this@HDMovieBox.name,
                    link,
                    iframe,
                    Qualities.P1080.value,
                    isM3u8 = true,
                )
            )
            return true
        }
    }
}