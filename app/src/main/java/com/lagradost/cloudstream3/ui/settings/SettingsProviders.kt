package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.APIHolder.getApiFromName
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard


class SettingsProviders : PreferenceFragmentCompat() {


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_providers)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_providers, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.enabled_meta_providers_key)?.setOnPreferenceClickListener {

            val savedEnabledProviders: List<String> =
                settingsManager.getStringSet(getString(R.string.enabled_meta_providers_key), null)
                    ?.toList()
                    ?: allProviders.filter {
                        it.providerType == ProviderType.MetaProvider && it::class.java != CrossTmdbProvider::class.java
                    }.map { it.name }

            var index = 0 // TODO maybe should use the .withIndex() function but I lazy
            val enabledProvidersIndex = mutableListOf<Int>()

            val allAvailableProviders: List<String> =
                allProviders.filter { it.providerType == ProviderType.MetaProvider && it::class.java != CrossTmdbProvider::class.java }
                    .map { it.name }

            allAvailableProviders.forEach { provider -> // their is probably a better way to do it
                if (provider in savedEnabledProviders) {
                    enabledProvidersIndex += index
                }
                index++
            }

            activity?.showMultiDialog(
                allAvailableProviders,
                enabledProvidersIndex.toList(),
                getString(R.string.enabled_meta_providers),
                {}) { selectedList ->
                val enabledProviders: ArrayList<String> =
                    ArrayList(selectedList.mapNotNull { indexOfSelectedProvider ->
                        try {
                            allAvailableProviders[indexOfSelectedProvider]
                        } catch (e: Exception) {
                            logError(e)
                            null
                        }
                    })

                APIHolder.allEnabledMetaProviders =
                    ArrayList(enabledProviders.map { getApiFromName(it) })
                settingsManager.edit().putStringSet(
                    getString(R.string.enabled_meta_providers_key),
                    enabledProviders.toMutableSet()
                ).apply() // YES APPLY PLS
            }

            return@setOnPreferenceClickListener true
        }



        getPref(R.string.enabled_direct_providers_key)?.setOnPreferenceClickListener {

            val savedEnabledProviders: List<String> =
                settingsManager.getStringSet(getString(R.string.enabled_direct_providers_key), null)
                    ?.toList()
                    ?: allProviders.filter { it.providerType == ProviderType.DirectProvider && it::class.java != CrossTmdbProvider::class.java && it.hasSearchFilter }
                        .map { it.name }

            var index = 0 // TODO maybe should use the .withIndex() function but I lazy
            val enabledProvidersIndex = mutableListOf<Int>()

            val allAvailableProviders: List<String> =
                allProviders.filter { it.providerType == ProviderType.DirectProvider && it::class.java != CrossTmdbProvider::class.java && it.hasSearchFilter }
                    .map { it.name }

            allAvailableProviders.forEach { provider -> // their is probably a better way to do it
                if (provider in savedEnabledProviders) {
                    enabledProvidersIndex += index
                }
                index++
            }

            activity?.showMultiDialog(
                allAvailableProviders,
                enabledProvidersIndex.toList(),
                getString(R.string.enabled_direct_providers),
                {}) { selectedList ->
                val enabledProviders: ArrayList<String> =
                    ArrayList(selectedList.mapNotNull { indexOfSelectedProvider ->
                        try {
                            allAvailableProviders[indexOfSelectedProvider]
                        } catch (e: Exception) {
                            logError(e)
                            null
                        }
                    })

                APIHolder.allEnabledDirectProviders =
                    ArrayList(enabledProviders.map { getApiFromName(it) })
                settingsManager.edit().putStringSet(
                    getString(R.string.enabled_direct_providers_key),
                    enabledProviders.toMutableSet()
                ).apply() // YES APPLY PLS
            }

            return@setOnPreferenceClickListener true
        }

    }
}
