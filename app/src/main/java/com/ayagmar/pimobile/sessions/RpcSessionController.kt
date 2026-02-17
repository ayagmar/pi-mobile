package com.ayagmar.pimobile.sessions

import android.util.Log
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.PiRpcConnection
import com.ayagmar.pimobile.corenet.PiRpcConnectionConfig
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corerpc.AbortBashCommand
import com.ayagmar.pimobile.corerpc.AbortCommand
import com.ayagmar.pimobile.corerpc.AbortRetryCommand
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
import com.ayagmar.pimobile.corerpc.SetFollowUpModeCommand
import com.ayagmar.pimobile.corerpc.SetModelCommand
import com.ayagmar.pimobile.corerpc.SetSessionNameCommand
import com.ayagmar.pimobile.corerpc.SetSteeringModeCommand
import com.ayagmar.pimobile.corerpc.SetThinkingLevelCommand
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("TooManyFunctions", "LargeClass")
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
    private val _sessionChanged = MutableSharedFlow<String?>(extraBufferCapacity = 16)

    private var activeConnection: PiRpcConnection? = null
    private var activeContext: ActiveConnectionContext? = null
    private var transportPreference: TransportPreference = TransportPreference.AUTO
    private val clientId: String = UUID.randomUUID().toString()
    private var rpcEventsJob: Job? = null
    private var connectionStateJob: Job? = null
    private var streamingMonitorJob: Job? = null
    private var resyncMonitorJob: Job? = null

    override val rpcEvents: SharedFlow<RpcIncomingMessage> = _rpcEvents
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    override val sessionChanged: SharedFlow<String?> = _sessionChanged

    override fun setTransportPreference(preference: TransportPreference) {
        transportPreference = preference
    }

    override fun getTransportPreference(): TransportPreference = transportPreference

    override fun getEffectiveTransportPreference(): TransportPreference {
        return resolveEffectiveTransport(transportPreference)
    }

    override suspend fun ensureConnected(
        hostProfile: HostProfile,
        token: String,
        cwd: String,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                ensureConnectionLocked(
                    hostProfile = hostProfile,
                    token = token,
                    cwd = cwd,
                )
                Unit
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                clearActiveConnection()
                Unit
            }
        }
    }

    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection =
                    ensureConnectionLocked(
                        hostProfile = hostProfile,
                        token = token,
                        cwd = session.cwd,
                    )

                if (session.sessionPath.isNotBlank()) {
                    val switchResponse =
                        sendAndAwaitResponse(
                            connection = connection,
                            requestTimeoutMs = requestTimeoutMs,
                            command =
                                SwitchSessionCommand(
                                    id = UUID.randomUUID().toString(),
                                    sessionPath = session.sessionPath,
                                ),
                            expectedCommand = SWITCH_SESSION_COMMAND,
                        ).requireSuccess("Failed to resume selected session")

                    switchResponse.requireNotCancelled("Session switch was cancelled")
                }

                val newPath = refreshCurrentSessionPath(connection)
                _sessionChanged.emit(newPath)
                newPath
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

    override suspend fun getSessionTree(
        sessionPath: String?,
        filter: String?,
    ): Result<SessionTreeSnapshot> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bridgePayload =
                    buildJsonObject {
                        put("type", BRIDGE_GET_SESSION_TREE_TYPE)
                        if (!sessionPath.isNullOrBlank()) {
                            put("sessionPath", sessionPath)
                        }
                        if (!filter.isNullOrBlank()) {
                            put("filter", filter)
                        }
                    }

                val bridgeResponse = connection.requestBridge(bridgePayload, BRIDGE_SESSION_TREE_TYPE)
                parseSessionTreeSnapshot(bridgeResponse.payload)
            }
        }
    }

    override suspend fun getSessionFreshness(sessionPath: String): Result<SessionFreshnessSnapshot> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bridgePayload =
                    buildJsonObject {
                        put("type", BRIDGE_GET_SESSION_FRESHNESS_TYPE)
                        put("sessionPath", sessionPath)
                    }

                val bridgeResponse =
                    connection.requestBridge(
                        payload = bridgePayload,
                        expectedType = BRIDGE_SESSION_FRESHNESS_TYPE,
                    )

                parseSessionFreshnessSnapshot(bridgeResponse.payload)
            }
        }
    }

    override suspend fun navigateTreeToEntry(entryId: String): Result<TreeNavigationResult> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bridgePayload =
                    buildJsonObject {
                        put("type", BRIDGE_NAVIGATE_TREE_TYPE)
                        put("entryId", entryId)
                    }

                val bridgeResponse =
                    connection.requestBridge(
                        payload = bridgePayload,
                        expectedType = BRIDGE_TREE_NAVIGATION_RESULT_TYPE,
                    )

                parseTreeNavigationResult(bridgeResponse.payload)
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

        val newPath = refreshCurrentSessionPath(connection)
        _sessionChanged.emit(newPath)
        return newPath
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

                val shouldMarkStreaming = !isCurrentlyStreaming
                if (shouldMarkStreaming) {
                    _isStreaming.value = true
                }

                runCatching {
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = command,
                        expectedCommand = PROMPT_COMMAND,
                    ).requireSuccess("Failed to send prompt")
                    Unit
                }.onFailure {
                    if (shouldMarkStreaming) {
                        _isStreaming.value = false
                    }
                }.getOrThrow()
            }
        }
    }

    override suspend fun abort(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = AbortCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = ABORT_COMMAND,
                ).requireSuccess("Failed to abort")
                Unit
            }
        }
    }

    override suspend fun steer(message: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SteerCommand(
                            id = UUID.randomUUID().toString(),
                            message = message,
                        ),
                    expectedCommand = STEER_COMMAND,
                ).requireSuccess("Failed to steer")
                Unit
            }
        }
    }

    override suspend fun followUp(message: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        FollowUpCommand(
                            id = UUID.randomUUID().toString(),
                            message = message,
                        ),
                    expectedCommand = FOLLOW_UP_COMMAND,
                ).requireSuccess("Failed to queue follow-up")
                Unit
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

    override suspend fun setThinkingLevel(level: String): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = SetThinkingLevelCommand(id = UUID.randomUUID().toString(), level = level),
                        expectedCommand = SET_THINKING_LEVEL_COMMAND,
                    ).requireSuccess("Failed to set thinking level")

                response.data?.stringField("level") ?: level
            }
        }
    }

    override suspend fun abortRetry(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = AbortRetryCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = ABORT_RETRY_COMMAND,
                ).requireSuccess("Failed to abort retry")
                Unit
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
                val newSessionResponse =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = NewSessionCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = NEW_SESSION_COMMAND,
                    ).requireSuccess("Failed to create new session")

                newSessionResponse.requireNotCancelled("New session was cancelled")

                val newPath = refreshCurrentSessionPath(connection)
                _sessionChanged.emit(newPath)
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

                // set_model returns the model object directly (without thinkingLevel).
                // Refresh state to get the effective thinking level.
                val refreshedState = connection.requestState().requireSuccess("Failed to refresh state after set_model")
                parseModelInfo(refreshedState.data) ?: parseModelInfo(response.data)
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

    override suspend fun setSteeringMode(mode: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetSteeringModeCommand(
                            id = UUID.randomUUID().toString(),
                            mode = mode,
                        ),
                    expectedCommand = SET_STEERING_MODE_COMMAND,
                ).requireSuccess("Failed to set steering mode")
                Unit
            }
        }
    }

    override suspend fun setFollowUpMode(mode: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetFollowUpModeCommand(
                            id = UUID.randomUUID().toString(),
                            mode = mode,
                        ),
                    expectedCommand = SET_FOLLOW_UP_MODE_COMMAND,
                ).requireSuccess("Failed to set follow-up mode")
                Unit
            }
        }
    }

    private suspend fun ensureConnectionLocked(
        hostProfile: HostProfile,
        token: String,
        cwd: String,
    ): PiRpcConnection {
        val normalizedCwd = cwd.trim()
        require(normalizedCwd.isNotBlank()) { "cwd must not be blank" }

        val currentConnection = activeConnection
        val currentContext = activeContext
        val shouldReuse =
            currentConnection != null &&
                currentContext != null &&
                currentContext.matches(hostProfile = hostProfile, token = token, cwd = normalizedCwd) &&
                _connectionState.value != ConnectionState.DISCONNECTED

        if (shouldReuse) {
            return requireNotNull(currentConnection)
        }

        clearActiveConnection(resetContext = false)

        val nextConnection = connectionFactory()
        val endpoint = resolveEndpointForTransport(hostProfile)
        val config =
            PiRpcConnectionConfig(
                target =
                    WebSocketTarget(
                        url = endpoint,
                        headers = mapOf(AUTHORIZATION_HEADER to "Bearer $token"),
                        connectTimeoutMs = connectTimeoutMs,
                    ),
                cwd = normalizedCwd,
                clientId = clientId,
                connectTimeoutMs = connectTimeoutMs,
                requestTimeoutMs = requestTimeoutMs,
            )

        runCatching {
            nextConnection.connect(config)
        }.onFailure {
            runCatching { nextConnection.disconnect() }
        }.getOrThrow()

        activeConnection = nextConnection
        activeContext =
            ActiveConnectionContext(
                endpoint = hostProfile.endpoint,
                token = token,
                cwd = normalizedCwd,
            )
        observeConnection(nextConnection)
        return nextConnection
    }

    private fun resolveEndpointForTransport(hostProfile: HostProfile): String {
        val effectiveTransport = resolveEffectiveTransport(transportPreference)

        if (transportPreference == TransportPreference.SSE && effectiveTransport == TransportPreference.WEBSOCKET) {
            Log.i(
                TRANSPORT_LOG_TAG,
                "SSE transport requested but bridge currently supports WebSocket only; using WebSocket fallback",
            )
        }

        return when (effectiveTransport) {
            TransportPreference.WEBSOCKET,
            TransportPreference.AUTO,
            TransportPreference.SSE,
            -> hostProfile.endpoint
        }
    }

    private fun resolveEffectiveTransport(requested: TransportPreference): TransportPreference {
        return when (requested) {
            TransportPreference.AUTO,
            TransportPreference.WEBSOCKET,
            TransportPreference.SSE,
            -> TransportPreference.WEBSOCKET
        }
    }

    private suspend fun clearActiveConnection(resetContext: Boolean = true) {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()
        streamingMonitorJob?.cancel()
        resyncMonitorJob?.cancel()
        rpcEventsJob = null
        connectionStateJob = null
        streamingMonitorJob = null
        resyncMonitorJob = null

        activeConnection?.disconnect()
        activeConnection = null
        if (resetContext) {
            activeContext = null
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        _isStreaming.value = false
    }

    private fun observeConnection(connection: PiRpcConnection) {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()
        streamingMonitorJob?.cancel()
        resyncMonitorJob?.cancel()

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

        resyncMonitorJob =
            scope.launch {
                connection.resyncEvents.collect { snapshot ->
                    val isStreaming = snapshot.stateResponse.data.booleanField("isStreaming") ?: false
                    _isStreaming.value = isStreaming
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

    private data class ActiveConnectionContext(
        val endpoint: String,
        val token: String,
        val cwd: String,
    ) {
        fun matches(
            hostProfile: HostProfile,
            token: String,
            cwd: String,
        ): Boolean {
            return endpoint == hostProfile.endpoint && this.token == token && this.cwd == cwd
        }
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val PROMPT_COMMAND = "prompt"
        private const val ABORT_COMMAND = "abort"
        private const val STEER_COMMAND = "steer"
        private const val FOLLOW_UP_COMMAND = "follow_up"
        private const val SWITCH_SESSION_COMMAND = "switch_session"
        private const val SET_SESSION_NAME_COMMAND = "set_session_name"
        private const val COMPACT_COMMAND = "compact"
        private const val EXPORT_HTML_COMMAND = "export_html"
        private const val GET_FORK_MESSAGES_COMMAND = "get_fork_messages"
        private const val FORK_COMMAND = "fork"
        private const val CYCLE_MODEL_COMMAND = "cycle_model"
        private const val CYCLE_THINKING_COMMAND = "cycle_thinking_level"
        private const val SET_THINKING_LEVEL_COMMAND = "set_thinking_level"
        private const val ABORT_RETRY_COMMAND = "abort_retry"
        private const val NEW_SESSION_COMMAND = "new_session"
        private const val GET_COMMANDS_COMMAND = "get_commands"
        private const val BASH_COMMAND = "bash"
        private const val ABORT_BASH_COMMAND = "abort_bash"
        private const val GET_SESSION_STATS_COMMAND = "get_session_stats"
        private const val GET_AVAILABLE_MODELS_COMMAND = "get_available_models"
        private const val SET_MODEL_COMMAND = "set_model"
        private const val SET_AUTO_COMPACTION_COMMAND = "set_auto_compaction"
        private const val SET_AUTO_RETRY_COMMAND = "set_auto_retry"
        private const val SET_STEERING_MODE_COMMAND = "set_steering_mode"
        private const val SET_FOLLOW_UP_MODE_COMMAND = "set_follow_up_mode"
        private const val BRIDGE_GET_SESSION_TREE_TYPE = "bridge_get_session_tree"
        private const val BRIDGE_SESSION_TREE_TYPE = "bridge_session_tree"
        private const val BRIDGE_GET_SESSION_FRESHNESS_TYPE = "bridge_get_session_freshness"
        private const val BRIDGE_SESSION_FRESHNESS_TYPE = "bridge_session_freshness"
        private const val BRIDGE_NAVIGATE_TREE_TYPE = "bridge_navigate_tree"
        private const val BRIDGE_TREE_NAVIGATION_RESULT_TYPE = "bridge_tree_navigation_result"
        private const val EVENT_BUFFER_CAPACITY = 256
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val BASH_TIMEOUT_MS = 60_000L
        private const val TRANSPORT_LOG_TAG = "RpcTransport"
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

private fun RpcResponse.requireNotCancelled(defaultError: String): RpcResponse {
    check(data.booleanField("cancelled") != true) {
        defaultError
    }

    return this
}

private fun parseForkableMessages(data: JsonObject?): List<ForkableMessage> {
    val messages = runCatching { data?.get("messages")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return messages.mapNotNull { messageElement ->
        val messageObject = messageElement.jsonObject
        val entryId = messageObject.stringField("entryId") ?: return@mapNotNull null
        // pi RPC currently returns "text" for fork messages; keep "preview" as fallback.
        val preview = messageObject.stringField("text") ?: messageObject.stringField("preview") ?: "(no preview)"
        val timestamp = messageObject["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        ForkableMessage(
            entryId = entryId,
            preview = preview,
            timestamp = timestamp,
        )
    }
}

private fun parseSessionTreeSnapshot(payload: JsonObject): SessionTreeSnapshot {
    val sessionPath = payload.stringField("sessionPath") ?: error("Session tree response missing sessionPath")
    val rootIds =
        runCatching {
            payload["rootIds"]?.jsonArray?.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull
            }
        }.getOrNull() ?: emptyList()

    val entries =
        runCatching {
            payload["entries"]?.jsonArray?.mapNotNull { element ->
                val entryObject = element.jsonObject
                val entryId = entryObject.stringField("entryId") ?: return@mapNotNull null
                SessionTreeEntry(
                    entryId = entryId,
                    parentId = entryObject.stringField("parentId"),
                    entryType = entryObject.stringField("entryType") ?: "entry",
                    role = entryObject.stringField("role"),
                    timestamp = entryObject.stringField("timestamp"),
                    preview = entryObject.stringField("preview") ?: "entry",
                    label = entryObject.stringField("label"),
                    isBookmarked = entryObject.booleanField("isBookmarked") ?: false,
                )
            }
        }.getOrNull() ?: emptyList()

    return SessionTreeSnapshot(
        sessionPath = sessionPath,
        rootIds = rootIds,
        currentLeafId = payload.stringField("currentLeafId"),
        entries = entries,
    )
}

private fun parseSessionFreshnessSnapshot(payload: JsonObject): SessionFreshnessSnapshot {
    val sessionPath = payload.stringField("sessionPath") ?: error("Session freshness response missing sessionPath")
    val cwd = payload.stringField("cwd") ?: error("Session freshness response missing cwd")

    val fingerprintPayload = runCatching { payload["fingerprint"]?.jsonObject }.getOrNull()
    val lockPayload = runCatching { payload["lock"]?.jsonObject }.getOrNull()

    val fingerprint =
        SessionFreshnessFingerprint(
            mtimeMs = fingerprintPayload.longField("mtimeMs") ?: 0L,
            sizeBytes = fingerprintPayload.longField("sizeBytes") ?: 0L,
            entryCount = fingerprintPayload.intField("entryCount") ?: 0,
            lastEntryId = fingerprintPayload.stringField("lastEntryId"),
            lastEntriesHash = fingerprintPayload.stringField("lastEntriesHash"),
        )

    val lock =
        SessionLockMetadata(
            cwdOwnerClientId = lockPayload.stringField("cwdOwnerClientId"),
            sessionOwnerClientId = lockPayload.stringField("sessionOwnerClientId"),
            isCurrentClientCwdOwner = lockPayload.booleanField("isCurrentClientCwdOwner") ?: false,
            isCurrentClientSessionOwner = lockPayload.booleanField("isCurrentClientSessionOwner") ?: false,
        )

    return SessionFreshnessSnapshot(
        sessionPath = sessionPath,
        cwd = cwd,
        fingerprint = fingerprint,
        lock = lock,
    )
}

private fun parseTreeNavigationResult(payload: JsonObject): TreeNavigationResult {
    return TreeNavigationResult(
        cancelled = payload.booleanField("cancelled") ?: false,
        editorText = payload.stringField("editorText"),
        currentLeafId = payload.stringField("currentLeafId"),
        sessionPath = payload.stringField("sessionPath"),
    )
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
    val nestedModel = data?.get("model") as? JsonObject
    val model = nestedModel ?: data?.takeIf { it.stringField("id") != null } ?: return null

    return ModelInfo(
        id = model.stringField("id") ?: "unknown",
        name = model.stringField("name") ?: "Unknown Model",
        provider = model.stringField("provider") ?: "unknown",
        thinkingLevel = data.stringField("thinkingLevel") ?: "off",
        contextWindow = model.intField("contextWindow"),
    )
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
        // pi RPC uses "truncated" and "fullOutputPath".
        wasTruncated = data?.booleanField("truncated") ?: data?.booleanField("wasTruncated") ?: false,
        fullLogPath = data?.stringField("fullOutputPath") ?: data?.stringField("fullLogPath"),
    )
}

@Suppress("MagicNumber", "LongMethod")
private fun parseSessionStats(data: JsonObject?): SessionStats {
    val tokens = data?.get("tokens")?.jsonObject

    val inputTokens =
        coalesceLong(
            tokens?.longField("input"),
            data?.longField("inputTokens"),
        )
    val outputTokens =
        coalesceLong(
            tokens?.longField("output"),
            data?.longField("outputTokens"),
        )
    val cacheReadTokens =
        coalesceLong(
            tokens?.longField("cacheRead"),
            data?.longField("cacheReadTokens"),
        )
    val cacheWriteTokens =
        coalesceLong(
            tokens?.longField("cacheWrite"),
            data?.longField("cacheWriteTokens"),
        )
    val totalCost =
        coalesceDouble(
            data?.doubleField("cost"),
            data?.doubleField("totalCost"),
        )

    val messageCount =
        coalesceInt(
            data?.intField("totalMessages"),
            data?.intField("messageCount"),
        )
    val userMessageCount =
        coalesceInt(
            data?.intField("userMessages"),
            data?.intField("userMessageCount"),
        )
    val assistantMessageCount =
        coalesceInt(
            data?.intField("assistantMessages"),
            data?.intField("assistantMessageCount"),
        )
    val toolResultCount =
        coalesceInt(
            data?.intField("toolResults"),
            data?.intField("toolResultCount"),
        )
    val sessionPath =
        coalesceString(
            data?.stringField("sessionFile"),
            data?.stringField("sessionPath"),
        )
    val compactionCount =
        coalesceInt(
            data?.intField("compactions"),
            data?.intField("compactionCount"),
            data?.intField("autoCompactions"),
        )

    return SessionStats(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        totalCost = totalCost,
        messageCount = messageCount,
        userMessageCount = userMessageCount,
        assistantMessageCount = assistantMessageCount,
        toolResultCount = toolResultCount,
        sessionPath = sessionPath,
        compactionCount = compactionCount,
    )
}

private fun parseAvailableModels(data: JsonObject?): List<AvailableModel> {
    val models = runCatching { data?.get("models")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return models.mapNotNull { modelElement ->
        val modelObject = modelElement.jsonObject
        val id = modelObject.stringField("id") ?: return@mapNotNull null
        val cost = modelObject["cost"]?.jsonObject

        AvailableModel(
            id = id,
            name = modelObject.stringField("name") ?: id,
            provider = modelObject.stringField("provider") ?: "unknown",
            contextWindow = modelObject.intField("contextWindow"),
            maxOutputTokens = modelObject.intField("maxTokens") ?: modelObject.intField("maxOutputTokens"),
            supportsThinking =
                modelObject.booleanField("reasoning")
                    ?: modelObject.booleanField("supportsThinking")
                    ?: false,
            inputCostPer1k = cost?.doubleField("input") ?: modelObject.doubleField("inputCostPer1k"),
            outputCostPer1k = cost?.doubleField("output") ?: modelObject.doubleField("outputCostPer1k"),
        )
    }
}

private fun coalesceLong(vararg values: Long?): Long {
    return values.firstOrNull { it != null } ?: 0L
}

private fun coalesceInt(vararg values: Int?): Int {
    return values.firstOrNull { it != null } ?: 0
}

private fun coalesceDouble(vararg values: Double?): Double {
    return values.firstOrNull { it != null } ?: 0.0
}

private fun coalesceString(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
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
