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
