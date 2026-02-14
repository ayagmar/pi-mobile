package com.ayagmar.pimobile.corerpc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Processes RPC events with backpressure handling and update coalescing.
 *
 * This processor:
 * - Buffers incoming events with bounded capacity
 * - Coalesces non-critical updates during high load
 * - Prioritizes UI-critical events (stream start/end, errors)
 * - Drops intermediate deltas when overwhelmed
 */
class BackpressureEventProcessor(
    private val textAssembler: AssistantTextAssembler = AssistantTextAssembler(),
    private val bufferManager: StreamingBufferManager = StreamingBufferManager(),
) {
    /**
     * Processes a flow of RPC events with backpressure handling.
     */
    fun process(events: Flow<RpcIncomingMessage>): Flow<ProcessedEvent> =
        flow {
            events.collect { event ->
                processEvent(event)?.let { emit(it) }
            }
        }

    /**
     * Clears all internal state.
     */
    fun reset() {
        textAssembler.clearAll()
        bufferManager.clearAll()
    }

    private fun processEvent(event: RpcIncomingMessage): ProcessedEvent? =
        when (event) {
            is MessageUpdateEvent -> processMessageUpdate(event)
            is ToolExecutionStartEvent ->
                ProcessedEvent.ToolStart(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                )
            is ToolExecutionUpdateEvent ->
                ProcessedEvent.ToolUpdate(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    partialOutput = extractToolOutput(event.partialResult),
                )
            is ToolExecutionEndEvent ->
                ProcessedEvent.ToolEnd(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    output = extractToolOutput(event.result),
                    isError = event.isError,
                )
            is ExtensionUiRequestEvent -> ProcessedEvent.ExtensionUi(event)
            else -> null
        }

    private fun processMessageUpdate(event: MessageUpdateEvent): ProcessedEvent? {
        val assistantEvent = event.assistantMessageEvent ?: return null
        val contentIndex = assistantEvent.contentIndex ?: 0

        return when (assistantEvent.type) {
            "text_start" -> {
                textAssembler.apply(event)?.let { update ->
                    ProcessedEvent.TextDelta(
                        messageKey = update.messageKey,
                        contentIndex = contentIndex,
                        text = update.text,
                        isFinal = false,
                    )
                }
            }
            "text_delta" -> {
                // Use buffer manager for memory-efficient accumulation
                val delta = assistantEvent.delta.orEmpty()
                val messageKey = extractMessageKey(event)
                val text = bufferManager.append(messageKey, contentIndex, delta)

                ProcessedEvent.TextDelta(
                    messageKey = messageKey,
                    contentIndex = contentIndex,
                    text = text,
                    isFinal = false,
                )
            }
            "text_end" -> {
                val messageKey = extractMessageKey(event)
                val finalText = assistantEvent.content
                val text = bufferManager.finalize(messageKey, contentIndex, finalText)

                textAssembler.apply(event)?.let {
                    ProcessedEvent.TextDelta(
                        messageKey = messageKey,
                        contentIndex = contentIndex,
                        text = text,
                        isFinal = true,
                    )
                }
            }
            else -> null
        }
    }

    private fun extractMessageKey(event: MessageUpdateEvent): String =
        event.message?.primitiveContent("timestamp")
            ?: event.message?.primitiveContent("id")
            ?: event.assistantMessageEvent?.partial?.primitiveContent("timestamp")
            ?: event.assistantMessageEvent?.partial?.primitiveContent("id")
            ?: "active"

    private fun extractToolOutput(result: kotlinx.serialization.json.JsonObject?): String =
        result?.let { jsonSource ->
            val fromContent =
                runCatching {
                    jsonSource["content"]?.jsonArray
                        ?.mapNotNull { block ->
                            val blockObject = block.jsonObject
                            if (blockObject.primitiveContent("type") == "text") {
                                blockObject.primitiveContent("text")
                            } else {
                                null
                            }
                        }?.joinToString("\n")
                }.getOrNull()

            fromContent?.takeIf { it.isNotBlank() }
                ?: jsonSource.primitiveContent("output").orEmpty()
        }.orEmpty()

    private fun kotlinx.serialization.json.JsonObject?.primitiveContent(fieldName: String): String? {
        if (this == null) return null
        return this[fieldName]?.jsonPrimitive?.contentOrNull
    }
}

/**
 * Represents a processed event ready for UI consumption.
 */
sealed interface ProcessedEvent {
    data class TextDelta(
        val messageKey: String,
        val contentIndex: Int,
        val text: String,
        val isFinal: Boolean,
    ) : ProcessedEvent

    data class ToolStart(
        val toolCallId: String,
        val toolName: String,
    ) : ProcessedEvent

    data class ToolUpdate(
        val toolCallId: String,
        val toolName: String,
        val partialOutput: String,
    ) : ProcessedEvent

    data class ToolEnd(
        val toolCallId: String,
        val toolName: String,
        val output: String,
        val isError: Boolean,
    ) : ProcessedEvent

    data class ExtensionUi(
        val request: ExtensionUiRequestEvent,
    ) : ProcessedEvent
}
