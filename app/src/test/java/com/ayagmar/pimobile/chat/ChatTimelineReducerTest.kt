package com.ayagmar.pimobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineReducerTest {
    @Test
    fun upsertAssistantMergesAcrossStreamingIdChangesAndPreservesExpansion() {
        val initialAssistant =
            ChatTimelineItem.Assistant(
                id = "assistant-stream-active-0",
                text = "Hello",
                thinking = "Planning",
                isThinkingExpanded = true,
                isStreaming = true,
            )

        val initialState = ChatUiState(timeline = listOf(initialAssistant))

        val incomingAssistant =
            ChatTimelineItem.Assistant(
                id = "assistant-stream-1733234567900-0",
                text = "Hello world",
                thinking = "Planning done",
                isThinkingExpanded = false,
                isStreaming = true,
            )

        val nextState =
            ChatTimelineReducer.upsertTimelineItem(
                state = initialState,
                item = incomingAssistant,
                maxTimelineItems = 400,
            )

        val merged = nextState.timeline.single() as ChatTimelineItem.Assistant
        assertEquals("assistant-stream-1733234567900-0", merged.id)
        assertEquals("Hello world", merged.text)
        assertEquals("Planning done", merged.thinking)
        assertTrue(merged.isThinkingExpanded)
    }

    @Test
    fun upsertToolPreservesManualCollapseAndExistingArguments() {
        val initialTool =
            ChatTimelineItem.Tool(
                id = "tool-call-1",
                toolName = "bash",
                output = "Running",
                isCollapsed = false,
                isStreaming = true,
                isError = false,
                arguments = mapOf("command" to "ls"),
            )
        val initialState = ChatUiState(timeline = listOf(initialTool))

        val incomingTool =
            ChatTimelineItem.Tool(
                id = "tool-call-1",
                toolName = "bash",
                output = "Done",
                isCollapsed = true,
                isStreaming = false,
                isError = false,
                arguments = emptyMap(),
            )

        val nextState =
            ChatTimelineReducer.upsertTimelineItem(
                state = initialState,
                item = incomingTool,
                maxTimelineItems = 400,
            )

        val merged = nextState.timeline.single() as ChatTimelineItem.Tool
        assertFalse(merged.isCollapsed)
        assertEquals(mapOf("command" to "ls"), merged.arguments)
        assertEquals("Done", merged.output)
    }

    @Test
    fun limitTimelineKeepsMostRecentEntriesOnly() {
        val timeline =
            listOf(
                ChatTimelineItem.User(id = "u1", text = "1"),
                ChatTimelineItem.User(id = "u2", text = "2"),
                ChatTimelineItem.User(id = "u3", text = "3"),
            )

        val limited = ChatTimelineReducer.limitTimeline(timeline, maxTimelineItems = 2)

        assertEquals(listOf("u2", "u3"), limited.map { it.id })
    }
}
