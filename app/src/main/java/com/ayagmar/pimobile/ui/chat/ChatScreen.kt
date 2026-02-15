@file:Suppress("TooManyFunctions")

package com.ayagmar.pimobile.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ayagmar.pimobile.chat.ChatTimelineItem
import com.ayagmar.pimobile.chat.ChatUiState
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ChatViewModelFactory
import com.ayagmar.pimobile.chat.ExtensionNotification
import com.ayagmar.pimobile.chat.ExtensionUiRequest
import com.ayagmar.pimobile.chat.ExtensionWidget
import com.ayagmar.pimobile.chat.ImageEncoder
import com.ayagmar.pimobile.chat.PendingImage
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionTreeEntry
import com.ayagmar.pimobile.sessions.SessionTreeSnapshot
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.delay

private data class ChatCallbacks(
    val onToggleToolExpansion: (String) -> Unit,
    val onToggleThinkingExpansion: (String) -> Unit,
    val onToggleDiffExpansion: (String) -> Unit,
    val onToggleToolArgumentsExpansion: (String) -> Unit,
    val onLoadOlderMessages: () -> Unit,
    val onInputTextChanged: (String) -> Unit,
    val onSendPrompt: () -> Unit,
    val onAbort: () -> Unit,
    val onSteer: (String) -> Unit,
    val onFollowUp: (String) -> Unit,
    val onSetThinkingLevel: (String) -> Unit,
    val onFetchLastAssistantText: ((String?) -> Unit) -> Unit,
    val onAbortRetry: () -> Unit,
    val onSendExtensionUiResponse: (String, String?, Boolean?, Boolean) -> Unit,
    val onDismissExtensionRequest: () -> Unit,
    val onClearNotification: (Int) -> Unit,
    val onShowCommandPalette: () -> Unit,
    val onHideCommandPalette: () -> Unit,
    val onCommandsQueryChanged: (String) -> Unit,
    val onCommandSelected: (SlashCommandInfo) -> Unit,
    // Bash callbacks
    val onShowBashDialog: () -> Unit,
    val onHideBashDialog: () -> Unit,
    val onBashCommandChanged: (String) -> Unit,
    val onExecuteBash: () -> Unit,
    val onAbortBash: () -> Unit,
    val onSelectBashHistory: (String) -> Unit,
    // Session stats callbacks
    val onShowStatsSheet: () -> Unit,
    val onHideStatsSheet: () -> Unit,
    val onRefreshStats: () -> Unit,
    // Model picker callbacks
    val onShowModelPicker: () -> Unit,
    val onHideModelPicker: () -> Unit,
    val onModelsQueryChanged: (String) -> Unit,
    val onSelectModel: (AvailableModel) -> Unit,
    // Tree navigation callbacks
    val onShowTreeSheet: () -> Unit,
    val onHideTreeSheet: () -> Unit,
    val onForkFromTreeEntry: (String) -> Unit,
    val onJumpAndContinueFromTreeEntry: (String) -> Unit,
    val onTreeFilterChanged: (String) -> Unit,
    // Image attachment callbacks
    val onAddImage: (PendingImage) -> Unit,
    val onRemoveImage: (Int) -> Unit,
    val onClearImages: () -> Unit,
)

