package com.ayagmar.pimobile.di

import com.ayagmar.pimobile.sessions.RpcSessionController
import com.ayagmar.pimobile.sessions.SessionController

object AppServices {
    private val lock = Any()
    private var sessionControllerInstance: SessionController? = null

    fun sessionController(): SessionController {
        return synchronized(lock) {
            sessionControllerInstance
                ?: RpcSessionController().also { created ->
                    sessionControllerInstance = created
                }
        }
    }
}
