package com.ayagmar.pimobile.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.coresessions.SessionGroup
import com.ayagmar.pimobile.coresessions.SessionIndexRepository
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.perf.PerformanceMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Suppress("TooManyFunctions")
class SessionsViewModel(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val repository: SessionIndexRepository,
    private val sessionController: SessionController,
    private val cwdPreferenceStore: SessionCwdPreferenceStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState(isLoading = true))
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _navigateToChat = Channel<Unit>(Channel.BUFFERED)
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    val navigateToChat: Flow<Unit> = _navigateToChat.receiveAsFlow()

    private var observeJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var warmupConnectionJob: Job? = null
    private var warmConnectionHostId: String? = null
    private var warmConnectionCwd: String? = null

    init {
        loadHosts()
    }

    fun refreshHosts() {
        loadHosts()
    }

    private fun emitMessage(message: String) {
        _messages.tryEmit(message)
    }

    fun onHostSelected(hostId: String) {
        val state = _uiState.value
        if (state.selectedHostId == hostId) {
            return
        }

        searchDebounceJob?.cancel()
        resetWarmConnectionIfHostChanged(hostId)

        _uiState.update { current ->
            current.copy(
                selectedHostId = hostId,
                selectedCwd = readPreferredCwd(hostId),
                isLoading = true,
                groups = emptyList(),
                activeSessionPath = null,
                isForkPickerVisible = false,
                forkCandidates = emptyList(),
                isLoadingForkMessages = false,
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
            )
        }

        val hostId = _uiState.value.selectedHostId ?: return
        searchDebounceJob?.cancel()
        searchDebounceJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                observeHost(hostId)
            }
    }

    fun onCwdSelected(cwd: String) {
        if (cwd == _uiState.value.selectedCwd) {
            return
        }

        _uiState.update { current ->
            current.copy(selectedCwd = cwd)
        }

        _uiState.value.selectedHostId?.let { hostId ->
            persistPreferredCwd(hostId = hostId, cwd = cwd)
            maybeWarmupConnection(hostId = hostId, preferredCwd = cwd)
        }
    }

    fun toggleFlatView() {
        _uiState.update { current ->
            current.copy(isFlatView = !current.isFlatView)
        }
    }

    fun refreshSessions() {
        val hostId = _uiState.value.selectedHostId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            repository.refresh(hostId)
        }
    }

    fun newSession() {
        val hostId = _uiState.value.selectedHostId ?: return
        val selectedHost = _uiState.value.hosts.firstOrNull { host -> host.id == hostId } ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val token = tokenStore.getToken(hostId)
            if (token.isNullOrBlank()) {
                emitError("No token configured for host ${selectedHost.name}")
                return@launch
            }

            _uiState.update { current ->
                current.copy(isResuming = true, isPerformingAction = false, errorMessage = null)
            }

            val cwd = resolveConnectionCwdForHost(hostId)
            val connectResult = sessionController.ensureConnected(selectedHost, token, cwd)
            if (connectResult.isFailure) {
                emitError(connectResult.exceptionOrNull()?.message ?: "Failed to connect for new session")
                return@launch
            }

            markConnectionWarm(hostId = hostId, cwd = cwd)
            completeNewSession()
        }
    }

    private suspend fun completeNewSession() {
        val newSessionResult = sessionController.newSession()
        if (newSessionResult.isSuccess) {
            val sessionPath = resolveActiveSessionPath()
            emitMessage("New session created")
            _navigateToChat.trySend(Unit)
            _uiState.update { current ->
                current.copy(isResuming = false, activeSessionPath = sessionPath, errorMessage = null)
            }
        } else {
            emitError(newSessionResult.exceptionOrNull()?.message ?: "Failed to create new session")
        }
    }

    private fun emitError(message: String) {
        _uiState.update { current -> current.copy(isResuming = false, errorMessage = message) }
    }

    private fun readPreferredCwd(hostId: String): String? {
        return cwdPreferenceStore.getPreferredCwd(hostId)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun persistPreferredCwd(
        hostId: String,
        cwd: String,
    ) {
        val normalized = cwd.trim().takeIf { it.isNotBlank() } ?: return
        cwdPreferenceStore.setPreferredCwd(hostId = hostId, cwd = normalized)
    }

    private fun maybeWarmupConnection(
        hostId: String,
        preferredCwd: String?,
    ) {
        val selectedHost = _uiState.value.hosts.firstOrNull { host -> host.id == hostId }
        val shouldSkipWarmup =
            preferredCwd.isNullOrBlank() ||
                selectedHost == null ||
                (warmConnectionHostId == hostId && warmConnectionCwd == preferredCwd) ||
                warmupConnectionJob?.isActive == true
        if (shouldSkipWarmup) return

        val cwd = requireNotNull(preferredCwd)

        warmupConnectionJob =
            viewModelScope.launch(Dispatchers.IO) {
                val token = tokenStore.getToken(hostId)
                if (token.isNullOrBlank()) return@launch

                val result = sessionController.ensureConnected(requireNotNull(selectedHost), token, cwd)
                if (result.isSuccess) {
                    markConnectionWarm(hostId = hostId, cwd = cwd)
                }
            }
    }

    private fun resolveConnectionCwdForHost(hostId: String): String {
        val state = _uiState.value

        return resolveConnectionCwd(
            hostId = hostId,
            selectedCwd = state.selectedCwd,
            warmConnectionHostId = warmConnectionHostId,
            warmConnectionCwd = warmConnectionCwd,
            groups = state.groups,
        )
    }

    private suspend fun resolveActiveSessionPath(): String? {
        val stateResponse = sessionController.getState().getOrNull() ?: return null
        return stateResponse.data
            ?.get("sessionFile")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private fun markConnectionWarm(
        hostId: String,
        cwd: String,
    ) {
        warmConnectionHostId = hostId
        warmConnectionCwd = cwd
    }

    private fun resetWarmConnectionIfHostChanged(hostId: String) {
        if (warmConnectionHostId != null && warmConnectionHostId != hostId) {
            warmConnectionHostId = null
            warmConnectionCwd = null
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
                )
            }

            val resumeResult =
                sessionController.resume(
                    hostProfile = selectedHost,
                    token = token,
                    session = session,
                )

            if (resumeResult.isSuccess) {
                markConnectionWarm(hostId = hostId, cwd = session.cwd)
                emitMessage("Resumed ${session.summaryTitle()}")
                _navigateToChat.trySend(Unit)
            }

            _uiState.update { current ->
                if (resumeResult.isSuccess) {
                    current.copy(
                        isResuming = false,
                        activeSessionPath = resumeResult.getOrNull() ?: session.sessionPath,
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isResuming = false,
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
                )
            }

            val result = sessionController.forkSessionFromEntryId(action.entryId)

            if (result.isSuccess) {
                repository.refresh(hostId)
            }

            if (result.isSuccess) {
                emitMessage("Forked from selected message")
            }

            _uiState.update { current ->
                if (result.isSuccess) {
                    val updatedPath = result.getOrNull() ?: current.activeSessionPath
                    current.copy(
                        isPerformingAction = false,
                        activeSessionPath = updatedPath,
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isPerformingAction = false,
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
                )
            }

            val result = action.execute(sessionController)

            if (result.isSuccess) {
                repository.refresh(hostId)
            }

            if (result.isSuccess) {
                emitMessage(action.successMessage)
            }

            _uiState.update { current ->
                if (result.isSuccess) {
                    val updatedPath = result.getOrNull() ?: current.activeSessionPath
                    current.copy(
                        isPerformingAction = false,
                        activeSessionPath = updatedPath,
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isPerformingAction = false,
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
                )
            }

            val exportResult = sessionController.exportSession()

            if (exportResult.isSuccess) {
                emitMessage("Exported HTML to ${exportResult.getOrNull()}")
            }

            _uiState.update { current ->
                if (exportResult.isSuccess) {
                    current.copy(
                        isPerformingAction = false,
                        errorMessage = null,
                    )
                } else {
                    current.copy(
                        isPerformingAction = false,
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
                        errorMessage = "Add a host to browse sessions.",
                    )
                }
                return@launch
            }

            val current = _uiState.value
            val hostIds = hosts.map { it.id }.toSet()

            // Preserve existing host selection if still valid; otherwise pick first
            val selectedHostId =
                if (current.selectedHostId != null && current.selectedHostId in hostIds) {
                    current.selectedHostId
                } else {
                    hosts.first().id
                }

            // Skip full reload if hosts haven't changed and sessions are already loaded
            val hostsChanged = current.hosts.map { it.id }.toSet() != hostIds
            val needsObserve = hostsChanged || current.groups.isEmpty()

            _uiState.update { state ->
                val preferredCwd = readPreferredCwd(selectedHostId)
                state.copy(
                    isLoading = needsObserve && state.groups.isEmpty(),
                    hosts = hosts,
                    selectedHostId = selectedHostId,
                    selectedCwd =
                        if (state.selectedHostId == selectedHostId) {
                            state.selectedCwd ?: preferredCwd
                        } else {
                            preferredCwd
                        },
                    errorMessage = null,
                )
            }

            maybeWarmupConnection(
                hostId = selectedHostId,
                preferredCwd = _uiState.value.selectedCwd ?: _uiState.value.groups.firstOrNull()?.cwd,
            )

            if (needsObserve) {
                observeHost(selectedHostId)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.initialize(selectedHostId)
                }
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
                        val mappedGroups = mapGroups(state.groups)
                        val preferredSelection = current.selectedCwd ?: readPreferredCwd(hostId)
                        val selectedCwd = resolveSelectedCwd(preferredSelection, mappedGroups)

                        current.copy(
                            isLoading = false,
                            groups = mappedGroups,
                            selectedCwd = selectedCwd,
                            isRefreshing = state.isRefreshing,
                            errorMessage = state.errorMessage,
                        )
                    }

                    _uiState.value.selectedCwd?.let { selectedCwd ->
                        persistPreferredCwd(hostId = hostId, cwd = selectedCwd)
                    }

                    maybeWarmupConnection(
                        hostId = hostId,
                        preferredCwd = _uiState.value.selectedCwd ?: state.groups.firstOrNull()?.cwd,
                    )
                }
            }
    }

    override fun onCleared() {
        observeJob?.cancel()
        searchDebounceJob?.cancel()
        warmupConnectionJob?.cancel()
        _navigateToChat.close()
        super.onCleared()
    }
}

