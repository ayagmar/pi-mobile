package com.ayagmar.pimobile.corerpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reconstructs assistant text content from streaming [MessageUpdateEvent] updates.
 */
class AssistantTextAssembler(
    private val maxTrackedMessages: Int = DEFAULT_MAX_TRACKED_MESSAGES,
) {
    private val buffersByMessage = linkedMapOf<String, MutableMap<Int, StringBuilder>>()

    init {
        require(maxTrackedMessages > 0) { "maxTrackedMessages must be greater than 0" }
    }

    fun apply(event: MessageUpdateEvent): AssistantTextUpdate? {
        val assistantEvent = event.assistantMessageEvent ?: return null
        val contentIndex = assistantEvent.contentIndex ?: 0

        return when (assistantEvent.type) {
            "text_start" -> {
                val builder = builderFor(event, contentIndex, reset = true)
                AssistantTextUpdate(
                    messageKey = messageKeyFor(event),
                    contentIndex = contentIndex,
                    text = builder.toString(),
                    isFinal = false,
                )
            }

            "text_delta" -> {
                val builder = builderFor(event, contentIndex)
                builder.append(assistantEvent.delta.orEmpty())
                AssistantTextUpdate(
                    messageKey = messageKeyFor(event),
                    contentIndex = contentIndex,
                    text = builder.toString(),
                    isFinal = false,
                )
            }

            "text_end" -> {
                val builder = builderFor(event, contentIndex)
                val resolvedText = assistantEvent.content ?: builder.toString()
                builder.clear()
                builder.append(resolvedText)
                AssistantTextUpdate(
                    messageKey = messageKeyFor(event),
                    contentIndex = contentIndex,
                    text = resolvedText,
                    isFinal = true,
                )
            }

            else -> null
        }
    }

    fun snapshot(
        messageKey: String,
        contentIndex: Int = 0,
    ): String? = buffersByMessage[messageKey]?.get(contentIndex)?.toString()

    fun clearMessage(messageKey: String) {
        buffersByMessage.remove(messageKey)
    }

    fun clearAll() {
        buffersByMessage.clear()
    }

    private fun builderFor(
        event: MessageUpdateEvent,
        contentIndex: Int,
        reset: Boolean = false,
    ): StringBuilder {
        val messageKey = messageKeyFor(event)
        val messageBuffers = getOrCreateMessageBuffers(messageKey)
        if (reset) {
            val resetBuilder = StringBuilder()
            messageBuffers[contentIndex] = resetBuilder
            return resetBuilder
        }
        return messageBuffers.getOrPut(contentIndex) { StringBuilder() }
    }

    private fun getOrCreateMessageBuffers(messageKey: String): MutableMap<Int, StringBuilder> {
        val existing = buffersByMessage[messageKey]
        if (existing != null) {
            return existing
        }

        if (buffersByMessage.size >= maxTrackedMessages) {
            val oldestKey = buffersByMessage.entries.firstOrNull()?.key
            if (oldestKey != null) {
                buffersByMessage.remove(oldestKey)
            }
        }

        val created = mutableMapOf<Int, StringBuilder>()
        buffersByMessage[messageKey] = created
        return created
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
    val isFinal: Boolean,
)
