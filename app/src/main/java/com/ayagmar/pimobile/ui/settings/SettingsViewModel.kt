package com.ayagmar.pimobile.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.sessions.SessionController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val sessionController: SessionController,
    context: Context,
) : ViewModel() {
    var uiState by mutableStateOf(SettingsUiState())
        private set

    init {
        val appVersion =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                "unknown"
            }

        uiState = uiState.copy(appVersion = appVersion)

        // Observe connection state
        viewModelScope.launch {
            sessionController.connectionState.collect { state ->
                val status =
                    when (state) {
                        ConnectionState.CONNECTED -> ConnectionStatus.CONNECTED
                        ConnectionState.DISCONNECTED -> ConnectionStatus.DISCONNECTED
                        else -> ConnectionStatus.DISCONNECTED
                    }
                uiState = uiState.copy(connectionStatus = status)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun pingBridge() {
        viewModelScope.launch {
            uiState =
                uiState.copy(
                    isChecking = true,
                    errorMessage = null,
                    piVersion = null,
                    connectionStatus = ConnectionStatus.CHECKING,
                )

            try {
                val result = sessionController.getState()
                if (result.isSuccess) {
                    val data = result.getOrNull()?.data
                    val model = data?.get("model")?.toString()
                    uiState =
                        uiState.copy(
                            isChecking = false,
                            connectionStatus = ConnectionStatus.CONNECTED,
                            piVersion = model?.let { "Model: $it" } ?: "Connected",
                        )
                } else {
                    uiState =
                        uiState.copy(
                            isChecking = false,
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            errorMessage = result.exceptionOrNull()?.message ?: "Connection failed",
                        )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState =
                    uiState.copy(
                        isChecking = false,
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        errorMessage = "${e.javaClass.simpleName}: ${e.message}",
                    )
            }
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)

            val result = sessionController.newSession()

            uiState =
                uiState.copy(
                    isLoading = false,
                    errorMessage = if (result.isSuccess) "New session created" else result.exceptionOrNull()?.message,
                )
        }
    }
}

data class SettingsUiState(
    val connectionStatus: ConnectionStatus? = null,
    val isChecking: Boolean = false,
    val isLoading: Boolean = false,
    val piVersion: String? = null,
    val appVersion: String = "unknown",
    val errorMessage: String? = null,
)

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    CHECKING,
}
