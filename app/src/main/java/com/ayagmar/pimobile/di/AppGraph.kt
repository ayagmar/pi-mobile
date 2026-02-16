package com.ayagmar.pimobile.di

import android.content.Context
import com.ayagmar.pimobile.coresessions.FileSessionIndexCache
import com.ayagmar.pimobile.coresessions.SessionIndexRepository
import com.ayagmar.pimobile.hosts.ConnectionDiagnostics
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.hosts.KeystoreHostTokenStore
import com.ayagmar.pimobile.hosts.SharedPreferencesHostProfileStore
import com.ayagmar.pimobile.sessions.BridgeSessionIndexRemoteDataSource
import com.ayagmar.pimobile.sessions.RpcSessionController
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionCwdPreferenceStore
import com.ayagmar.pimobile.sessions.SharedPreferencesSessionCwdPreferenceStore

class AppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    val sessionController: SessionController by lazy { RpcSessionController() }

    val sessionCwdPreferenceStore: SessionCwdPreferenceStore by lazy {
        SharedPreferencesSessionCwdPreferenceStore(appContext)
    }

    val hostProfileStore: HostProfileStore by lazy {
        SharedPreferencesHostProfileStore(appContext)
    }

    val hostTokenStore: HostTokenStore by lazy {
        KeystoreHostTokenStore(appContext)
    }

    val sessionIndexRepository: SessionIndexRepository by lazy {
        SessionIndexRepository(
            remoteDataSource = BridgeSessionIndexRemoteDataSource(hostProfileStore, hostTokenStore),
            cache = FileSessionIndexCache(appContext.cacheDir.toPath().resolve("session-index-cache")),
        )
    }

    val connectionDiagnostics: ConnectionDiagnostics by lazy { ConnectionDiagnostics() }
}
