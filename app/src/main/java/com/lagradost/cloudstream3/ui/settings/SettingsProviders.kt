package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getApiFromName
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.ui.search.SEARCH_PREF_PROVIDERS
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.HOMEPAGE_API
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import org.acra.util.mapNotNullToSparseArray


const val ENABLED_META_PROVIDERS = "enabled_meta_providers" // Probably wrong

class SettingsProviders : PreferenceFragmentCompat() {


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_providers)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_providers, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.enabled_providers_key)?.setOnPreferenceClickListener {

            val savedSettingsProviders = settingsManager.getStringSet(getString(R.string.enabled_providers_key), null)?.toList()
            val savedEnabledProviders: List<String> = if (!savedSettingsProviders.isNullOrEmpty()) {
                savedSettingsProviders
            } else {
                APIHolder.allProviders.filter{ it.providerType == ProviderType.MetaProvider && it.name != "MultiMedia"}.map { it.name } // TODO FIX EXCLUDE MULTIMEDIA
            }

            var index = 0 // TODO maybe should use the .withIndex() function but I lazy
            val enabledProvidersIndex = mutableListOf<Int>()

            val allAvailableProviders: List<String> = APIHolder.allProviders.filter{ it.providerType == ProviderType.MetaProvider && it.name != "MultiMedia"}.map { it.name }// TODO FIX EXCLUDE MULTIMEDIA

            allAvailableProviders.forEach { provider -> // their is probably a better way to do it
                if (provider in savedEnabledProviders) {
                    enabledProvidersIndex += index
                }
                index++
            }

            activity?.showMultiDialog(
                allAvailableProviders,
                enabledProvidersIndex.toList(),
                getString(R.string.enabled_providers),
                {}) { selectedList ->
                val enabledProviders: ArrayList<String> = ArrayList(selectedList.mapNotNull { indexOfSelectedProvider ->
                    try {
                        allAvailableProviders[indexOfSelectedProvider]
                    } catch (e: Exception) {
                        logError(e)
                        null
                    }
                })

                APIHolder.allEnabledProviders = ArrayList(enabledProviders.map{ getApiFromName(it) })
                settingsManager.edit().putStringSet(getString(R.string.enabled_providers_key), enabledProviders.toMutableSet()).apply() // YES APPLY PLS
            }

            return@setOnPreferenceClickListener true
        }

    }
}
