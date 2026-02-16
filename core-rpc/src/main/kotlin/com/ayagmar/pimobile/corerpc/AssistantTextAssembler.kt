package com.ayagmar.pimobile.corerpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reconstructs assistant text and thinking content from streaming [MessageUpdateEvent] updates.
 */
class AssistantTextAssembler(
    private val maxTrackedMessages: Int = DEFAULT_MAX_TRACKED_MESSAGES,
) {
    private val textBuffersByMessage = linkedMapOf<String, MutableMap<Int, StringBuilder>>()
    private val thinkingBuffersByMessage = linkedMapOf<String, MutableMap<Int, StringBuilder>>()

    init {
        require(maxTrackedMessages > 0) { "maxTrackedMessages must be greater than 0" }
    }

    fun apply(event: MessageUpdateEvent): AssistantTextUpdate? {
        val assistantEvent = event.assistantMessageEvent ?: return null
        val contentIndex = assistantEvent.contentIndex ?: 0
        val type = assistantEvent.type

        return when {
            type == "text_start" -> handleTextStart(event, contentIndex)
            type == "text_delta" -> handleTextDelta(event, contentIndex, assistantEvent.delta)
            type == "text_end" -> handleTextEnd(event, contentIndex, assistantEvent.content)
            type == "thinking_start" -> handleThinkingStart(event, contentIndex)
            type == "thinking_delta" -> handleThinkingDelta(event, contentIndex, assistantEvent.delta)
            type == "thinking_end" -> handleThinkingEnd(event, contentIndex, assistantEvent.thinking)
            else -> null
        }
    }

    private fun handleTextStart(
        event: MessageUpdateEvent,
        contentIndex: Int,
    ): AssistantTextUpdate {
        val builder = textBuilderFor(event, contentIndex, reset = true)
        return createTextUpdate(
            event = event,
            contentIndex = contentIndex,
            text = builder.toString(),
            isFinal = false,
        )
    }

    private fun handleTextDelta(
        event: MessageUpdateEvent,
        contentIndex: Int,
        delta: String?,
    ): AssistantTextUpdate {
        val builder = textBuilderFor(event, contentIndex)
        builder.append(delta.orEmpty())
        return createTextUpdate(
            event = event,
            contentIndex = contentIndex,
            text = builder.toString(),
            isFinal = false,
        )
    }

    private fun handleTextEnd(
        event: MessageUpdateEvent,
        contentIndex: Int,
        content: String?,
    ): AssistantTextUpdate {
        val builder = textBuilderFor(event, contentIndex)
        val resolvedText = content ?: builder.toString()
        builder.clear()
        builder.append(resolvedText)
        return createTextUpdate(
            event = event,
            contentIndex = contentIndex,
            text = resolvedText,
            isFinal = true,
        )
    }

    private fun handleThinkingStart(
        event: MessageUpdateEvent,
        contentIndex: Int,
    ): AssistantTextUpdate {
        val builder = thinkingBuilderFor(event, contentIndex, reset = true)
        return AssistantTextUpdate(
            messageKey = messageKeyFor(event),
            contentIndex = contentIndex,
            text = textSnapshot(event, contentIndex).orEmpty(),
            thinking = builder.toString(),
            isThinkingComplete = false,
            isFinal = false,
        )
    }

    private fun handleThinkingDelta(
        event: MessageUpdateEvent,
        contentIndex: Int,
        delta: String?,
    ): AssistantTextUpdate {
        val builder = thinkingBuilderFor(event, contentIndex)
        builder.append(delta.orEmpty())
        return AssistantTextUpdate(
            messageKey = messageKeyFor(event),
            contentIndex = contentIndex,
            text = textSnapshot(event, contentIndex).orEmpty(),
            thinking = builder.toString(),
            isThinkingComplete = false,
            isFinal = false,
        )
    }

    private fun handleThinkingEnd(
        event: MessageUpdateEvent,
        contentIndex: Int,
        thinking: String?,
    ): AssistantTextUpdate {
        val builder = thinkingBuilderFor(event, contentIndex)
        val resolvedThinking = thinking ?: builder.toString()
        builder.clear()
        builder.append(resolvedThinking)
        return AssistantTextUpdate(
            messageKey = messageKeyFor(event),
            contentIndex = contentIndex,
            text = textSnapshot(event, contentIndex).orEmpty(),
            thinking = resolvedThinking,
            isThinkingComplete = true,
            isFinal = false,
        )
    }

    private fun createTextUpdate(
        event: MessageUpdateEvent,
        contentIndex: Int,
        text: String,
        isFinal: Boolean,
    ): AssistantTextUpdate =
        AssistantTextUpdate(
            messageKey = messageKeyFor(event),
            contentIndex = contentIndex,
            text = text,
            thinking = thinkingSnapshot(event, contentIndex),
            isThinkingComplete = isThinkingComplete(event, contentIndex),
            isFinal = isFinal,
        )

    fun snapshot(
        messageKey: String,
        contentIndex: Int = 0,
    ): AssistantContentSnapshot? {
        val text = textBuffersByMessage[messageKey]?.get(contentIndex)?.toString()
        val thinking = thinkingBuffersByMessage[messageKey]?.get(contentIndex)?.toString()
        if (text == null && thinking == null) return null
        return AssistantContentSnapshot(
            text = text,
            thinking = thinking,
        )
    }

    fun clearMessage(messageKey: String) {
        textBuffersByMessage.remove(messageKey)
        thinkingBuffersByMessage.remove(messageKey)
    }

    fun clearAll() {
        textBuffersByMessage.clear()
        thinkingBuffersByMessage.clear()
    }

    private fun textBuilderFor(
        event: MessageUpdateEvent,
        contentIndex: Int,
        reset: Boolean = false,
    ): StringBuilder {
        val messageKey = messageKeyFor(event)
        val messageBuffers = getOrCreateTextBuffers(messageKey)
        if (reset) {
            val resetBuilder = StringBuilder()
            messageBuffers[contentIndex] = resetBuilder
            return resetBuilder
        }
        return messageBuffers.getOrPut(contentIndex) { StringBuilder() }
    }

    private fun thinkingBuilderFor(
        event: MessageUpdateEvent,
        contentIndex: Int,
        reset: Boolean = false,
    ): StringBuilder {
        val messageKey = messageKeyFor(event)
        val messageBuffers = getOrCreateThinkingBuffers(messageKey)
        if (reset) {
            val resetBuilder = StringBuilder()
            messageBuffers[contentIndex] = resetBuilder
            return resetBuilder
        }
        return messageBuffers.getOrPut(contentIndex) { StringBuilder() }
    }

    private fun getOrCreateTextBuffers(messageKey: String): MutableMap<Int, StringBuilder> {
        return getOrCreateBuffers(messageKey, textBuffersByMessage)
    }

    private fun getOrCreateThinkingBuffers(messageKey: String): MutableMap<Int, StringBuilder> {
        return getOrCreateBuffers(messageKey, thinkingBuffersByMessage)
    }

    private fun getOrCreateBuffers(
        messageKey: String,
        bufferMap: LinkedHashMap<String, MutableMap<Int, StringBuilder>>,
    ): MutableMap<Int, StringBuilder> {
        val existing = bufferMap[messageKey]
        if (existing != null) {
            return existing
        }

        if (bufferMap.size >= maxTrackedMessages) {
            val oldestKey = bufferMap.entries.firstOrNull()?.key
            if (oldestKey != null) {
                bufferMap.remove(oldestKey)
            }
        }

        val created = mutableMapOf<Int, StringBuilder>()
        bufferMap[messageKey] = created
        return created
    }

    private fun textSnapshot(
        event: MessageUpdateEvent,
        contentIndex: Int,
    ): String? {
        val messageKey = messageKeyFor(event)
        return textBuffersByMessage[messageKey]?.get(contentIndex)?.toString()
    }

    private fun thinkingSnapshot(
        event: MessageUpdateEvent,
        contentIndex: Int,
    ): String? {
        val messageKey = messageKeyFor(event)
        return thinkingBuffersByMessage[messageKey]?.get(contentIndex)?.toString()
    }

    private fun isThinkingComplete(
        event: MessageUpdateEvent,
        contentIndex: Int,
    ): Boolean {
        val messageKey = messageKeyFor(event)
        val thinkingBuffer = thinkingBuffersByMessage[messageKey]?.get(contentIndex)
        return thinkingBuffer != null && thinkingBuffer.isNotEmpty()
    }

    private fun messageKeyFor(event: MessageUpdateEvent): String =
        extractKey(event.message)
            ?: extractKey(event.assistantMessageEvent?.partial)
            ?: ACTIVE_MESSAGE_KEY

    private fun extractKey(source: JsonObject?): String? {
        if (source == null) return null
        return source.primitiveContent("timestamp") ?: source.primitiveContent("id")
    }

    private fun JsonObject.primitiveContent(fieldName: String): String? {
        val primitive = this[fieldName] as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }

    companion object {
        const val ACTIVE_MESSAGE_KEY = "active"
        const val DEFAULT_MAX_TRACKED_MESSAGES = 8
    }
}

data class AssistantTextUpdate(
    val messageKey: String,
    val contentIndex: Int,
    val text: String,
    val thinking: String?,
    val isThinkingComplete: Boolean,
    val isFinal: Boolean,
)

data class AssistantContentSnapshot(
    val text: String?,
    val thinking: String?,
)
