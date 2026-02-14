package com.ayagmar.pimobile.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.sessions.SessionController
import kotlinx.coroutines.CancellationException
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
            } catch (_: PackageManager.NameNotFoundException) {
                "unknown"
            }

        uiState = uiState.copy(appVersion = appVersion)

        viewModelScope.launch {
            sessionController.connectionState.collect { state ->
                if (uiState.isChecking) return@collect

                val status =
                    when (state) {
                        ConnectionState.CONNECTED -> ConnectionStatus.CONNECTED
                        ConnectionState.CONNECTING,
                        ConnectionState.RECONNECTING,
                        -> ConnectionStatus.CHECKING
                        ConnectionState.DISCONNECTED -> ConnectionStatus.DISCONNECTED
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
                    statusMessage = null,
                    piVersion = null,
                    connectionStatus = ConnectionStatus.CHECKING,
                )

            try {
                val result = sessionController.getState()
                if (result.isSuccess) {
                    val data = result.getOrNull()?.data
                    val modelDescription =
                        data?.get("model")?.let { modelElement ->
                            if (modelElement is kotlinx.serialization.json.JsonObject) {
                                val name = modelElement["name"]?.toString()?.trim('"')
                                val provider = modelElement["provider"]?.toString()?.trim('"')
                                if (name != null && provider != null) "$name ($provider)" else name ?: provider
                            } else {
                                modelElement.toString().trim('"')
                            }
                        }

                    uiState =
                        uiState.copy(
                            isChecking = false,
                            connectionStatus = ConnectionStatus.CONNECTED,
                            piVersion = modelDescription,
                            statusMessage = "Bridge reachable",
                            errorMessage = null,
                        )
                } else {
                    uiState =
                        uiState.copy(
                            isChecking = false,
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            statusMessage = null,
                            errorMessage = result.exceptionOrNull()?.message ?: "Connection failed",
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState =
                    uiState.copy(
                        isChecking = false,
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        statusMessage = null,
                        errorMessage = "${e.javaClass.simpleName}: ${e.message}",
                    )
            }
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null, statusMessage = null)

            val result = sessionController.newSession()

            uiState =
                if (result.isSuccess) {
                    uiState.copy(
                        isLoading = false,
                        statusMessage = "New session created",
                        errorMessage = null,
                    )
                } else {
                    uiState.copy(
                        isLoading = false,
                        statusMessage = null,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to create new session",
                    )
                }
        }
    }
}

class SettingsViewModelFactory(
    private val context: Context,
    private val sessionController: SessionController,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == SettingsViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(
            sessionController = sessionController,
            context = context.applicationContext,
        ) as T
    }
}

data class SettingsUiState(
    val connectionStatus: ConnectionStatus? = null,
    val isChecking: Boolean = false,
    val isLoading: Boolean = false,
    val piVersion: String? = null,
    val appVersion: String = "unknown",
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    CHECKING,
}