@Composable
fun ChatRoute() {
    val context = LocalContext.current
    val imageEncoder = remember { ImageEncoder(context) }
    val factory = remember { ChatViewModelFactory(imageEncoder = imageEncoder) }
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    val callbacks =
        remember(chatViewModel) {
            ChatCallbacks(
                onToggleToolExpansion = chatViewModel::toggleToolExpansion,
                onToggleThinkingExpansion = chatViewModel::toggleThinkingExpansion,
                onToggleDiffExpansion = chatViewModel::toggleDiffExpansion,
                onToggleToolArgumentsExpansion = chatViewModel::toggleToolArgumentsExpansion,
                onLoadOlderMessages = chatViewModel::loadOlderMessages,
                onInputTextChanged = chatViewModel::onInputTextChanged,
                onSendPrompt = chatViewModel::sendPrompt,
                onAbort = chatViewModel::abort,
                onSteer = chatViewModel::steer,
                onFollowUp = chatViewModel::followUp,
                onSetThinkingLevel = chatViewModel::setThinkingLevel,
                onFetchLastAssistantText = chatViewModel::fetchLastAssistantText,
                onAbortRetry = chatViewModel::abortRetry,
                onSendExtensionUiResponse = chatViewModel::sendExtensionUiResponse,
                onDismissExtensionRequest = chatViewModel::dismissExtensionRequest,
                onClearNotification = chatViewModel::clearNotification,
                onShowCommandPalette = chatViewModel::showCommandPalette,
                onHideCommandPalette = chatViewModel::hideCommandPalette,
                onCommandsQueryChanged = chatViewModel::onCommandsQueryChanged,
                onCommandSelected = chatViewModel::onCommandSelected,
                onShowBashDialog = chatViewModel::showBashDialog,
                onHideBashDialog = chatViewModel::hideBashDialog,
                onBashCommandChanged = chatViewModel::onBashCommandChanged,
                onExecuteBash = chatViewModel::executeBash,
                onAbortBash = chatViewModel::abortBash,
                onSelectBashHistory = chatViewModel::selectBashHistoryItem,
                onShowStatsSheet = chatViewModel::showStatsSheet,
                onHideStatsSheet = chatViewModel::hideStatsSheet,
                onRefreshStats = chatViewModel::refreshSessionStats,
                onShowModelPicker = chatViewModel::showModelPicker,
                onHideModelPicker = chatViewModel::hideModelPicker,
                onModelsQueryChanged = chatViewModel::onModelsQueryChanged,
                onSelectModel = chatViewModel::selectModel,
                onShowTreeSheet = chatViewModel::showTreeSheet,
                onHideTreeSheet = chatViewModel::hideTreeSheet,
                onForkFromTreeEntry = chatViewModel::forkFromTreeEntry,
                onJumpAndContinueFromTreeEntry = chatViewModel::jumpAndContinueFromTreeEntry,
                onTreeFilterChanged = chatViewModel::setTreeFilter,
                onAddImage = chatViewModel::addImage,
                onRemoveImage = chatViewModel::removeImage,
                onClearImages = chatViewModel::clearImages,
            )
        }

    ChatScreen(
        state = uiState,
        callbacks = callbacks,
    )
}

@Suppress("LongMethod")
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

    CommandPalette(
        isVisible = state.isCommandPaletteVisible,
        commands = state.commands,
        query = state.commandsQuery,
        isLoading = state.isLoadingCommands,
        onQueryChange = callbacks.onCommandsQueryChanged,
        onCommandSelected = callbacks.onCommandSelected,
        onDismiss = callbacks.onHideCommandPalette,
    )

    BashDialog(
        isVisible = state.isBashDialogVisible,
        command = state.bashCommand,
        output = state.bashOutput,
        exitCode = state.bashExitCode,
        isExecuting = state.isBashExecuting,
        wasTruncated = state.bashWasTruncated,
        fullLogPath = state.bashFullLogPath,
        history = state.bashHistory,
        onCommandChange = callbacks.onBashCommandChanged,
        onExecute = callbacks.onExecuteBash,
        onAbort = callbacks.onAbortBash,
        onSelectHistory = callbacks.onSelectBashHistory,
        onDismiss = callbacks.onHideBashDialog,
    )

    SessionStatsSheet(
        isVisible = state.isStatsSheetVisible,
        stats = state.sessionStats,
        isLoading = state.isLoadingStats,
        onRefresh = callbacks.onRefreshStats,
        onDismiss = callbacks.onHideStatsSheet,
    )

    ModelPickerSheet(
        isVisible = state.isModelPickerVisible,
        models = state.availableModels,
        currentModel = state.currentModel,
        query = state.modelsQuery,
        isLoading = state.isLoadingModels,
        onQueryChange = callbacks.onModelsQueryChanged,
        onSelectModel = callbacks.onSelectModel,
        onDismiss = callbacks.onHideModelPicker,
    )

    TreeNavigationSheet(
        isVisible = state.isTreeSheetVisible,
        tree = state.sessionTree,
        selectedFilter = state.treeFilter,
        isLoading = state.isLoadingTree,
        errorMessage = state.treeErrorMessage,
        onFilterChange = callbacks.onTreeFilterChanged,
        onForkFromEntry = callbacks.onForkFromTreeEntry,
        onJumpAndContinue = callbacks.onJumpAndContinueFromTreeEntry,
        onDismiss = callbacks.onHideTreeSheet,
    )
}

@Composable
private fun ChatScreenContent(
    state: ChatUiState,
    callbacks: ChatCallbacks,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (state.isStreaming) 8.dp else 12.dp),
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

