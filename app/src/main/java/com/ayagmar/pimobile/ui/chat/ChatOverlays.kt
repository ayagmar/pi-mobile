package com.ayagmar.pimobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ExtensionNotification
import com.ayagmar.pimobile.chat.ExtensionUiRequest
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.delay

@Composable
internal fun ExtensionUiDialogs(
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
internal fun NotificationsDisplay(
    notifications: List<ExtensionNotification>,
    onClear: (Int) -> Unit,
) {
    val latestNotification = notifications.lastOrNull() ?: return
    val index = notifications.lastIndex

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

private data class PaletteCommandItem(
    val command: SlashCommandInfo,
    val support: CommandSupport,
)

private enum class CommandSupport {
    SUPPORTED,
    BRIDGE_BACKED,
    UNSUPPORTED,
}

private val commandSupportOrder =
    listOf(
        CommandSupport.SUPPORTED,
        CommandSupport.BRIDGE_BACKED,
        CommandSupport.UNSUPPORTED,
    )

private val CommandSupport.groupLabel: String
    get() =
        when (this) {
            CommandSupport.SUPPORTED -> "Supported"
            CommandSupport.BRIDGE_BACKED -> "Bridge-backed"
            CommandSupport.UNSUPPORTED -> "Unsupported"
        }

private val CommandSupport.badge: String
    get() =
        when (this) {
            CommandSupport.SUPPORTED -> "supported"
            CommandSupport.BRIDGE_BACKED -> "bridge-backed"
            CommandSupport.UNSUPPORTED -> "unsupported"
        }

@Composable
private fun CommandSupport.color(): Color {
    return when (this) {
        CommandSupport.SUPPORTED -> MaterialTheme.colorScheme.primary
        CommandSupport.BRIDGE_BACKED -> MaterialTheme.colorScheme.tertiary
        CommandSupport.UNSUPPORTED -> MaterialTheme.colorScheme.error
    }
}

private fun commandSupport(command: SlashCommandInfo): CommandSupport {
    return when (command.source) {
        ChatViewModel.COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED -> CommandSupport.BRIDGE_BACKED
        ChatViewModel.COMMAND_SOURCE_BUILTIN_UNSUPPORTED -> CommandSupport.UNSUPPORTED
        else -> CommandSupport.SUPPORTED
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun CommandPalette(
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

    val filteredPaletteCommands =
        remember(filteredCommands) {
            filteredCommands.map { command ->
                PaletteCommandItem(
                    command = command,
                    support = commandSupport(command),
                )
            }
        }

    val groupedCommands =
        remember(filteredPaletteCommands) {
            filteredPaletteCommands.groupBy { item -> item.support }
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
                } else if (filteredPaletteCommands.isEmpty()) {
                    Text(
                        text = "No commands found",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        commandSupportOrder.forEach { support ->
                            val commandsInGroup = groupedCommands[support].orEmpty()
                            if (commandsInGroup.isEmpty()) {
                                return@forEach
                            }

                            item {
                                Text(
                                    text = support.groupLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            items(
                                items = commandsInGroup,
                                key = { item -> "${item.command.source}:${item.command.name}" },
                            ) { item ->
                                CommandItem(
                                    command = item.command,
                                    support = item.support,
                                    onClick = { onCommandSelected(item.command) },
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
    support: CommandSupport,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "/${command.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = support.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = support.color(),
                )
            }
            command.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (support == CommandSupport.SUPPORTED) {
                Text(
                    text = "Source: ${command.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private const val NOTIFICATION_AUTO_DISMISS_MS = 4_000L
