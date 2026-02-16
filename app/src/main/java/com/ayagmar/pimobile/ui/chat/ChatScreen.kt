package com.ayagmar.pimobile.ui.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ayagmar.pimobile.chat.ChatTimelineItem
import com.ayagmar.pimobile.chat.ChatUiState
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ChatViewModelFactory
import com.ayagmar.pimobile.chat.ExtensionWidget
import com.ayagmar.pimobile.chat.ImageEncoder
import com.ayagmar.pimobile.chat.PendingImage
import com.ayagmar.pimobile.chat.PendingQueueItem
import com.ayagmar.pimobile.chat.PendingQueueType
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.perf.StreamingFrameMetrics
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionTreeEntry
import com.ayagmar.pimobile.sessions.SessionTreeSnapshot
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import com.ayagmar.pimobile.ui.settings.KEY_SHOW_EXTENSION_STATUS_STRIP
import com.ayagmar.pimobile.ui.settings.SETTINGS_PREFS_NAME
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val onRemovePendingQueueItem: (String) -> Unit,
    val onClearPendingQueueItems: () -> Unit,
    val onSetThinkingLevel: (String) -> Unit,
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
    val onSyncNow: () -> Unit,
    // Tree navigation callbacks
    val onShowTreeSheet: () -> Unit,
    val onHideTreeSheet: () -> Unit,
    val onForkFromTreeEntry: (String) -> Unit,
    val onJumpAndContinueFromTreeEntry: (String) -> Unit,
    val onTreeFilterChanged: (String) -> Unit,
    // Image attachment callbacks
    val onAddImage: (PendingImage) -> Unit,
    val onRemoveImage: (Int) -> Unit,
)

internal data class PromptControlsCallbacks(
    val onInputTextChanged: (String) -> Unit,
    val onSendPrompt: () -> Unit,
    val onShowCommandPalette: () -> Unit,
    val onAddImage: (PendingImage) -> Unit,
    val onRemoveImage: (Int) -> Unit,
    val onAbort: () -> Unit,
    val onAbortRetry: () -> Unit,
    val onSteer: (String) -> Unit,
    val onFollowUp: (String) -> Unit,
    val onRemovePendingQueueItem: (String) -> Unit,
    val onClearPendingQueueItems: () -> Unit,
)

@Suppress("LongMethod")
@Composable
fun ChatRoute(sessionController: SessionController) {
    val context = LocalContext.current
    val imageEncoder = remember { ImageEncoder(context) }
    val factory =
        remember(sessionController, imageEncoder) {
            ChatViewModelFactory(
                sessionController = sessionController,
                imageEncoder = imageEncoder,
            )
        }
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    val settingsPrefs =
        remember(context) {
            context.getSharedPreferences(SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        }
    var showExtensionStatusStrip by remember(settingsPrefs) {
        mutableStateOf(settingsPrefs.getBoolean(KEY_SHOW_EXTENSION_STATUS_STRIP, true))
    }

    DisposableEffect(settingsPrefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == KEY_SHOW_EXTENSION_STATUS_STRIP) {
                    showExtensionStatusStrip = prefs.getBoolean(KEY_SHOW_EXTENSION_STATUS_STRIP, true)
                }
            }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

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
                onRemovePendingQueueItem = chatViewModel::removePendingQueueItem,
                onClearPendingQueueItems = chatViewModel::clearPendingQueueItems,
                onSetThinkingLevel = chatViewModel::setThinkingLevel,
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
                onSyncNow = chatViewModel::syncNow,
                onShowTreeSheet = chatViewModel::showTreeSheet,
                onHideTreeSheet = chatViewModel::hideTreeSheet,
                onForkFromTreeEntry = chatViewModel::forkFromTreeEntry,
                onJumpAndContinueFromTreeEntry = chatViewModel::jumpAndContinueFromTreeEntry,
                onTreeFilterChanged = chatViewModel::setTreeFilter,
                onAddImage = chatViewModel::addImage,
                onRemoveImage = chatViewModel::removeImage,
            )
        }

    ChatScreen(
        state = uiState,
        callbacks = callbacks,
        showExtensionStatusStrip = showExtensionStatusStrip,
    )
}

