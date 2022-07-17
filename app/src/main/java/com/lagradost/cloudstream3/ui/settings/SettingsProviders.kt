package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getApiFromName
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
    // idk, if you find a way of automating this it would be great
    // https://www.iemoji.com/view/emoji/1794/flags/antarctica
    // Emoji Character Encoding Data --> C/C++/Java Src
    // https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes leave blank for auto


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_providers)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_providers, rootKey)
        //val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.enabled_providers_key)?.setOnPreferenceClickListener {

            val savedSettingsProviders = context?.getKey<List<String>>(ENABLED_META_PROVIDERS)

            val allAvailableProviders: List<String> = APIHolder.allProviders.filter{ it.providerType == ProviderType.MetaProvider}.map { it.name }

            val savedEnabledProviders: List<String> = if (savedSettingsProviders?.isEmpty() == false) {
                savedSettingsProviders
            } else {
                APIHolder.allProviders.filter{ it.providerType == ProviderType.MetaProvider}.map { it.name }
            }


            var index = 0 // TODO use the .withIndex() function but I lazy
            val enabledProvidersIndex = mutableListOf<Int>()

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
                context?.setKey(ENABLED_META_PROVIDERS, enabledProviders)
            }

            return@setOnPreferenceClickListener true
        }

    }
}
