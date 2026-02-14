package com.ayagmar.pimobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.chat.ChatTimelineItem
import com.ayagmar.pimobile.chat.ChatUiState
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ChatViewModelFactory

@Composable
fun ChatRoute() {
    val factory = remember { ChatViewModelFactory() }
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    ChatScreen(
        state = uiState,
        onToggleToolExpansion = chatViewModel::toggleToolExpansion,
    )
}

@Composable
private fun ChatScreen(
    state: ChatUiState,
    onToggleToolExpansion: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Chat",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Connection: ${state.connectionState.name.lowercase()}",
            style = MaterialTheme.typography.bodyMedium,
        )

        state.errorMessage?.let { errorMessage ->
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.timeline.isEmpty()) {
            Text(
                text = "No chat messages yet. Resume a session and send a prompt.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            ChatTimeline(
                timeline = state.timeline,
                onToggleToolExpansion = onToggleToolExpansion,
            )
        }
    }
}

@Composable
private fun ChatTimeline(
    timeline: List<ChatTimelineItem>,
    onToggleToolExpansion: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = timeline, key = { item -> item.id }) { item ->
            when (item) {
                is ChatTimelineItem.User -> TimelineCard(title = "User", text = item.text)
                is ChatTimelineItem.Assistant -> {
                    val title = if (item.isStreaming) "Assistant (streaming)" else "Assistant"
                    TimelineCard(title = title, text = item.text)
                }

                is ChatTimelineItem.Tool -> {
                    ToolCard(
                        item = item,
                        onToggleToolExpansion = onToggleToolExpansion,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(
    title: String,
    text: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = text.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ToolCard(
    item: ChatTimelineItem.Tool,
    onToggleToolExpansion: (String) -> Unit,
) {
    val displayOutput =
        if (item.isCollapsed && item.output.length > COLLAPSED_OUTPUT_LENGTH) {
            item.output.take(COLLAPSED_OUTPUT_LENGTH) + "…"
        } else {
            item.output
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val suffix =
                when {
                    item.isError -> "(error)"
                    item.isStreaming -> "(running)"
                    else -> ""
                }

            Text(
                text = "Tool: ${item.toolName} $suffix".trim(),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = displayOutput.ifBlank { "(no output yet)" },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (item.output.length > COLLAPSED_OUTPUT_LENGTH) {
                TextButton(onClick = { onToggleToolExpansion(item.id) }) {
                    Text(if (item.isCollapsed) "Expand" else "Collapse")
                }
            }
        }
    }
}

private const val COLLAPSED_OUTPUT_LENGTH = 280