@Suppress("LongMethod")
@Composable
private fun ChatScreen(
    state: ChatUiState,
    callbacks: ChatCallbacks,
    showExtensionStatusStrip: Boolean,
) {
    StreamingFrameMetrics(
        isStreaming = state.isStreaming,
        onJankDetected = { droppedFrame ->
            Log.d(
                STREAMING_FRAME_LOG_TAG,
                "jank severity=${droppedFrame.severity} " +
                    "frame=${droppedFrame.frameTimeMs}ms dropped=${droppedFrame.expectedFrames}",
            )
        },
    )

    ChatScreenContent(
        state = state,
        callbacks = callbacks,
        showExtensionStatusStrip = showExtensionStatusStrip,
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

@Suppress("LongMethod")
@Composable
private fun ChatScreenContent(
    state: ChatUiState,
    callbacks: ChatCallbacks,
    showExtensionStatusStrip: Boolean,
) {
    val hasStreamingTimelineItem =
        remember(state.timeline) {
            state.timeline.any { item ->
                when (item) {
                    is ChatTimelineItem.Assistant -> item.isStreaming
                    is ChatTimelineItem.Tool -> item.isStreaming
                    is ChatTimelineItem.User -> false
                }
            }
        }
    val isRunActive = state.isStreaming || state.isRetrying || hasStreamingTimelineItem

    var runStartedAtMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(isRunActive) {
        if (isRunActive) {
            if (runStartedAtMs == null) {
                runStartedAtMs = System.currentTimeMillis()
            }
        } else {
            runStartedAtMs = null
        }
    }

    val elapsedSeconds by
        produceState(
            initialValue = 0L,
            key1 = isRunActive,
            key2 = runStartedAtMs,
        ) {
            val startedAt = runStartedAtMs
            if (!isRunActive || startedAt == null) {
                value = 0L
                return@produceState
            }

            while (true) {
                value = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L)) / RUN_PROGRESS_TICK_MS
                delay(RUN_PROGRESS_TICK_MS)
            }
        }

    val runPhase =
        remember(state.isRetrying, state.timeline) {
            inferLiveRunPhase(
                isRetrying = state.isRetrying,
                timeline = state.timeline,
            )
        }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(if (isRunActive) 8.dp else 12.dp),
    ) {
        ChatHeader(
            isRunActive = isRunActive,
            isSyncingSession = state.isSyncingSession,
            sessionCoherencyWarning = state.sessionCoherencyWarning,
            extensionTitle = state.extensionTitle,
            connectionState = state.connectionState,
            currentModel = state.currentModel,
            thinkingLevel = state.thinkingLevel,
            errorMessage = state.errorMessage,
            callbacks = callbacks,
        )

        // Extension widgets (above editor)
        ExtensionWidgets(
            widgets = state.extensionWidgets,
            placement = "aboveEditor",
        )

        Box(modifier = Modifier.weight(1f)) {
            ChatBody(
                isLoading = state.isLoading,
                timeline = state.timeline,
                hasOlderMessages = state.hasOlderMessages,
                hiddenHistoryCount = state.hiddenHistoryCount,
                expandedToolArguments = state.expandedToolArguments,
                isRunActive = isRunActive,
                runPhase = runPhase,
                runElapsedSeconds = elapsedSeconds,
                callbacks = callbacks,
            )
        }

        // Extension widgets (below editor)
        ExtensionWidgets(
            widgets = state.extensionWidgets,
            placement = "belowEditor",
        )

        PromptControls(
            isStreaming = isRunActive,
            isRetrying = state.isRetrying,
            pendingQueueItems = state.pendingQueueItems,
            steeringMode = state.steeringMode,
            followUpMode = state.followUpMode,
            inputText = state.inputText,
            pendingImages = state.pendingImages,
            callbacks =
                PromptControlsCallbacks(
                    onInputTextChanged = callbacks.onInputTextChanged,
                    onSendPrompt = callbacks.onSendPrompt,
                    onShowCommandPalette = callbacks.onShowCommandPalette,
                    onAddImage = callbacks.onAddImage,
                    onRemoveImage = callbacks.onRemoveImage,
                    onAbort = callbacks.onAbort,
                    onAbortRetry = callbacks.onAbortRetry,
                    onSteer = callbacks.onSteer,
                    onFollowUp = callbacks.onFollowUp,
                    onRemovePendingQueueItem = callbacks.onRemovePendingQueueItem,
                    onClearPendingQueueItems = callbacks.onClearPendingQueueItems,
                ),
        )

        if (showExtensionStatusStrip) {
            ExtensionStatusStrip(statuses = state.extensionStatuses)
        }
    }
}

