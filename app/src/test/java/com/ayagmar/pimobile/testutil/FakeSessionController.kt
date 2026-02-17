package com.ayagmar.pimobile.testutil

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.ImagePayload
import com.ayagmar.pimobile.corerpc.RpcIncomingMessage
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.sessions.ForkableMessage
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionFreshnessSnapshot
import com.ayagmar.pimobile.sessions.SessionTreeSnapshot
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import com.ayagmar.pimobile.sessions.TransportPreference
import com.ayagmar.pimobile.sessions.TreeNavigationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

@Suppress("TooManyFunctions")
class FakeSessionController : SessionController {
    private val events = MutableSharedFlow<RpcIncomingMessage>(extraBufferCapacity = 16)
    private val streamingState = MutableStateFlow(false)
    private val connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _sessionChanged = MutableSharedFlow<String?>(extraBufferCapacity = 16)

    var availableCommands: List<SlashCommandInfo> = emptyList()
    var getCommandsCallCount: Int = 0
    var sendPromptCallCount: Int = 0
    var getMessagesCallCount: Int = 0
    var getStateCallCount: Int = 0
    var getSessionFreshnessCallCount: Int = 0
    var lastPromptMessage: String? = null
    var lastFreshnessSessionPath: String? = null
    var sendPromptResult: Result<Unit> = Result.success(Unit)
    var sendPromptDelayMs: Long = 0L
    var abortResult: Result<Unit> = Result.success(Unit)
    var abortRetryResult: Result<Unit> = Result.success(Unit)
    var abortCallCount: Int = 0
    var abortRetryCallCount: Int = 0
    var messagesPayload: JsonObject? = null
    var sessionFreshnessResult: Result<SessionFreshnessSnapshot> =
        Result.failure(IllegalStateException("Not used"))
    var treeNavigationResult: Result<TreeNavigationResult> =
        Result.success(
            TreeNavigationResult(
                cancelled = false,
                editorText = null,
                currentLeafId = null,
                sessionPath = null,
            ),
        )
    var lastNavigatedEntryId: String? = null
    var steeringModeResult: Result<Unit> = Result.success(Unit)
    var followUpModeResult: Result<Unit> = Result.success(Unit)
    var newSessionResult: Result<Unit> = Result.success(Unit)
    var renameSessionResult: Result<String?> = Result.success(null)
    var compactSessionResult: Result<String?> = Result.success(null)
    var exportSessionResult: Result<String> = Result.success("/tmp/export.html")
    var renameSessionCallCount: Int = 0
    var compactSessionCallCount: Int = 0
    var exportSessionCallCount: Int = 0
    var newSessionCallCount: Int = 0
    var lastRenamedSessionName: String? = null
    var lastSteeringMode: String? = null
    var lastFollowUpMode: String? = null
    var lastTransportPreference: TransportPreference = TransportPreference.AUTO

    override val rpcEvents: SharedFlow<RpcIncomingMessage> = events
    override val connectionState: StateFlow<ConnectionState> = connectionStateFlow
    override val isStreaming: StateFlow<Boolean> = streamingState
    override val sessionChanged: SharedFlow<String?> = _sessionChanged

    suspend fun emitEvent(event: RpcIncomingMessage) {
        events.emit(event)
    }

    suspend fun emitSessionChanged(sessionPath: String? = null) {
        _sessionChanged.emit(sessionPath)
    }

    fun setStreaming(isStreaming: Boolean) {
        streamingState.value = isStreaming
    }

    fun setConnectionState(state: ConnectionState) {
        connectionStateFlow.value = state
    }

    override fun setTransportPreference(preference: TransportPreference) {
        lastTransportPreference = preference
    }

    override fun getTransportPreference(): TransportPreference = lastTransportPreference

    override fun getEffectiveTransportPreference(): TransportPreference = TransportPreference.WEBSOCKET

