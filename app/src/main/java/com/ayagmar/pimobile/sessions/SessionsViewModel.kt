package com.ayagmar.pimobile.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.coresessions.FileSessionIndexCache
import com.ayagmar.pimobile.coresessions.SessionGroup
import com.ayagmar.pimobile.coresessions.SessionIndexRepository
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.di.AppServices
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.hosts.KeystoreHostTokenStore
import com.ayagmar.pimobile.hosts.SharedPreferencesHostProfileStore
import com.ayagmar.pimobile.perf.PerformanceMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class SessionsViewModel(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val repository: SessionIndexRepository,
    private val sessionController: SessionController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState(isLoading = true))
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val collapsedCwds = linkedSetOf<String>()
    private var observeJob: Job? = null

    init {
        loadHosts()
    }

    fun onHostSelected(hostId: String) {
        val state = _uiState.value
        if (state.selectedHostId == hostId) {
            return
        }

        collapsedCwds.clear()

        _uiState.update { current ->
            current.copy(
                selectedHostId = hostId,
                isLoading = true,
                groups = emptyList(),
                activeSessionPath = null,
                isForkPickerVisible = false,
                forkCandidates = emptyList(),
                isLoadingForkMessages = false,
                statusMessage = null,
                errorMessage = null,
            )
        }

        observeHost(hostId)
        viewModelScope.launch(Dispatchers.IO) {
            repository.initialize(hostId)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { current ->
            current.copy(
                query = query,
                statusMessage = null,
            )
        }

        val hostId = _uiState.value.selectedHostId ?: return
        observeHost(hostId)
    }

    fun onCwdToggle(cwd: String) {
        if (collapsedCwds.contains(cwd)) {
            collapsedCwds.remove(cwd)
        } else {
            collapsedCwds.add(cwd)
        }

        _uiState.update { current ->
            current.copy(groups = remapGroups(current.groups, collapsedCwds))
        }
    }

    fun refreshSessions() {
        val hostId = _uiState.value.selectedHostId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            repository.refresh(hostId)
        }
    }

    fun resumeSession(session: SessionRecord) {
        val hostId = _uiState.value.selectedHostId ?: return
        val selectedHost = _uiState.value.hosts.firstOrNull { host -> host.id == hostId } ?: return

        // Record resume start for performance tracking
        PerformanceMetrics.recordResumeStart()

        viewModelScope.launch(Dispatchers.IO) {
            val token = tokenStore.getToken(hostId)
            if (token.isNullOrBlank()) {
                _uiState.update { current ->
                    current.copy(
                        errorMessage = "No token configured for host ${selectedHost.name}",
                        statusMessage = null,
                    )
                }
                return@launch
            }

            _uiState.update { current ->
                current.copy(
                    isResuming = true,
                    isPerformingAction = false,
                    isForkPickerVisible = false,
                    forkCandidates = emptyList(),
                    isLoadingForkMessages = false,
                    errorMessage = null,
                    statusMessage = null,
                )
            }

            val resumeResult =
                sessionController.resume(
                    hostProfile = selectedHost,
                    token = token,
                    session = session,
                )

            _uiState.update { current ->
                if (resumeResult.isSuccess) {
                    current.copy(
                        isResuming = false,
                        activeSessionPath = resumeResult.getOrNull() ?: session.sessionPath,
                        statusMessage = "Resumed ${session.summaryTitle()}",
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isResuming = false,
                        statusMessage = null,
                        errorMessage =
                            resumeResult.exceptionOrNull()?.message
                                ?: "Failed to resume session",
                    )
                }
            }
        }
    }

    fun runSessionAction(action: SessionAction) {
        when (action) {
            is SessionAction.Export -> runExportAction()
            is SessionAction.ForkFromEntry -> runForkFromEntryAction(action)
            else -> runStandardAction(action)
        }
    }

    fun requestForkMessages() {
        val activeSessionPath = _uiState.value.activeSessionPath
        if (activeSessionPath == null) {
            _uiState.update { current ->
                current.copy(
                    errorMessage = "Resume a session before forking",
                    statusMessage = null,
                )
            }
            return
        }

        if (_uiState.value.isLoadingForkMessages) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoadingForkMessages = true,
                    isForkPickerVisible = true,
                    forkCandidates = emptyList(),
                    errorMessage = null,
                    statusMessage = null,
                )
            }

            val result = sessionController.getForkMessages()

            _uiState.update { current ->
                if (result.isSuccess) {
                    val candidates = result.getOrNull().orEmpty()
                    if (candidates.isEmpty()) {
                        current.copy(
                            isLoadingForkMessages = false,
                            isForkPickerVisible = false,
                            forkCandidates = emptyList(),
                            errorMessage = "No forkable user messages found",
                        )
                    } else {
                        current.copy(
                            isLoadingForkMessages = false,
                            isForkPickerVisible = true,
                            forkCandidates = candidates,
                            errorMessage = null,
                        )
                    }
                } else {
                    current.copy(
                        isLoadingForkMessages = false,
                        isForkPickerVisible = false,
                        forkCandidates = emptyList(),
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to load fork messages",
                    )
                }
            }
        }
    }

    fun dismissForkPicker() {
        _uiState.update { current ->
            current.copy(
                isForkPickerVisible = false,
                forkCandidates = emptyList(),
                isLoadingForkMessages = false,
            )
        }
    }

    fun forkFromSelectedMessage(entryId: String) {
        dismissForkPicker()
        runSessionAction(SessionAction.ForkFromEntry(entryId))
    }

    private fun runForkFromEntryAction(action: SessionAction.ForkFromEntry) {
        val activeSessionPath = _uiState.value.activeSessionPath
        if (activeSessionPath == null) {
            _uiState.update { current ->
                current.copy(
                    errorMessage = "Resume a session before forking",
                    statusMessage = null,
                )
            }
            return
        }

        val hostId = _uiState.value.selectedHostId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { current ->
                current.copy(
                    isPerformingAction = true,
                    isResuming = false,
                    isForkPickerVisible = false,
                    forkCandidates = emptyList(),
                    errorMessage = null,
                    statusMessage = null,
                )
            }

            val result = sessionController.forkSessionFromEntryId(action.entryId)

            if (result.isSuccess) {
                repository.refresh(hostId)
            }

            _uiState.update { current ->
                if (result.isSuccess) {
                    val updatedPath = result.getOrNull() ?: current.activeSessionPath
                    current.copy(
                        isPerformingAction = false,
                        activeSessionPath = updatedPath,
                        statusMessage = "Forked from selected message",
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isPerformingAction = false,
                        statusMessage = null,
                        errorMessage = result.exceptionOrNull()?.message ?: "Fork failed",
                    )
                }
            }
        }
    }

    private fun runStandardAction(action: SessionAction) {
        val activeSessionPath = _uiState.value.activeSessionPath
        if (activeSessionPath == null) {
            _uiState.update { current ->
                current.copy(
                    errorMessage = "Resume a session before running this action",
                    statusMessage = null,
                )
            }
            return
        }

        val hostId = _uiState.value.selectedHostId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { current ->
                current.copy(
                    isPerformingAction = true,
                    isResuming = false,
                    isForkPickerVisible = false,
                    forkCandidates = emptyList(),
                    isLoadingForkMessages = false,
                    errorMessage = null,
                    statusMessage = null,
                )
            }

            val result = action.execute(sessionController)

            if (result.isSuccess) {
                repository.refresh(hostId)
            }

            _uiState.update { current ->
                if (result.isSuccess) {
                    val updatedPath = result.getOrNull() ?: current.activeSessionPath
                    current.copy(
                        isPerformingAction = false,
                        activeSessionPath = updatedPath,
                        statusMessage = action.successMessage,
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isPerformingAction = false,
                        statusMessage = null,
                        errorMessage = result.exceptionOrNull()?.message ?: "Session action failed",
                    )
                }
            }
        }
    }

    private fun runExportAction() {
        val activeSessionPath = _uiState.value.activeSessionPath
        if (activeSessionPath == null) {
            _uiState.update { current ->
                current.copy(
                    errorMessage = "Resume a session before exporting",
                    statusMessage = null,
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { current ->
                current.copy(
                    isPerformingAction = true,
                    isResuming = false,
                    isForkPickerVisible = false,
                    forkCandidates = emptyList(),
                    isLoadingForkMessages = false,
                    errorMessage = null,
                    statusMessage = null,
                )
            }

            val exportResult = sessionController.exportSession()

            _uiState.update { current ->
                if (exportResult.isSuccess) {
                    current.copy(
                        isPerformingAction = false,
                        statusMessage = "Exported HTML to ${exportResult.getOrNull()}",
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isPerformingAction = false,
                        statusMessage = null,
                        errorMessage = exportResult.exceptionOrNull()?.message ?: "Failed to export session",
                    )
                }
            }
        }
    }

    private fun loadHosts() {
        viewModelScope.launch(Dispatchers.IO) {
            val hosts = profileStore.list().sortedBy { host -> host.name.lowercase() }

            if (hosts.isEmpty()) {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        hosts = emptyList(),
                        selectedHostId = null,
                        groups = emptyList(),
                        statusMessage = null,
                        errorMessage = "Add a host to browse sessions.",
                    )
                }
                return@launch
            }

            val selectedHostId = hosts.first().id

            _uiState.update { current ->
                current.copy(
                    isLoading = true,
                    hosts = hosts,
                    selectedHostId = selectedHostId,
                    statusMessage = null,
                    errorMessage = null,
                )
            }

            observeHost(selectedHostId)
            viewModelScope.launch(Dispatchers.IO) {
                repository.initialize(selectedHostId)
            }
        }
    }

    private fun observeHost(hostId: String) {
        observeJob?.cancel()
        observeJob =
            viewModelScope.launch {
                repository.observe(hostId, query = _uiState.value.query).collect { state ->
                    _uiState.update { current ->
                        // Record sessions visible on first successful load
                        if (current.isLoading && state.groups.isNotEmpty()) {
                            PerformanceMetrics.recordSessionsVisible()
                        }
                        current.copy(
                            isLoading = false,
                            groups = mapGroups(state.groups, collapsedCwds),
                            isRefreshing = state.isRefreshing,
                            errorMessage = state.errorMessage,
                        )
                    }
                }
            }
    }
}