@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
private fun ChatHeader(
    isRunActive: Boolean,
    isSyncingSession: Boolean,
    sessionCoherencyWarning: String?,
    extensionTitle: String?,
    connectionState: com.ayagmar.pimobile.corenet.ConnectionState,
    currentModel: ModelInfo?,
    thinkingLevel: String?,
    errorMessage: String?,
    callbacks: ChatCallbacks,
) {
    val isCompact = isRunActive
    var showSecondaryActionsMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top row: Title and minimal actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val title = extensionTitle ?: "Chat"
                Text(
                    text = title,
                    style =
                        if (isCompact) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                )

                // Subtle connection status
                if (!isCompact && extensionTitle == null) {
                    val statusText =
                        when (connectionState) {
                            com.ayagmar.pimobile.corenet.ConnectionState.CONNECTED -> "●"
                            com.ayagmar.pimobile.corenet.ConnectionState.CONNECTING -> "○"
                            else -> "○"
                        }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            when (connectionState) {
                                com.ayagmar.pimobile.corenet.ConnectionState.CONNECTED ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                    )
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSyncingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = callbacks.onSyncNow) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync now",
                        )
                    }
                }

                IconButton(onClick = { showSecondaryActionsMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More actions",
                    )
                }

                DropdownMenu(
                    expanded = showSecondaryActionsMenu,
                    onDismissRequest = { showSecondaryActionsMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Tree") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onShowTreeSheet()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Bash") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onShowBashDialog()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Stats") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onShowStatsSheet()
                        },
                    )
                }
            }
        }

        sessionCoherencyWarning?.let { warning ->
            Text(
                text = warning,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Compact model/thinking controls
        ModelThinkingControls(
            currentModel = currentModel,
            thinkingLevel = thinkingLevel,
            onSetThinkingLevel = callbacks.onSetThinkingLevel,
            onShowModelPicker = callbacks.onShowModelPicker,
        )

        // Error message if any
        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LiveRunProgressIndicator(
    phase: LiveRunPhase,
    elapsedSeconds: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag(CHAT_RUN_PROGRESS_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = "Working · ${phase.label} · ${formatRunElapsed(elapsedSeconds)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InlineRunProgressCard(
    phase: LiveRunPhase,
    elapsedSeconds: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Assistant",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            LiveRunProgressIndicator(
                phase = phase,
                elapsedSeconds = elapsedSeconds,
                modifier = Modifier,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ChatBody(
    isLoading: Boolean,
    timeline: List<ChatTimelineItem>,
    hasOlderMessages: Boolean,
    hiddenHistoryCount: Int,
    expandedToolArguments: Set<String>,
    isRunActive: Boolean,
    runPhase: LiveRunPhase,
    runElapsedSeconds: Long,
    callbacks: ChatCallbacks,
) {
    val hasStreamingTimelineItem =
        remember(timeline) {
            timeline.any { item ->
                when (item) {
                    is ChatTimelineItem.Assistant -> item.isStreaming
                    is ChatTimelineItem.Tool -> item.isStreaming
                    is ChatTimelineItem.User -> false
                }
            }
        }
    val showInlineRunProgress = isRunActive && !hasStreamingTimelineItem

    if (isLoading) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
    } else if (timeline.isEmpty() && !showInlineRunProgress) {
        Text(
            text = "No chat messages yet. Resume a session and send a prompt.",
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        ChatTimeline(
            timeline = timeline,
            hasOlderMessages = hasOlderMessages,
            hiddenHistoryCount = hiddenHistoryCount,
            expandedToolArguments = expandedToolArguments,
            showInlineRunProgress = showInlineRunProgress,
            isRunActive = isRunActive,
            runPhase = runPhase,
            runElapsedSeconds = runElapsedSeconds,
            onLoadOlderMessages = callbacks.onLoadOlderMessages,
            onToggleToolExpansion = callbacks.onToggleToolExpansion,
            onToggleThinkingExpansion = callbacks.onToggleThinkingExpansion,
            onToggleDiffExpansion = callbacks.onToggleDiffExpansion,
            onToggleToolArgumentsExpansion = callbacks.onToggleToolArgumentsExpansion,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ChatTimeline(
    timeline: List<ChatTimelineItem>,
    hasOlderMessages: Boolean,
    hiddenHistoryCount: Int,
    expandedToolArguments: Set<String>,
    showInlineRunProgress: Boolean,
    isRunActive: Boolean,
    runPhase: LiveRunPhase,
    runElapsedSeconds: Long,
    onLoadOlderMessages: () -> Unit,
    onToggleToolExpansion: (String) -> Unit,
    onToggleThinkingExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleToolArgumentsExpansion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val totalItems = timeline.size + if (showInlineRunProgress) 1 else 0
    val shouldAutoScrollToBottom by
        remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val lastItemIndex = layoutInfo.totalItemsCount - 1

                lastItemIndex <= 0 || lastVisibleIndex >= lastItemIndex - AUTO_SCROLL_BOTTOM_THRESHOLD_ITEMS
            }
        }
    val shouldShowJumpToLatest = isRunActive && totalItems > 0 && !shouldAutoScrollToBottom

    // Auto-scroll only while the user stays near the bottom.
    // This avoids jumping when loading older history or reading past messages.
    LaunchedEffect(timeline.lastOrNull(), totalItems, shouldAutoScrollToBottom) {
        if (totalItems > 0 && shouldAutoScrollToBottom) {
            listState.scrollToItem(totalItems - 1)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
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
                    is ChatTimelineItem.User -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            UserCard(
                                text = item.text,
                                imageCount = item.imageCount,
                                imageUris = item.imageUris,
                            )
                        }
                    }

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

            if (showInlineRunProgress) {
                item(key = "inline-run-progress") {
                    InlineRunProgressCard(
                        phase = runPhase,
                        elapsedSeconds = runElapsedSeconds,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = shouldShowJumpToLatest,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(totalItems - 1)
                    }
                },
                modifier = Modifier.testTag(CHAT_JUMP_TO_LATEST_TAG),
            ) {
                Text("Jump to latest")
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun UserCard(
    text: String,
    imageCount: Int,
    imageUris: List<String>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 340.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "You",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = text.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            if (imageUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = imageUris.take(MAX_INLINE_USER_IMAGE_PREVIEWS),
                        key = { index, uri -> "$uri-$index" },
                    ) { _, uriString ->
                        UserImagePreview(uriString = uriString)
                    }

                    val remaining = imageUris.size - MAX_INLINE_USER_IMAGE_PREVIEWS
                    if (remaining > 0) {
                        item(key = "more-images") {
                            Box(
                                modifier =
                                    Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "+$remaining", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (imageCount > 0) {
                Text(
                    text = if (imageCount == 1) "📎 1 image attached" else "📎 $imageCount images attached",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun UserImagePreview(uriString: String) {
    val uri = remember(uriString) { Uri.parse(uriString) }
    var loadFailed by remember(uriString) { mutableStateOf(false) }

    if (loadFailed) {
        Box(
            modifier =
                Modifier
                    .size(USER_IMAGE_PREVIEW_SIZE_DP.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "IMG",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    AsyncImage(
        model = uri,
        contentDescription = "Sent image preview",
        modifier =
            Modifier
                .size(USER_IMAGE_PREVIEW_SIZE_DP.dp)
                .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop,
        onError = {
            loadFailed = true
        },
    )
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

            AssistantMessageContent(
                text = item.text,
                modifier = Modifier.fillMaxWidth(),
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
private fun AssistantMessageContent(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) {
        Text(
            text = "(empty)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier,
        )
        return
    }

    // Fast path for common plain-text streaming updates (avoid regex parsing/jank on each delta).
    if (!text.contains("```")) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier,
        )
        return
    }

    val blocks = remember(text) { parseAssistantMessageBlocks(text) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is AssistantMessageBlock.Paragraph -> {
                    if (block.text.isNotBlank()) {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                is AssistantMessageBlock.Code -> {
                    AssistantCodeBlock(
                        code = block.code,
                        language = block.language,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantCodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlighted = highlightCodeBlock(code, language, colors)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
    ) {
        SelectionContainer {
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private sealed interface AssistantMessageBlock {
    data class Paragraph(
        val text: String,
    ) : AssistantMessageBlock

    data class Code(
        val code: String,
        val language: String?,
    ) : AssistantMessageBlock
}

private fun parseAssistantMessageBlocks(text: String): List<AssistantMessageBlock> {
    if (text.isBlank()) return emptyList()

    val blocks = mutableListOf<AssistantMessageBlock>()
    var cursor = 0

    CODE_FENCE_REGEX.findAll(text).forEach { match ->
        val matchStart = match.range.first
        val matchEndExclusive = match.range.last + 1

        if (matchStart > cursor) {
            val paragraph = text.substring(cursor, matchStart).trim()
            if (paragraph.isNotEmpty()) {
                blocks += AssistantMessageBlock.Paragraph(paragraph)
            }
        }

        val language = match.groupValues[1].takeIf { it.isNotBlank() }
        val code = match.groupValues[2]
        blocks += AssistantMessageBlock.Code(code = code.trimEnd(), language = language)
        cursor = matchEndExclusive
    }

    if (cursor < text.length) {
        val paragraph = text.substring(cursor).trim()
        if (paragraph.isNotEmpty()) {
            blocks += AssistantMessageBlock.Paragraph(paragraph)
        }
    }

    return blocks
}

private fun highlightCodeBlock(
    code: String,
    language: String?,
    colors: androidx.compose.material3.ColorScheme,
): AnnotatedString {
    val text = code.ifBlank { "(empty code block)" }
    val commentPattern = commentRegexFor(language)
    val keywordPattern = keywordRegexFor(language)

    val commentStyle = SpanStyle(color = colors.outline)
    val stringStyle = SpanStyle(color = colors.tertiary)
    val numberStyle = SpanStyle(color = colors.secondary)
    val keywordStyle = SpanStyle(color = colors.primary)

    return buildAnnotatedString {
        append(text)

        applyStyle(STRING_REGEX, stringStyle, text)
        applyStyle(NUMBER_REGEX, numberStyle, text)
        applyStyle(keywordPattern, keywordStyle, text)
        applyStyle(commentPattern, commentStyle, text)
    }
}

private fun AnnotatedString.Builder.applyStyle(
    regex: Regex,
    style: SpanStyle,
    text: String,
) {
    regex.findAll(text).forEach { match ->
        addStyle(style, match.range.first, match.range.last + 1)
    }
}

private fun keywordRegexFor(language: String?): Regex {
    return when (language?.lowercase()) {
        "kotlin", "kt" -> KOTLIN_KEYWORD_REGEX
        "java" -> JAVA_KEYWORD_REGEX
        "python", "py" -> PYTHON_KEYWORD_REGEX
        "javascript", "js", "typescript", "ts", "tsx" -> JS_TS_KEYWORD_REGEX
        "bash", "shell", "sh" -> BASH_KEYWORD_REGEX
        else -> GENERIC_KEYWORD_REGEX
    }
}

private fun commentRegexFor(language: String?): Regex {
    return when (language?.lowercase()) {
        "python", "py", "bash", "shell", "sh", "yaml", "yml" -> HASH_COMMENT_REGEX
        else -> SLASH_COMMENT_REGEX
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

@Suppress("LongMethod", "CyclomaticComplexMethod")
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

                val rawOutput = displayOutput.ifBlank { "(no output yet)" }
                val shouldHighlight = !item.isStreaming && rawOutput.length <= TOOL_HIGHLIGHT_MAX_LENGTH

                SelectionContainer {
                    if (shouldHighlight) {
                        val inferredLanguage = inferLanguageFromToolContext(item)
                        val highlightedOutput =
                            highlightCodeBlock(
                                code = rawOutput,
                                language = inferredLanguage,
                                colors = MaterialTheme.colorScheme,
                            )
                        Text(
                            text = highlightedOutput,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(
                            text = rawOutput,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
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
@Composable
private fun getToolInfo(toolName: String): ToolDisplayInfo {
    val colors = MaterialTheme.colorScheme
    return when (toolName) {
        "read" -> ToolDisplayInfo(Icons.Default.Description, colors.primary)
        "write" -> ToolDisplayInfo(Icons.Default.Edit, colors.secondary)
        "edit" -> ToolDisplayInfo(Icons.Default.Edit, colors.tertiary)
        "bash" -> ToolDisplayInfo(Icons.Default.Terminal, colors.error)
        "grep", "rg", "find" -> ToolDisplayInfo(Icons.Default.Search, colors.primary)
        "ls" -> ToolDisplayInfo(Icons.Default.Folder, colors.secondary)
        else -> ToolDisplayInfo(Icons.Default.Terminal, colors.outline)
    }
}

private data class ToolDisplayInfo(
    val icon: ImageVector,
    val color: Color,
)

private fun inferLanguageFromToolContext(item: ChatTimelineItem.Tool): String? {
    val path = item.arguments["path"] ?: return null
    val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return TOOL_OUTPUT_LANGUAGE_BY_EXTENSION[extension]
}

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun PromptControls(
    isStreaming: Boolean,
    isRetrying: Boolean,
    pendingQueueItems: List<PendingQueueItem>,
    steeringMode: String,
    followUpMode: String,
    inputText: String,
    pendingImages: List<PendingImage>,
    callbacks: PromptControlsCallbacks,
) {
    var showSteerDialog by remember { mutableStateOf(false) }
    var showFollowUpDialog by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(CHAT_PROMPT_CONTROLS_TAG)
                .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = isStreaming || isRetrying,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            StreamingControls(
                isRetrying = isRetrying,
                onAbort = callbacks.onAbort,
                onAbortRetry = callbacks.onAbortRetry,
                onSteerClick = { showSteerDialog = true },
                onFollowUpClick = { showFollowUpDialog = true },
            )
        }

        AnimatedVisibility(
            visible = isStreaming && pendingQueueItems.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            PendingQueueInspector(
                pendingItems = pendingQueueItems,
                steeringMode = steeringMode,
                followUpMode = followUpMode,
                onRemoveItem = callbacks.onRemovePendingQueueItem,
                onClear = callbacks.onClearPendingQueueItems,
            )
        }

        PromptInputRow(
            inputText = inputText,
            isStreaming = isStreaming,
            pendingImages = pendingImages,
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

@Suppress("LongMethod")
@Composable
private fun StreamingControls(
    isRetrying: Boolean,
    onAbort: () -> Unit,
    onAbortRetry: () -> Unit,
    onSteerClick: () -> Unit,
    onFollowUpClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(CHAT_STREAMING_CONTROLS_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    text = "Abort",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isRetrying) {
                OutlinedButton(
                    onClick = onAbortRetry,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Abort Retry", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (!isRetrying) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSteerClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Steer", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onFollowUpClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Follow Up", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PendingQueueInspector(
    pendingItems: List<PendingQueueItem>,
    steeringMode: String,
    followUpMode: String,
    onRemoveItem: (String) -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pending queue (${pendingItems.size})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }

            Text(
                text = "Steer: ${deliveryModeLabel(steeringMode)} · Follow-up: ${deliveryModeLabel(followUpMode)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            pendingItems.forEach { item ->
                PendingQueueItemRow(
                    item = item,
                    onRemove = { onRemoveItem(item.id) },
                )
            }

            Text(
                text = "Items shown here were sent while streaming; clearing only removes local inspector entries.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingQueueItemRow(
    item: PendingQueueItem,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val typeLabel =
                when (item.type) {
                    PendingQueueType.STEER -> "Steer"
                    PendingQueueType.FOLLOW_UP -> "Follow-up"
                }
            Text(
                text = "$typeLabel · ${deliveryModeLabel(item.mode)}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = item.message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }

        TextButton(onClick = onRemove) {
            Text("Remove")
        }
    }
}

private fun deliveryModeLabel(mode: String): String {
    return when (mode) {
        ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME -> "one-at-a-time"
        else -> "all"
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun PromptInputRow(
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

    val submitPrompt = {
        onSendPrompt()
    }

    val photoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris ->
            uris.forEach { uri ->
                imageEncoder.getImageInfo(uri)?.let { info -> onAddImage(info) }
            }
        }

    Column(modifier = Modifier.fillMaxWidth().testTag(CHAT_PROMPT_INPUT_ROW_TAG)) {
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
                keyboardActions = KeyboardActions(onSend = { submitPrompt() }),
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
                onClick = submitPrompt,
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
            key = { index, image -> "${image.uri}-$index" },
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
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
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
@Composable
private fun ModelThinkingControls(
    currentModel: ModelInfo?,
    thinkingLevel: String?,
    onSetThinkingLevel: (String) -> Unit,
    onShowModelPicker: () -> Unit,
) {
    var showThinkingMenu by remember { mutableStateOf(false) }

    val modelText = currentModel?.name ?: "Select model"
    val thinkingText = thinkingLevel?.uppercase() ?: "OFF"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onShowModelPicker,
            modifier = Modifier.weight(1f),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 6.dp,
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
                Text(
                    text = modelText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }

        // Thinking level selector
        Box(modifier = Modifier.wrapContentWidth()) {
            OutlinedButton(
                onClick = { showThinkingMenu = true },
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 6.dp,
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

@Suppress("LongMethod")
@Composable
private fun ExtensionStatusStrip(statuses: Map<String, String>) {
    if (statuses.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var previousStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var hasPreviousSnapshot by remember { mutableStateOf(false) }

    val comparisonSnapshot =
        if (hasPreviousSnapshot) {
            previousStatuses
        } else {
            statuses.mapValues { (_, value) -> value.trim() }
        }

    val presentation =
        remember(statuses, comparisonSnapshot, expanded) {
            buildExtensionStatusPresentation(
                statuses = statuses,
                previousStatuses = comparisonSnapshot,
                expanded = expanded,
            )
        }

    LaunchedEffect(statuses) {
        previousStatuses = statuses.mapValues { (_, value) -> value.trim() }
        hasPreviousSnapshot = true
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Extension status (${statuses.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${presentation.activeCount} active · ${presentation.quietCount} quiet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            if (!expanded) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = presentation.visibleEntries,
                        key = { entry -> entry.key },
                    ) { entry ->
                        StatusPill(entry = entry)
                    }
                }
            } else {
                presentation.visibleEntries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (entry.isChanged) "•" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            text = "${entry.key}: ${entry.value.take(STATUS_VALUE_MAX_LENGTH)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (!expanded && presentation.hiddenCount > 0) {
                Text(
                    text = "+${presentation.hiddenCount} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (presentation.changedCount > 0) {
                Text(
                    text = "${presentation.changedCount} update(s) since last refresh",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(entry: ExtensionStatusEntry) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color =
            if (entry.isLowSignal) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
    ) {
        Text(
            text = "${entry.key}: ${entry.value.take(EXTENSION_STATUS_PILL_MAX_LENGTH)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

internal data class ExtensionStatusEntry(
    val key: String,
    val value: String,
    val isLowSignal: Boolean,
    val isChanged: Boolean,
)

internal data class ExtensionStatusPresentation(
    val visibleEntries: List<ExtensionStatusEntry>,
    val hiddenCount: Int,
    val activeCount: Int,
    val quietCount: Int,
    val changedCount: Int,
)

internal fun buildExtensionStatusPresentation(
    statuses: Map<String, String>,
    previousStatuses: Map<String, String>,
    expanded: Boolean,
): ExtensionStatusPresentation {
    if (statuses.isEmpty()) {
        return ExtensionStatusPresentation(
            visibleEntries = emptyList(),
            hiddenCount = 0,
            activeCount = 0,
            quietCount = 0,
            changedCount = 0,
        )
    }

    val entries =
        statuses
            .toSortedMap()
            .map { (key, rawValue) ->
                val value = rawValue.trim().ifEmpty { "(empty)" }
                ExtensionStatusEntry(
                    key = key,
                    value = value,
                    isLowSignal = isLowSignalExtensionStatus(value),
                    isChanged = previousStatuses[key] != value,
                )
            }

    val changed = entries.filter { it.isChanged }
    val active = entries.filterNot { it.isLowSignal }
    val quietCount = entries.size - active.size

    val compactCandidates =
        when {
            changed.isNotEmpty() -> changed
            active.isNotEmpty() -> active
            else -> entries
        }

    val visibleEntries = if (expanded) entries else compactCandidates.take(MAX_COMPACT_EXTENSION_STATUS_ITEMS)

    return ExtensionStatusPresentation(
        visibleEntries = visibleEntries,
        hiddenCount = if (expanded) 0 else (entries.size - visibleEntries.size).coerceAtLeast(0),
        activeCount = active.size,
        quietCount = quietCount,
        changedCount = changed.size,
    )
}

internal fun isLowSignalExtensionStatus(value: String): Boolean {
    val normalized = value.trim().lowercase()
    return LOW_SIGNAL_STATUS_TOKENS.any { token -> normalized.contains(token) }
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

internal const val CHAT_PROMPT_CONTROLS_TAG = "chat_prompt_controls"
internal const val CHAT_STREAMING_CONTROLS_TAG = "chat_streaming_controls"
internal const val CHAT_PROMPT_INPUT_ROW_TAG = "chat_prompt_input_row"
internal const val CHAT_RUN_PROGRESS_TAG = "chat_run_progress"
internal const val CHAT_JUMP_TO_LATEST_TAG = "chat_jump_to_latest"

private const val COLLAPSED_OUTPUT_LENGTH = 280
private const val THINKING_COLLAPSE_THRESHOLD = 280
private const val MAX_ARG_DISPLAY_LENGTH = 100
private const val MAX_INLINE_USER_IMAGE_PREVIEWS = 4
private const val USER_IMAGE_PREVIEW_SIZE_DP = 56
private const val AUTO_SCROLL_BOTTOM_THRESHOLD_ITEMS = 2
private const val TOOL_HIGHLIGHT_MAX_LENGTH = 1_000
private const val STATUS_VALUE_MAX_LENGTH = 180
private const val EXTENSION_STATUS_PILL_MAX_LENGTH = 56
private const val MAX_COMPACT_EXTENSION_STATUS_ITEMS = 2
private const val RUN_PROGRESS_TICK_MS = 1_000L
private const val STREAMING_FRAME_LOG_TAG = "StreamingFrameMetrics"
private val THINKING_LEVEL_OPTIONS = listOf("off", "minimal", "low", "medium", "high", "xhigh")
private val CODE_FENCE_REGEX = Regex("```([\\w+-]*)\\r?\\n([\\s\\S]*?)```")
private val STRING_REGEX = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
private val NUMBER_REGEX = Regex("\\b\\d+(?:\\.\\d+)?\\b")
private val HASH_COMMENT_REGEX = Regex("#.*$", setOf(RegexOption.MULTILINE))
private val SLASH_COMMENT_REGEX =
    Regex(
        "//.*$|/\\*.*?\\*/",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    )
private val KOTLIN_KEYWORD_REGEX =
    Regex(
        "\\b(class|object|interface|fun|val|var|when|if|else|return|suspend|data|sealed|" +
            "private|public|override|import|package)\\b",
    )
private val JAVA_KEYWORD_REGEX =
    Regex(
        "\\b(class|interface|enum|public|private|protected|static|final|void|return|if|" +
            "else|switch|case|new|import|package)\\b",
    )
private val PYTHON_KEYWORD_REGEX =
    Regex(
        "\\b(def|class|import|from|as|if|elif|else|for|while|return|try|except|with|lambda|pass|break|continue)\\b",
    )
private val JS_TS_KEYWORD_REGEX =
    Regex(
        "\\b(function|class|const|let|var|return|if|else|switch|case|import|from|export|async|await|interface|type)\\b",
    )
private val BASH_KEYWORD_REGEX = Regex("\\b(if|then|fi|for|do|done|case|esac|function|export|echo)\\b")
private val GENERIC_KEYWORD_REGEX =
    Regex("\\b(if|else|for|while|return|class|function|import|from|const|let|var|def|public|private)\\b")
private val TOOL_OUTPUT_LANGUAGE_BY_EXTENSION =
    mapOf(
        "kt" to "kotlin",
        "kts" to "kotlin",
        "java" to "java",
        "js" to "javascript",
        "jsx" to "javascript",
        "ts" to "typescript",
        "tsx" to "typescript",
        "py" to "python",
        "json" to "json",
        "jsonl" to "json",
        "xml" to "xml",
        "html" to "xml",
        "svg" to "xml",
        "sh" to "bash",
        "bash" to "bash",
        "sql" to "sql",
        "yml" to "yaml",
        "yaml" to "yaml",
        "go" to "go",
        "rs" to "rust",
        "md" to "markdown",
    )
private val LOW_SIGNAL_STATUS_TOKENS =
    setOf(
        "idle",
        "ready",
        "ok",
        "connected",
        "none",
        "no updates",
        "synced",
    )

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
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy output",
                                        modifier = Modifier.size(18.dp),
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
        ChatViewModel.TREE_FILTER_ALL to "all",
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
