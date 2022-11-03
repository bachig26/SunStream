package com.lagradost.cloudstream3.plugins

import android.app.*
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.removeKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.plugins.RepositoryManager.ONLINE_PLUGINS_FOLDER
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.downloadPluginToFile
import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.VideoDownloadManager.sanitizeFilename
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.PathClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStreamReader
import java.util.*

// Different keys for local and not since local can be removed at any time without app knowing, hence the local are getting rebuilt on every app start
const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"

const val EXTENSIONS_CHANNEL_ID = "cloudstream3.extensions"
const val EXTENSIONS_CHANNEL_NAME = "Extensions"
const val EXTENSIONS_CHANNEL_DESCRIPT = "Extension notification channel"

// Data class for internal storage
data class PluginData(
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("url") val url: String?,
    @JsonProperty("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") val filePath: String,
    @JsonProperty("version") val version: Int,
) {
    fun toSitePlugin(): SitePlugin {
        return SitePlugin(
            this.filePath,
            PROVIDER_STATUS_OK,
            maxOf(1, version),
            1,
            internalName,
            internalName,
            emptyList(),
            File(this.filePath).name,
            null,
            null,
            null,
            null,
            File(this.filePath).length()
        )
    }
}

// This is used as a placeholder / not set version
const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE

// This always updates
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1

object PluginManager {
    // Prevent multiple writes at once
    val lock = Mutex()

    const val TAG = "PluginManager"

    private var hasCreatedNotChanel = false

