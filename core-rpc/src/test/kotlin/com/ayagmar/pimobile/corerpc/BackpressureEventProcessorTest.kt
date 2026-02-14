package com.ayagmar.pimobile.corerpc

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackpressureEventProcessorTest {
    @Test
    fun `processes text delta events into TextDelta`() =
        runTest {
            val processor = BackpressureEventProcessor()

            val events =
                flowOf(
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent =
                            AssistantMessageEvent(
                                type = "text_delta",
                                contentIndex = 0,
                                delta = "Hello ",
                            ),
                    ),
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent =
                            AssistantMessageEvent(
                                type = "text_delta",
                                contentIndex = 0,
                                delta = "World",
                            ),
                    ),
                )

            val results = processor.process(events).toList()

            assertEquals(2, results.size)
            assertIs<ProcessedEvent.TextDelta>(results[0])
            assertEquals("Hello ", (results[0] as ProcessedEvent.TextDelta).text)
            assertEquals("Hello World", (results[1] as ProcessedEvent.TextDelta).text)
        }

    @Test
    fun `processes tool execution lifecycle`() =
        runTest {
            val processor = BackpressureEventProcessor()

            val events =
                flowOf(
                    ToolExecutionStartEvent(
                        type = "tool_execution_start",
                        toolCallId = "call_1",
                        toolName = "bash",
                    ),
                    ToolExecutionEndEvent(
                        type = "tool_execution_end",
                        toolCallId = "call_1",
                        toolName = "bash",
                        isError = false,
                    ),
                )

            val results = processor.process(events).toList()

            assertEquals(2, results.size)
            assertIs<ProcessedEvent.ToolStart>(results[0])
            assertEquals("bash", (results[0] as ProcessedEvent.ToolStart).toolName)
            assertIs<ProcessedEvent.ToolEnd>(results[1])
            assertEquals("bash", (results[1] as ProcessedEvent.ToolEnd).toolName)
        }

    @Test
    fun `processes extension UI request`() =
        runTest {
            val processor = BackpressureEventProcessor()

            val events =
                flowOf(
                    ExtensionUiRequestEvent(
                        type = "extension_ui_request",
                        id = "req-1",
                        method = "confirm",
                        title = "Confirm?",
                        message = "Are you sure?",
                    ),
                )

            val results = processor.process(events).toList()

            assertEquals(1, results.size)
            assertIs<ProcessedEvent.ExtensionUi>(results[0])
        }

    @Test
    fun `finalizes text on text_end event`() =
        runTest {
            val processor = BackpressureEventProcessor()

            val events =
                flowOf(
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent =
                            AssistantMessageEvent(
                                type = "text_delta",
                                contentIndex = 0,
                                delta = "Partial",
                            ),
                    ),
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent =
                            AssistantMessageEvent(
                                type = "text_end",
                                contentIndex = 0,
                                content = "Final Text",
                            ),
                    ),
                )

            val results = processor.process(events).toList()

            assertEquals(2, results.size)
            val finalEvent = results[1] as ProcessedEvent.TextDelta
            assertTrue(finalEvent.isFinal)
            assertEquals("Final Text", finalEvent.text)
        }

    @Test
    fun `reset clears all state`() =
        runTest {
            val processor = BackpressureEventProcessor()

            // Process some events
            val events1 =
                flowOf(
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent =
                            AssistantMessageEvent(
                                type = "text_delta",
                                contentIndex = 0,
                                delta = "Hello",
                            ),
                    ),
                )
            processor.process(events1).toList()

            // Reset
            processor.reset()

            // Process new events - should start fresh
            val events2 =
                flowOf(
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent =
                            AssistantMessageEvent(
                                type = "text_delta",
                                contentIndex = 0,
                                delta = "World",
                            ),
                    ),
                )
            val results = processor.process(events2).toList()

            assertEquals(1, results.size)
            assertEquals("World", (results[0] as ProcessedEvent.TextDelta).text)
        }

    @Test
    fun `ignores unknown message update types`() =
        runTest {
            val processor = BackpressureEventProcessor()

            val events =
                flowOf(
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent =
                            AssistantMessageEvent(
                                type = "unknown_type",
                            ),
                    ),
                )

            val results = processor.process(events).toList()
            assertTrue(results.isEmpty())
        }

    @Test
    fun `handles null assistantMessageEvent gracefully`() =
        runTest {
            val processor = BackpressureEventProcessor()

            val events =
                flowOf(
                    MessageUpdateEvent(
                        type = "message_update",
                        assistantMessageEvent = null,
                    ),
                )

            val results = processor.process(events).toList()
            assertTrue(results.isEmpty())
        }
}
