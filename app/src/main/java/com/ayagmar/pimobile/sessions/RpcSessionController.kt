@file:Suppress("TooManyFunctions")

package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.PiRpcConnection
import com.ayagmar.pimobile.corenet.PiRpcConnectionConfig
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corerpc.AbortBashCommand
import com.ayagmar.pimobile.corerpc.AbortCommand
import com.ayagmar.pimobile.corerpc.AgentEndEvent
import com.ayagmar.pimobile.corerpc.AgentStartEvent
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashCommand
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.CompactCommand
import com.ayagmar.pimobile.corerpc.CycleModelCommand
import com.ayagmar.pimobile.corerpc.CycleThinkingLevelCommand
import com.ayagmar.pimobile.corerpc.ExportHtmlCommand
import com.ayagmar.pimobile.corerpc.ExtensionUiResponseCommand
import com.ayagmar.pimobile.corerpc.FollowUpCommand
import com.ayagmar.pimobile.corerpc.ForkCommand
import com.ayagmar.pimobile.corerpc.GetAvailableModelsCommand
import com.ayagmar.pimobile.corerpc.GetCommandsCommand
import com.ayagmar.pimobile.corerpc.GetForkMessagesCommand
import com.ayagmar.pimobile.corerpc.GetSessionStatsCommand
import com.ayagmar.pimobile.corerpc.ImagePayload
import com.ayagmar.pimobile.corerpc.NewSessionCommand
import com.ayagmar.pimobile.corerpc.PromptCommand
import com.ayagmar.pimobile.corerpc.RpcCommand
import com.ayagmar.pimobile.corerpc.RpcIncomingMessage
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.corerpc.SetAutoCompactionCommand
import com.ayagmar.pimobile.corerpc.SetAutoRetryCommand
import com.ayagmar.pimobile.corerpc.SetModelCommand
import com.ayagmar.pimobile.corerpc.SetSessionNameCommand
import com.ayagmar.pimobile.corerpc.SteerCommand
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

