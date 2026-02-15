package com.ayagmar.pimobile.corenet

import com.ayagmar.pimobile.corerpc.AbortBashCommand
import com.ayagmar.pimobile.corerpc.AbortCommand
import com.ayagmar.pimobile.corerpc.BashCommand
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
import com.ayagmar.pimobile.corerpc.GetMessagesCommand
import com.ayagmar.pimobile.corerpc.GetSessionStatsCommand
import com.ayagmar.pimobile.corerpc.GetStateCommand
import com.ayagmar.pimobile.corerpc.NewSessionCommand
import com.ayagmar.pimobile.corerpc.PromptCommand
import com.ayagmar.pimobile.corerpc.RpcCommand
import com.ayagmar.pimobile.corerpc.SetAutoCompactionCommand
import com.ayagmar.pimobile.corerpc.SetAutoRetryCommand
import com.ayagmar.pimobile.corerpc.SetFollowUpModeCommand
import com.ayagmar.pimobile.corerpc.SetModelCommand
import com.ayagmar.pimobile.corerpc.SetSessionNameCommand
import com.ayagmar.pimobile.corerpc.SetSteeringModeCommand
import com.ayagmar.pimobile.corerpc.SteerCommand
import com.ayagmar.pimobile.corerpc.SwitchSessionCommand
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private typealias RpcCommandEncoder = (Json, RpcCommand) -> JsonObject

private val rpcCommandEncoders: Map<Class<out RpcCommand>, RpcCommandEncoder> =
    mapOf(
        PromptCommand::class.java to typedEncoder(PromptCommand.serializer()),
        SteerCommand::class.java to typedEncoder(SteerCommand.serializer()),
        FollowUpCommand::class.java to typedEncoder(FollowUpCommand.serializer()),
        AbortCommand::class.java to typedEncoder(AbortCommand.serializer()),
        GetStateCommand::class.java to typedEncoder(GetStateCommand.serializer()),
        GetMessagesCommand::class.java to typedEncoder(GetMessagesCommand.serializer()),
        SwitchSessionCommand::class.java to typedEncoder(SwitchSessionCommand.serializer()),
        SetSessionNameCommand::class.java to typedEncoder(SetSessionNameCommand.serializer()),
        GetForkMessagesCommand::class.java to typedEncoder(GetForkMessagesCommand.serializer()),
        ForkCommand::class.java to typedEncoder(ForkCommand.serializer()),
        ExportHtmlCommand::class.java to typedEncoder(ExportHtmlCommand.serializer()),
        CompactCommand::class.java to typedEncoder(CompactCommand.serializer()),
        CycleModelCommand::class.java to typedEncoder(CycleModelCommand.serializer()),
        CycleThinkingLevelCommand::class.java to typedEncoder(CycleThinkingLevelCommand.serializer()),
        ExtensionUiResponseCommand::class.java to typedEncoder(ExtensionUiResponseCommand.serializer()),
        NewSessionCommand::class.java to typedEncoder(NewSessionCommand.serializer()),
        GetCommandsCommand::class.java to typedEncoder(GetCommandsCommand.serializer()),
        BashCommand::class.java to typedEncoder(BashCommand.serializer()),
        AbortBashCommand::class.java to typedEncoder(AbortBashCommand.serializer()),
        GetSessionStatsCommand::class.java to typedEncoder(GetSessionStatsCommand.serializer()),
        GetAvailableModelsCommand::class.java to typedEncoder(GetAvailableModelsCommand.serializer()),
        SetModelCommand::class.java to typedEncoder(SetModelCommand.serializer()),
        SetAutoCompactionCommand::class.java to typedEncoder(SetAutoCompactionCommand.serializer()),
        SetAutoRetryCommand::class.java to typedEncoder(SetAutoRetryCommand.serializer()),
        SetSteeringModeCommand::class.java to typedEncoder(SetSteeringModeCommand.serializer()),
        SetFollowUpModeCommand::class.java to typedEncoder(SetFollowUpModeCommand.serializer()),
    )

fun encodeRpcCommand(
    json: Json,
    command: RpcCommand,
): JsonObject {
    val basePayload = serializeRpcCommand(json, command)

    return buildJsonObject {
        basePayload.forEach { (key, value) ->
            put(key, value)
        }

        if (!basePayload.containsKey("type")) {
            put("type", command.type)
        }

        val commandId = command.id
        if (commandId != null && !basePayload.containsKey("id")) {
            put("id", commandId)
        }
    }
}

private fun serializeRpcCommand(
    json: Json,
    command: RpcCommand,
): JsonObject {
    val encoder =
        rpcCommandEncoders[command.javaClass]
            ?: error("Unsupported RPC command type: ${command::class.qualifiedName}")

    return encoder(json, command)
}

@Suppress("UNCHECKED_CAST")
private fun <T : RpcCommand> typedEncoder(serializer: KSerializer<T>): RpcCommandEncoder {
    return { currentJson, currentCommand ->
        currentJson.encodeToJsonElement(serializer, currentCommand as T).jsonObject
    }
}