sealed interface SessionAction {
    val successMessage: String

    suspend fun execute(controller: SessionController): Result<String?>

    data class Rename(
        val name: String,
    ) : SessionAction {
        override val successMessage: String = "Renamed active session"

        override suspend fun execute(controller: SessionController): Result<String?> {
            return controller.renameSession(name)
        }
    }

    data object Compact : SessionAction {
        override val successMessage: String = "Compacted active session"

        override suspend fun execute(controller: SessionController): Result<String?> {
            return controller.compactSession()
        }
    }

    data class ForkFromEntry(
        val entryId: String,
    ) : SessionAction {
        override val successMessage: String = "Forked from selected message"

        override suspend fun execute(controller: SessionController): Result<String?> {
            return controller.forkSessionFromEntryId(entryId)
        }
    }

    data object Export : SessionAction {
        override val successMessage: String = "Exported active session"

        override suspend fun execute(controller: SessionController): Result<String?> {
            return controller.exportSession().map { null }
        }
    }
}

private fun mapGroups(
    groups: List<SessionGroup>,
    collapsedCwds: Set<String>,
): List<CwdSessionGroupUiState> {
    return groups.map { group ->
        CwdSessionGroupUiState(
            cwd = group.cwd,
            sessions = group.sessions,
            isExpanded = !collapsedCwds.contains(group.cwd),
        )
    }
}

