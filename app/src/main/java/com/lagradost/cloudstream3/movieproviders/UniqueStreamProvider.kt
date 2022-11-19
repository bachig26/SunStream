package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.*

private fun base64DecodeAPI(api: String): String {
    return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
}

// Credit: hexated https://github.com/hexated/cloudstream-extensions-hexated/blob/master/SoraStream/src/main/kotlin/com/hexated/SoraExtractor.kt
class UniqueStreamProvider : TmdbProvider() {
    override val apiName = "uniquestream"
    override var name = "UniqueStream"
    override var mainUrl = "https://uniquestream.net"
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

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    data class VideoData(
        @JsonProperty("mediaUrl") val mediaUrl: String? = null,
    )

    data class Video(
        @JsonProperty("data") val data: VideoData? = null,
    )

    data class SubtitlingList(
        @JsonProperty("languageAbbr") val languageAbbr: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("subtitlingUrl") val subtitlingUrl: String? = null,
    )

    data class DefinitionList(
        @JsonProperty("code") val code: String? = null,
        @JsonProperty("description") val description: String? = null,
    )

    data class EpisodeVo(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("seriesNo") val seriesNo: Int? = null,
        @JsonProperty("definitionList") val definitionList: ArrayList<DefinitionList>? = arrayListOf(),
        @JsonProperty("subtitlingList") val subtitlingList: ArrayList<SubtitlingList>? = arrayListOf(),
    )

    data class MediaDetail(
        @JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
    )

    data class Load(
        @JsonProperty("data") val data: MediaDetail? = null,
    )

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
        val uniqueStreamAPI = mainUrl
        println("mappedData")
        println(mappedData)
        val fixTitle = title.fixTitle()
        val url = if (season == null) {
            "$uniqueStreamAPI/movies/$fixTitle-$year"
        } else {
            "$uniqueStreamAPI/episodes/$fixTitle-season-$season-episode-$episode"
        }

        val res = app.get(url)
        if (!res.isSuccessful) return false
        val document = res.document
        val type = if (url.contains("/movies/")) "movie" else "tv"
        document.select("ul#playeroptionsul > li").apmap { el ->
            val id = el.attr("data-post")
            val nume = el.attr("data-nume")
            val source = app.post(
                url = "$uniqueStreamAPI/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to id,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = url
            ).parsed<ResponseHash>().embed_url.let { fixUrl(it) }

            when {
                source.contains("uniquestream") -> {
                    val resDoc = app.get(
                        source, referer = "$uniqueStreamAPI/", headers = mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                        )
                    ).document
                    val srcm3u8 =
                        resDoc.selectFirst("script:containsData(let url =)")?.data()?.let {
                            Regex("['|\"](.*?.m3u8)['|\"]").find(it)?.groupValues?.getOrNull(1)
                        } ?: return@apmap null
                    val quality = app.get(
                        srcm3u8, referer = source, headers = mapOf(
                            "Accept" to "*/*",
                        )
                    ).text.let { quality ->
                        if (quality.contains("RESOLUTION=1920")) Qualities.P1080.value else Qualities.P720.value
                    }
                    callback.invoke(
                        ExtractorLink(
                            "UniqueStream",
                            "UniqueStream",
                            srcm3u8,
                            source,
                            quality,
                            true,
                            headers = mapOf(
                                "Accept" to "*/*",
                            )
                        )
                    )
                }
                !source.contains("youtube") -> loadExtractor(
                    source,
                    "$uniqueStreamAPI/",
                    subtitleCallback,
                    callback
                )
                else -> {
                    // pass
                }
            }
        }
        return true
    }
}