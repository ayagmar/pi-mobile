package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.PiRpcConnection
import com.ayagmar.pimobile.corenet.PiRpcConnectionConfig
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corerpc.CompactCommand
import com.ayagmar.pimobile.corerpc.ExportHtmlCommand
import com.ayagmar.pimobile.corerpc.ForkCommand
import com.ayagmar.pimobile.corerpc.GetForkMessagesCommand
import com.ayagmar.pimobile.corerpc.RpcCommand
import com.ayagmar.pimobile.corerpc.RpcIncomingMessage
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SetSessionNameCommand
import com.ayagmar.pimobile.corerpc.SwitchSessionCommand
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

interface SessionController {
    val rpcEvents: SharedFlow<RpcIncomingMessage>
    val connectionState: StateFlow<ConnectionState>

    suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?>

    suspend fun getMessages(): Result<RpcResponse>

    suspend fun renameSession(name: String): Result<String?>

    suspend fun compactSession(): Result<String?>

    suspend fun exportSession(): Result<String>

    suspend fun forkSessionFromLatestMessage(): Result<String?>
}

class RpcSessionController(
    private val connectionFactory: () -> PiRpcConnection = { PiRpcConnection() },
    private val connectTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : SessionController {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _rpcEvents = MutableSharedFlow<RpcIncomingMessage>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    private var activeConnection: PiRpcConnection? = null
    private var clientId: String = UUID.randomUUID().toString()
    private var rpcEventsJob: Job? = null
    private var connectionStateJob: Job? = null

    override val rpcEvents: SharedFlow<RpcIncomingMessage> = _rpcEvents
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?> {
        return mutex.withLock {
            runCatching {
                clearActiveConnection()

                val nextConnection = connectionFactory()

                val config =
                    PiRpcConnectionConfig(
                        target =
                            WebSocketTarget(
                                url = hostProfile.endpoint,
                                headers = mapOf(AUTHORIZATION_HEADER to "Bearer $token"),
                                connectTimeoutMs = connectTimeoutMs,
                            ),
                        cwd = session.cwd,
                        sessionPath = session.sessionPath,
                        clientId = clientId,
                        connectTimeoutMs = connectTimeoutMs,
                        requestTimeoutMs = requestTimeoutMs,
                    )

                runCatching {
                    nextConnection.connect(config)
                    sendAndAwaitResponse(
                        connection = nextConnection,
                        requestTimeoutMs = requestTimeoutMs,
                        command =
                            SwitchSessionCommand(
                                id = UUID.randomUUID().toString(),
                                sessionPath = session.sessionPath,
                            ),
                        expectedCommand = SWITCH_SESSION_COMMAND,
                    ).requireSuccess("Failed to resume selected session")
                }.onFailure {
                    runCatching { nextConnection.disconnect() }
                }.getOrThrow()

                activeConnection = nextConnection
                observeConnection(nextConnection)
                refreshCurrentSessionPath(nextConnection)
            }
        }
    }

    override suspend fun getMessages(): Result<RpcResponse> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                connection.requestMessages().requireSuccess("Failed to load messages")
            }
        }
    }

    override suspend fun renameSession(name: String): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = SetSessionNameCommand(id = UUID.randomUUID().toString(), name = name),
                    expectedCommand = SET_SESSION_NAME_COMMAND,
                ).requireSuccess("Failed to rename session")

                refreshCurrentSessionPath(connection)
            }
        }
    }

    override suspend fun compactSession(): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = CompactCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = COMPACT_COMMAND,
                ).requireSuccess("Failed to compact session")

                refreshCurrentSessionPath(connection)
            }
        }
    }

    override suspend fun exportSession(): Result<String> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = ExportHtmlCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = EXPORT_HTML_COMMAND,
                    ).requireSuccess("Failed to export session")

                response.data.stringField("path") ?: error("Export succeeded but did not return output path")
            }
        }
    }

    override suspend fun forkSessionFromLatestMessage(): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val forkMessagesResponse =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetForkMessagesCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_FORK_MESSAGES_COMMAND,
                    ).requireSuccess("Failed to load fork messages")

                val latestEntryId =
                    parseForkEntryIds(forkMessagesResponse.data).lastOrNull()
                        ?: error("No user messages available for fork")

                val forkResponse =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command =
                            ForkCommand(
                                id = UUID.randomUUID().toString(),
                                entryId = latestEntryId,
                            ),
                        expectedCommand = FORK_COMMAND,
                    ).requireSuccess("Failed to fork session")

                val cancelled = forkResponse.data.booleanField("cancelled") ?: false
                check(!cancelled) {
                    "Fork was cancelled"
                }

                refreshCurrentSessionPath(connection)
            }
        }
    }

    private suspend fun clearActiveConnection() {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()
        rpcEventsJob = null
        connectionStateJob = null

        activeConnection?.disconnect()
        activeConnection = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun observeConnection(connection: PiRpcConnection) {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()

        rpcEventsJob =
            scope.launch {
                connection.rpcEvents.collect { event ->
                    _rpcEvents.emit(event)
                }
            }

        connectionStateJob =
            scope.launch {
                connection.connectionState.collect { state ->
                    _connectionState.value = state
                }
            }
    }

    private fun ensureActiveConnection(): PiRpcConnection {
        return requireNotNull(activeConnection) {
            "No active session. Resume a session first."
        }
    }

    private suspend fun refreshCurrentSessionPath(connection: PiRpcConnection): String? {
        val stateResponse = connection.requestState().requireSuccess("Failed to read connection state")
        return stateResponse.data.stringField("sessionFile")
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val SWITCH_SESSION_COMMAND = "switch_session"
        private const val SET_SESSION_NAME_COMMAND = "set_session_name"
        private const val COMPACT_COMMAND = "compact"
        private const val EXPORT_HTML_COMMAND = "export_html"
        private const val GET_FORK_MESSAGES_COMMAND = "get_fork_messages"
        private const val FORK_COMMAND = "fork"
        private const val EVENT_BUFFER_CAPACITY = 256
        private const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

private suspend fun sendAndAwaitResponse(
    connection: PiRpcConnection,
    requestTimeoutMs: Long,
    command: RpcCommand,
    expectedCommand: String,
): RpcResponse {
    val commandId = requireNotNull(command.id) { "RPC command id is required" }

    return coroutineScope {
        val responseDeferred =
            async {
                connection.rpcEvents
                    .filterIsInstance<RpcResponse>()
                    .first { response ->
                        response.id == commandId && response.command == expectedCommand
                    }
            }

        connection.sendCommand(command)

        withTimeout(requestTimeoutMs) {
            responseDeferred.await()
        }
    }
}

private fun RpcResponse.requireSuccess(defaultError: String): RpcResponse {
    check(success) {
        error ?: defaultError
    }

    return this
}

private fun parseForkEntryIds(data: JsonObject?): List<String> {
    val messages = runCatching { data?.get("messages")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return messages.mapNotNull { messageElement ->
        val messageObject = messageElement.jsonObject
        messageObject.stringField("entryId")
    }
}

private fun JsonObject?.stringField(fieldName: String): String? {
    val jsonObject = this ?: return null
    return jsonObject[fieldName]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject?.booleanField(fieldName: String): Boolean? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toBooleanStrictOrNull()
}
