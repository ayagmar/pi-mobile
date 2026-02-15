package com.ayagmar.pimobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.TransportPreference
import com.ayagmar.pimobile.ui.components.PiButton
import com.ayagmar.pimobile.ui.components.PiCard
import com.ayagmar.pimobile.ui.components.PiSpacing
import com.ayagmar.pimobile.ui.components.PiTopBar
import com.ayagmar.pimobile.ui.theme.ThemePreference
import kotlinx.coroutines.delay

@Composable
fun SettingsRoute(sessionController: SessionController) {
    val context = LocalContext.current
    val factory =
        remember(context, sessionController) {
            SettingsViewModelFactory(
                context = context,
                sessionController = sessionController,
            )
        }
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    var transientStatusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settingsViewModel) {
        settingsViewModel.messages.collect { message ->
            transientStatusMessage = message
        }
    }

    LaunchedEffect(transientStatusMessage) {
        if (transientStatusMessage != null) {
            delay(STATUS_MESSAGE_DURATION_MS)
            transientStatusMessage = null
        }
    }

    SettingsScreen(
        viewModel = settingsViewModel,
        transientStatusMessage = transientStatusMessage,
    )
}

@Composable
private fun SettingsScreen(
    viewModel: SettingsViewModel,
    transientStatusMessage: String?,
) {
    val uiState = viewModel.uiState

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PiSpacing.md),
    ) {
        PiTopBar(
            title = {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            actions = {},
        )

        ConnectionStatusCard(
            state = uiState,
            transientStatusMessage = transientStatusMessage,
            onPing = viewModel::pingBridge,
        )

        AgentBehaviorCard(
            autoCompactionEnabled = uiState.autoCompactionEnabled,
            autoRetryEnabled = uiState.autoRetryEnabled,
            transportPreference = uiState.transportPreference,
            effectiveTransportPreference = uiState.effectiveTransportPreference,
            transportRuntimeNote = uiState.transportRuntimeNote,
            themePreference = uiState.themePreference,
            steeringMode = uiState.steeringMode,
            followUpMode = uiState.followUpMode,
            isUpdatingSteeringMode = uiState.isUpdatingSteeringMode,
            isUpdatingFollowUpMode = uiState.isUpdatingFollowUpMode,
            onToggleAutoCompaction = viewModel::toggleAutoCompaction,
            onToggleAutoRetry = viewModel::toggleAutoRetry,
            onTransportPreferenceSelected = viewModel::setTransportPreference,
            onThemePreferenceSelected = viewModel::setThemePreference,
            onSteeringModeSelected = viewModel::setSteeringMode,
            onFollowUpModeSelected = viewModel::setFollowUpMode,
        )

        ChatHelpCard()

        AppInfoCard(
            version = uiState.appVersion,
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    state: SettingsUiState,
    transientStatusMessage: String?,
    onPing: () -> Unit,
) {
    PiCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Connection",
            style = MaterialTheme.typography.titleMedium,
        )

        ConnectionStatusRow(
            connectionStatus = state.connectionStatus,
            isChecking = state.isChecking,
        )

        ConnectionMessages(
            state = state,
            transientStatusMessage = transientStatusMessage,
        )

        PiButton(
            label = "Check Connection",
            onClick = onPing,
            enabled = !state.isChecking,
            modifier = Modifier.padding(top = PiSpacing.sm),
        )
    }
}

