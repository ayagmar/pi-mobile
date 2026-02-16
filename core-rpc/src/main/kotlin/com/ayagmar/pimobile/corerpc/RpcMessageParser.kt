package com.ayagmar.pimobile.corerpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RpcMessageParser(
    private val json: Json = defaultJson,
) {
    @Suppress("CyclomaticComplexMethod")
    fun parse(line: String): RpcIncomingMessage {
        val jsonObject = parseObject(line)
        val type =
            jsonObject["type"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("RPC message is missing required field: type")

        return when (type) {
            "response" -> json.decodeFromJsonElement<RpcResponse>(jsonObject)
            "message_update" -> json.decodeFromJsonElement<MessageUpdateEvent>(jsonObject)
            "message_start" -> json.decodeFromJsonElement<MessageStartEvent>(jsonObject)
            "message_end" -> json.decodeFromJsonElement<MessageEndEvent>(jsonObject)
            "tool_execution_start" -> json.decodeFromJsonElement<ToolExecutionStartEvent>(jsonObject)
            "tool_execution_update" -> json.decodeFromJsonElement<ToolExecutionUpdateEvent>(jsonObject)
            "tool_execution_end" -> json.decodeFromJsonElement<ToolExecutionEndEvent>(jsonObject)
            "extension_ui_request" -> json.decodeFromJsonElement<ExtensionUiRequestEvent>(jsonObject)
            "extension_error" -> json.decodeFromJsonElement<ExtensionErrorEvent>(jsonObject)
            "agent_start" -> json.decodeFromJsonElement<AgentStartEvent>(jsonObject)
            "agent_end" -> json.decodeFromJsonElement<AgentEndEvent>(jsonObject)
            "turn_start" -> json.decodeFromJsonElement<TurnStartEvent>(jsonObject)
            "turn_end" -> json.decodeFromJsonElement<TurnEndEvent>(jsonObject)
            "auto_compaction_start" -> json.decodeFromJsonElement<AutoCompactionStartEvent>(jsonObject)
            "auto_compaction_end" -> json.decodeFromJsonElement<AutoCompactionEndEvent>(jsonObject)
            "auto_retry_start" -> json.decodeFromJsonElement<AutoRetryStartEvent>(jsonObject)
            "auto_retry_end" -> json.decodeFromJsonElement<AutoRetryEndEvent>(jsonObject)
            else -> GenericRpcEvent(type = type, payload = jsonObject)
        }
    }

    private fun parseObject(line: String): JsonObject {
        return try {
            json.parseToJsonElement(line).jsonObject
        } catch (exception: IllegalStateException) {
            throw IllegalArgumentException("RPC message is not a JSON object", exception)
        } catch (exception: SerializationException) {
            throw IllegalArgumentException("Failed to decode RPC message JSON", exception)
        }
    }

    companion object {
        val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
            }
    }
}
