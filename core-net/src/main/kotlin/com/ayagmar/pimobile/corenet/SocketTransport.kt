package com.ayagmar.pimobile.corenet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SocketTransport {
    val inboundMessages: Flow<String>
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(target: WebSocketTarget)

    suspend fun reconnect()

    suspend fun disconnect()

    suspend fun send(message: String)
}
