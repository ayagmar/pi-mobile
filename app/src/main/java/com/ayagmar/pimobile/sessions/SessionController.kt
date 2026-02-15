package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.RpcIncomingMessage
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Controls connection to a pi session.
 */
@Suppress("TooManyFunctions")
interface SessionController {
    val rpcEvents: SharedFlow<RpcIncomingMessage>
    val connectionState: StateFlow<ConnectionState>
    val isStreaming: StateFlow<Boolean>

    suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?>

    suspend fun getMessages(): Result<RpcResponse>

    suspend fun getState(): Result<RpcResponse>

    suspend fun sendPrompt(message: String): Result<Unit>

    suspend fun abort(): Result<Unit>

    suspend fun steer(message: String): Result<Unit>

    suspend fun followUp(message: String): Result<Unit>

    suspend fun renameSession(name: String): Result<String?>

    suspend fun compactSession(): Result<String?>

    suspend fun exportSession(): Result<String>

    suspend fun forkSessionFromEntryId(entryId: String): Result<String?>

    suspend fun getForkMessages(): Result<List<ForkableMessage>>

    suspend fun cycleModel(): Result<ModelInfo?>

    suspend fun cycleThinkingLevel(): Result<String?>

    suspend fun sendExtensionUiResponse(
        requestId: String,
        value: String? = null,
        confirmed: Boolean? = null,
        cancelled: Boolean? = null,
    ): Result<Unit>

    suspend fun newSession(): Result<Unit>

    suspend fun getCommands(): Result<List<SlashCommandInfo>>

    suspend fun executeBash(
        command: String,
        timeoutMs: Int? = null,
    ): Result<BashResult>

    suspend fun abortBash(): Result<Unit>

    suspend fun getSessionStats(): Result<SessionStats>

    suspend fun getAvailableModels(): Result<List<AvailableModel>>

    suspend fun setModel(
        provider: String,
        modelId: String,
    ): Result<ModelInfo?>
}

/**
 * Information about a forkable message from get_fork_messages response.
 */
data class ForkableMessage(
    val entryId: String,
    val preview: String,
    val timestamp: Long?,
)

/**
 * Model information returned from cycle_model.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val thinkingLevel: String,
)

/**
 * Slash command information from get_commands response.
 */
data class SlashCommandInfo(
    val name: String,
    val description: String?,
    val source: String,
    val location: String?,
    val path: String?,
)
