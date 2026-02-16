package com.ayagmar.pimobile.chat

internal object ChatTimelineReducer {
    fun toggleToolExpansion(
        state: ChatUiState,
        itemId: String,
    ): ChatUiState {
        return state.copy(
            timeline =
                state.timeline.map { item ->
                    if (item is ChatTimelineItem.Tool && item.id == itemId) {
                        item.copy(isCollapsed = !item.isCollapsed)
                    } else {
                        item
                    }
                },
        )
    }

    fun toggleDiffExpansion(
        state: ChatUiState,
        itemId: String,
    ): ChatUiState {
        return state.copy(
            timeline =
                state.timeline.map { item ->
                    if (item is ChatTimelineItem.Tool && item.id == itemId) {
                        item.copy(isDiffExpanded = !item.isDiffExpanded)
                    } else {
                        item
                    }
                },
        )
    }

    fun toggleThinkingExpansion(
        state: ChatUiState,
        itemId: String,
    ): ChatUiState {
        val existingIndex = state.timeline.indexOfFirst { it.id == itemId }
        val existing = state.timeline.getOrNull(existingIndex)
        val assistantItem = existing as? ChatTimelineItem.Assistant

        return if (existingIndex < 0 || assistantItem == null) {
            state
        } else {
            val updatedTimeline = state.timeline.toMutableList()
            updatedTimeline[existingIndex] =
                assistantItem.copy(
                    isThinkingExpanded = !assistantItem.isThinkingExpanded,
                )
            state.copy(timeline = updatedTimeline)
        }
    }

    fun toggleToolArgumentsExpansion(
        state: ChatUiState,
        itemId: String,
    ): ChatUiState {
        val expanded = state.expandedToolArguments.toMutableSet()
        if (itemId in expanded) {
            expanded.remove(itemId)
        } else {
            expanded.add(itemId)
        }

        return state.copy(expandedToolArguments = expanded)
    }

    fun upsertTimelineItem(
        state: ChatUiState,
        item: ChatTimelineItem,
        maxTimelineItems: Int,
    ): ChatUiState {
        val targetIndex = findUpsertTargetIndex(state.timeline, item)
        val updatedTimeline =
            if (targetIndex >= 0) {
                state.timeline.toMutableList().also { timeline ->
                    val existing = timeline[targetIndex]
                    timeline[targetIndex] = mergeTimelineItems(existing = existing, incoming = item)
                }
            } else {
                state.timeline + item
            }

        return state.copy(timeline = limitTimeline(updatedTimeline, maxTimelineItems))
    }

    fun limitTimeline(
        timeline: List<ChatTimelineItem>,
        maxTimelineItems: Int,
    ): List<ChatTimelineItem> {
        if (timeline.size <= maxTimelineItems) {
            return timeline
        }

        return timeline.takeLast(maxTimelineItems)
    }
}

private const val ASSISTANT_STREAM_PREFIX = "assistant-stream-"

private fun findUpsertTargetIndex(
    timeline: List<ChatTimelineItem>,
    incoming: ChatTimelineItem,
): Int {
    val directIndex = timeline.indexOfFirst { existing -> existing.id == incoming.id }
    val contentIndex = assistantStreamContentIndex(incoming.id)
    return when {
        directIndex >= 0 -> directIndex
        incoming !is ChatTimelineItem.Assistant -> -1
        contentIndex == null -> -1
        else ->
            timeline.indexOfFirst { existing ->
                existing is ChatTimelineItem.Assistant &&
                    existing.isStreaming &&
                    assistantStreamContentIndex(existing.id) == contentIndex
            }
    }
}

private fun mergeTimelineItems(
    existing: ChatTimelineItem,
    incoming: ChatTimelineItem,
): ChatTimelineItem {
    return when {
        existing is ChatTimelineItem.Tool && incoming is ChatTimelineItem.Tool -> {
            incoming.copy(
                isCollapsed = existing.isCollapsed,
                isDiffExpanded = existing.isDiffExpanded,
                arguments = incoming.arguments.takeIf { it.isNotEmpty() } ?: existing.arguments,
                editDiff = incoming.editDiff ?: existing.editDiff,
            )
        }
        existing is ChatTimelineItem.Assistant && incoming is ChatTimelineItem.Assistant -> {
            incoming.copy(
                text = mergeStreamingContent(existing.text, incoming.text).orEmpty(),
                thinking = mergeStreamingContent(existing.thinking, incoming.thinking),
                isThinkingExpanded = existing.isThinkingExpanded,
            )
        }
        else -> incoming
    }
}

private fun assistantStreamContentIndex(itemId: String): Int? {
    if (!itemId.startsWith(ASSISTANT_STREAM_PREFIX)) return null
    return itemId.substringAfterLast('-').toIntOrNull()
}

private fun mergeStreamingContent(
    previous: String?,
    incoming: String?,
): String? {
    return when {
        previous.isNullOrEmpty() -> incoming
        incoming.isNullOrEmpty() -> previous
        incoming.startsWith(previous) -> incoming
        previous.startsWith(incoming) -> previous
        else -> mergeStreamingContentWithOverlap(previous, incoming)
    }
}

private fun mergeStreamingContentWithOverlap(
    previous: String,
    incoming: String,
): String {
    val maxOverlap = minOf(previous.length, incoming.length)
    var overlapLength = 0
    for (overlap in maxOverlap downTo 1) {
        if (previous.endsWith(incoming.substring(0, overlap))) {
            overlapLength = overlap
            break
        }
    }
    return previous + incoming.substring(overlapLength)
}
