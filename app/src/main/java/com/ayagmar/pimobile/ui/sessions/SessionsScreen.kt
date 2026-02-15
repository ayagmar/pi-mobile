package com.ayagmar.pimobile.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.coresessions.SessionIndexRepository
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.sessions.CwdSessionGroupUiState
import com.ayagmar.pimobile.sessions.SessionAction
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionsUiState
import com.ayagmar.pimobile.sessions.SessionsViewModel
import com.ayagmar.pimobile.sessions.SessionsViewModelFactory
import com.ayagmar.pimobile.ui.components.PiButton
import com.ayagmar.pimobile.ui.components.PiCard
import com.ayagmar.pimobile.ui.components.PiSpacing
import com.ayagmar.pimobile.ui.components.PiTextField
import com.ayagmar.pimobile.ui.components.PiTopBar
import kotlinx.coroutines.delay

@Composable
fun SessionsRoute(
    profileStore: HostProfileStore,
    tokenStore: HostTokenStore,
    repository: SessionIndexRepository,
    sessionController: SessionController,
    onNavigateToChat: () -> Unit = {},
) {
    val factory =
        remember(profileStore, tokenStore, repository, sessionController) {
            SessionsViewModelFactory(
                profileStore = profileStore,
                tokenStore = tokenStore,
                repository = repository,
                sessionController = sessionController,
            )
        }
    val sessionsViewModel: SessionsViewModel = viewModel(factory = factory)
    val uiState by sessionsViewModel.uiState.collectAsStateWithLifecycle()
    var transientStatusMessage by remember { mutableStateOf<String?>(null) }

    // Refresh hosts when screen is resumed (e.g., after adding a host)
    LaunchedEffect(Unit) {
        sessionsViewModel.refreshHosts()
    }

    // Navigate to chat when session is successfully resumed
    LaunchedEffect(sessionsViewModel) {
        sessionsViewModel.navigateToChat.collect {
            onNavigateToChat()
        }
    }

    LaunchedEffect(sessionsViewModel) {
        sessionsViewModel.messages.collect { message ->
            transientStatusMessage = message
        }
    }

    LaunchedEffect(transientStatusMessage) {
        if (transientStatusMessage != null) {
            delay(STATUS_MESSAGE_DURATION_MS)
            transientStatusMessage = null
        }
    }

    SessionsScreen(
        state = uiState,
        transientStatusMessage = transientStatusMessage,
        callbacks =
            SessionsScreenCallbacks(
                onHostSelected = sessionsViewModel::onHostSelected,
                onSearchChanged = sessionsViewModel::onSearchQueryChanged,
                onCwdToggle = sessionsViewModel::onCwdToggle,
                onToggleFlatView = sessionsViewModel::toggleFlatView,
                onRefreshClick = sessionsViewModel::refreshSessions,
                onNewSession = sessionsViewModel::newSession,
                onResumeClick = sessionsViewModel::resumeSession,
                onRename = { name -> sessionsViewModel.runSessionAction(SessionAction.Rename(name)) },
                onFork = sessionsViewModel::requestForkMessages,
                onForkMessageSelected = sessionsViewModel::forkFromSelectedMessage,
                onDismissForkDialog = sessionsViewModel::dismissForkPicker,
                onExport = { sessionsViewModel.runSessionAction(SessionAction.Export) },
                onCompact = { sessionsViewModel.runSessionAction(SessionAction.Compact) },
            ),
    )
}

private data class SessionsScreenCallbacks(
    val onHostSelected: (String) -> Unit,
    val onSearchChanged: (String) -> Unit,
    val onCwdToggle: (String) -> Unit,
    val onToggleFlatView: () -> Unit,
    val onRefreshClick: () -> Unit,
    val onNewSession: () -> Unit,
    val onResumeClick: (SessionRecord) -> Unit,
    val onRename: (String) -> Unit,
    val onFork: () -> Unit,
    val onForkMessageSelected: (String) -> Unit,
    val onDismissForkDialog: () -> Unit,
    val onExport: () -> Unit,
    val onCompact: () -> Unit,
)

