package com.lagradost.cloudstream3.ui.settings.extensions

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.result.txt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import kotlinx.coroutines.launch
import me.xdrop.fuzzywuzzy.FuzzySearch

typealias Plugin = Pair<String, SitePlugin>
/**
 * The boolean signifies if the plugin list should be scrolled to the top, used for searching.
 * */
typealias PluginViewDataUpdate = Pair<Boolean, List<PluginViewData>>

class PluginsViewModel : ViewModel() {

    /** plugins is an unaltered list of plugins */
    private var plugins: List<PluginViewData> = emptyList()

    /** filteredPlugins is a subset of plugins following the current search query and tv type selection */
    private var _filteredPlugins = MutableLiveData<PluginViewDataUpdate>()
    var filteredPlugins: LiveData<PluginViewDataUpdate> = _filteredPlugins

    val tvTypes = mutableListOf<String>()
    var languages = listOf<String>()
    private var currentQuery: String? = null

    companion object {
        private val repositoryCache: MutableMap<String, List<Plugin>> = mutableMapOf()
        const val TAG = "PLG"

        private fun isDownloaded(plugin: Plugin, data: Set<String>? = null): Boolean {
            return (data ?: getDownloads()).contains(plugin.second.internalName)
        }

        private suspend fun getPlugins(
            repositoryUrl: String,
            canUseCache: Boolean = true
        ): List<Plugin> {
            Log.i(TAG, "getPlugins = $repositoryUrl")
            if (canUseCache && repositoryCache.containsKey(repositoryUrl)) {
                repositoryCache[repositoryUrl]?.let {
                    return it
                }
            }
            return RepositoryManager.getRepoPlugins(repositoryUrl)
                ?.also { repositoryCache[repositoryUrl] = it } ?: emptyList()
        }

        private fun getStoredPlugins(): Array<PluginData> {
            return PluginManager.getPluginsOnline()
        }

        private fun getDownloads(): Set<String> {
            return getStoredPlugins().map { it.internalName }.toSet()
        }

        /**
         * @param viewModel optional, updates the plugins livedata for that viewModel if included
         * */
        fun downloadAll(activity: Activity?, repositoryUrl: String, viewModel: PluginsViewModel?) =
            ioSafe {
                if (activity == null) return@ioSafe
                val stored = getDownloads()
                val plugins = getPlugins(repositoryUrl)

                plugins.filter { plugin -> !isDownloaded(plugin, stored) }.also { list ->
                    main {
                        showToast(
                            activity,
                            if (list.isEmpty()) {
                                txt(
                                    R.string.batch_download_nothing_to_download_format,
                                    txt(R.string.plugin)
                                )
                            } else {
                                txt(
                                    R.string.batch_download_start_format,
                                    list.size,
                                    txt(if (list.size == 1) R.string.plugin_singular else R.string.plugin)
                                )
                            },
                            Toast.LENGTH_SHORT
                        )
                    }
                }.apmap { (repo, metadata) ->
                    PluginManager.downloadAndLoadPlugin(
                        activity,
                        metadata.url,
                        metadata.name,
                        repo
                    )
                }.main { list ->
                    if (list.any { it }) {
                        showToast(
                            activity,
                            txt(
                                R.string.batch_download_finish_format,
                                list.count { it },
                                txt(if (list.size == 1) R.string.plugin_singular else R.string.plugin)
                            ),
                            Toast.LENGTH_SHORT
                        )
                        viewModel?.updatePluginListPrivate(repositoryUrl)
                    } else if (list.isNotEmpty()) {
                        showToast(activity, R.string.download_failed, Toast.LENGTH_SHORT)
                    }
                }
            }
    }

    /**
     * @param isLocal defines if the plugin data is from local data instead of repo
     * Will only allow removal of plugins. Used for the local file management.
     * */
    fun handlePluginAction(
        activity: Activity?,
        repositoryUrl: String,
        plugin: Plugin,
        isLocal: Boolean
    ) = ioSafe {
        Log.i(TAG, "handlePluginAction = $repositoryUrl, $plugin, $isLocal")

        if (activity == null) return@ioSafe
        val (repo, metadata) = plugin

        val (success, message) = if (isDownloaded(plugin) || isLocal) {
            PluginManager.deletePlugin(
                metadata.url,
                isLocal
            ) to R.string.plugin_deleted
        } else {
            PluginManager.downloadAndLoadPlugin(
                activity,
                metadata.url,
                metadata.name,
                repo
            ) to R.string.plugin_loaded
        }

        runOnMainThread {
            if (success)
                showToast(activity, message, Toast.LENGTH_SHORT)
            else
                showToast(activity, R.string.error, Toast.LENGTH_SHORT)
        }

        if (success)
            if (isLocal)
                updatePluginListLocal()
            else
                updatePluginListPrivate(repositoryUrl)
    }

    private suspend fun updatePluginListPrivate(repositoryUrl: String) {
        val stored = getDownloads()
        val plugins = getPlugins(repositoryUrl)
        val list = plugins.map { plugin ->
            PluginViewData(plugin, isDownloaded(plugin, stored))
        }

        this.plugins = list
        _filteredPlugins.postValue(false to list.filterTvTypes().filterLang().sortByQuery(currentQuery))
    }

    // Perhaps can be optimized?
    private fun List<PluginViewData>.filterTvTypes(): List<PluginViewData> {
        if (tvTypes.isEmpty()) return this
        return this.filter {
            (it.plugin.second.tvTypes?.any { type -> tvTypes.contains(type) } == true) ||
            (tvTypes.contains("Others") && (it.plugin.second.tvTypes ?: emptyList()).isEmpty())
        }
    }

    private fun List<PluginViewData>.filterLang(): List<PluginViewData> {
        if (languages.isEmpty()) return this
        return this.filter {
            if (it.plugin.second.language == null) {
                return@filter languages.contains("none")
            }
            languages.contains(it.plugin.second.language)
        }
    }

    private fun List<PluginViewData>.sortByQuery(query: String?): List<PluginViewData> {
        return if (query == null) {
            // Return list to base state if no query
            this.sortedBy { it.plugin.second.name }
        } else {
            this.sortedBy { -FuzzySearch.ratio(it.plugin.second.name, query) }
        }
    }

    fun updateFilteredPlugins() {
        _filteredPlugins.postValue(false to plugins.filterTvTypes().filterLang().sortByQuery(currentQuery))
    }

    fun updatePluginList(repositoryUrl: String) = viewModelScope.launchSafe {
        Log.i(TAG, "updatePluginList = $repositoryUrl")
        updatePluginListPrivate(repositoryUrl)
    }

    fun search(query: String?) {
        currentQuery = query
        _filteredPlugins.postValue(true to (filteredPlugins.value?.second?.sortByQuery(query) ?: emptyList()))
    }

    /**
     * Update the list but only with the local data. Used for file management.
     * */
    fun updatePluginListLocal() = viewModelScope.launchSafe {
        Log.i(TAG, "updatePluginList = local")

        val downloadedPlugins = (PluginManager.getPluginsOnline() + PluginManager.getPluginsLocal())
            .distinctBy { it.filePath }
            .map {
                PluginViewData("" to it.toSitePlugin(), true)
            }

        plugins = downloadedPlugins
        _filteredPlugins.postValue(false to downloadedPlugins.filterTvTypes().filterLang().sortByQuery(currentQuery))
    }
}