@Composable
private fun ConnectionStatusRow(
    connectionStatus: ConnectionStatus?,
    isChecking: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val statusColor =
            when (connectionStatus) {
                ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
                ConnectionStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
                null -> MaterialTheme.colorScheme.outline
            }

        Text(
            text = "Status: ${connectionStatus?.name ?: "Unknown"}",
            color = statusColor,
        )

        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.padding(start = 8.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ConnectionMessages(
    state: SettingsUiState,
    transientStatusMessage: String?,
) {
    state.piVersion?.let { version ->
        Text(
            text = "Pi version: $version",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    transientStatusMessage?.let { status ->
        Text(
            text = status,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    state.errorMessage?.let { error ->
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun AgentBehaviorCard(
    autoCompactionEnabled: Boolean,
    autoRetryEnabled: Boolean,
    transportPreference: TransportPreference,
    effectiveTransportPreference: TransportPreference,
    transportRuntimeNote: String,
    themePreference: ThemePreference,
    steeringMode: String,
    followUpMode: String,
    isUpdatingSteeringMode: Boolean,
    isUpdatingFollowUpMode: Boolean,
    onToggleAutoCompaction: () -> Unit,
    onToggleAutoRetry: () -> Unit,
    onTransportPreferenceSelected: (TransportPreference) -> Unit,
    onThemePreferenceSelected: (ThemePreference) -> Unit,
    onSteeringModeSelected: (String) -> Unit,
    onFollowUpModeSelected: (String) -> Unit,
) {
    PiCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Agent Behavior",
            style = MaterialTheme.typography.titleMedium,
        )

        SettingsToggleRow(
            title = "Auto-compact context",
            description = "Automatically compact conversation when nearing token limit",
            checked = autoCompactionEnabled,
            onToggle = onToggleAutoCompaction,
        )

        SettingsToggleRow(
            title = "Auto-retry on errors",
            description = "Automatically retry failed requests with exponential backoff",
            checked = autoRetryEnabled,
            onToggle = onToggleAutoRetry,
        )

        TransportPreferenceRow(
            selectedPreference = transportPreference,
            effectivePreference = effectiveTransportPreference,
            runtimeNote = transportRuntimeNote,
            onPreferenceSelected = onTransportPreferenceSelected,
        )

        ThemePreferenceRow(
            selectedPreference = themePreference,
            onPreferenceSelected = onThemePreferenceSelected,
        )

        ModeSelectorRow(
            title = "Steering mode",
            description = "How steer messages are delivered while streaming",
            selectedMode = steeringMode,
            isUpdating = isUpdatingSteeringMode,
            onModeSelected = onSteeringModeSelected,
        )

        ModeSelectorRow(
            title = "Follow-up mode",
            description = "How follow-up messages are queued while streaming",
            selectedMode = followUpMode,
            isUpdating = isUpdatingFollowUpMode,
            onModeSelected = onFollowUpModeSelected,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun TransportPreferenceRow(
    selectedPreference: TransportPreference,
    effectivePreference: TransportPreference,
    runtimeNote: String,
    onPreferenceSelected: (TransportPreference) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Transport preference",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Preferred transport between the app and bridge runtime",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportOptionButton(
                label = "Auto",
                selected = selectedPreference == TransportPreference.AUTO,
                onClick = { onPreferenceSelected(TransportPreference.AUTO) },
            )
            TransportOptionButton(
                label = "WebSocket",
                selected = selectedPreference == TransportPreference.WEBSOCKET,
                onClick = { onPreferenceSelected(TransportPreference.WEBSOCKET) },
            )
            TransportOptionButton(
                label = "SSE",
                selected = selectedPreference == TransportPreference.SSE,
                onClick = { onPreferenceSelected(TransportPreference.SSE) },
            )
        }

        Text(
            text = "Effective: ${effectivePreference.value}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        if (runtimeNote.isNotBlank()) {
            Text(
                text = runtimeNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemePreferenceRow(
    selectedPreference: ThemePreference,
    onPreferenceSelected: (ThemePreference) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Choose app appearance mode",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeOptionButton(
                label = "System",
                selected = selectedPreference == ThemePreference.SYSTEM,
                onClick = { onPreferenceSelected(ThemePreference.SYSTEM) },
            )
            ThemeOptionButton(
                label = "Light",
                selected = selectedPreference == ThemePreference.LIGHT,
                onClick = { onPreferenceSelected(ThemePreference.LIGHT) },
            )
            ThemeOptionButton(
                label = "Dark",
                selected = selectedPreference == ThemePreference.DARK,
                onClick = { onPreferenceSelected(ThemePreference.DARK) },
            )
        }
    }
}

@Composable
private fun ModeSelectorRow(
    title: String,
    description: String,
    selectedMode: String,
    isUpdating: Boolean,
    onModeSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeOptionButton(
                label = "All",
                selected = selectedMode == SettingsViewModel.MODE_ALL,
                enabled = !isUpdating,
                onClick = { onModeSelected(SettingsViewModel.MODE_ALL) },
            )
            ModeOptionButton(
                label = "One at a time",
                selected = selectedMode == SettingsViewModel.MODE_ONE_AT_A_TIME,
                enabled = !isUpdating,
                onClick = { onModeSelected(SettingsViewModel.MODE_ONE_AT_A_TIME) },
            )
        }
    }
}

@Composable
private fun TransportOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PiButton(
        label = label,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun ThemeOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PiButton(
        label = label,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun ModeOptionButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    PiButton(
        label = label,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun ChatHelpCard() {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Chat actions & gestures",
            style = MaterialTheme.typography.titleMedium,
        )

        HelpItem(
            action = "Send",
            help = "Tap the send icon or use keyboard Send action",
        )
        HelpItem(
            action = "Commands",
            help = "Tap the menu icon in the prompt field to open slash commands",
        )
        HelpItem(
            action = "Model",
            help = "Tap model chip to cycle; long-press to open full picker",
        )
        HelpItem(
            action = "Thinking/Tool output",
            help = "Tap show more/show less to expand or collapse long sections",
        )
        HelpItem(
            action = "Tree",
            help = "Open Tree from chat header to inspect branches and fork from entries",
        )
        HelpItem(
            action = "Bash & Stats",
            help = "Use terminal and chart icons in chat header",
        )
    }
}

@Composable
private fun HelpItem(
    action: String,
    help: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = action,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppInfoCard(version: String) {
    PiCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Version: $version",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val STATUS_MESSAGE_DURATION_MS = 3_000L
