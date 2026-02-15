package com.ayagmar.pimobile.corerpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

sealed interface RpcIncomingMessage

@Serializable
data class RpcResponse(
    val id: String? = null,
    val type: String,
    val command: String,
    val success: Boolean,
    val data: JsonObject? = null,
    val error: String? = null,
) : RpcIncomingMessage

sealed interface RpcEvent : RpcIncomingMessage {
    val type: String
}

@Serializable
data class MessageUpdateEvent(
    override val type: String,
    val message: JsonObject? = null,
    val assistantMessageEvent: AssistantMessageEvent? = null,
) : RpcEvent

@Serializable
data class AssistantMessageEvent(
    val type: String,
    val contentIndex: Int? = null,
    val delta: String? = null,
    val content: String? = null,
    val partial: JsonObject? = null,
    val thinking: String? = null,
)

@Serializable
data class ToolExecutionStartEvent(
    override val type: String,
    val toolCallId: String,
    val toolName: String,
    val args: JsonObject? = null,
) : RpcEvent

@Serializable
data class ToolExecutionUpdateEvent(
    override val type: String,
    val toolCallId: String,
    val toolName: String,
    val args: JsonObject? = null,
    val partialResult: JsonObject? = null,
) : RpcEvent

@Serializable
data class ToolExecutionEndEvent(
    override val type: String,
    val toolCallId: String,
    val toolName: String,
    val result: JsonObject? = null,
    val isError: Boolean,
) : RpcEvent

@Serializable
data class ExtensionUiRequestEvent(
    override val type: String,
    val id: String,
    val method: String,
    val title: String? = null,
    val message: String? = null,
    val options: List<String>? = null,
    val placeholder: String? = null,
    val prefill: String? = null,
    val notifyType: String? = null,
    val statusKey: String? = null,
    val statusText: String? = null,
    val widgetKey: String? = null,
    val widgetLines: List<String>? = null,
    val widgetPlacement: String? = null,
    val text: String? = null,
    val timeout: Long? = null,
) : RpcEvent

@Serializable
data class GenericRpcEvent(
    override val type: String,
    val payload: JsonObject,
) : RpcEvent

@Serializable
data class AgentStartEvent(
    override val type: String,
) : RpcEvent

@Serializable
data class AgentEndEvent(
    override val type: String,
    val messages: List<JsonObject>? = null,
) : RpcEvent

@Serializable
data class TurnStartEvent(
    override val type: String,
) : RpcEvent

@Serializable
data class TurnEndEvent(
    override val type: String,
    val message: JsonObject? = null,
    val toolResults: List<JsonObject>? = null,
) : RpcEvent
