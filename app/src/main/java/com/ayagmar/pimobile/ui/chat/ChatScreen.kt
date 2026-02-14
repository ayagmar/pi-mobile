@file:Suppress("TooManyFunctions")

package com.ayagmar.pimobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
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
import com.ayagmar.pimobile.chat.ExtensionNotification
import com.ayagmar.pimobile.chat.ExtensionUiRequest
import com.ayagmar.pimobile.chat.ExtensionWidget
import com.ayagmar.pimobile.sessions.ModelInfo

private data class ChatCallbacks(
    val onToggleToolExpansion: (String) -> Unit,
    val onInputTextChanged: (String) -> Unit,
    val onSendPrompt: () -> Unit,
    val onAbort: () -> Unit,
    val onSteer: (String) -> Unit,
    val onFollowUp: (String) -> Unit,
    val onCycleModel: () -> Unit,
    val onCycleThinking: () -> Unit,
    val onSendExtensionUiResponse: (String, String?, Boolean?, Boolean) -> Unit,
    val onDismissExtensionRequest: () -> Unit,
    val onClearNotification: (Int) -> Unit,
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
                onCycleModel = chatViewModel::cycleModel,
                onCycleThinking = chatViewModel::cycleThinkingLevel,
                onSendExtensionUiResponse = chatViewModel::sendExtensionUiResponse,
                onDismissExtensionRequest = chatViewModel::dismissExtensionRequest,
                onClearNotification = chatViewModel::clearNotification,
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
    ChatScreenContent(
        state = state,
        callbacks = callbacks,
    )

    ExtensionUiDialogs(
        request = state.activeExtensionRequest,
        onSendResponse = callbacks.onSendExtensionUiResponse,
        onDismiss = callbacks.onDismissExtensionRequest,
    )

    NotificationsDisplay(
        notifications = state.notifications,
        onClear = callbacks.onClearNotification,
    )
}

@Composable
private fun ChatScreenContent(
    state: ChatUiState,
    callbacks: ChatCallbacks,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatHeader(
            state = state,
            callbacks = callbacks,
        )

        // Extension widgets (above editor)
        ExtensionWidgets(
            widgets = state.extensionWidgets,
            placement = "aboveEditor",
        )

        Box(modifier = Modifier.weight(1f)) {
            ChatBody(
                state = state,
                callbacks = callbacks,
            )
        }

        // Extension widgets (below editor)
        ExtensionWidgets(
            widgets = state.extensionWidgets,
            placement = "belowEditor",
        )

        // Extension statuses
        ExtensionStatuses(statuses = state.extensionStatuses)

        PromptControls(
            state = state,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun ChatHeader(
    state: ChatUiState,
    callbacks: ChatCallbacks,
) {
    // Show extension title if set, otherwise "Chat"
    val title = state.extensionTitle ?: "Chat"
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
    )

    // Only show connection status if no custom title
    if (state.extensionTitle == null) {
        Text(
            text = "Connection: ${state.connectionState.name.lowercase()}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    ModelThinkingControls(
        currentModel = state.currentModel,
        thinkingLevel = state.thinkingLevel,
        onCycleModel = callbacks.onCycleModel,
        onCycleThinking = callbacks.onCycleThinking,
    )

    state.errorMessage?.let { errorMessage ->
        Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChatBody(
    state: ChatUiState,
    callbacks: ChatCallbacks,
) {
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ExtensionUiDialogs(
    request: ExtensionUiRequest?,
    onSendResponse: (String, String?, Boolean?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (request) {
        is ExtensionUiRequest.Select -> {
            SelectDialog(
                request = request,
                onConfirm = { value ->
                    onSendResponse(request.requestId, value, null, false)
                },
                onDismiss = onDismiss,
            )
        }
        is ExtensionUiRequest.Confirm -> {
            ConfirmDialog(
                request = request,
                onConfirm = { confirmed ->
                    onSendResponse(request.requestId, null, confirmed, false)
                },
                onDismiss = onDismiss,
            )
        }
        is ExtensionUiRequest.Input -> {
            InputDialog(
                request = request,
                onConfirm = { value ->
                    onSendResponse(request.requestId, value, null, false)
                },
                onDismiss = onDismiss,
            )
        }
        is ExtensionUiRequest.Editor -> {
            EditorDialog(
                request = request,
                onConfirm = { value ->
                    onSendResponse(request.requestId, value, null, false)
                },
                onDismiss = onDismiss,
            )
        }
        null -> Unit
    }
}

@Composable
private fun SelectDialog(
    request: ExtensionUiRequest.Select,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.options.forEach { option ->
                    TextButton(
                        onClick = { onConfirm(option) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    request: ExtensionUiRequest.Confirm,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = {
            Button(onClick = { onConfirm(true) }) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(false) }) {
                Text("No")
            }
        },
    )
}

@Composable
private fun InputDialog(
    request: ExtensionUiRequest.Input,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = request.placeholder?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EditorDialog(
    request: ExtensionUiRequest.Editor,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(request.prefill) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                singleLine = false,
                maxLines = 10,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NotificationsDisplay(
    notifications: List<ExtensionNotification>,
    onClear: (Int) -> Unit,
) {
    notifications.forEachIndexed { index, notification ->
        val color =
            when (notification.type) {
                "error" -> MaterialTheme.colorScheme.error
                "warning" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }

        androidx.compose.material3.Snackbar(
            action = {
                TextButton(onClick = { onClear(index) }) {
                    Text("Dismiss")
                }
            },
            modifier = Modifier.padding(8.dp),
        ) {
            Text(
                text = notification.message,
                color = color,
            )
        }
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

@Composable
private fun ModelThinkingControls(
    currentModel: ModelInfo?,
    thinkingLevel: String?,
    onCycleModel: () -> Unit,
    onCycleThinking: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val modelText = currentModel?.let { "${it.name} (${it.provider})" } ?: "No model"
        val thinkingText = thinkingLevel?.let { "Thinking: $it" } ?: "Thinking: off"

        TextButton(
            onClick = onCycleModel,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = modelText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }

        TextButton(
            onClick = onCycleThinking,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = thinkingText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ExtensionWidgets(
    widgets: Map<String, ExtensionWidget>,
    placement: String,
) {
    val matchingWidgets = widgets.values.filter { it.placement == placement }

    matchingWidgets.forEach { widget ->
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
            ) {
                widget.lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionStatuses(statuses: Map<String, String>) {
    if (statuses.isEmpty()) return

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        statuses.values.forEach { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val COLLAPSED_OUTPUT_LENGTH = 280
