package com.ayagmar.pimobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.chat.ChatTimelineItem
import com.ayagmar.pimobile.chat.ChatUiState
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ChatViewModelFactory

private data class ChatCallbacks(
    val onToggleToolExpansion: (String) -> Unit,
    val onInputTextChanged: (String) -> Unit,
    val onSendPrompt: () -> Unit,
    val onAbort: () -> Unit,
    val onSteer: (String) -> Unit,
    val onFollowUp: (String) -> Unit,
)

@Composable
fun ChatRoute() {
    val factory = remember { ChatViewModelFactory() }
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    val callbacks =
        remember {
            ChatCallbacks(
                onToggleToolExpansion = chatViewModel::toggleToolExpansion,
                onInputTextChanged = chatViewModel::onInputTextChanged,
                onSendPrompt = chatViewModel::sendPrompt,
                onAbort = chatViewModel::abort,
                onSteer = chatViewModel::steer,
                onFollowUp = chatViewModel::followUp,
            )
        }

    ChatScreen(
        state = uiState,
        callbacks = callbacks,
    )
}

@Composable
private fun ChatScreen(
    state: ChatUiState,
    callbacks: ChatCallbacks,
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
                onToggleToolExpansion = callbacks.onToggleToolExpansion,
                modifier = Modifier.weight(1f),
            )
        }

        PromptControls(
            state = state,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun ChatTimeline(
    timeline: List<ChatTimelineItem>,
    onToggleToolExpansion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
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

@Composable
private fun PromptControls(
    state: ChatUiState,
    callbacks: ChatCallbacks,
) {
    var showSteerDialog by remember { mutableStateOf(false) }
    var showFollowUpDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.isStreaming) {
            StreamingControls(
                onAbort = callbacks.onAbort,
                onSteerClick = { showSteerDialog = true },
                onFollowUpClick = { showFollowUpDialog = true },
            )
        }

        PromptInputRow(
            inputText = state.inputText,
            isStreaming = state.isStreaming,
            onInputTextChanged = callbacks.onInputTextChanged,
            onSendPrompt = callbacks.onSendPrompt,
        )
    }

    if (showSteerDialog) {
        SteerFollowUpDialog(
            title = "Steer",
            onDismiss = { showSteerDialog = false },
            onConfirm = { message ->
                callbacks.onSteer(message)
                showSteerDialog = false
            },
        )
    }

    if (showFollowUpDialog) {
        SteerFollowUpDialog(
            title = "Follow Up",
            onDismiss = { showFollowUpDialog = false },
            onConfirm = { message ->
                callbacks.onFollowUp(message)
                showFollowUpDialog = false
            },
        )
    }
}

@Composable
private fun StreamingControls(
    onAbort: () -> Unit,
    onSteerClick: () -> Unit,
    onFollowUpClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onAbort,
            modifier = Modifier.weight(1f),
            colors =
                androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Abort",
                modifier = Modifier.padding(end = 4.dp),
            )
            Text("Abort")
        }

        Button(
            onClick = onSteerClick,
            modifier = Modifier.weight(1f),
        ) {
            Text("Steer")
        }

        Button(
            onClick = onFollowUpClick,
            modifier = Modifier.weight(1f),
        ) {
            Text("Follow Up")
        }
    }
}

@Composable
private fun PromptInputRow(
    inputText: String,
    isStreaming: Boolean,
    onInputTextChanged: (String) -> Unit,
    onSendPrompt: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputTextChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message...") },
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSendPrompt() }),
            enabled = !isStreaming,
        )

        IconButton(
            onClick = onSendPrompt,
            enabled = inputText.isNotBlank() && !isStreaming,
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send",
            )
        }
    }
}

@Composable
private fun SteerFollowUpDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Enter your message...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 6,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private const val COLLAPSED_OUTPUT_LENGTH = 280
