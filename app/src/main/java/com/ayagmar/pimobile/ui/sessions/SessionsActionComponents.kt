package com.ayagmar.pimobile.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.coresessions.SessionRecord

@Composable
fun SessionActionsRow(
    isBusy: Boolean,
    onRenameClick: () -> Unit,
    onForkClick: () -> Unit,
    onExportClick: () -> Unit,
    onCompactClick: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(actionItems(onRenameClick, onForkClick, onExportClick, onCompactClick)) { actionItem ->
            TextButton(onClick = actionItem.onClick, enabled = !isBusy) {
                Text(actionItem.label)
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

val SessionRecord.displayTitle: String
    get() {
        return displayName ?: firstUserMessagePreview ?: sessionPath.substringAfterLast('/')
    }

private data class ActionItem(
    val label: String,
    val onClick: () -> Unit,
)

private fun actionItems(
    onRenameClick: () -> Unit,
    onForkClick: () -> Unit,
    onExportClick: () -> Unit,
    onCompactClick: () -> Unit,
): List<ActionItem> {
    return listOf(
        ActionItem(label = "Rename", onClick = onRenameClick),
        ActionItem(label = "Fork", onClick = onForkClick),
        ActionItem(label = "Export", onClick = onExportClick),
        ActionItem(label = "Compact", onClick = onCompactClick),
    )
}
