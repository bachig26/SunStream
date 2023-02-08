package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.auto.service.AutoService
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.isTvSettings
import com.lagradost.cloudstream3.utils.AppUtils.openBrowser
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import kotlinx.coroutines.runBlocking

import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.Exception

import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess



class ExceptionHandler(val errorFile: File, val onError: (() -> Unit)) :
    Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            PrintStream(errorFile).use { ps ->
                ps.println(String.format("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}"))
                ps.println(
                    String.format(
                        "Fatal exception on thread %s (%d)",
                        thread.name,
                        thread.id
                    )
                )
                error.printStackTrace(ps)
            }
        } catch (ignored: FileNotFoundException) {
        }
        try {
            onError.invoke()
        } catch (ignored: Exception) {
        }
        exitProcess(1)
    }

}

class AcraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(ExceptionHandler(filesDir.resolve("last_error")) {
            val intent = context!!.packageManager.getLaunchIntentForPackage(context!!.packageName)
            startActivity(Intent.makeRestartActivityTask(intent!!.component))
        })
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base

    }

    companion object {
        /** Use to get activity from Context */
        tailrec fun Context.getActivity(): Activity? = this as? Activity
            ?: (this as? ContextWrapper)?.baseContext?.getActivity()

        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            private set(value) {
                _context = WeakReference(value)
            }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /**
         * If fallbackWebview is true and a fragment is supplied then it will open a webview with the url if the browser fails.
         * */
        fun openBrowser(url: String, fallbackWebview: Boolean = false, fragment: Fragment? = null) {
            context?.openBrowser(url, fallbackWebview, fragment)
        }

        /** Will fallback to webview if in TV layout */
        fun openBrowser(url: String, activity: FragmentActivity?) {
            openBrowser(
                url,
                isTvSettings(),
                activity?.supportFragmentManager?.fragments?.lastOrNull()
            )
        }

    }
}