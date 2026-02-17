package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.chat.ChatTimelineItem
import java.util.Locale

internal enum class LiveRunPhase(
    val label: String,
) {
    WORKING("Working"),
    THINKING("Thinking"),
    RESPONDING("Responding"),
    RUNNING_TOOLS("Running tools"),
    RETRYING("Retrying"),
}

internal fun inferLiveRunPhase(
    isRetrying: Boolean,
    timeline: List<ChatTimelineItem>,
): LiveRunPhase {
    if (isRetrying) return LiveRunPhase.RETRYING

    val latestStreamingItem =
        timeline
            .asReversed()
            .firstOrNull { item ->
                when (item) {
                    is ChatTimelineItem.Assistant -> item.isStreaming
                    is ChatTimelineItem.Tool -> item.isStreaming
                    is ChatTimelineItem.User -> false
                }
            }

    return when (latestStreamingItem) {
        is ChatTimelineItem.Tool -> LiveRunPhase.RUNNING_TOOLS
        is ChatTimelineItem.Assistant -> {
            if (!latestStreamingItem.thinking.isNullOrBlank() && !latestStreamingItem.isThinkingComplete) {
                LiveRunPhase.THINKING
            } else {
                LiveRunPhase.RESPONDING
            }
        }
        else -> LiveRunPhase.WORKING
    }
}

internal fun formatRunElapsed(elapsedSeconds: Long): String {
    val safeSeconds = elapsedSeconds.coerceAtLeast(0L)
    val minutes = safeSeconds / SECONDS_PER_MINUTE
    val seconds = safeSeconds % SECONDS_PER_MINUTE
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private const val SECONDS_PER_MINUTE = 60L
