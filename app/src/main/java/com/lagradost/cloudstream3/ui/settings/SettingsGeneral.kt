package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.hippo.unifile.UniFile
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.ui.EasterEggMonke
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.cloudstream3.utils.VideoDownloadManager
import com.lagradost.cloudstream3.utils.VideoDownloadManager.getBasePath
import kotlinx.android.synthetic.main.add_remove_sites.*
import kotlinx.android.synthetic.main.add_site_input.*
import java.io.File
import java.net.Socket


fun getCurrentLocale(context: Context): String {
    val res = context.resources
    // Change locale settings in the app.
    // val dm = res.displayMetrics
    val conf = res.configuration
    return conf?.locale?.toString() ?: "en"
}

// idk, if you find a way of automating this it would be great
// https://www.iemoji.com/view/emoji/1794/flags/antarctica
// Emoji Character Encoding Data --> C/C++/Java Src
// https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes leave blank for auto
val appLanguages = arrayListOf(
    /* begin language list */
    Triple("", "Arabic", "ar"),
    Triple("", "Bulgarian", "bg"),
    Triple("", "Bengali", "bn"),
    Triple("\uD83C\uDDE7\uD83C\uDDF7", "Brazilian Portuguese", "bp"),
    Triple("", "Czech", "cs"),
    Triple("", "German", "de"),
    Triple("", "Greek", "el"),
    Triple("", "English", "en"),
    Triple("", "Spanish", "es"),
    Triple("", "French", "fr"),
    Triple("", "Hindi", "hi"),
    Triple("", "Croatian", "hr"),
    Triple("\uD83C\uDDEE\uD83C\uDDE9", "Indonesian", "in"),
    Triple("", "Italian", "it"),
    Triple("\uD83C\uDDEE\uD83C\uDDF1", "Hebrew", "iw"),
    Triple("", "Kannada", "kn"),
    Triple("", "Macedonian", "mk"),
    Triple("", "Malayalam", "ml"),
    Triple("", "Moldavian", "mo"),
    Triple("", "Dutch", "nl"),
    Triple("", "Norsk", "no"),
    Triple("", "Polish", "pl"),
    Triple("\uD83C\uDDF5\uD83C\uDDF9", "Portuguese", "pt"),
    Triple("", "Romanian", "ro"),
    Triple("", "Russian", "ru"),
    Triple("", "Swedish", "sv"),
    Triple("", "Tamil", "ta"),
    Triple("", "Tagalog", "tl"),
    Triple("", "Turkish", "tr"),
    Triple("", "Urdu", "ur"),
    Triple("", "Viet Nam", "vi"),
    Triple("", "Chinese Simplified", "zh"),
    Triple("\uD83C\uDDF9\uD83C\uDDFC", "Chinese Traditional", "zh-rTW"),
/* end language list */
).sortedBy { it.second } //ye, we go alphabetical, so ppl don't put their lang on top

