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



val session = Session(Requests().baseClient)

// Credit: hexated https://github.com/hexated/cloudstream-extensions-hexated/blob/master/SoraStream/src/main/kotlin/com/hexated/SoraExtractor.kt
class FilmxProvider : TmdbProvider() {
    override val apiName = "filmxprovider"
    override var name = "FilmxProvider"
    override var mainUrl = "https://www.filmxy.vip"
    override val useMetaLoadResponse = true
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

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


    data class FilmxyCookies(
        val phpsessid: String? = null,
        val wLog: String? = null,
        val wSec: String? = null,
    )



    suspend fun getFilmxyCookies(imdbId: String? = null, season: Int? = null): FilmxyCookies? {

        val url = if (season == null) {
            "${mainUrl}/movie/$imdbId"
        } else {
            "${mainUrl}/tv/$imdbId"
        }
        val cookieUrl = "${mainUrl}/wp-admin/admin-ajax.php"

        val res = session.get(
            url,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            ),
        )

        if(!res.isSuccessful) return FilmxyCookies()

        val userNonce =
            res.document.select("script").find { it.data().contains("var userNonce") }?.data()?.let {
                Regex("var\\suserNonce.*?\"(\\S+?)\";").find(it)?.groupValues?.get(1)
            }

        var phpsessid = session.baseClient.cookieJar.loadForRequest(url.toHttpUrl())
            .first { it.name == "PHPSESSID" }.value

        session.post(
            cookieUrl,
            data = mapOf(
                "action" to "guest_login",
                "nonce" to "$userNonce",
            ),
            headers = mapOf(
                "Cookie" to "PHPSESSID=$phpsessid; G_ENABLED_IDPS=google",
                "X-Requested-With" to "XMLHttpRequest",
            )
        )

        val cookieJar = session.baseClient.cookieJar.loadForRequest(cookieUrl.toHttpUrl())
        phpsessid = cookieJar.first { it.name == "PHPSESSID" }.value
        val wLog = cookieJar.first { it.name == "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" }.value
        val wSec = cookieJar.first { it.name == "wordpress_sec_8bf9d5433ac88cc9a3a396d6b154cd01" }.value

        return FilmxyCookies(phpsessid, wLog, wSec)
    }

    override suspend fun loadLinks( // invokeFilmxy(res.imdbId, res.season, res.episode, subtitleCallback, callback)
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mappedData = tryParseJson<TmdbLink>(data)
        val season = mappedData?.season
        val episode = mappedData?.episode
        val imdbId = mappedData?.imdbID

        // (imdbId: String? = null, season: Int? = null): FilmxyCookies? {
        val url = if (season == null) {
            "${mainUrl}/movie/$imdbId"
        } else {
            "${mainUrl}/tv/$imdbId"
        }
        val filmxyCookies = getFilmxyCookies(imdbId, season) ?: throw ErrorLoadingException("No Cookies Found")

        val cookiesDoc = mapOf(
            "G_ENABLED_IDPS" to "google",
            "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" to "${filmxyCookies.wLog}",
            "PHPSESSID" to "${filmxyCookies.phpsessid}"
        )

        val request = session.get(url, cookies = cookiesDoc)
        if(!request.isSuccessful) return false

        val doc = request.document
        val script = doc.selectFirst("script:containsData(var isSingle)")?.data().toString()
        val sourcesData = Regex("listSE\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1)
        val sourcesDetail = Regex("linkDetails\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1)

        //Gson is shit, but i don't care
        val sourcesJson = JsonParser().parse(sourcesData).asJsonObject
        val sourcesDetailJson = JsonParser().parse(sourcesDetail).asJsonObject

        val sources = if (season == null && episode == null) {
            sourcesJson.getAsJsonObject("movie").getAsJsonArray("movie")
        } else {
            val eps = if (episode!! < 10) "0$episode" else episode
            val sson = if (season!! < 10) "0$season" else season
            sourcesJson.getAsJsonObject("s$sson").getAsJsonArray("e$eps")
        }.asJsonArray

        val scriptUser = doc.select("script").find { it.data().contains("var userNonce") }?.data().toString()
        val userNonce = Regex("var\\suserNonce.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)
        val userId = Regex("var\\suser_id.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)
        val linkIDs = sources.joinToString("") {
            "&linkIDs%5B%5D=$it"
        }.replace("\"", "")

        val body = "action=get_vid_links$linkIDs&user_id=$userId&nonce=$userNonce".toRequestBody()
        val cookiesJson = mapOf(
            "G_ENABLED_IDPS" to "google",
            "PHPSESSID" to "${filmxyCookies.phpsessid}",
            "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" to "${filmxyCookies.wLog}",
            "wordpress_sec_8bf9d5433ac88cc9a3a396d6b154cd01" to "${filmxyCookies.wSec}"
        )
        val json = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            requestBody = body,
            referer = url,
            headers = mapOf(
                "Accept" to "*/*",
                "DNT" to "1",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            cookies = cookiesJson
        ).text.let { JsonParser().parse(it).asJsonObject }

        sources.map { source ->
            val src = source.asString
            val link = json.getAsJsonPrimitive(src).asString
            val quality = sourcesDetailJson.getAsJsonObject(src).getAsJsonPrimitive("resolution").asString
            val server = sourcesDetailJson.getAsJsonObject(src).getAsJsonPrimitive("server").asString
            val size = sourcesDetailJson.getAsJsonObject(src).getAsJsonPrimitive("size").asString

            callback.invoke(
                ExtractorLink(
                    "Filmxy $size ($server)",
                    "Filmxy $size ($server)",
                    link,
                    "$mainUrl/",
                    getQualityFromName(quality)
                )
            )
        }
        return true
    }
}