    override suspend fun ensureConnected(
        hostProfile: HostProfile,
        token: String,
        cwd: String,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?> = Result.success(null)

    override suspend fun getMessages(): Result<RpcResponse> {
        getMessagesCallCount += 1
        return Result.success(
            RpcResponse(
                type = "response",
                command = "get_messages",
                success = true,
                data = messagesPayload,
            ),
        )
    }

    override suspend fun getState(): Result<RpcResponse> {
        getStateCallCount += 1
        return Result.success(
            RpcResponse(
                type = "response",
                command = "get_state",
                success = true,
            ),
        )
    }

    override suspend fun sendPrompt(
        message: String,
        images: List<ImagePayload>,
    ): Result<Unit> {
        sendPromptCallCount += 1
        lastPromptMessage = message
        if (sendPromptDelayMs > 0) {
            delay(sendPromptDelayMs)
        }
        return sendPromptResult
    }

    override suspend fun abort(): Result<Unit> {
        abortCallCount += 1
        return abortResult
    }

    override suspend fun steer(message: String): Result<Unit> = Result.success(Unit)

    override suspend fun followUp(message: String): Result<Unit> = Result.success(Unit)

    override suspend fun renameSession(name: String): Result<String?> {
        renameSessionCallCount += 1
        lastRenamedSessionName = name
        return renameSessionResult
    }

    override suspend fun compactSession(): Result<String?> {
        compactSessionCallCount += 1
        return compactSessionResult
    }

    override suspend fun exportSession(): Result<String> {
        exportSessionCallCount += 1
        return exportSessionResult
    }

    override suspend fun forkSessionFromEntryId(entryId: String): Result<String?> = Result.success(null)

    override suspend fun getForkMessages(): Result<List<ForkableMessage>> = Result.success(emptyList())

    override suspend fun getSessionTree(
        sessionPath: String?,
        filter: String?,
    ): Result<SessionTreeSnapshot> = Result.failure(IllegalStateException("Not used"))

    override suspend fun getSessionFreshness(sessionPath: String): Result<SessionFreshnessSnapshot> {
        getSessionFreshnessCallCount += 1
        lastFreshnessSessionPath = sessionPath
        return sessionFreshnessResult
    }

    override suspend fun navigateTreeToEntry(entryId: String): Result<TreeNavigationResult> {
        lastNavigatedEntryId = entryId
        return treeNavigationResult
    }

    override suspend fun cycleModel(): Result<ModelInfo?> = Result.success(null)

    override suspend fun cycleThinkingLevel(): Result<String?> = Result.success(null)

    override suspend fun setThinkingLevel(level: String): Result<String?> = Result.success(level)

    override suspend fun abortRetry(): Result<Unit> {
        abortRetryCallCount += 1
        return abortRetryResult
    }

    override suspend fun sendExtensionUiResponse(
        requestId: String,
        value: String?,
        confirmed: Boolean?,
        cancelled: Boolean?,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun newSession(): Result<Unit> {
        newSessionCallCount += 1
        return newSessionResult
    }

    override suspend fun getCommands(): Result<List<SlashCommandInfo>> {
        getCommandsCallCount += 1
        return Result.success(availableCommands)
    }

    override suspend fun executeBash(
        command: String,
        timeoutMs: Int?,
    ): Result<BashResult> =
        Result.success(
            BashResult(
                output = "",
                exitCode = 0,
                wasTruncated = false,
            ),
        )

    override suspend fun abortBash(): Result<Unit> = Result.success(Unit)

    override suspend fun getSessionStats(): Result<SessionStats> =
        Result.success(
            SessionStats(
                inputTokens = 0,
                outputTokens = 0,
                cacheReadTokens = 0,
                cacheWriteTokens = 0,
                totalCost = 0.0,
                messageCount = 0,
                userMessageCount = 0,
                assistantMessageCount = 0,
                toolResultCount = 0,
                sessionPath = null,
            ),
        )

    override suspend fun getAvailableModels(): Result<List<AvailableModel>> = Result.success(emptyList())

    override suspend fun setModel(
        provider: String,
        modelId: String,
    ): Result<ModelInfo?> = Result.success(null)

    override suspend fun setAutoCompaction(enabled: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun setAutoRetry(enabled: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun setSteeringMode(mode: String): Result<Unit> {
        lastSteeringMode = mode
        return steeringModeResult
    }

    override suspend fun setFollowUpMode(mode: String): Result<Unit> {
        lastFollowUpMode = mode
        return followUpModeResult
    }
}
