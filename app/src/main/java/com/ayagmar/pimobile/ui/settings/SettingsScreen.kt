package com.ayagmar.pimobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.di.AppServices

@Composable
fun SettingsRoute() {
    val context = LocalContext.current
    val viewModel =
        remember {
            SettingsViewModel(
                sessionController = AppServices.sessionController(),
                context = context.applicationContext,
            )
        }

    SettingsScreen(
        viewModel = viewModel,
    )
}

@Composable
private fun SettingsScreen(viewModel: SettingsViewModel) {
    val uiState = viewModel.uiState

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
        )

        // Connection Status
        ConnectionStatusCard(
            state = uiState,
            onPing = { viewModel.pingBridge() },
        )

        // Session Actions
        SessionActionsCard(
            onNewSession = { viewModel.createNewSession() },
            isLoading = uiState.isLoading,
        )

        // App Info
        AppInfoCard(
            version = uiState.appVersion,
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    state: SettingsUiState,
    onPing: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val statusColor =
                    when (state.connectionStatus) {
                        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
                        ConnectionStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
                        null -> MaterialTheme.colorScheme.outline
                    }

                Text(
                    text = "Status: ${state.connectionStatus?.name ?: "Unknown"}",
                    color = statusColor,
                )

                if (state.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            state.piVersion?.let { version ->
                Text(
                    text = "Pi version: $version",
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

            Button(
                onClick = onPing,
                enabled = !state.isChecking,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Check Connection")
            }
        }
    }
}

@Composable
private fun SessionActionsCard(
    onNewSession: () -> Unit,
    isLoading: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Session",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = "Create a new session in the current working directory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onNewSession,
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("New Session")
            }
        }
    }
}

@Composable
private fun AppInfoCard(version: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
}
