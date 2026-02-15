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
        if (existingIndex < 0) return state

        val existing = state.timeline[existingIndex]
        if (existing !is ChatTimelineItem.Assistant) return state

        val updatedTimeline = state.timeline.toMutableList()
        updatedTimeline[existingIndex] =
            existing.copy(
                isThinkingExpanded = !existing.isThinkingExpanded,
            )

        return state.copy(timeline = updatedTimeline)
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
}
