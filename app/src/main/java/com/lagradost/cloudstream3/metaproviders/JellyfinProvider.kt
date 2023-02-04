package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class JellyfinProvider: MainAPI() {

    override var name = "Jellyfin"
    override val providerType = ProviderType.MetaProvider
    override var mainUrl = "http://192.168.1.18:8096"
    override var hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )



    companion object {
        var everyrequestheader = mapOf("Authorization" to "Emby", "UserId" to "c514fd773cba47d89ddce852dd54dd0d", "Client" to "Android", "Device" to "Samsung Galaxy SIII", "DeviceId" to "xxx", "Version"  to "1.0.0.0", "Content-type" to "json")
        var authheader = mapOf("X-Emby-Token" to "0262336d589d4a738921814509364d43")
        var userid: String? = "c514fd773cba47d89ddce852dd54dd0d"
        var username = "Raph"
        var password = "null"
        //var loginCredentials: String? = null
        //var overrideUrl: String? = null
        //var pathToLibrary: String? = null // the library displayed when getting overrideUrl /home/username/media/
        const val ERROR_STRING = "No nginx url specified in the settings"
    }

    data class authResponse (
        @JsonProperty("User") var User : User? = User(),
        @JsonProperty("AccessToken") var AccessToken : String? = null,
        @JsonProperty("ServerId") var ServerId : String? = null

    )

    data class User (
        @JsonProperty("Name") var Name : String?  = null,
        @JsonProperty("ServerId") var ServerId : String? = null,
        @JsonProperty("ServerName") var ServerName : String? = null,
        @JsonProperty("Id") var Id : String? = null,

    )
    data class mainPageList (
        @JsonProperty("Items") val items: List<Item>,

        @JsonProperty("TotalRecordCount",) val totalRecordCount: Long,

        @JsonProperty("StartIndex") val startIndex: Long
    )

    data class Item (
        @JsonProperty("Name") val name: String,
        @JsonProperty("Id") val id: String,
    )

    data class mainPageItemsResponse (
        @JsonProperty("Items") val items: List<Item>,
    )
    
    
    private suspend fun getAuthHeader(username: String, password: String): Map<String, String?>? {
        val url = mainUrl

        val requestBody = mapOf("Raph" to username, "Pw" to password)
        val response = app.post("$url/Users/AuthenticateByName", requestBody = (requestBody + everyrequestheader).toString().toRequestBody("application/json;charset=UTF-8".toMediaTypeOrNull())).text
        println("response $response ")
        val test = tryParseJson<authResponse>(response)
        return if (test != null) {
            mapOf("X-Emby-Token" to test.AccessToken)
        } else {
            null
        }

    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        println("getMainPage")
        println(getAuthHeader(username, password))
        val response = app.get("$mainUrl/Users/$userid/Views", params=authheader)
        println(response.code)
        println(response.text)
        val listOfHomePage = tryParseJson<mainPageList>(response.text)?.items?.amap{
            Pair(it.name, it.id)
        }
        val homePageResponse = listOfHomePage?.map {
            val output = app.get("$mainUrl/Users/$userid/Items?parentId=${it.second}&includeItemTypes=video", params=authheader).text
            val listSearchResponse = tryParseJson<mainPageItemsResponse>(output)?.items?.map {
                newMovieSearchResponse(
                    it.name,
                    it.id,
                )
            }
            if (listSearchResponse!= null) {
                return@map HomePageList(
                    it.first,
                    listSearchResponse
                )
            } else {
                null
            }
        }?.filterNotNull()
        if (homePageResponse != null) {
            return HomePageResponse(
                homePageResponse
            )
        }
        return null

    }




}