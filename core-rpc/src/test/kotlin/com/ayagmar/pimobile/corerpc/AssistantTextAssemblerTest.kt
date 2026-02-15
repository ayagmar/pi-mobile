package com.ayagmar.pimobile.corerpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        assertNull(message100?.thinking)
        assertFalse(message100?.isThinkingComplete ?: true)
        assertFalse(message100?.isFinal ?: true)

        val snapshot100 = assembler.snapshot(messageKey = "100")
        assertNotNull(snapshot100)
        assertEquals("Hello", snapshot100.text)
        assertNull(snapshot100.thinking)

        val snapshot200 = assembler.snapshot(messageKey = "200")
        assertNotNull(snapshot200)
        assertEquals("Other", snapshot200.text)

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

        val snapshot100Index0 = assembler.snapshot(messageKey = "100", contentIndex = 0)
        assertNotNull(snapshot100Index0)
        assertEquals("Hello", snapshot100Index0.text)

        val snapshot100Index1 = assembler.snapshot(messageKey = "100", contentIndex = 1)
        assertNotNull(snapshot100Index1)
        assertEquals("World", snapshot100Index1.text)
    }

    @Suppress("LongMethod")
    @Test
    fun `handles thinking events separately from text`() {
        val assembler = AssistantTextAssembler()

        // Thinking starts
        val thinkingStart =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "thinking_start",
                    contentIndex = 0,
                ),
            )
        assertEquals("100", thinkingStart?.messageKey)
        assertEquals("", thinkingStart?.thinking)
        assertFalse(thinkingStart?.isThinkingComplete ?: true)

        // Thinking delta
        val thinkingDelta =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "thinking_delta",
                    contentIndex = 0,
                    delta = "Let me analyze",
                ),
            )
        assertEquals("Let me analyze", thinkingDelta?.thinking)
        assertFalse(thinkingDelta?.isThinkingComplete ?: true)

        // Text starts while thinking continues
        assembler.apply(
            messageUpdate(messageTimestamp = 100, eventType = "text_start", contentIndex = 0),
        )
        assembler.apply(
            messageUpdate(
                messageTimestamp = 100,
                eventType = "text_delta",
                contentIndex = 0,
                delta = "Result: ",
            ),
        )

        // More thinking
        val moreThinking =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "thinking_delta",
                    contentIndex = 0,
                    delta = " this problem.",
                ),
            )
        assertEquals("Let me analyze this problem.", moreThinking?.thinking)
        assertEquals("Result: ", moreThinking?.text)

        // Thinking ends
        val thinkingEnd =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "thinking_end",
                    contentIndex = 0,
                    thinking = "Let me analyze this problem carefully.",
                ),
            )
        assertEquals("Let me analyze this problem carefully.", thinkingEnd?.thinking)
        assertTrue(thinkingEnd?.isThinkingComplete ?: false)
        assertEquals("Result: ", thinkingEnd?.text)

        // Text continues and ends
        val textEnd =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "text_end",
                    contentIndex = 0,
                    content = "Result: 42",
                ),
            )
        assertEquals("Result: 42", textEnd?.text)
        assertTrue(textEnd?.isFinal ?: false)
        assertEquals("Let me analyze this problem carefully.", textEnd?.thinking)
        assertTrue(textEnd?.isThinkingComplete ?: false)
    }

    @Test
    fun `evicts oldest message buffers when limit reached`() {
        val assembler = AssistantTextAssembler(maxTrackedMessages = 1)

        assembler.apply(
            messageUpdate(messageTimestamp = 100, eventType = "text_delta", contentIndex = 0, delta = "first"),
        )
        assembler.apply(
            messageUpdate(messageTimestamp = 200, eventType = "text_delta", contentIndex = 0, delta = "second"),
        )

        assertNull(assembler.snapshot(messageKey = "100"))
        val snapshot200 = assembler.snapshot(messageKey = "200")
        assertNotNull(snapshot200)
        assertEquals("second", snapshot200.text)
    }

    @Test
    fun `evicts thinking buffers along with text buffers`() {
        val assembler = AssistantTextAssembler(maxTrackedMessages = 1)

        assembler.apply(
            messageUpdate(
                messageTimestamp = 100,
                eventType = "thinking_delta",
                contentIndex = 0,
                delta = "thinking1",
            ),
        )
        assembler.apply(
            messageUpdate(
                messageTimestamp = 200,
                eventType = "thinking_delta",
                contentIndex = 0,
                delta = "thinking2",
            ),
        )

        assertNull(assembler.snapshot(messageKey = "100"))
        val snapshot200 = assembler.snapshot(messageKey = "200")
        assertNotNull(snapshot200)
        assertEquals("thinking2", snapshot200.thinking)
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
        val snapshot = assembler.snapshot(AssistantTextAssembler.ACTIVE_MESSAGE_KEY)
        assertNotNull(snapshot)
        assertEquals("hello", snapshot.text)
    }

    @Test
    fun `handles thinking without text`() {
        val assembler = AssistantTextAssembler()

        assembler.apply(
            messageUpdate(
                messageTimestamp = 100,
                eventType = "thinking_start",
                contentIndex = 0,
            ),
        )
        val thinkingDelta =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "thinking_delta",
                    contentIndex = 0,
                    delta = "Deep reasoning here",
                ),
            )

        assertEquals("Deep reasoning here", thinkingDelta?.thinking)
        assertEquals("", thinkingDelta?.text)
        assertFalse(thinkingDelta?.isThinkingComplete ?: true)
    }

    @Test
    fun `ignores unknown event types`() {
        val assembler = AssistantTextAssembler()

        val result =
            assembler.apply(
                messageUpdate(
                    messageTimestamp = 100,
                    eventType = "unknown_event",
                    contentIndex = 0,
                ),
            )

        assertNull(result)
    }

    @Test
    fun `clearMessage removes both text and thinking buffers`() {
        val assembler = AssistantTextAssembler()

        assembler.apply(
            messageUpdate(
                messageTimestamp = 100,
                eventType = "text_delta",
                contentIndex = 0,
                delta = "text",
            ),
        )
        assembler.apply(
            messageUpdate(
                messageTimestamp = 100,
                eventType = "thinking_delta",
                contentIndex = 0,
                delta = "thinking",
            ),
        )

        assertNotNull(assembler.snapshot(messageKey = "100"))

        assembler.clearMessage("100")

        assertNull(assembler.snapshot(messageKey = "100"))
    }

    @Test
    fun `clearAll removes all buffers`() {
        val assembler = AssistantTextAssembler()

        assembler.apply(
            messageUpdate(
                messageTimestamp = 100,
                eventType = "text_delta",
                contentIndex = 0,
                delta = "text1",
            ),
        )
        assembler.apply(
            messageUpdate(
                messageTimestamp = 200,
                eventType = "thinking_delta",
                contentIndex = 0,
                delta = "thinking2",
            ),
        )

        assembler.clearAll()

        assertNull(assembler.snapshot(messageKey = "100"))
        assertNull(assembler.snapshot(messageKey = "200"))
    }

    @Suppress("LongParameterList")
    private fun messageUpdate(
        messageTimestamp: Long,
        eventType: String,
        contentIndex: Int,
        delta: String? = null,
        content: String? = null,
        thinking: String? = null,
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
                    thinking = thinking,
                ),
        )

    private fun parseObject(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
