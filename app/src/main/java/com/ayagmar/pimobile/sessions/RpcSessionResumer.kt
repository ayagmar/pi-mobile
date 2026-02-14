package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corenet.PiRpcConnection
import com.ayagmar.pimobile.corenet.PiRpcConnectionConfig
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SwitchSessionCommand
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

interface SessionResumer {
    suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<Unit>
}

class RpcSessionResumer(
    private val connectionFactory: () -> PiRpcConnection = { PiRpcConnection() },
    private val connectTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : SessionResumer {
    private val mutex = Mutex()
    private var activeConnection: PiRpcConnection? = null
    private var clientId: String = UUID.randomUUID().toString()

    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                activeConnection?.disconnect()

                val nextConnection = connectionFactory()

                val target =
                    WebSocketTarget(
                        url = hostProfile.endpoint,
                        headers = mapOf(AUTHORIZATION_HEADER to "Bearer $token"),
                        connectTimeoutMs = connectTimeoutMs,
                    )

                val config =
                    PiRpcConnectionConfig(
                        target = target,
                        cwd = session.cwd,
                        sessionPath = session.sessionPath,
                        clientId = clientId,
                        connectTimeoutMs = connectTimeoutMs,
                        requestTimeoutMs = requestTimeoutMs,
                    )

                val connectResult =
                    runCatching {
                        nextConnection.connect(config)

                        val switchCommandId = UUID.randomUUID().toString()
                        val switchResponse =
                            awaitSwitchSessionResponse(
                                connection = nextConnection,
                                sessionPath = session.sessionPath,
                                switchCommandId = switchCommandId,
                            )

                        check(switchResponse.success) {
                            switchResponse.error ?: "Failed to resume selected session"
                        }
                    }

                if (connectResult.isFailure) {
                    runCatching {
                        nextConnection.disconnect()
                    }
                    connectResult.getOrThrow()
                }

                activeConnection = nextConnection
            }
        }
    }

    private suspend fun awaitSwitchSessionResponse(
        connection: PiRpcConnection,
        sessionPath: String,
        switchCommandId: String,
    ): RpcResponse {
        return coroutineScope {
            val responseDeferred =
                async {
                    connection.rpcEvents
                        .filterIsInstance<RpcResponse>()
                        .first { response ->
                            response.id == switchCommandId && response.command == SWITCH_SESSION_COMMAND
                        }
                }

            connection.sendCommand(
                SwitchSessionCommand(
                    id = switchCommandId,
                    sessionPath = sessionPath,
                ),
            )

            withTimeout(requestTimeoutMs) {
                responseDeferred.await()
            }
        }
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val SWITCH_SESSION_COMMAND = "switch_session"
        private const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
