package com.lagradost.cloudstream3.utils

import android.app.ProgressDialog.show
import android.os.Looper
import android.provider.Settings.Global.getString
import android.widget.Toast
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.utils.Coroutines.main
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

object LazyRemoteServer {


    fun server() {
        try {
            embeddedServer(Netty, port = 5050) {
                routing {
                    get("/player") {
                        val action = call.request.queryParameters["action"]?.toIntOrNull()
                        val event = CSPlayerEvent.values()
                            .firstOrNull { it.value == action } // find event by value
                        if (event != null) {
                            main {
                                CS3IPlayer.requestFromRemote?.invoke(event) // invoke event
                            }
                            call.response.status(HttpStatusCode.OK)
                        } else {
                            call.response.status(HttpStatusCode.BadRequest)
                        }
                    }


                    /* // TODO add keyboad to search from phone
                get("/keyboad") {
                    val action = call.request.queryParameters["content"]


                }

                 */


                    /*
                get("/info/") {
                    //addBanana()
                    call.respondText("version 0.1")
                }
                */

                }
            }.start(wait = true)
        } catch (e: Exception) {
            main {
                val errorCantStartServer = Toast.makeText(context, R.string.error_cant_start_server, Toast.LENGTH_LONG)
                errorCantStartServer.show()
            }
        }
    }
}