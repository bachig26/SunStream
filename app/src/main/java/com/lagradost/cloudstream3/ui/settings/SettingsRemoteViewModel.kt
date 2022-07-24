package com.lagradost.cloudstream3.ui.settings

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.utils.Coroutines.main
import io.ktor.http.*
import kotlinx.coroutines.launch

class SettingsRemoteViewModel : ViewModel()  {

    fun remoteEvent(server: String, Event: CSPlayerEvent) {
        val updatedQuery = server + "/player?action=" + Event.value.toString()

        viewModelScope.launch {
            val output = app.get(updatedQuery).code
            onResult(output)
        }
    }

    private fun onResult(action: Int){
        if (action == HttpStatusCode.OK.value) {
            main {
                Toast.makeText(context, "Sent", Toast.LENGTH_SHORT)
            }
            return
        }
        if (action == HttpStatusCode.NotFound.value) {
            main {
                Toast.makeText(context, R.string.device_not_found, Toast.LENGTH_LONG)
            }

        } else {
            main {
                Toast.makeText(context, "Error: $action", Toast.LENGTH_SHORT)
            }
        }
    }
}