@Suppress("TooManyFunctions")
class RpcSessionController(
    private val connectionFactory: () -> PiRpcConnection = { PiRpcConnection() },
    private val connectTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : SessionController {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _rpcEvents = MutableSharedFlow<RpcIncomingMessage>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _isStreaming = MutableStateFlow(false)

    private var activeConnection: PiRpcConnection? = null
    private var clientId: String = UUID.randomUUID().toString()
    private var rpcEventsJob: Job? = null
    private var connectionStateJob: Job? = null
    private var streamingMonitorJob: Job? = null

    override val rpcEvents: SharedFlow<RpcIncomingMessage> = _rpcEvents
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

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

    override suspend fun getState(): Result<RpcResponse> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                connection.requestState().requireSuccess("Failed to load state")
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

    override suspend fun forkSessionFromEntryId(entryId: String): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                forkWithEntryId(connection, entryId)
            }
        }
    }

    override suspend fun getForkMessages(): Result<List<ForkableMessage>> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetForkMessagesCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_FORK_MESSAGES_COMMAND,
                    ).requireSuccess("Failed to load fork messages")

                parseForkableMessages(response.data)
            }
        }
    }

    private suspend fun forkWithEntryId(
        connection: PiRpcConnection,
        entryId: String,
    ): String? {
        val forkResponse =
            sendAndAwaitResponse(
                connection = connection,
                requestTimeoutMs = requestTimeoutMs,
                command =
                    ForkCommand(
                        id = UUID.randomUUID().toString(),
                        entryId = entryId,
                    ),
                expectedCommand = FORK_COMMAND,
            ).requireSuccess("Failed to fork session")

        val cancelled = forkResponse.data.booleanField("cancelled") ?: false
        check(!cancelled) {
            "Fork was cancelled"
        }

        return refreshCurrentSessionPath(connection)
    }

    override suspend fun sendPrompt(
        message: String,
        images: List<ImagePayload>,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val isCurrentlyStreaming = _isStreaming.value
                val command =
                    PromptCommand(
                        id = UUID.randomUUID().toString(),
                        message = message,
                        images = images,
                        streamingBehavior = if (isCurrentlyStreaming) "steer" else null,
                    )
                connection.sendCommand(command)
            }
        }
    }

    override suspend fun abort(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val command = AbortCommand(id = UUID.randomUUID().toString())
                connection.sendCommand(command)
            }
        }
    }

    override suspend fun steer(message: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val command =
                    SteerCommand(
                        id = UUID.randomUUID().toString(),
                        message = message,
                    )
                connection.sendCommand(command)
            }
        }
    }

    override suspend fun followUp(message: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val command =
                    FollowUpCommand(
                        id = UUID.randomUUID().toString(),
                        message = message,
                    )
                connection.sendCommand(command)
            }
        }
    }

    override suspend fun cycleModel(): Result<ModelInfo?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = CycleModelCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = CYCLE_MODEL_COMMAND,
                    ).requireSuccess("Failed to cycle model")

                parseModelInfo(response.data)
            }
        }
    }

    override suspend fun cycleThinkingLevel(): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = CycleThinkingLevelCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = CYCLE_THINKING_COMMAND,
                    ).requireSuccess("Failed to cycle thinking level")

                response.data?.stringField("level")
            }
        }
    }

    override suspend fun sendExtensionUiResponse(
        requestId: String,
        value: String?,
        confirmed: Boolean?,
        cancelled: Boolean?,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val command =
                    ExtensionUiResponseCommand(
                        id = requestId,
                        value = value,
                        confirmed = confirmed,
                        cancelled = cancelled,
                    )
                connection.sendCommand(command)
            }
        }
    }

    override suspend fun newSession(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = NewSessionCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = NEW_SESSION_COMMAND,
                ).requireSuccess("Failed to create new session")
                Unit
            }
        }
    }

    override suspend fun getCommands(): Result<List<SlashCommandInfo>> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetCommandsCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_COMMANDS_COMMAND,
                    ).requireSuccess("Failed to load commands")

                parseSlashCommands(response.data)
            }
        }
    }

    override suspend fun executeBash(
        command: String,
        timeoutMs: Int?,
    ): Result<BashResult> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bashCommand =
                    BashCommand(
                        id = UUID.randomUUID().toString(),
                        command = command,
                        timeoutMs = timeoutMs,
                    )
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = timeoutMs?.toLong() ?: BASH_TIMEOUT_MS,
                        command = bashCommand,
                        expectedCommand = BASH_COMMAND,
                    ).requireSuccess("Failed to execute bash command")

                parseBashResult(response.data)
            }
        }
    }

    override suspend fun abortBash(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = AbortBashCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = ABORT_BASH_COMMAND,
                ).requireSuccess("Failed to abort bash command")
                Unit
            }
        }
    }

    override suspend fun getSessionStats(): Result<SessionStats> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetSessionStatsCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_SESSION_STATS_COMMAND,
                    ).requireSuccess("Failed to get session stats")

                parseSessionStats(response.data)
            }
        }
    }

    override suspend fun getAvailableModels(): Result<List<AvailableModel>> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetAvailableModelsCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_AVAILABLE_MODELS_COMMAND,
                    ).requireSuccess("Failed to get available models")

                parseAvailableModels(response.data)
            }
        }
    }

    override suspend fun setModel(
        provider: String,
        modelId: String,
    ): Result<ModelInfo?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command =
                            SetModelCommand(
                                id = UUID.randomUUID().toString(),
                                provider = provider,
                                modelId = modelId,
                            ),
                        expectedCommand = SET_MODEL_COMMAND,
                    ).requireSuccess("Failed to set model")

                parseModelInfo(response.data)
            }
        }
    }

    override suspend fun setAutoCompaction(enabled: Boolean): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetAutoCompactionCommand(
                            id = UUID.randomUUID().toString(),
                            enabled = enabled,
                        ),
                    expectedCommand = SET_AUTO_COMPACTION_COMMAND,
                ).requireSuccess("Failed to set auto-compaction")
                Unit
            }
        }
    }

    override suspend fun setAutoRetry(enabled: Boolean): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetAutoRetryCommand(
                            id = UUID.randomUUID().toString(),
                            enabled = enabled,
                        ),
                    expectedCommand = SET_AUTO_RETRY_COMMAND,
                ).requireSuccess("Failed to set auto-retry")
                Unit
            }
        }
    }

    private suspend fun clearActiveConnection() {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()
        streamingMonitorJob?.cancel()
        rpcEventsJob = null
        connectionStateJob = null
        streamingMonitorJob = null

        activeConnection?.disconnect()
        activeConnection = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _isStreaming.value = false
    }

    private fun observeConnection(connection: PiRpcConnection) {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()
        streamingMonitorJob?.cancel()

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

        streamingMonitorJob =
            scope.launch {
                connection.rpcEvents.collect { event ->
                    when (event) {
                        is AgentStartEvent -> _isStreaming.value = true
                        is AgentEndEvent -> _isStreaming.value = false
                        else -> Unit
                    }
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
        private const val CYCLE_MODEL_COMMAND = "cycle_model"
        private const val CYCLE_THINKING_COMMAND = "cycle_thinking_level"
        private const val NEW_SESSION_COMMAND = "new_session"
        private const val GET_COMMANDS_COMMAND = "get_commands"
        private const val BASH_COMMAND = "bash"
        private const val ABORT_BASH_COMMAND = "abort_bash"
        private const val GET_SESSION_STATS_COMMAND = "get_session_stats"
        private const val GET_AVAILABLE_MODELS_COMMAND = "get_available_models"
        private const val SET_MODEL_COMMAND = "set_model"
        private const val SET_AUTO_COMPACTION_COMMAND = "set_auto_compaction"
        private const val SET_AUTO_RETRY_COMMAND = "set_auto_retry"
        private const val EVENT_BUFFER_CAPACITY = 256
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val BASH_TIMEOUT_MS = 60_000L
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

private fun parseForkableMessages(data: JsonObject?): List<ForkableMessage> {
    val messages = runCatching { data?.get("messages")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return messages.mapNotNull { messageElement ->
        val messageObject = messageElement.jsonObject
        val entryId = messageObject.stringField("entryId") ?: return@mapNotNull null
        val preview = messageObject.stringField("preview") ?: "(no preview)"
        val timestamp = messageObject["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        ForkableMessage(
            entryId = entryId,
            preview = preview,
            timestamp = timestamp,
        )
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

private fun parseModelInfo(data: JsonObject?): ModelInfo? {
    return data?.let {
        it["model"]?.jsonObject?.let { model ->
            ModelInfo(
                id = model.stringField("id") ?: "unknown",
                name = model.stringField("name") ?: "Unknown Model",
                provider = model.stringField("provider") ?: "unknown",
                thinkingLevel = data.stringField("thinkingLevel") ?: "off",
            )
        }
    }
}

private fun parseSlashCommands(data: JsonObject?): List<SlashCommandInfo> {
    val commands = runCatching { data?.get("commands")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return commands.mapNotNull { commandElement ->
        val commandObject = commandElement.jsonObject
        val name = commandObject.stringField("name") ?: return@mapNotNull null
        SlashCommandInfo(
            name = name,
            description = commandObject.stringField("description"),
            source = commandObject.stringField("source") ?: "unknown",
            location = commandObject.stringField("location"),
            path = commandObject.stringField("path"),
        )
    }
}

private fun parseBashResult(data: JsonObject?): BashResult {
    return BashResult(
        output = data?.stringField("output") ?: "",
        exitCode = data?.get("exitCode")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1,
        wasTruncated = data?.booleanField("wasTruncated") ?: false,
        fullLogPath = data?.stringField("fullLogPath"),
    )
}

@Suppress("MagicNumber")
private fun parseSessionStats(data: JsonObject?): SessionStats {
    return SessionStats(
        inputTokens = data?.longField("inputTokens") ?: 0L,
        outputTokens = data?.longField("outputTokens") ?: 0L,
        cacheReadTokens = data?.longField("cacheReadTokens") ?: 0L,
        cacheWriteTokens = data?.longField("cacheWriteTokens") ?: 0L,
        totalCost = data?.doubleField("totalCost") ?: 0.0,
        messageCount = data?.intField("messageCount") ?: 0,
        userMessageCount = data?.intField("userMessageCount") ?: 0,
        assistantMessageCount = data?.intField("assistantMessageCount") ?: 0,
        toolResultCount = data?.intField("toolResultCount") ?: 0,
        sessionPath = data?.stringField("sessionPath"),
    )
}

private fun parseAvailableModels(data: JsonObject?): List<AvailableModel> {
    val models = runCatching { data?.get("models")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return models.mapNotNull { modelElement ->
        val modelObject = modelElement.jsonObject
        val id = modelObject.stringField("id") ?: return@mapNotNull null
        AvailableModel(
            id = id,
            name = modelObject.stringField("name") ?: id,
            provider = modelObject.stringField("provider") ?: "unknown",
            contextWindow = modelObject.intField("contextWindow"),
            maxOutputTokens = modelObject.intField("maxOutputTokens"),
            supportsThinking = modelObject.booleanField("supportsThinking") ?: false,
            inputCostPer1k = modelObject.doubleField("inputCostPer1k"),
            outputCostPer1k = modelObject.doubleField("outputCostPer1k"),
        )
    }
}

private fun JsonObject?.longField(fieldName: String): Long? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toLongOrNull()
}

private fun JsonObject?.intField(fieldName: String): Int? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toIntOrNull()
}

private fun JsonObject?.doubleField(fieldName: String): Double? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toDoubleOrNull()
}