private data class ActiveSessionActionCallbacks(
    val onRename: () -> Unit,
    val onFork: () -> Unit,
    val onExport: () -> Unit,
    val onCompact: () -> Unit,
)

private data class SessionsListCallbacks(
    val onCwdToggle: (String) -> Unit,
    val onResumeClick: (SessionRecord) -> Unit,
    val actions: ActiveSessionActionCallbacks,
)

private data class RenameDialogUiState(
    val isVisible: Boolean,
    val draft: String,
    val onDraftChange: (String) -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
private fun SessionsScreen(
    state: SessionsUiState,
    transientStatusMessage: String?,
    callbacks: SessionsScreenCallbacks,
) {
    var renameDraft by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(PiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PiSpacing.sm),
    ) {
        SessionsHeader(
            isRefreshing = state.isRefreshing,
            isFlatView = state.isFlatView,
            onRefreshClick = callbacks.onRefreshClick,
            onToggleFlatView = callbacks.onToggleFlatView,
            onNewSession = callbacks.onNewSession,
        )

        HostSelector(
            state = state,
            onHostSelected = callbacks.onHostSelected,
        )

        PiTextField(
            value = state.query,
            onValueChange = callbacks.onSearchChanged,
            label = "Search sessions",
        )

        StatusMessages(
            errorMessage = state.errorMessage,
            statusMessage = transientStatusMessage,
        )

        SessionsContent(
            state = state,
            callbacks = callbacks,
            activeSessionActions =
                ActiveSessionActionCallbacks(
                    onRename = {
                        renameDraft = ""
                        showRenameDialog = true
                    },
                    onFork = callbacks.onFork,
                    onExport = callbacks.onExport,
                    onCompact = callbacks.onCompact,
                ),
        )
    }

    SessionsDialogs(
        state = state,
        callbacks = callbacks,
        renameDialog =
            RenameDialogUiState(
                isVisible = showRenameDialog,
                draft = renameDraft,
                onDraftChange = { renameDraft = it },
                onDismiss = { showRenameDialog = false },
            ),
    )
}

@Composable
private fun SessionsDialogs(
    state: SessionsUiState,
    callbacks: SessionsScreenCallbacks,
    renameDialog: RenameDialogUiState,
) {
    if (renameDialog.isVisible) {
        RenameSessionDialog(
            name = renameDialog.draft,
            isBusy = state.isPerformingAction,
            onNameChange = renameDialog.onDraftChange,
            onDismiss = renameDialog.onDismiss,
            onConfirm = {
                callbacks.onRename(renameDialog.draft)
                renameDialog.onDismiss()
            },
        )
    }

    if (state.isForkPickerVisible) {
        ForkPickerDialog(
            isLoading = state.isLoadingForkMessages,
            candidates = state.forkCandidates,
            onDismiss = callbacks.onDismissForkDialog,
            onSelect = callbacks.onForkMessageSelected,
        )
    }
}

@Composable
private fun SessionsHeader(
    isRefreshing: Boolean,
    isFlatView: Boolean,
    onRefreshClick: () -> Unit,
    onToggleFlatView: () -> Unit,
    onNewSession: () -> Unit,
) {
    PiTopBar(
        title = {
            Text(
                text = "Sessions",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleFlatView) {
                    Text(if (isFlatView) "Tree" else "All")
                }
                TextButton(onClick = onRefreshClick, enabled = !isRefreshing) {
                    Text(if (isRefreshing) "Refreshing" else "Refresh")
                }
                PiButton(
                    label = "New",
                    onClick = onNewSession,
                )
            }
        },
    )
}