private const val SEARCH_DEBOUNCE_MS = 250L
private const val DEFAULT_NEW_SESSION_CWD = "/home/user"

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

@Suppress("LongParameterList")
internal fun resolveConnectionCwd(
    hostId: String,
    selectedCwd: String?,
    warmConnectionHostId: String?,
    warmConnectionCwd: String?,
    groups: List<CwdSessionGroupUiState>,
    defaultCwd: String = DEFAULT_NEW_SESSION_CWD,
): String {
    return selectedCwd
        ?: warmConnectionCwd?.takeIf { warmConnectionHostId == hostId }
        ?: groups.firstOrNull()?.cwd
        ?: defaultCwd
}

private fun mapGroups(groups: List<SessionGroup>): List<CwdSessionGroupUiState> {
    return groups.map { group ->
        CwdSessionGroupUiState(
            cwd = group.cwd,
            sessions = group.sessions,
        )
    }
}

internal fun resolveSelectedCwd(
    currentSelection: String?,
    groups: List<CwdSessionGroupUiState>,
): String? {
    if (groups.isEmpty()) {
        return null
    }

    return currentSelection
        ?.takeIf { selected -> groups.any { group -> group.cwd == selected } }
        ?: groups.first().cwd
}

private fun SessionRecord.summaryTitle(): String {
    return displayName ?: firstUserMessagePreview ?: sessionPath.substringAfterLast('/')
}

data class SessionsUiState(
    val isLoading: Boolean = false,
    val hosts: List<HostProfile> = emptyList(),
    val selectedHostId: String? = null,
    val selectedCwd: String? = null,
    val query: String = "",
    val groups: List<CwdSessionGroupUiState> = emptyList(),
    val isRefreshing: Boolean = false,
    val isResuming: Boolean = false,
    val isPerformingAction: Boolean = false,
    val isLoadingForkMessages: Boolean = false,
    val isForkPickerVisible: Boolean = false,
    val forkCandidates: List<ForkableMessage> = emptyList(),
    val activeSessionPath: String? = null,
    val errorMessage: String? = null,
    val isFlatView: Boolean = true,
)

data class CwdSessionGroupUiState(
    val cwd: String,
    val sessions: List<SessionRecord>,
)

class SessionsViewModelFactory(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val repository: SessionIndexRepository,
    private val sessionController: SessionController,
    private val cwdPreferenceStore: SessionCwdPreferenceStore,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == SessionsViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return SessionsViewModel(
            profileStore = profileStore,
            tokenStore = tokenStore,
            repository = repository,
            sessionController = sessionController,
            cwdPreferenceStore = cwdPreferenceStore,
        ) as T
    }
}