    /**
     * Store data about the plugin for fetching later
     * */
    private suspend fun setPluginData(data: PluginData) {
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline()
                val newPlugins = plugins.filter { it.filePath != data.filePath } + data
                setKey(PLUGINS_KEY, newPlugins)
            } else {
                val plugins = getPluginsLocal()
                setKey(PLUGINS_KEY_LOCAL, plugins.filter { it.filePath != data.filePath } + data)
            }
        }
    }

    private suspend fun deletePluginData(data: PluginData?) {
        if (data == null) return
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline().filter { it.url != data.url }
                setKey(PLUGINS_KEY, plugins)
            } else {
                val plugins = getPluginsLocal().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY_LOCAL, plugins)
            }
        }
    }

    suspend fun deleteRepositoryData(repositoryPath: String) {
        lock.withLock {
            val plugins = getPluginsOnline().filter {
                !it.filePath.contains(repositoryPath)
            }
            val file = File(repositoryPath)
            normalSafeApiCall {
                if (file.exists()) file.deleteRecursively()
            }
            setKey(PLUGINS_KEY, plugins)
        }
    }

    fun getPluginsOnline(): Array<PluginData> {
        return getKey(PLUGINS_KEY) ?: emptyArray()
    }

    fun getPluginsLocal(): Array<PluginData> {
        return getKey(PLUGINS_KEY_LOCAL) ?: emptyArray()
    }

    private val LOCAL_PLUGINS_PATH =
        Environment.getExternalStorageDirectory().absolutePath + "/Cloudstream3/plugins"

    public var currentlyLoading: String? = null

    // Maps filepath to plugin
    val plugins: MutableMap<String, Plugin> =
        LinkedHashMap<String, Plugin>()

    // Maps urls to plugin
    val urlPlugins: MutableMap<String, Plugin> =
        LinkedHashMap<String, Plugin>()

    private val classLoaders: MutableMap<PathClassLoader, Plugin> =
        HashMap<PathClassLoader, Plugin>()

    private var loadedLocalPlugins = false
    private val gson = Gson()

    private suspend fun maybeLoadPlugin(activity: Activity, file: File) {
        val name = file.name
        if (file.extension == "zip" || file.extension == "cs3") {
            loadPlugin(
                activity,
                file,
                PluginData(name, null, false, file.absolutePath, PLUGIN_VERSION_NOT_SET)
            )
        } else {
            Log.i(TAG, "Skipping invalid plugin file: $file")
        }
    }


    // Helper class for updateAllOnlinePluginsAndLoadThem
    data class OnlinePluginData(
        val savedData: PluginData,
        val onlineData: Pair<String, SitePlugin>,
    ) {
        val isOutdated =
            onlineData.second.version > savedData.version || onlineData.second.version == PLUGIN_VERSION_ALWAYS_UPDATE
        val isDisabled = onlineData.second.status == PROVIDER_STATUS_DOWN

        fun validOnlineData(context: Context): Boolean {
            return getPluginPath(
                context,
                savedData.internalName,
                onlineData.first
            ).absolutePath == savedData.filePath
        }
    }

    // var allCurrentOutDatedPlugins: Set<OnlinePluginData> = emptySet()

    suspend fun loadSinglePlugin(activity: Activity, apiName: String): Boolean {
        return (getPluginsOnline().firstOrNull {
            // Most of the time the provider ends with Provider which isn't part of the api name
            it.internalName.replace("provider", "", ignoreCase = true) == apiName
        }
            ?: getPluginsLocal().firstOrNull {
                it.internalName.replace("provider", "", ignoreCase = true) == apiName
            })?.let { savedData ->
            // OnlinePluginData(savedData, onlineData)
            loadPlugin(
                activity,
                File(savedData.filePath),
                savedData
            )
        } ?: false
    }

    /**
     * Needs to be run before other plugin loading because plugin loading can not be overwritten
     * 1. Gets all online data about the downloaded plugins
     * 2. If disabled do nothing
     * 3. If outdated download and load the plugin
     * 4. Else load the plugin normally
     **/
    fun updateAllOnlinePluginsAndLoadThem(activity: Activity) {
        // Load all plugins as fast as possible!
        loadAllOnlinePlugins(activity)

            afterPluginsLoadedEvent.invoke(true)


        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val onlinePlugins = urls.toList().apmap {
            getRepoPlugins(it.url)?.toList() ?: emptyList()
        }.flatten().distinctBy { it.second.url }

        // Iterates over all offline plugins, compares to remote repo and returns the plugins which are outdated
        val outdatedPlugins = getPluginsOnline().map { savedData ->
            onlinePlugins
                .filter { onlineData -> savedData.internalName == onlineData.second.internalName }
                .map { onlineData ->
                    OnlinePluginData(savedData, onlineData)
                }.filter {
                    it.validOnlineData(activity)
                }
        }.flatten().distinctBy { it.onlineData.second.url }

        debugPrint {
            "Outdated plugins: ${outdatedPlugins.filter { it.isOutdated }}"
        }

        val updatedPlugins = mutableListOf<String>()

        outdatedPlugins.apmap { pluginData ->
            if (pluginData.isDisabled) {
                //updatedPlugins.add(activity.getString(R.string.single_plugin_disabled, pluginData.onlineData.second.name))
                unloadPlugin(pluginData.savedData.filePath)
            } else if (pluginData.isOutdated) {
                downloadAndLoadPlugin(
                    activity,
                    pluginData.onlineData.second.url,
                    pluginData.savedData.internalName,
                    File(pluginData.savedData.filePath)
                ).let { success ->
                    if (success)
                        updatedPlugins.add(pluginData.onlineData.second.name)
                }
            }
        }

        main {
            createNotification(activity, updatedPlugins)
        }

       // ioSafe {
            afterPluginsLoadedEvent.invoke(true)
       // }

        Log.i(TAG, "Plugin update done!")
    }

    /**
     * Use updateAllOnlinePluginsAndLoadThem
     * */
    fun loadAllOnlinePlugins(activity: Activity) {
        // Load all plugins as fast as possible!
        (getPluginsOnline()).toList().apmap { pluginData ->
            loadPlugin(
                activity,
                File(pluginData.filePath),
                pluginData
            )
        }
    }

    fun loadAllLocalPlugins(activity: Activity) {
        val dir = File(LOCAL_PLUGINS_PATH)
        removeKey(PLUGINS_KEY_LOCAL)

        if (!dir.exists()) {
            val res = dir.mkdirs()
            if (!res) {
                Log.w(TAG, "Failed to create local directories")
                return
            }
        }

        val sortedPlugins = dir.listFiles()
        // Always sort plugins alphabetically for reproducible results

        Log.d(TAG, "Files in '${LOCAL_PLUGINS_PATH}' folder: $sortedPlugins")

        sortedPlugins?.sortedBy { it.name }?.apmap { file ->
            maybeLoadPlugin(activity, file)
        }

        loadedLocalPlugins = true
        afterPluginsLoadedEvent.invoke(true)
    }

    /**
     * @return True if successful, false if not
     * */
    private suspend fun loadPlugin(activity: Activity, file: File, data: PluginData): Boolean {
        val fileName = file.nameWithoutExtension
        val filePath = file.absolutePath
        currentlyLoading = fileName
        Log.i(TAG, "Loading plugin: $data")

        return try {
            val loader = PathClassLoader(filePath, activity.classLoader)
            var manifest: Plugin.Manifest
            loader.getResourceAsStream("manifest.json").use { stream ->
                if (stream == null) {
                    Log.e(TAG, "Failed to load plugin  $fileName: No manifest found")
                    return false
                }
                InputStreamReader(stream).use { reader ->
                    manifest = gson.fromJson(
                        reader,
                        Plugin.Manifest::class.java
                    )
                }
            }

            val name: String = manifest.name ?: "NO NAME".also {
                Log.d(TAG, "No manifest name for ${data.internalName}")
            }
            val version: Int = manifest.version ?: PLUGIN_VERSION_NOT_SET.also {
                Log.d(TAG, "No manifest version for ${data.internalName}")
            }
            val pluginClass: Class<*> =
                loader.loadClass(manifest.pluginClassName) as Class<out Plugin?>
            val pluginInstance: Plugin =
                pluginClass.newInstance() as Plugin

            // Sets with the proper version
            setPluginData(data.copy(version = version))

            if (plugins.containsKey(filePath)) {
                Log.i(TAG, "Plugin with name $name already exists")
                return true
            }

            pluginInstance.__filename = fileName
            if (manifest.requiresResources) {
                Log.d(TAG, "Loading resources for ${data.internalName}")
                // based on https://stackoverflow.com/questions/7483568/dynamic-resource-loading-from-other-apk
                val assets = AssetManager::class.java.newInstance()
                val addAssetPath =
                    AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                addAssetPath.invoke(assets, file.absolutePath)
                pluginInstance.resources = Resources(
                    assets,
                    activity.resources.displayMetrics,
                    activity.resources.configuration
                )
            }
            plugins[filePath] = pluginInstance
            classLoaders[loader] = pluginInstance
            urlPlugins[data.url ?: filePath] = pluginInstance
            pluginInstance.load(activity)
            Log.i(TAG, "Loaded plugin ${data.internalName} successfully")
            currentlyLoading = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $file: ${Log.getStackTraceString(e)}")
            showToast(
                activity,
                activity.getString(R.string.plugin_load_fail).format(fileName),
                Toast.LENGTH_LONG
            )
            currentlyLoading = null
            false
        }
    }

    private fun unloadPlugin(absolutePath: String) {
        Log.i(TAG, "Unloading plugin: $absolutePath")
        val plugin = plugins[absolutePath]
        if (plugin == null) {
            Log.w(TAG, "Couldn't find plugin $absolutePath")
            return
        }

        try {
            plugin.beforeUnload()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run beforeUnload $absolutePath: ${Log.getStackTraceString(e)}")
        }

        // remove all registered apis
        APIHolder.apis.filter { api -> api.sourcePlugin == plugin.__filename }.forEach {
            removePluginMapping(it)
        }
        APIHolder.allProviders.removeIf { provider: MainAPI -> provider.sourcePlugin == plugin.__filename }
        extractorApis.removeIf { provider: ExtractorApi -> provider.sourcePlugin == plugin.__filename }

        classLoaders.values.removeIf { v -> v == plugin }

        plugins.remove(absolutePath)
        urlPlugins.values.removeIf { v -> v == plugin }
    }

    /**
     * Spits out a unique and safe filename based on name.
     * Used for repo folders (using repo url) and plugin file names (using internalName)
     * */
    fun getPluginSanitizedFileName(name: String): String {
        return sanitizeFilename(
            name,
            true
        ) + "." + name.hashCode()
    }

    /**
     * This should not be changed as it is used to also detect if a plugin is installed!
     **/
    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String
    ): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl) // Guaranteed unique
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}/${folderName}/$fileName.cs3")
    }

    /**
     * Used for fresh installs
     * */
    suspend fun downloadAndLoadPlugin(
        activity: Activity,
        pluginUrl: String,
        internalName: String,
        repositoryUrl: String
    ): Boolean {
        val file = getPluginPath(activity, internalName, repositoryUrl)
        downloadAndLoadPlugin(activity, pluginUrl, internalName, file)
        return true
    }

    /**
     * Used for updates.
     *
     * Uses a file instead of repository url, as extensions can get moved it is better to directly
     * update the files instead of getting the filepath from repo url.
     * */
    private suspend fun downloadAndLoadPlugin(
        activity: Activity,
        pluginUrl: String,
        internalName: String,
        file: File,
    ): Boolean {
        try {
            unloadPlugin(file.absolutePath)

            Log.d(TAG, "Downloading plugin: $pluginUrl to ${file.absolutePath}")
            // The plugin file needs to be salted with the repository url hash as to allow multiple repositories with the same internal plugin names
            val newFile = downloadPluginToFile(pluginUrl, file)
            return loadPlugin(
                activity,
                newFile ?: return false,
                PluginData(
                    internalName,
                    pluginUrl,
                    true,
                    newFile.absolutePath,
                    PLUGIN_VERSION_NOT_SET
                )
            )
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    suspend fun deletePlugin(file: File): Boolean {
        val list = (getPluginsLocal() + getPluginsOnline()).filter { it.filePath == file.absolutePath }

        return try {
            if (File(file.absolutePath).delete()) {
                unloadPlugin(file.absolutePath)
                list.forEach { deletePluginData(it) }
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = EXTENSIONS_CHANNEL_NAME //getString(R.string.channel_name)
            val descriptionText =
                EXTENSIONS_CHANNEL_DESCRIPT//getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(EXTENSIONS_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        context: Context,
        extensionNames: List<String>
    ): Notification? {
        try {
            if (extensionNames.isEmpty()) return null

            val content = extensionNames.joinToString(", ")
//        main { // DON'T WANT TO SLOW IT DOWN
            val builder = NotificationCompat.Builder(context, EXTENSIONS_CHANNEL_ID)
                .setAutoCancel(false)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(context.getString(R.string.plugins_updated, extensionNames.size))
                .setSmallIcon(R.drawable.ic_baseline_extension_24)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(content)
                )
                .setContentText(content)

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            with(NotificationManagerCompat.from(context)) {
                // notificationId is a unique int for each notification that you must define
                notify((System.currentTimeMillis() / 1000).toInt(), notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }
}