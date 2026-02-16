package com.ayagmar.pimobile.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.hosts.ConnectionDiagnostics
import com.ayagmar.pimobile.hosts.DiagnosticStatus
import com.ayagmar.pimobile.hosts.DiagnosticsResult
import com.ayagmar.pimobile.hosts.HostDraft
import com.ayagmar.pimobile.hosts.HostProfileItem
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.hosts.HostsUiState
import com.ayagmar.pimobile.hosts.HostsViewModel
import com.ayagmar.pimobile.hosts.HostsViewModelFactory

@Composable
fun HostsRoute(
    profileStore: HostProfileStore,
    tokenStore: HostTokenStore,
    diagnostics: ConnectionDiagnostics,
) {
    val factory =
        remember(profileStore, tokenStore, diagnostics) {
            HostsViewModelFactory(
                profileStore = profileStore,
                tokenStore = tokenStore,
                diagnostics = diagnostics,
            )
        }
    val hostsViewModel: HostsViewModel = viewModel(factory = factory)
    val uiState by hostsViewModel.uiState.collectAsStateWithLifecycle()

    var editorDraft by remember { mutableStateOf<HostDraft?>(null) }

    HostsScreen(
        state = uiState,
        onAddClick = {
            editorDraft = HostDraft()
        },
        onEditClick = { item ->
            editorDraft =
                HostDraft(
                    id = item.profile.id,
                    name = item.profile.name,
                    host = item.profile.host,
                    port = item.profile.port.toString(),
                    useTls = item.profile.useTls,
                )
        },
        onDeleteClick = { hostId ->
            hostsViewModel.deleteHost(hostId)
        },
        onTestClick = { hostId ->
            hostsViewModel.testConnection(hostId)
        },
    )

    val activeDraft = editorDraft
    if (activeDraft != null) {
        HostEditorDialog(
            initialDraft = activeDraft,
            onDismiss = {
                editorDraft = null
            },
            onSave = { draft ->
                hostsViewModel.saveHost(draft)
                editorDraft = null
            },
        )
    }
}

@Composable
private fun HostsScreen(
    state: HostsUiState,
    onAddClick: () -> Unit,
    onEditClick: (HostProfileItem) -> Unit,
    onDeleteClick: (String) -> Unit,
    onTestClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Hosts",
                style = MaterialTheme.typography.headlineSmall,
            )
            Button(onClick = onAddClick) {
                Text("Add host")
            }
        }

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
            return
        }

        if (state.profiles.isEmpty()) {
            Text(
                text = "No hosts configured yet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = state.profiles,
                key = { item -> item.profile.id },
            ) { item ->
                HostCard(
                    item = item,
                    diagnosticResult = state.diagnosticResults[item.profile.id],
                    onEditClick = { onEditClick(item) },
                    onDeleteClick = { onDeleteClick(item.profile.id) },
                    onTestClick = { onTestClick(item.profile.id) },
                )
            }
        }
    }
}

@Composable
private fun HostCard(
    item: HostProfileItem,
    diagnosticResult: DiagnosticsResult?,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onTestClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.profile.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                DiagnosticStatusIcon(status = item.diagnosticStatus)
            }

            Text(
                text = item.profile.endpoint,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = if (item.hasToken) "Token stored securely" else "No token configured",
                style = MaterialTheme.typography.bodySmall,
            )

            // Show diagnostic result details if available
            diagnosticResult?.let { result ->
                DiagnosticResultDetail(result = result)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onTestClick,
                    enabled = item.diagnosticStatus != DiagnosticStatus.TESTING,
                ) {
                    if (item.diagnosticStatus == DiagnosticStatus.TESTING) {
                        Text("Testing...")
                    } else {
                        Text("Test")
                    }
                }
                TextButton(onClick = onEditClick) {
                    Text("Edit")
                }
                TextButton(onClick = onDeleteClick) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStatusIcon(status: DiagnosticStatus) {
    when (status) {
        DiagnosticStatus.NONE -> {}
        DiagnosticStatus.TESTING -> {
            CircularProgressIndicator(
                modifier = Modifier.padding(4.dp),
                strokeWidth = 2.dp,
            )
        }
        DiagnosticStatus.SUCCESS -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Connection successful",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DiagnosticStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Connection failed",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DiagnosticResultDetail(result: DiagnosticsResult) {
    when (result) {
        is DiagnosticsResult.Success -> {
            Column {
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                result.model?.let {
                    Text(
                        text = "Model: $it",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                result.cwd?.let {
                    Text(
                        text = "CWD: $it",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        is DiagnosticsResult.NetworkError -> {
            Text(
                text = "Network: ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is DiagnosticsResult.AuthError -> {
            Text(
                text = "Auth: ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is DiagnosticsResult.RpcError -> {
            Text(
                text = "RPC: ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HostEditorDialog(
    initialDraft: HostDraft,
    onDismiss: () -> Unit,
    onSave: (HostDraft) -> Unit,
) {
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialDraft.id == null) "Add host" else "Edit host")
        },
        text = {
            HostDraftFields(
                draft = draft,
                onDraftChange = { updatedDraft ->
                    draft = updatedDraft
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text("Save")
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
private fun HostDraftFields(
    draft: HostDraft,
    onDraftChange: (HostDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { newName ->
                onDraftChange(draft.copy(name = newName))
            },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.host,
            onValueChange = { newHost ->
                onDraftChange(draft.copy(host = newHost))
            },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.port,
            onValueChange = { newPort ->
                onDraftChange(draft.copy(port = newPort))
            },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.token,
            onValueChange = { newToken ->
                onDraftChange(draft.copy(token = newToken))
            },
            label = { Text("Token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Use TLS")
            Switch(
                checked = draft.useTls,
                onCheckedChange = { checked ->
                    onDraftChange(draft.copy(useTls = checked))
                },
            )
        }
    }
}
