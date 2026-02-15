package com.ayagmar.pimobile.corerpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RpcCommand {
    val id: String?
    val type: String
}

@Serializable
data class PromptCommand(
    override val id: String? = null,
    override val type: String = "prompt",
    val message: String,
    val images: List<ImagePayload> = emptyList(),
    val streamingBehavior: String? = null,
) : RpcCommand

@Serializable
data class SteerCommand(
    override val id: String? = null,
    override val type: String = "steer",
    val message: String,
    val images: List<ImagePayload> = emptyList(),
) : RpcCommand

@Serializable
@SerialName("follow_up")
data class FollowUpCommand(
    override val id: String? = null,
    override val type: String = "follow_up",
    val message: String,
    val images: List<ImagePayload> = emptyList(),
) : RpcCommand

@Serializable
data class AbortCommand(
    override val id: String? = null,
    override val type: String = "abort",
) : RpcCommand

@Serializable
data class GetStateCommand(
    override val id: String? = null,
    override val type: String = "get_state",
) : RpcCommand

@Serializable
data class GetMessagesCommand(
    override val id: String? = null,
    override val type: String = "get_messages",
) : RpcCommand

@Serializable
data class SwitchSessionCommand(
    override val id: String? = null,
    override val type: String = "switch_session",
    val sessionPath: String,
) : RpcCommand

@Serializable
data class SetSessionNameCommand(
    override val id: String? = null,
    override val type: String = "set_session_name",
    val name: String,
) : RpcCommand

@Serializable
data class GetForkMessagesCommand(
    override val id: String? = null,
    override val type: String = "get_fork_messages",
) : RpcCommand

@Serializable
data class ForkCommand(
    override val id: String? = null,
    override val type: String = "fork",
    val entryId: String,
) : RpcCommand

@Serializable
data class ExportHtmlCommand(
    override val id: String? = null,
    override val type: String = "export_html",
    val outputPath: String? = null,
) : RpcCommand

@Serializable
data class CompactCommand(
    override val id: String? = null,
    override val type: String = "compact",
    val customInstructions: String? = null,
) : RpcCommand

@Serializable
data class CycleModelCommand(
    override val id: String? = null,
    override val type: String = "cycle_model",
) : RpcCommand

@Serializable
data class CycleThinkingLevelCommand(
    override val id: String? = null,
    override val type: String = "cycle_thinking_level",
) : RpcCommand

@Serializable
data class ExtensionUiResponseCommand(
    override val id: String? = null,
    override val type: String = "extension_ui_response",
    val value: String? = null,
    val confirmed: Boolean? = null,
    val cancelled: Boolean? = null,
) : RpcCommand

@Serializable
data class NewSessionCommand(
    override val id: String? = null,
    override val type: String = "new_session",
    val parentSession: String? = null,
) : RpcCommand

@Serializable
data class GetCommandsCommand(
    override val id: String? = null,
    override val type: String = "get_commands",
) : RpcCommand

@Serializable
data class BashCommand(
    override val id: String? = null,
    override val type: String = "bash",
    val command: String,
    val timeoutMs: Int? = null,
) : RpcCommand

@Serializable
data class AbortBashCommand(
    override val id: String? = null,
    override val type: String = "abort_bash",
) : RpcCommand

@Serializable
data class GetSessionStatsCommand(
    override val id: String? = null,
    override val type: String = "get_session_stats",
) : RpcCommand

@Serializable
data class GetAvailableModelsCommand(
    override val id: String? = null,
    override val type: String = "get_available_models",
) : RpcCommand

@Serializable
data class SetModelCommand(
    override val id: String? = null,
    override val type: String = "set_model",
    val provider: String,
    val modelId: String,
) : RpcCommand

@Serializable
data class ImagePayload(
    val type: String = "image",
    val data: String,
    val mimeType: String,
)

@Serializable
data class SlashCommand(
    val name: String,
    val description: String? = null,
    val source: String,
    val location: String? = null,
    val path: String? = null,
)

/**
 * Result of a bash command execution.
 */
data class BashResult(
    val output: String,
    val exitCode: Int,
    val wasTruncated: Boolean,
    val fullLogPath: String? = null,
)

/**
 * Session statistics from get_session_stats response.
 */
data class SessionStats(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalCost: Double,
    val messageCount: Int,
    val userMessageCount: Int,
    val assistantMessageCount: Int,
    val toolResultCount: Int,
    val sessionPath: String?,
)

/**
 * Available model information from get_available_models response.
 */
data class AvailableModel(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val supportsThinking: Boolean,
    val inputCostPer1k: Double?,
    val outputCostPer1k: Double?,
)