class SettingsGeneral : PreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_general)
        setPaddingBottom()
    }

    data class CustomSite(
        @JsonProperty("parentJavaClass") // javaClass.simpleName
        val parentJavaClass: String,
        @JsonProperty("name")
        val name: String,
        @JsonProperty("url")
        val url: String,
        @JsonProperty("lang")
        val lang: String,
    )

    // Open file picker
    private val pathPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            // It lies, it can be null if file manager quits.
            if (uri == null) return@registerForActivityResult
            val context = context ?: AcraApplication.context ?: return@registerForActivityResult
            // RW perms for the path
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            context.contentResolver.takePersistableUriPermission(uri, flags)

            val file = UniFile.fromUri(context, uri)
            println("Selected URI path: $uri - Full path: ${file.filePath}")

            // Stores the real URI using download_path_key
            // Important that the URI is stored instead of filepath due to permissions.
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(getString(R.string.download_path_key), uri.toString()).apply()

            // From URI -> File path
            // File path here is purely for cosmetic purposes in settings
            (file.filePath ?: uri.toString()).let {
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putString(getString(R.string.download_path_pref), it).apply()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_general, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        fun getCurrent(): MutableList<CustomSite> {
            return getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList()
                ?: mutableListOf()
        }

        getPref(R.string.locale_key)?.setOnPreferenceClickListener { pref ->
            val tempLangs = appLanguages.toMutableList()
            //if (beneneCount > 100) {
            //    tempLangs.add(Triple("\uD83E\uDD8D", "mmmm... monke", "mo"))
            //}
            val current = getCurrentLocale(pref.context)
            val languageCodes = tempLangs.map { (_, _, iso) -> iso }
            val languageNames = tempLangs.map { (emoji, name, iso) ->
                val flag = emoji.ifBlank { SubtitleHelper.getFlagFromIso(iso) ?: "ERROR" }
                "$flag $name"
            }
            val index = languageCodes.indexOf(current)

            activity?.showDialog(
                languageNames, index, getString(R.string.app_language), true, { }
            ) { languageIndex ->
                try {
                    val code = languageCodes[languageIndex]
                    CommonActivity.setLocale(activity, code)
                    settingsManager.edit().putString(getString(R.string.locale_key), code).apply()
                    activity?.recreate()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }


        fun showAdd() {
            val providers = allProviders.distinctBy { it.javaClass }.sortedBy { it.name }
            activity?.showDialog(
                providers.map { "${it.name} (${it.mainUrl})" },
                -1,
                context?.getString(R.string.add_site_pref) ?: return,
                true,
                {}) { selection ->
                val provider = providers.getOrNull(selection) ?: return@showDialog

                val builder =
                    AlertDialog.Builder(context ?: return@showDialog, R.style.AlertDialogCustom)
                        .setView(R.layout.add_site_input)

                val dialog = builder.create()
                dialog.show()

                dialog.text2?.text = provider.name
                dialog.apply_btt?.setOnClickListener {
                    val name = dialog.site_name_input?.text?.toString()
                    val url = dialog.site_url_input?.text?.toString()
                    val lang = dialog.site_lang_input?.text?.toString()
                    val realLang = if (lang.isNullOrBlank()) provider.lang else lang
                    if (url.isNullOrBlank() || name.isNullOrBlank() || realLang.length != 2) {
                        showToast(activity, R.string.error_invalid_data, Toast.LENGTH_SHORT)
                        return@setOnClickListener
                    }

                    val current = getCurrent()
                    val newSite = CustomSite(provider.javaClass.simpleName, name, url, realLang)
                    current.add(newSite)
                    setKey(USER_PROVIDER_API, current.toTypedArray())

                    dialog.dismissSafe(activity)
                }
                dialog.cancel_btt?.setOnClickListener {
                    dialog.dismissSafe(activity)
                }
            }
        }

        fun showDelete() {
            val current = getCurrent()

            activity?.showMultiDialog(
                current.map { it.name },
                listOf(),
                context?.getString(R.string.remove_site_pref) ?: return,
                {}) { indexes ->
                current.removeAll(indexes.map { current[it] })
                setKey(USER_PROVIDER_API, current.toTypedArray())
            }
        }

        fun showAddOrDelete() {
            val builder =
                AlertDialog.Builder(context ?: return, R.style.AlertDialogCustom)
                    .setView(R.layout.add_remove_sites)

            val dialog = builder.create()
            dialog.show()

            dialog.add_site?.setOnClickListener {
                showAdd()
                dialog.dismissSafe(activity)
            }
            dialog.remove_site?.setOnClickListener {
                showDelete()
                dialog.dismissSafe(activity)
            }
        }

        getPref(R.string.override_site_key)?.setOnPreferenceClickListener { _ ->

            if (getCurrent().isEmpty()) {
                showAdd()
            } else {
                showAddOrDelete()
            }

            return@setOnPreferenceClickListener true
        }


        getPref(R.string.dns_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.dns_pref)
            val prefValues = resources.getIntArray(R.array.dns_pref_values)

            val currentDns =
                settingsManager.getInt(getString(R.string.dns_pref), 5)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(currentDns),
                getString(R.string.dns_pref),
                true,
                {}) {
                settingsManager.edit().putInt(getString(R.string.dns_pref), prefValues[it]).apply()
                (context ?: AcraApplication.context)?.let { ctx -> app.initClient(ctx) }
            }
            return@setOnPreferenceClickListener true
        }
        fun getDownloadDirs(): List<String> {
            return normalSafeApiCall {
                val defaultDir = VideoDownloadManager.getDownloadDir()?.filePath

                // app_name_download_path = Cloudstream and does not change depending on release.
                // DOES NOT WORK ON SCOPED STORAGE.
                val secondaryDir =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) null else Environment.getExternalStorageDirectory().absolutePath +
                            File.separator + resources.getString(R.string.app_name_download_path)
                val first = listOf(defaultDir, secondaryDir)
                (try {
                    val currentDir = context?.getBasePath()?.let { it.first?.filePath ?: it.second }

                    (first +
                            requireContext().getExternalFilesDirs("").mapNotNull { it.path } +
                            currentDir)
                } catch (e: Exception) {
                    first
                }).filterNotNull().distinct()
            } ?: emptyList()
        }

        getPref(R.string.download_path_key)?.setOnPreferenceClickListener {
            val dirs = getDownloadDirs()

            val currentDir =
                settingsManager.getString(getString(R.string.download_path_pref), null)
                    ?: VideoDownloadManager.getDownloadDir().toString()

            activity?.showBottomDialog(
                dirs + listOf("Custom"),
                dirs.indexOf(currentDir),
                getString(R.string.download_path_pref),
                true,
                {}) {
                // Last = custom
                if (it == dirs.size) {
                    try {
                        pathPicker.launch(Uri.EMPTY)
                    } catch (e: Exception) {
                        logError(e)
                    }
                } else {
                    // Sets both visual and actual paths.
                    // key = used path
                    // pref = visual path
                    settingsManager.edit()
                        .putString(getString(R.string.download_path_key), dirs[it]).apply()
                    settingsManager.edit()
                        .putString(getString(R.string.download_path_pref), dirs[it]).apply()
                }
            }
            return@setOnPreferenceClickListener true
        }


        try {
            SettingsFragment.beneneCount =
                settingsManager.getInt(getString(R.string.benene_count), 0)
            getPref(R.string.benene_count)?.let { pref ->
                pref.summary =
                    if (SettingsFragment.beneneCount <= 0) getString(R.string.benene_count_text_none) else getString(
                        R.string.benene_count_text
                    ).format(
                        SettingsFragment.beneneCount
                    )

                pref.setOnPreferenceClickListener {
                    try {
                        SettingsFragment.beneneCount++
                        if (SettingsFragment.beneneCount%20 == 0) {
                            val intent = Intent(context, EasterEggMonke::class.java)
                            startActivity(intent)
                        }
                        settingsManager.edit().putInt(
                            getString(R.string.benene_count),
                            SettingsFragment.beneneCount
                        )
                            .apply()
                        it.summary =
                            getString(R.string.benene_count_text).format(SettingsFragment.beneneCount)
                    } catch (e: Exception) {
                        logError(e)
                    }

                    return@setOnPreferenceClickListener true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        getPref(R.string.show_local_ip_key)?.setOnPreferenceClickListener {
            val builder: AlertDialog.Builder =
                AlertDialog.Builder(it.context, R.style.AlertDialogCustom)
            builder.setTitle(R.string.local_ip_pref)
            getLocalIpAddress(builder)
        }

    }


    private fun getLocalIpAddress(builder: AlertDialog.Builder): Boolean {
        ioSafe {
            try {
                val s1 = Socket("192.168.1.1", 80) // try to ping router, will fail if router is not here exactly
                val address = s1.localAddress.hostAddress
                s1.close()
                showMessageWithIp(builder, address)
            } catch (e: Exception) {
                val s2 = Socket("google.com", 80) // not private
                showMessageWithIp(builder, s2.localAddress.hostAddress)
                s2.close()
            }
        }
        return true
    }


    private fun showMessageWithIp(builder: AlertDialog.Builder, address: String?) {
        main {
            builder.setMessage("your local ip is: $address")
            builder.show()
        }
    }


}
