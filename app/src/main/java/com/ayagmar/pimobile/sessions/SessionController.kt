package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.ImagePayload
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

    /**
     * Emits the new session path whenever the session changes (switch, new, fork).
     * ChatViewModel observes this to reload the timeline.
     */
    val sessionChanged: SharedFlow<String?>

    fun setTransportPreference(preference: TransportPreference)

    fun getTransportPreference(): TransportPreference

    fun getEffectiveTransportPreference(): TransportPreference

    suspend fun ensureConnected(
        hostProfile: HostProfile,
        token: String,
        cwd: String,
    ): Result<Unit>

    suspend fun disconnect(): Result<Unit>

    suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?>

    suspend fun getMessages(): Result<RpcResponse>

    suspend fun getState(): Result<RpcResponse>

    suspend fun sendPrompt(
        message: String,
        images: List<ImagePayload> = emptyList(),
    ): Result<Unit>

    suspend fun abort(): Result<Unit>

    suspend fun steer(message: String): Result<Unit>

    suspend fun followUp(message: String): Result<Unit>

    suspend fun renameSession(name: String): Result<String?>

    suspend fun compactSession(): Result<String?>

    suspend fun exportSession(): Result<String>

    suspend fun forkSessionFromEntryId(entryId: String): Result<String?>

    suspend fun getForkMessages(): Result<List<ForkableMessage>>

    suspend fun getSessionTree(
        sessionPath: String? = null,
        filter: String? = null,
    ): Result<SessionTreeSnapshot>

    suspend fun getSessionFreshness(sessionPath: String): Result<SessionFreshnessSnapshot>

    suspend fun navigateTreeToEntry(entryId: String): Result<TreeNavigationResult>

    suspend fun cycleModel(): Result<ModelInfo?>

    suspend fun cycleThinkingLevel(): Result<String?>

    suspend fun setThinkingLevel(level: String): Result<String?>

    suspend fun abortRetry(): Result<Unit>

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

    suspend fun setAutoCompaction(enabled: Boolean): Result<Unit>

    suspend fun setAutoRetry(enabled: Boolean): Result<Unit>

    suspend fun setSteeringMode(mode: String): Result<Unit>

    suspend fun setFollowUpMode(mode: String): Result<Unit>
}

/**
 * Information about a forkable message from get_fork_messages response.
 */
data class ForkableMessage(
    val entryId: String,
    val preview: String,
    val timestamp: Long?,
)

data class SessionTreeSnapshot(
    val sessionPath: String,
    val rootIds: List<String>,
    val currentLeafId: String?,
    val entries: List<SessionTreeEntry>,
)

data class SessionFreshnessSnapshot(
    val sessionPath: String,
    val cwd: String,
    val fingerprint: SessionFreshnessFingerprint,
    val lock: SessionLockMetadata,
)

data class SessionFreshnessFingerprint(
    val mtimeMs: Long,
    val sizeBytes: Long,
    val entryCount: Int,
    val lastEntryId: String?,
    val lastEntriesHash: String?,
)

data class SessionLockMetadata(
    val cwdOwnerClientId: String?,
    val sessionOwnerClientId: String?,
    val isCurrentClientCwdOwner: Boolean,
    val isCurrentClientSessionOwner: Boolean,
)

data class SessionTreeEntry(
    val entryId: String,
    val parentId: String?,
    val entryType: String,
    val role: String?,
    val timestamp: String?,
    val preview: String,
    val label: String? = null,
    val isBookmarked: Boolean = false,
)

data class TreeNavigationResult(
    val cancelled: Boolean,
    val editorText: String?,
    val currentLeafId: String?,
    val sessionPath: String?,
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