@Composable
private fun StatusMessages(
    errorMessage: String?,
    statusMessage: String?,
) {
    errorMessage?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    statusMessage?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SessionsContent(
    state: SessionsUiState,
    callbacks: SessionsScreenCallbacks,
    activeSessionActions: ActiveSessionActionCallbacks,
) {
    when {
        state.isLoading -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        state.hosts.isEmpty() -> {
            Text(
                text = "No hosts configured yet.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        state.groups.isEmpty() -> {
            Text(
                text = "No sessions found for this host.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        else -> {
            if (state.isFlatView) {
                FlatSessionsList(
                    groups = state.groups,
                    activeSessionPath = state.activeSessionPath,
                    isBusy = state.isResuming || state.isPerformingAction,
                    onResumeClick = callbacks.onResumeClick,
                    actions = activeSessionActions,
                )
            } else {
                SessionsList(
                    groups = state.groups,
                    activeSessionPath = state.activeSessionPath,
                    isBusy = state.isResuming || state.isPerformingAction,
                    callbacks =
                        SessionsListCallbacks(
                            onCwdToggle = callbacks.onCwdToggle,
                            onResumeClick = callbacks.onResumeClick,
                            actions = activeSessionActions,
                        ),
                )
            }
        }
    }
}

@Composable
private fun HostSelector(
    state: SessionsUiState,
    onHostSelected: (String) -> Unit,
) {
    if (state.hosts.isEmpty()) {
        return
    }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = state.hosts, key = { host -> host.id }) { host ->
            FilterChip(
                selected = host.id == state.selectedHostId,
                onClick = { onHostSelected(host.id) },
                label = { Text(host.name) },
            )
        }
    }
}

@Composable
private fun SessionsList(
    groups: List<CwdSessionGroupUiState>,
    activeSessionPath: String?,
    isBusy: Boolean,
    callbacks: SessionsListCallbacks,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            item(key = "header-${group.cwd}") {
                CwdHeader(
                    group = group,
                    onToggle = { callbacks.onCwdToggle(group.cwd) },
                )
            }

            if (group.isExpanded) {
                items(items = group.sessions, key = { session -> session.sessionPath }) { session ->
                    SessionCard(
                        session = session,
                        isActive = activeSessionPath == session.sessionPath,
                        isBusy = isBusy,
                        onResumeClick = { callbacks.onResumeClick(session) },
                        actions = callbacks.actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlatSessionsList(
    groups: List<CwdSessionGroupUiState>,
    activeSessionPath: String?,
    isBusy: Boolean,
    onResumeClick: (SessionRecord) -> Unit,
    actions: ActiveSessionActionCallbacks,
) {
    // Flatten all sessions and sort by updatedAt (most recent first)
    val allSessions =
        remember(groups) {
            groups.flatMap { it.sessions }.sortedByDescending { it.updatedAt }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = allSessions, key = { session -> session.sessionPath }) { session ->
            SessionCard(
                session = session,
                isActive = activeSessionPath == session.sessionPath,
                isBusy = isBusy,
                onResumeClick = { onResumeClick(session) },
                actions = actions,
                showCwd = true,
            )
        }
    }
}

@Composable
private fun CwdHeader(
    group: CwdSessionGroupUiState,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.cwd,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (group.isExpanded) "▼" else "▶",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun SessionCard(
    session: SessionRecord,
    isActive: Boolean,
    isBusy: Boolean,
    onResumeClick: () -> Unit,
    actions: ActiveSessionActionCallbacks,
    showCwd: Boolean = false,
) {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = session.displayTitle,
            style = MaterialTheme.typography.titleMedium,
        )

        if (showCwd) {
            val cwd = session.sessionPath.substringBeforeLast("/", "")
            if (cwd.isNotEmpty()) {
                Text(
                    text = cwd,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = session.sessionPath,
            style = MaterialTheme.typography.bodySmall,
        )

        session.firstUserMessagePreview?.let { preview ->
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Updated ${session.updatedAt}",
                style = MaterialTheme.typography.bodySmall,
            )

            PiButton(
                label = if (isActive) "Active" else "Resume",
                enabled = !isBusy && !isActive,
                onClick = onResumeClick,
            )
        }

        if (isActive) {
            SessionActionsRow(
                isBusy = isBusy,
                onRenameClick = actions.onRename,
                onForkClick = actions.onFork,
                onExportClick = actions.onExport,
                onCompactClick = actions.onCompact,
            )
        }
    }
}

private const val STATUS_MESSAGE_DURATION_MS = 3_000L
