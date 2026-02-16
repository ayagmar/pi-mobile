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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.sessions.ForkableMessage

@Composable
fun SessionActionsRow(
    isBusy: Boolean,
    onRenameClick: () -> Unit,
    onForkClick: () -> Unit,
    onExportClick: () -> Unit,
    onCompactClick: () -> Unit,
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
    }
}

@Composable
fun RenameSessionDialog(
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
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Session name") },
                singleLine = true,
            )
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
                                Text(candidate.preview)
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

val SessionRecord.displayTitle: String
    get() {
        return displayName ?: firstUserMessagePreview ?: sessionPath.substringAfterLast('/')
    }
