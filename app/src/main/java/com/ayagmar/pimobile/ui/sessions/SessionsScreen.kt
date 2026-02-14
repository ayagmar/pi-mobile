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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.sessions.CwdSessionGroupUiState
import com.ayagmar.pimobile.sessions.SessionAction
import com.ayagmar.pimobile.sessions.SessionsUiState
import com.ayagmar.pimobile.sessions.SessionsViewModel
import com.ayagmar.pimobile.sessions.SessionsViewModelFactory

@Composable
fun SessionsRoute() {
    val context = LocalContext.current
    val factory = remember(context) { SessionsViewModelFactory(context) }
    val sessionsViewModel: SessionsViewModel = viewModel(factory = factory)
    val uiState by sessionsViewModel.uiState.collectAsStateWithLifecycle()

    SessionsScreen(
        state = uiState,
        callbacks =
            SessionsScreenCallbacks(
                onHostSelected = sessionsViewModel::onHostSelected,
                onSearchChanged = sessionsViewModel::onSearchQueryChanged,
                onCwdToggle = sessionsViewModel::onCwdToggle,
                onRefreshClick = sessionsViewModel::refreshSessions,
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
    val onRefreshClick: () -> Unit,
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
    callbacks: SessionsScreenCallbacks,
) {
    var renameDraft by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionsHeader(
            isRefreshing = state.isRefreshing,
            onRefreshClick = callbacks.onRefreshClick,
        )

        HostSelector(
            state = state,
            onHostSelected = callbacks.onHostSelected,
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = callbacks.onSearchChanged,
            label = { Text("Search sessions") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        StatusMessages(
            errorMessage = state.errorMessage,
            statusMessage = state.statusMessage,
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
    onRefreshClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.headlineSmall,
        )
        TextButton(onClick = onRefreshClick, enabled = !isRefreshing) {
            Text(if (isRefreshing) "Refreshing" else "Refresh")
        }
    }
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
private fun SessionCard(
    session: SessionRecord,
    isActive: Boolean,
    isBusy: Boolean,
    onResumeClick: () -> Unit,
    actions: ActiveSessionActionCallbacks,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = session.displayTitle,
                style = MaterialTheme.typography.titleMedium,
            )

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

                Button(
                    enabled = !isBusy && !isActive,
                    onClick = onResumeClick,
                ) {
                    Text(if (isActive) "Active" else "Resume")
                }
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
}