@Suppress("LongMethod")
@Composable
private fun ChatHeader(
    state: ChatUiState,
    callbacks: ChatCallbacks,
) {
    val clipboardManager = LocalClipboardManager.current
    val isCompact = state.isStreaming

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val title = state.extensionTitle ?: "Chat"
            Text(
                text = title,
                style = if (isCompact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            )

            if (!isCompact && state.extensionTitle == null) {
                Text(
                    text = "Connection: ${state.connectionState.name.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = callbacks.onShowTreeSheet) {
                Text("Tree")
            }

            if (isCompact) {
                IconButton(onClick = callbacks.onShowModelPicker) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Select model",
                    )
                }
            } else {
                IconButton(onClick = callbacks.onShowStatsSheet) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "Session Stats",
                    )
                }

                IconButton(
                    onClick = {
                        callbacks.onFetchLastAssistantText { text ->
                            text?.let { clipboardManager.setText(AnnotatedString(it)) }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy last assistant text",
                    )
                }

                IconButton(onClick = callbacks.onShowBashDialog) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Run Bash",
                    )
                }
            }
        }
    }

    ModelThinkingControls(
        currentModel = state.currentModel,
        thinkingLevel = state.thinkingLevel,
        onSetThinkingLevel = callbacks.onSetThinkingLevel,
        onShowModelPicker = callbacks.onShowModelPicker,
        compact = isCompact,
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
            hasOlderMessages = state.hasOlderMessages,
            hiddenHistoryCount = state.hiddenHistoryCount,
            expandedToolArguments = state.expandedToolArguments,
            onLoadOlderMessages = callbacks.onLoadOlderMessages,
            onToggleToolExpansion = callbacks.onToggleToolExpansion,
            onToggleThinkingExpansion = callbacks.onToggleThinkingExpansion,
            onToggleDiffExpansion = callbacks.onToggleDiffExpansion,
            onToggleToolArgumentsExpansion = callbacks.onToggleToolArgumentsExpansion,
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
    var text by rememberSaveable(request.requestId) { mutableStateOf("") }

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
    var text by rememberSaveable(request.requestId) { mutableStateOf(request.prefill) }

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
    // Only show the most recent notification
    val latestNotification = notifications.lastOrNull() ?: return
    val index = notifications.lastIndex

    // Auto-dismiss after 4 seconds
    LaunchedEffect(index) {
        delay(NOTIFICATION_AUTO_DISMISS_MS)
        onClear(index)
    }

    val color =
        when (latestNotification.type) {
            "error" -> MaterialTheme.colorScheme.error
            "warning" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }

    val containerColor =
        when (latestNotification.type) {
            "error" -> MaterialTheme.colorScheme.errorContainer
            "warning" -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }

    Snackbar(
        action = {
            TextButton(onClick = { onClear(index) }) {
                Text("Dismiss")
            }
        },
        containerColor = containerColor,
        modifier = Modifier.padding(8.dp),
    ) {
        Text(
            text = latestNotification.message,
            color = color,
        )
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun CommandPalette(
    isVisible: Boolean,
    commands: List<SlashCommandInfo>,
    query: String,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onCommandSelected: (SlashCommandInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val filteredCommands =
        remember(commands, query) {
            if (query.isBlank()) {
                commands
            } else {
                commands.filter { command ->
                    command.name.contains(query, ignoreCase = true) ||
                        command.description?.contains(query, ignoreCase = true) == true
                }
            }
        }

    val groupedCommands =
        remember(filteredCommands) {
            filteredCommands.groupBy { it.source }
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commands") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search commands...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredCommands.isEmpty()) {
                    Text(
                        text = "No commands found",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        groupedCommands.forEach { (source, commandsInGroup) ->
                            item {
                                Text(
                                    text = source.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            items(
                                items = commandsInGroup,
                                key = { command -> "${command.source}:${command.name}" },
                            ) { command ->
                                CommandItem(
                                    command = command,
                                    onClick = { onCommandSelected(command) },
                                )
                            }
                        }
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
private fun CommandItem(
    command: SlashCommandInfo,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "/${command.name}",
                style = MaterialTheme.typography.bodyMedium,
            )
            command.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ChatTimeline(
    timeline: List<ChatTimelineItem>,
    hasOlderMessages: Boolean,
    hiddenHistoryCount: Int,
    expandedToolArguments: Set<String>,
    onLoadOlderMessages: () -> Unit,
    onToggleToolExpansion: (String) -> Unit,
    onToggleThinkingExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleToolArgumentsExpansion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive or during streaming
    LaunchedEffect(timeline.size, timeline.lastOrNull()?.id) {
        if (timeline.isNotEmpty()) {
            listState.animateScrollToItem(timeline.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasOlderMessages) {
            item(key = "load-older-messages") {
                TextButton(
                    onClick = onLoadOlderMessages,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load older messages ($hiddenHistoryCount hidden)")
                }
            }
        }

        items(items = timeline, key = { item -> item.id }) { item ->
            when (item) {
                is ChatTimelineItem.User -> TimelineCard(title = "User", text = item.text)
                is ChatTimelineItem.Assistant -> {
                    AssistantCard(
                        item = item,
                        onToggleThinkingExpansion = onToggleThinkingExpansion,
                    )
                }

                is ChatTimelineItem.Tool -> {
                    ToolCard(
                        item = item,
                        isArgumentsExpanded = item.id in expandedToolArguments,
                        onToggleToolExpansion = onToggleToolExpansion,
                        onToggleDiffExpansion = onToggleDiffExpansion,
                        onToggleArgumentsExpansion = onToggleToolArgumentsExpansion,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = text.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantCard(
    item: ChatTimelineItem.Assistant,
    onToggleThinkingExpansion: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = if (item.isStreaming) "Assistant (streaming)" else "Assistant"
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Text(
                text = item.text.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            ThinkingBlock(
                thinking = item.thinking,
                isThinkingComplete = item.isThinkingComplete,
                isThinkingExpanded = item.isThinkingExpanded,
                itemId = item.id,
                onToggleThinkingExpansion = onToggleThinkingExpansion,
            )
        }
    }
}

@Composable
private fun ThinkingBlock(
    thinking: String?,
    isThinkingComplete: Boolean,
    isThinkingExpanded: Boolean,
    itemId: String,
    onToggleThinkingExpansion: (String) -> Unit,
) {
    if (thinking == null) return

    val shouldCollapse = thinking.length > THINKING_COLLAPSE_THRESHOLD
    val displayThinking =
        if (!isThinkingExpanded && shouldCollapse) {
            thinking.take(THINKING_COLLAPSE_THRESHOLD) + "…"
        } else {
            thinking
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            ),
        border =
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = if (isThinkingComplete) " Thinking" else " Thinking…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = displayThinking,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            if (shouldCollapse || isThinkingExpanded) {
                TextButton(
                    onClick = { onToggleThinkingExpansion(itemId) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        if (isThinkingExpanded) "Show less" else "Show more",
                    )
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ToolCard(
    item: ChatTimelineItem.Tool,
    isArgumentsExpanded: Boolean,
    onToggleToolExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleArgumentsExpansion: (String) -> Unit,
) {
    val isEditTool = item.toolName == "edit" && item.editDiff != null
    val toolInfo = getToolInfo(item.toolName)
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Tool header with icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tool icon with color
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(toolInfo.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = toolInfo.icon,
                        contentDescription = item.toolName,
                        tint = toolInfo.color,
                        modifier = Modifier.size(18.dp),
                    )
                }

                val suffix =
                    when {
                        item.isError -> "(error)"
                        item.isStreaming -> "(running)"
                        else -> ""
                    }

                Text(
                    text = "${item.toolName} $suffix".trim(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )

                if (item.isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // Collapsible arguments section
            if (item.arguments.isNotEmpty()) {
                ToolArgumentsSection(
                    arguments = item.arguments,
                    isExpanded = isArgumentsExpanded,
                    onToggleExpand = { onToggleArgumentsExpansion(item.id) },
                    onCopy = {
                        val argsJson = item.arguments.entries.joinToString("\n") { (k, v) -> "\"$k\": \"$v\"" }
                        clipboardManager.setText(AnnotatedString("{\n$argsJson\n}"))
                    },
                )
            }

            // Show diff viewer for edit tools, otherwise show standard output
            if (isEditTool && item.editDiff != null) {
                DiffViewer(
                    diffInfo = item.editDiff,
                    isCollapsed = !item.isDiffExpanded,
                    onToggleCollapse = { onToggleDiffExpansion(item.id) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                val displayOutput =
                    if (item.isCollapsed && item.output.length > COLLAPSED_OUTPUT_LENGTH) {
                        item.output.take(COLLAPSED_OUTPUT_LENGTH) + "…"
                    } else {
                        item.output
                    }

                SelectionContainer {
                    Text(
                        text = displayOutput.ifBlank { "(no output yet)" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                if (item.output.length > COLLAPSED_OUTPUT_LENGTH) {
                    TextButton(onClick = { onToggleToolExpansion(item.id) }) {
                        Icon(
                            imageVector = if (item.isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(if (item.isCollapsed) "Expand" else "Collapse")
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ToolArgumentsSection(
    arguments: Map<String, String>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Arguments (${arguments.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy arguments",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isExpanded) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    arguments.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "=",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val displayValue =
                                if (value.length > MAX_ARG_DISPLAY_LENGTH) {
                                    value.take(MAX_ARG_DISPLAY_LENGTH) + "…"
                                } else {
                                    value
                                }
                            Text(
                                text = displayValue,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Get tool icon and color based on tool name.
 */
@Suppress("MagicNumber")
private fun getToolInfo(toolName: String): ToolDisplayInfo {
    return when (toolName) {
        "read" -> ToolDisplayInfo(Icons.Default.Description, Color(0xFF2196F3)) // Blue
        "write" -> ToolDisplayInfo(Icons.Default.Edit, Color(0xFF4CAF50)) // Green
        "edit" -> ToolDisplayInfo(Icons.Default.Edit, Color(0xFFFFC107)) // Yellow/Amber
        "bash" -> ToolDisplayInfo(Icons.Default.Terminal, Color(0xFF9C27B0)) // Purple
        "grep", "rg" -> ToolDisplayInfo(Icons.Default.Search, Color(0xFFFF9800)) // Orange
        "find" -> ToolDisplayInfo(Icons.Default.Search, Color(0xFFFF9800)) // Orange
        "ls" -> ToolDisplayInfo(Icons.Default.Folder, Color(0xFF00BCD4)) // Cyan
        else -> ToolDisplayInfo(Icons.Default.Terminal, Color(0xFF607D8B)) // Gray
    }
}

private data class ToolDisplayInfo(
    val icon: ImageVector,
    val color: Color,
)

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
        if (state.isStreaming || state.isRetrying) {
            StreamingControls(
                isRetrying = state.isRetrying,
                onAbort = callbacks.onAbort,
                onAbortRetry = callbacks.onAbortRetry,
                onSteerClick = { showSteerDialog = true },
                onFollowUpClick = { showFollowUpDialog = true },
            )
        }

        PromptInputRow(
            inputText = state.inputText,
            isStreaming = state.isStreaming,
            pendingImages = state.pendingImages,
            onInputTextChanged = callbacks.onInputTextChanged,
            onSendPrompt = callbacks.onSendPrompt,
            onShowCommandPalette = callbacks.onShowCommandPalette,
            onAddImage = callbacks.onAddImage,
            onRemoveImage = callbacks.onRemoveImage,
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
    isRetrying: Boolean,
    onAbort: () -> Unit,
    onAbortRetry: () -> Unit,
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

        if (isRetrying) {
            Button(
                onClick = onAbortRetry,
                modifier = Modifier.weight(1f),
                colors =
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Abort Retry")
            }
        } else {
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
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun PromptInputRow(
    inputText: String,
    isStreaming: Boolean,
    pendingImages: List<PendingImage>,
    onInputTextChanged: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onShowCommandPalette: () -> Unit = {},
    onAddImage: (PendingImage) -> Unit,
    onRemoveImage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val imageEncoder = remember { ImageEncoder(context) }

    val photoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris ->
            uris.forEach { uri ->
                imageEncoder.getImageInfo(uri)?.let { info -> onAddImage(info) }
            }
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Pending images strip
        if (pendingImages.isNotEmpty()) {
            ImageAttachmentStrip(
                images = pendingImages,
                onRemove = onRemoveImage,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Attachment button
            IconButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !isStreaming,
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach Image",
                )
            }

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
                trailingIcon = {
                    if (inputText.isEmpty() && !isStreaming) {
                        IconButton(onClick = onShowCommandPalette) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Commands",
                            )
                        }
                    }
                },
            )

            IconButton(
                onClick = onSendPrompt,
                enabled = (inputText.isNotBlank() || pendingImages.isNotEmpty()) && !isStreaming,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                )
            }
        }
    }
}

@Composable
private fun ImageAttachmentStrip(
    images: List<PendingImage>,
    onRemove: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = images,
            key = { _, image -> image.uri },
        ) { index, image ->
            ImageThumbnail(
                image = image,
                onRemove = { onRemove(index) },
            )
        }
    }
}

@Suppress("MagicNumber", "LongMethod")
@Composable
private fun ImageThumbnail(
    image: PendingImage,
    onRemove: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val uri = remember(image.uri) { Uri.parse(image.uri) }
        AsyncImage(
            model = uri,
            contentDescription = image.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Size warning badge
        if (image.sizeBytes > ImageEncoder.MAX_IMAGE_SIZE_BYTES) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = ">5MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(14.dp),
            )
        }

        // File name / size label
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(2.dp),
        ) {
            Text(
                text = formatFileSize(image.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Suppress("MagicNumber")
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(java.util.Locale.US, "%.0fKB", bytes / 1_024.0)
        else -> "${bytes}B"
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

@Suppress("LongMethod", "LongParameterList")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelThinkingControls(
    currentModel: ModelInfo?,
    thinkingLevel: String?,
    onSetThinkingLevel: (String) -> Unit,
    onShowModelPicker: () -> Unit,
    compact: Boolean = false,
) {
    var showThinkingMenu by remember { mutableStateOf(false) }

    val modelText = currentModel?.name ?: "Select model"
    val providerText = currentModel?.provider?.uppercase() ?: ""
    val thinkingText = thinkingLevel?.uppercase() ?: "OFF"
    val buttonPadding = if (compact) 6.dp else 8.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onShowModelPicker,
            modifier = Modifier.weight(1f),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = buttonPadding,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Column {
                    Text(
                        text = modelText,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    if (!compact && providerText.isNotEmpty()) {
                        Text(
                            text = providerText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Thinking level selector
        Box(modifier = Modifier.wrapContentWidth()) {
            OutlinedButton(
                onClick = { showThinkingMenu = true },
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = buttonPadding,
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = thinkingText,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            DropdownMenu(
                expanded = showThinkingMenu,
                onDismissRequest = { showThinkingMenu = false },
            ) {
                THINKING_LEVEL_OPTIONS.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            onSetThinkingLevel(level)
                            showThinkingMenu = false
                        },
                    )
                }
            }
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
private const val THINKING_COLLAPSE_THRESHOLD = 280
private const val MAX_ARG_DISPLAY_LENGTH = 100
private const val NOTIFICATION_AUTO_DISMISS_MS = 4000L
private val THINKING_LEVEL_OPTIONS = listOf("off", "minimal", "low", "medium", "high", "xhigh")

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun BashDialog(
    isVisible: Boolean,
    command: String,
    output: String,
    exitCode: Int?,
    isExecuting: Boolean,
    wasTruncated: Boolean,
    fullLogPath: String?,
    history: List<String>,
    onCommandChange: (String) -> Unit,
    onExecute: () -> Unit,
    onAbort: () -> Unit,
    onSelectHistory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    var showHistoryDropdown by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!isExecuting) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Run Bash Command")
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Command input with history dropdown
                Box {
                    OutlinedTextField(
                        value = command,
                        onValueChange = onCommandChange,
                        placeholder = { Text("Enter command...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isExecuting,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            if (history.isNotEmpty() && !isExecuting) {
                                IconButton(onClick = { showHistoryDropdown = true }) {
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = "History",
                                    )
                                }
                            }
                        },
                    )

                    DropdownMenu(
                        expanded = showHistoryDropdown,
                        onDismissRequest = { showHistoryDropdown = false },
                    ) {
                        history.forEach { historyCommand ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = historyCommand,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                    )
                                },
                                onClick = {
                                    onSelectHistory(historyCommand)
                                    showHistoryDropdown = false
                                },
                            )
                        }
                    }
                }

                // Output display
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors =
                        androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Output",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (output.isNotEmpty()) {
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(output)) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy output",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        SelectionContainer {
                            Text(
                                text = output.ifEmpty { "(no output)" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }

                // Exit code and truncation info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (exitCode != null) {
                        val exitColor =
                            if (exitCode == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "Exit: $exitCode",
                                    color = exitColor,
                                )
                            },
                        )
                    }

                    if (wasTruncated && fullLogPath != null) {
                        TextButton(
                            onClick = { clipboardManager.setText(AnnotatedString(fullLogPath)) },
                        ) {
                            Text(
                                text = "Output truncated (copy path)",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isExecuting) {
                Button(
                    onClick = onAbort,
                    colors =
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    )
                    Text("Abort")
                }
            } else {
                Button(
                    onClick = onExecute,
                    enabled = command.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    )
                    Text("Execute")
                }
            }
        },
        dismissButton = {
            if (!isExecuting) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun SessionStatsSheet(
    isVisible: Boolean,
    stats: SessionStats?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val clipboardManager = LocalClipboardManager.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Session Statistics")
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                    )
                }
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (stats == null) {
                Text(
                    text = "No statistics available",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Token stats
                    StatsSection(title = "Tokens") {
                        StatRow("Input Tokens", formatNumber(stats.inputTokens))
                        StatRow("Output Tokens", formatNumber(stats.outputTokens))
                        StatRow("Cache Read", formatNumber(stats.cacheReadTokens))
                        StatRow("Cache Write", formatNumber(stats.cacheWriteTokens))
                    }

                    // Cost
                    StatsSection(title = "Cost") {
                        StatRow("Total Cost", formatCost(stats.totalCost))
                    }

                    // Messages
                    StatsSection(title = "Messages") {
                        StatRow("Total", stats.messageCount.toString())
                        StatRow("User", stats.userMessageCount.toString())
                        StatRow("Assistant", stats.assistantMessageCount.toString())
                        StatRow("Tool Results", stats.toolResultCount.toString())
                    }

                    // Session path
                    stats.sessionPath?.let { path ->
                        StatsSection(title = "Session File") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = path.takeLast(SESSION_PATH_DISPLAY_LENGTH),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(path)) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy path",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun StatsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Suppress("MagicNumber")
private fun formatNumber(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

@Suppress("MagicNumber")
private fun formatCost(value: Double): String {
    return String.format(java.util.Locale.US, "$%.4f", value)
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ModelPickerSheet(
    isVisible: Boolean,
    models: List<AvailableModel>,
    currentModel: ModelInfo?,
    query: String,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectModel: (AvailableModel) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val filteredModels =
        remember(models, query) {
            if (query.isBlank()) {
                models
            } else {
                models.filter { model ->
                    model.name.contains(query, ignoreCase = true) ||
                        model.provider.contains(query, ignoreCase = true) ||
                        model.id.contains(query, ignoreCase = true)
                }
            }
        }

    val groupedModels =
        remember(filteredModels) {
            filteredModels.groupBy { it.provider }
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search models...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredModels.isEmpty()) {
                    Text(
                        text = "No models found",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        groupedModels.forEach { (provider, modelsInGroup) ->
                            item {
                                Text(
                                    text = provider.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(
                                items = modelsInGroup,
                                key = { model -> "${model.provider}:${model.id}" },
                            ) { model ->
                                ModelItem(
                                    model = model,
                                    isSelected =
                                        currentModel?.id == model.id &&
                                            currentModel.provider == model.provider,
                                    onClick = { onSelectModel(model) },
                                )
                            }
                        }
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

@Suppress("LongMethod")
@Composable
private fun ModelItem(
    model: AvailableModel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onClick() },
        colors =
            if (isSelected) {
                androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            } else {
                androidx.compose.material3.CardDefaults.cardColors()
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (model.supportsThinking) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "Thinking",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                model.contextWindow?.let { ctx ->
                    Text(
                        text = "Context: ${formatNumber(ctx.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.inputCostPer1k?.let { cost ->
                    Text(
                        text = "In: \$${String.format(java.util.Locale.US, "%.4f", cost)}/1k",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.outputCostPer1k?.let { cost ->
                    Text(
                        text = "Out: \$${String.format(java.util.Locale.US, "%.4f", cost)}/1k",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun TreeNavigationSheet(
    isVisible: Boolean,
    tree: SessionTreeSnapshot?,
    selectedFilter: String,
    isLoading: Boolean,
    errorMessage: String?,
    onFilterChange: (String) -> Unit,
    onForkFromEntry: (String) -> Unit,
    onJumpAndContinue: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val entries = tree?.entries.orEmpty()
    val depthByEntry = remember(entries) { computeDepthMap(entries) }
    val childCountByEntry = remember(entries) { computeChildCountMap(entries) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session tree") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                tree?.sessionPath?.let { sessionPath ->
                    Text(
                        text = truncatePath(sessionPath),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                // Scrollable filter chips to avoid overflow
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = TREE_FILTER_OPTIONS,
                        key = { (filter, _) -> filter },
                    ) { (filter, label) ->
                        FilterChip(
                            selected = filter == selectedFilter,
                            onClick = { onFilterChange(filter) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    errorMessage != null -> {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    entries.isEmpty() -> {
                        Text(
                            text = "No tree data available",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(
                                items = entries,
                                key = { entry -> entry.entryId },
                            ) { entry ->
                                TreeEntryRow(
                                    entry = entry,
                                    depth = depthByEntry[entry.entryId] ?: 0,
                                    childCount = childCountByEntry[entry.entryId] ?: 0,
                                    isCurrent = tree?.currentLeafId == entry.entryId,
                                    onForkFromEntry = onForkFromEntry,
                                    onJumpAndContinue = onJumpAndContinue,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Suppress("MagicNumber", "LongMethod", "LongParameterList")
@Composable
private fun TreeEntryRow(
    entry: SessionTreeEntry,
    depth: Int,
    childCount: Int,
    isCurrent: Boolean,
    onForkFromEntry: (String) -> Unit,
    onJumpAndContinue: (String) -> Unit,
) {
    val indent = (depth * 8).dp
    val isMessage = entry.entryType == "message"
    val containerColor =
        when {
            isCurrent -> MaterialTheme.colorScheme.primaryContainer
            isMessage -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    val contentColor =
        when {
            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }

    Card(
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val typeIcon = treeEntryIcon(entry.entryType)
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = contentColor.copy(alpha = 0.7f),
                    )
                    val label =
                        buildString {
                            append(entry.entryType.replace('_', ' '))
                            entry.role?.let { append(" · $it") }
                        }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }

                if (isCurrent) {
                    Text(
                        text = "● current",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (isMessage) {
                Text(
                    text = entry.preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = contentColor,
                )
            }

            if (entry.isBookmarked && !entry.label.isNullOrBlank()) {
                Text(
                    text = "🔖 ${entry.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (childCount > 1) {
                    Text(
                        text = "↳ $childCount branches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(
                        onClick = { onJumpAndContinue(entry.entryId) },
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 0.dp,
                            ),
                    ) {
                        Text("Jump", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { onForkFromEntry(entry.entryId) },
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 0.dp,
                            ),
                    ) {
                        Text("Fork", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun treeEntryIcon(entryType: String): ImageVector {
    return when (entryType) {
        "message" -> Icons.Default.Description
        "model_change" -> Icons.Default.Refresh
        "thinking_level_change" -> Icons.Default.Menu
        else -> Icons.Default.PlayArrow
    }
}

@Suppress("ReturnCount")
private fun computeDepthMap(entries: List<SessionTreeEntry>): Map<String, Int> {
    val byId = entries.associateBy { it.entryId }
    val memo = mutableMapOf<String, Int>()

    fun depth(
        entryId: String,
        stack: MutableSet<String>,
    ): Int {
        memo[entryId]?.let { return it }
        if (!stack.add(entryId)) {
            return 0
        }

        val entry = byId[entryId]
        val resolvedDepth =
            when {
                entry == null -> 0
                entry.parentId == null -> 0
                else -> depth(entry.parentId, stack) + 1
            }

        stack.remove(entryId)
        memo[entryId] = resolvedDepth
        return resolvedDepth
    }

    entries.forEach { entry -> depth(entry.entryId, mutableSetOf()) }
    return memo
}

private fun computeChildCountMap(entries: List<SessionTreeEntry>): Map<String, Int> {
    return entries
        .groupingBy { it.parentId }
        .eachCount()
        .mapNotNull { (parentId, count) ->
            parentId?.let { it to count }
        }.toMap()
}

private val TREE_FILTER_OPTIONS =
    listOf(
        ChatViewModel.TREE_FILTER_DEFAULT to "default",
        ChatViewModel.TREE_FILTER_NO_TOOLS to "no-tools",
        ChatViewModel.TREE_FILTER_USER_ONLY to "user-only",
        ChatViewModel.TREE_FILTER_LABELED_ONLY to "labeled-only",
    )

private const val SESSION_PATH_DISPLAY_LENGTH = 40

private fun truncatePath(path: String): String {
    if (path.length <= SESSION_PATH_DISPLAY_LENGTH) {
        return path
    }
    val head = SESSION_PATH_DISPLAY_LENGTH / 2
    val tail = SESSION_PATH_DISPLAY_LENGTH - head - 1
    return "${path.take(head)}…${path.takeLast(tail)}"
}
