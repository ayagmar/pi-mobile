package com.ayagmar.pimobile.hosts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class HostsViewModel(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostsUiState(isLoading = true))
    val uiState: StateFlow<HostsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { previous ->
            previous.copy(
                isLoading = true,
                errorMessage = null,
                diagnosticResults = emptyMap(),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val profiles = profileStore.list()
            val items =
                profiles
                    .sortedBy { profile -> profile.name.lowercase() }
                    .map { profile ->
                        HostProfileItem(
                            profile = profile,
                            hasToken = tokenStore.hasToken(profile.id),
                            diagnosticStatus = DiagnosticStatus.NONE,
                        )
                    }

            _uiState.value =
                HostsUiState(
                    isLoading = false,
                    profiles = items,
                    errorMessage = null,
                    diagnosticResults = emptyMap(),
                )
        }
    }

    fun testConnection(hostId: String) {
        val profile = _uiState.value.profiles.find { it.profile.id == hostId }?.profile ?: return
        val token = tokenStore.getToken(hostId)

        if (token.isNullOrBlank()) {
            _uiState.update { current ->
                current.copy(
                    diagnosticResults =
                        current.diagnosticResults + (
                            hostId to
                                DiagnosticsResult.AuthError(
                                    hostProfile = profile,
                                    message = "No token configured",
                                )
                        ),
                )
            }
            return
        }

        // Mark as testing
        _uiState.update { current ->
            current.copy(
                profiles =
                    current.profiles.map { item ->
                        if (item.profile.id == hostId) {
                            item.copy(diagnosticStatus = DiagnosticStatus.TESTING)
                        } else {
                            item
                        }
                    },
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = diagnostics.testHost(profile, token)

            _uiState.update { current ->
                current.copy(
                    profiles =
                        current.profiles.map { item ->
                            if (item.profile.id == hostId) {
                                item.copy(
                                    diagnosticStatus =
                                        when (result) {
                                            is DiagnosticsResult.Success -> DiagnosticStatus.SUCCESS
                                            else -> DiagnosticStatus.FAILED
                                        },
                                )
                            } else {
                                item
                            }
                        },
                    diagnosticResults = current.diagnosticResults + (hostId to result),
                )
            }
        }
    }

    fun saveHost(draft: HostDraft) {
        when (val validation = draft.validate()) {
            is HostValidationResult.Invalid -> {
                _uiState.update { state -> state.copy(errorMessage = validation.reason) }
            }

            is HostValidationResult.Valid -> {
                val profile =
                    validation.profile.let { validated ->
                        if (validated.id.isBlank()) {
                            validated.copy(id = UUID.randomUUID().toString())
                        } else {
                            validated
                        }
                    }

                viewModelScope.launch(Dispatchers.IO) {
                    profileStore.upsert(profile)
                    if (draft.token.isNotBlank()) {
                        tokenStore.setToken(profile.id, draft.token)
                    }
                    refresh()
                }
            }
        }
    }

    fun deleteHost(hostId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            profileStore.delete(hostId)
            tokenStore.clearToken(hostId)
            refresh()
        }
    }
}

data class HostsUiState(
    val isLoading: Boolean = false,
    val profiles: List<HostProfileItem> = emptyList(),
    val errorMessage: String? = null,
    val diagnosticResults: Map<String, DiagnosticsResult> = emptyMap(),
)

class HostsViewModelFactory(
    context: Context,
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == HostsViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return HostsViewModel(
            profileStore = SharedPreferencesHostProfileStore(appContext),
            tokenStore = KeystoreHostTokenStore(appContext),
        ) as T
    }
}
