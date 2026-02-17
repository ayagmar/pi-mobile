package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.chat.ChatTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRunProgressTest {
    @Test
    fun retryingStateTakesPriorityOverTimeline() {
        val phase =
            inferLiveRunPhase(
                isRetrying = true,
                timeline =
                    listOf(
                        ChatTimelineItem.Assistant(
                            id = "assistant-1",
                            text = "",
                            thinking = "draft",
                            isThinkingComplete = false,
                            isStreaming = true,
                        ),
                    ),
            )

        assertEquals(LiveRunPhase.RETRYING, phase)
    }

    @Test
    fun infersThinkingWhenAssistantHasIncompleteThinkingContent() {
        val phase =
            inferLiveRunPhase(
                isRetrying = false,
                timeline =
                    listOf(
                        ChatTimelineItem.Assistant(
                            id = "assistant-1",
                            text = "",
                            thinking = "reasoning",
                            isThinkingComplete = false,
                            isStreaming = true,
                        ),
                    ),
            )

        assertEquals(LiveRunPhase.THINKING, phase)
    }

    @Test
    fun infersRespondingForStreamingAssistantWithoutActiveThinking() {
        val phase =
            inferLiveRunPhase(
                isRetrying = false,
                timeline =
                    listOf(
                        ChatTimelineItem.Assistant(
                            id = "assistant-1",
                            text = "partial",
                            thinking = "done",
                            isThinkingComplete = true,
                            isStreaming = true,
                        ),
                    ),
            )

        assertEquals(LiveRunPhase.RESPONDING, phase)
    }

    @Test
    fun infersToolPhaseWhenLatestStreamingItemIsTool() {
        val phase =
            inferLiveRunPhase(
                isRetrying = false,
                timeline =
                    listOf(
                        ChatTimelineItem.Assistant(
                            id = "assistant-1",
                            text = "partial",
                            thinking = null,
                            isStreaming = true,
                        ),
                        ChatTimelineItem.Tool(
                            id = "tool-1",
                            toolName = "bash",
                            output = "running",
                            isCollapsed = true,
                            isStreaming = true,
                            isError = false,
                        ),
                    ),
            )

        assertEquals(LiveRunPhase.RUNNING_TOOLS, phase)
    }

    @Test
    fun fallsBackToWorkingWhenNoStreamingTimelineContext() {
        val phase =
            inferLiveRunPhase(
                isRetrying = false,
                timeline =
                    listOf(
                        ChatTimelineItem.User(
                            id = "user-1",
                            text = "hello",
                        ),
                    ),
            )

        assertEquals(LiveRunPhase.WORKING, phase)
    }

    @Test
    fun formatsElapsedAsMinuteSecond() {
        assertEquals("00:00", formatRunElapsed(0))
        assertEquals("00:09", formatRunElapsed(9))
        assertEquals("01:01", formatRunElapsed(61))
    }
}
