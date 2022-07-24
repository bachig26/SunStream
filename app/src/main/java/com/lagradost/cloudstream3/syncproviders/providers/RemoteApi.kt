package com.lagradost.cloudstream3.syncproviders.providers

import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.movieproviders.NginxProvider
import com.lagradost.cloudstream3.movieproviders.SonarrProvider
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.InAppAuthAPI
import com.lagradost.cloudstream3.syncproviders.InAppAuthAPIManager

class RemoteApi(index: Int) : InAppAuthAPIManager(index) {
    override val name = "Remote Control"
    override val idPrefix = "remote"
    override val icon = R.drawable.ic_remote_control
    override val requiresServer = true
    override val requiresUsername = true
    override val requiresApiKey = false
    override val requiresPath = false
    override val createAccountUrl = "https://sarlays.com/unlisted/cloudstream-remote-control/"

    companion object {
        const val REMOTE_USER_KEY: String = "remote_user" // not required
    }

    override fun getLatestLoginData(): InAppAuthAPI.LoginData? {
        return getKey(accountId, REMOTE_USER_KEY)
    }


    override fun loginInfo(): AuthAPI.LoginInfo? {
        val data = getLatestLoginData() ?: return null
        return AuthAPI.LoginInfo(name = data.username ?: data.server, accountIndex = accountIndex)
    }

    override suspend fun login(data: InAppAuthAPI.LoginData): Boolean {
        if (data.server.isNullOrBlank()) return false // we require a server
        switchToNewAccount()
        setKey(accountId, REMOTE_USER_KEY, data)
        registerAccount()
        initialize()
        return true
    }

    override fun logOut() {
        removeAccountKeys()

    }
}