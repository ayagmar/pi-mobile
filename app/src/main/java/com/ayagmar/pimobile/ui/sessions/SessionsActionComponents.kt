package com.ayagmar.pimobile.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.sessions.ForkableMessage
import com.ayagmar.pimobile.sessions.formatCwdTail
import com.ayagmar.pimobile.sessions.privacySafeText

@Composable
@Suppress("LongParameterList")
fun SessionActionsRow(
    isBusy: Boolean,
    onRenameClick: () -> Unit,
    onForkClick: () -> Unit,
    onExportClick: () -> Unit,
    onCompactClick: () -> Unit,
    onShareClick: () -> Unit = {},
    onRevokeShareClick: () -> Unit = {},
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onRenameClick, enabled = !isBusy) {
                Text("Rename")
            }
        }
        item {
            TextButton(onClick = onForkClick, enabled = !isBusy) {
                Text("Fork")
            }
        }
        item {
            TextButton(onClick = onExportClick, enabled = !isBusy) {
                Text("Export")
            }
        }
        item {
            TextButton(onClick = onCompactClick, enabled = !isBusy) {
                Text("Compact")
            }
        }
        item {
            TextButton(onClick = onShareClick, enabled = !isBusy) {
                Text("Share session link")
            }
        }
        item {
            TextButton(onClick = onRevokeShareClick, enabled = !isBusy) {
                Text("Revoke shared link")
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
fun RenameSessionDialog(
    currentSession: SessionRecord?,
    name: String,
    isBusy: Boolean,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename active session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                currentSession?.let { session ->
                    Text(
                        text = session.displayTitle,
                    )
                    session.displaySubtitle?.let { subtitle ->
                        Text(subtitle)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Session name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isBusy && name.isNotBlank(),
            ) {
                Text("Rename")
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
fun ForkPickerDialog(
    isLoading: Boolean,
    candidates: List<ForkableMessage>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fork from message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = candidates,
                            key = { candidate -> candidate.entryId },
                        ) { candidate ->
                            TextButton(
                                onClick = { onSelect(candidate.entryId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(privacySafeText(candidate.preview) ?: "User message")
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
fun NewSessionDialog(
    workspaces: List<String>,
    initialCwd: String,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var cwdDraft by rememberSaveable { mutableStateOf(initialCwd) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (workspaces.isNotEmpty()) {
                    Text(
                        text = "Workspaces",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(workspaces, key = { it }) { ws ->
                            val isSelected = cwdDraft.trim().trimEnd('/') == ws.trim().trimEnd('/')
                            FilterChip(
                                selected = isSelected,
                                onClick = { cwdDraft = ws },
                                label = { Text(formatCwdTail(ws)) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = cwdDraft,
                    onValueChange = { cwdDraft = it },
                    label = { Text("Working directory") },
                    placeholder = { Text("/path/to/project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(cwdDraft.trim()) },
                enabled = !isBusy && cwdDraft.isNotBlank(),
            ) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

val SessionRecord.displayTitle: String
    get() = privacySafeText(displayName ?: firstUserMessagePreview) ?: "Untitled session"

val SessionRecord.displaySubtitle: String?
    get() =
        privacySafeText(firstUserMessagePreview)
            ?.takeIf { !displayName.isNullOrBlank() && it != displayTitle }
