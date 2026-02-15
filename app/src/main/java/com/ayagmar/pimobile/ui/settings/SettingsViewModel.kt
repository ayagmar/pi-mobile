package com.ayagmar.pimobile.ui.settings

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Suppress("TooManyFunctions")
class SettingsViewModel(
    private val sessionController: SessionController,
    context: Context? = null,
    sharedPreferences: SharedPreferences? = null,
    appVersionOverride: String? = null,
) : ViewModel() {
    var uiState by mutableStateOf(SettingsUiState())
        private set

    private val prefs: SharedPreferences =
        sharedPreferences
            ?: requireNotNull(context) {
                "SettingsViewModel requires a Context when sharedPreferences is not provided"
            }.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val appVersion =
            appVersionOverride
                ?: context.resolveAppVersion()

        uiState =
            uiState.copy(
                appVersion = appVersion,
                autoCompactionEnabled = prefs.getBoolean(KEY_AUTO_COMPACTION, true),
                autoRetryEnabled = prefs.getBoolean(KEY_AUTO_RETRY, true),
            )

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

        refreshDeliveryModesFromState()
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

                    val steeringMode = data.stateModeField("steeringMode", "steering_mode") ?: uiState.steeringMode
                    val followUpMode = data.stateModeField("followUpMode", "follow_up_mode") ?: uiState.followUpMode

                    uiState =
                        uiState.copy(
                            isChecking = false,
                            connectionStatus = ConnectionStatus.CONNECTED,
                            piVersion = modelDescription,
                            statusMessage = "Bridge reachable",
                            errorMessage = null,
                            steeringMode = steeringMode,
                            followUpMode = followUpMode,
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

    fun toggleAutoCompaction() {
        val newValue = !uiState.autoCompactionEnabled
        uiState = uiState.copy(autoCompactionEnabled = newValue)
        prefs.edit().putBoolean(KEY_AUTO_COMPACTION, newValue).apply()

        viewModelScope.launch {
            val result = sessionController.setAutoCompaction(newValue)
            if (result.isFailure) {
                // Revert on failure
                val revertedValue = !newValue
                uiState =
                    uiState.copy(
                        autoCompactionEnabled = revertedValue,
                        errorMessage = "Failed to update auto-compaction",
                    )
                prefs.edit().putBoolean(KEY_AUTO_COMPACTION, revertedValue).apply()
            }
        }
    }

    fun toggleAutoRetry() {
        val newValue = !uiState.autoRetryEnabled
        uiState = uiState.copy(autoRetryEnabled = newValue)
        prefs.edit().putBoolean(KEY_AUTO_RETRY, newValue).apply()

        viewModelScope.launch {
            val result = sessionController.setAutoRetry(newValue)
            if (result.isFailure) {
                // Revert on failure
                val revertedValue = !newValue
                uiState =
                    uiState.copy(
                        autoRetryEnabled = revertedValue,
                        errorMessage = "Failed to update auto-retry",
                    )
                prefs.edit().putBoolean(KEY_AUTO_RETRY, revertedValue).apply()
            }
        }
    }

    fun setSteeringMode(mode: String) {
        if (mode == uiState.steeringMode) return

        val previousMode = uiState.steeringMode
        uiState = uiState.copy(steeringMode = mode, isUpdatingSteeringMode = true, errorMessage = null)

        viewModelScope.launch {
            val result = sessionController.setSteeringMode(mode)
            uiState =
                if (result.isSuccess) {
                    uiState.copy(isUpdatingSteeringMode = false)
                } else {
                    uiState.copy(
                        steeringMode = previousMode,
                        isUpdatingSteeringMode = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to update steering mode",
                    )
                }
        }
    }

    fun setFollowUpMode(mode: String) {
        if (mode == uiState.followUpMode) return

        val previousMode = uiState.followUpMode
        uiState = uiState.copy(followUpMode = mode, isUpdatingFollowUpMode = true, errorMessage = null)

        viewModelScope.launch {
            val result = sessionController.setFollowUpMode(mode)
            uiState =
                if (result.isSuccess) {
                    uiState.copy(isUpdatingFollowUpMode = false)
                } else {
                    uiState.copy(
                        followUpMode = previousMode,
                        isUpdatingFollowUpMode = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to update follow-up mode",
                    )
                }
        }
    }

    private fun refreshDeliveryModesFromState() {
        viewModelScope.launch {
            val result = sessionController.getState()
            val data = result.getOrNull()?.data ?: return@launch
            val steeringMode = data.stateModeField("steeringMode", "steering_mode") ?: uiState.steeringMode
            val followUpMode = data.stateModeField("followUpMode", "follow_up_mode") ?: uiState.followUpMode
            uiState =
                uiState.copy(
                    steeringMode = steeringMode,
                    followUpMode = followUpMode,
                )
        }
    }

    companion object {
        const val MODE_ALL = "all"
        const val MODE_ONE_AT_A_TIME = "one-at-a-time"

        private const val PREFS_NAME = "pi_mobile_settings"
        private const val KEY_AUTO_COMPACTION = "auto_compaction_enabled"
        private const val KEY_AUTO_RETRY = "auto_retry_enabled"
    }
}

private fun Context?.resolveAppVersion(): String {
    val safeContext = this ?: return "unknown"
    return try {
        safeContext.packageManager.getPackageInfo(safeContext.packageName, 0).versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "unknown"
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
    val autoCompactionEnabled: Boolean = true,
    val autoRetryEnabled: Boolean = true,
    val steeringMode: String = SettingsViewModel.MODE_ALL,
    val followUpMode: String = SettingsViewModel.MODE_ALL,
    val isUpdatingSteeringMode: Boolean = false,
    val isUpdatingFollowUpMode: Boolean = false,
)

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    CHECKING,
}

private fun JsonObject?.stateModeField(
    camelCaseKey: String,
    snakeCaseKey: String,
): String? {
    val value =
        this?.get(camelCaseKey)?.jsonPrimitive?.contentOrNull
            ?: this?.get(snakeCaseKey)?.jsonPrimitive?.contentOrNull

    return value?.takeIf {
        it == SettingsViewModel.MODE_ALL || it == SettingsViewModel.MODE_ONE_AT_A_TIME
    }
}
