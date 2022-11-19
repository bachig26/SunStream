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
class IdlixProvider : TmdbProvider() {
    override val apiName = "idlix"
    override var name = "Idlix"
    override var mainUrl = "https://88.210.3.94"
    override val useMetaLoadResponse = true
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String?,
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
        val year = mappedData?.year
        val fixTitle = title.fixTitle()
        val url = if (season == null) {
            "$mainUrl/movie/$fixTitle-$year"
        } else {
            "$mainUrl/episode/$fixTitle-season-$season-episode-$episode"
        }

        val res = app.get(url)
        if (!res.isSuccessful) return false
        val document = res.document
        val id = document.select("meta#dooplay-ajax-counter").attr("data-postid")
        val type = if (url.contains("/movie/")) "movie" else "tv"

        document.select("ul#playeroptionsul > li").map {
            it.attr("data-nume")
        }.apmap { nume ->
            val source = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to id,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = url
            ).parsed<ResponseHash>().embed_url

            if (!source.contains("youtube")) {
                loadExtractor(source, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true

    }
}