private fun remapGroups(
    groups: List<CwdSessionGroupUiState>,
    collapsedCwds: Set<String>,
): List<CwdSessionGroupUiState> {
    return groups.map { group ->
        group.copy(isExpanded = !collapsedCwds.contains(group.cwd))
    }
}

private fun SessionRecord.summaryTitle(): String {
    return displayName ?: firstUserMessagePreview ?: sessionPath.substringAfterLast('/')
}

data class SessionsUiState(
    val isLoading: Boolean = false,
    val hosts: List<HostProfile> = emptyList(),
    val selectedHostId: String? = null,
    val query: String = "",
    val groups: List<CwdSessionGroupUiState> = emptyList(),
    val isRefreshing: Boolean = false,
    val isResuming: Boolean = false,
    val isPerformingAction: Boolean = false,
    val isLoadingForkMessages: Boolean = false,
    val isForkPickerVisible: Boolean = false,
    val forkCandidates: List<ForkableMessage> = emptyList(),
    val activeSessionPath: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

data class CwdSessionGroupUiState(
    val cwd: String,
    val sessions: List<SessionRecord>,
    val isExpanded: Boolean,
)

class SessionsViewModelFactory(
    context: Context,
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == SessionsViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        val profileStore = SharedPreferencesHostProfileStore(appContext)
        val tokenStore = KeystoreHostTokenStore(appContext)

        val repository =
            SessionIndexRepository(
                remoteDataSource = BridgeSessionIndexRemoteDataSource(profileStore, tokenStore),
                cache = FileSessionIndexCache(appContext.cacheDir.toPath().resolve("session-index-cache")),
            )

        @Suppress("UNCHECKED_CAST")
        return SessionsViewModel(
            profileStore = profileStore,
            tokenStore = tokenStore,
            repository = repository,
            sessionController = AppServices.sessionController(),
        ) as T
    }
}
