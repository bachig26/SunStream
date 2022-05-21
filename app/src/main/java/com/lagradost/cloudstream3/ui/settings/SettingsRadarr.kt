package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.utils.HOMEPAGE_API
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showNginxTextInputDialog
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard

class SettingsRadarr : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_radarr, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.radarr_credentials)?.setOnPreferenceClickListener {
            activity?.showNginxTextInputDialog(
                settingsManager.getString(getString(R.string.radarr_credentials_title), "Radarr Credential")
                    .toString(),
                settingsManager.getString(getString(R.string.radarr_credentials), "")
                    .toString(),  // key: the actual you use rn
                android.text.InputType.TYPE_TEXT_VARIATION_URI,  // uri
                {}) {
                settingsManager.edit()
                    .putString(getString(R.string.radarr_credentials), it)
                    .apply()  // change the stored url in nginx_url_key to it
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.radarr_url_key)?.setOnPreferenceClickListener {
            activity?.showNginxTextInputDialog(
                settingsManager.getString(getString(R.string.radarr_url_pref), "Radarr server url")
                    .toString(),
                settingsManager.getString(getString(R.string.radarr_url_key), "")
                    .toString(),  // key: the actual you use rn
                android.text.InputType.TYPE_TEXT_VARIATION_URI,  // uri
                {}) {
                settingsManager.edit()
                    .putString(getString(R.string.radarr_url_key), it)
                    .apply()  // change the stored url in radarr_url_key to it
            }
            return@setOnPreferenceClickListener true
        }
        getPref(R.string.radarr_root_folder_path)?.setOnPreferenceClickListener {
            activity?.showNginxTextInputDialog(
                settingsManager.getString(getString(R.string.radarr_root_folder_path_title), "Radarr Root folder path")
                    .toString(),
                settingsManager.getString(getString(R.string.radarr_root_folder_path), "")
                    .toString(),  // key: the actual you use rn
                android.text.InputType.TYPE_TEXT_VARIATION_URI,  // uri
                {}) {
                settingsManager.edit()
                    .putString(getString(R.string.radarr_root_folder_path), it)
                    .apply()  // change the stored url in nginx_url_key to it
            }
            return@setOnPreferenceClickListener true
        }
    }
}
