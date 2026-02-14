package com.ayagmar.pimobile.corerpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantTextAssemblerTest {
    @Test
    fun `reconstructs interleaved message streams deterministically`() {
        val assembler = AssistantTextAssembler()

        assembler.apply(messageUpdate(messageTimestamp = 100, eventType = "text_start", contentIndex = 0))
        assembler.apply(
            messageUpdate(messageTimestamp = 100, eventType = "text_delta", contentIndex = 0, delta = "Hel"),
        )
        assembler.apply(
            messageUpdate(messageTimestamp = 200, eventType = "text_delta", contentIndex = 0, delta = "Other"),
        )

        val message100 =
            assembler.apply(
                messageUpdate(messageTimestamp = 100, eventType = "text_delta", contentIndex = 0, delta = "lo"),
            )

        assertEquals("100", message100?.messageKey)
        assertEquals("Hello", message100?.text)
        assertFalse(message100?.isFinal ?: true)
        assertEquals("Hello", assembler.snapshot(messageKey = "100"))
        assertEquals("Other", assembler.snapshot(messageKey = "200"))

        val finalized =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "text_end",
                    contentIndex = 0,
                    content = "Hello",
                ),
            )
        assertTrue(finalized?.isFinal ?: false)
        assertEquals("Hello", finalized?.text)

        assembler.apply(messageUpdate(messageTimestamp = 100, eventType = "text_start", contentIndex = 1))
        assembler.apply(
            messageUpdate(messageTimestamp = 100, eventType = "text_delta", contentIndex = 1, delta = "World"),
        )

        assertEquals("Hello", assembler.snapshot(messageKey = "100", contentIndex = 0))
        assertEquals("World", assembler.snapshot(messageKey = "100", contentIndex = 1))
    }

    @Test
    fun `ignores non text updates and evicts oldest message buffers`() {
        val assembler = AssistantTextAssembler(maxTrackedMessages = 1)

        val ignored =
            assembler.apply(
                messageUpdate(messageTimestamp = 100, eventType = "thinking_delta", contentIndex = 0, delta = "plan"),
            )
        assertNull(ignored)

        assembler.apply(
            messageUpdate(messageTimestamp = 100, eventType = "text_delta", contentIndex = 0, delta = "first"),
        )
        assembler.apply(
            messageUpdate(messageTimestamp = 200, eventType = "text_delta", contentIndex = 0, delta = "second"),
        )

        assertNull(assembler.snapshot(messageKey = "100"))
        assertEquals("second", assembler.snapshot(messageKey = "200"))
    }

    @Test
    fun `uses active key fallback when message metadata is missing`() {
        val assembler = AssistantTextAssembler()
        val update =
            assembler.apply(
                MessageUpdateEvent(
                    type = "message_update",
                    message = null,
                    assistantMessageEvent = AssistantMessageEvent(type = "text_delta", delta = "hello"),
                ),
            )

        assertEquals(AssistantTextAssembler.ACTIVE_MESSAGE_KEY, update?.messageKey)
        assertEquals("hello", assembler.snapshot(AssistantTextAssembler.ACTIVE_MESSAGE_KEY))
    }

    private fun messageUpdate(
        messageTimestamp: Long,
        eventType: String,
        contentIndex: Int,
        delta: String? = null,
        content: String? = null,
    ): MessageUpdateEvent =
        MessageUpdateEvent(
            type = "message_update",
            message = parseObject("""{"timestamp":$messageTimestamp}"""),
            assistantMessageEvent =
                AssistantMessageEvent(
                    type = eventType,
                    contentIndex = contentIndex,
                    delta = delta,
                    content = content,
                ),
        )

    private fun parseObject(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
