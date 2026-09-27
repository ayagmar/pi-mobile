@file:Suppress("LongParameterList", "ReturnCount", "MaxLineLength", "ktlint:standard:max-line-length")

package com.ayagmar.pimobile.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.coresessions.SessionGroup
import com.ayagmar.pimobile.coresessions.SessionIndexRepository
import com.ayagmar.pimobile.coresessions.SessionIndexState
import com.ayagmar.pimobile.coresessions.SessionKey
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.coresessions.SharedSessionLocator
import com.ayagmar.pimobile.coresessions.SharedSessionLocatorCodec
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.hosts.endpointShareAuthority
import com.ayagmar.pimobile.perf.PerformanceMetrics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions", "LargeClass")
class SessionsViewModel(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val repository: SessionIndexRepository,
    private val sessionController: SessionController,
    private val cwdPreferenceStore: SessionCwdPreferenceStore,
    private val savedStateStore: SessionSavedStateStore,
    private val shareRemoteDataSource: BridgeSessionShareRemoteDataSource? = null,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onResumeStarted: () -> Unit = PerformanceMetrics::recordResumeStart,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState(isLoading = true))
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _navigateToChat = Channel<Unit>(Channel.BUFFERED)
    val uiState = _uiState.asStateFlow()
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    val navigateToChat: Flow<Unit> = _navigateToChat.receiveAsFlow()

    private var observedStates: List<SessionIndexState> = emptyList()
    private var savedState = SavedSessionsState()
    private var loadHostsJob: Job? = null
    private var observeJob: Job? = null
    private var initializeJob: Job? = null
    private var refreshJob: Job? = null
    private var warmupConnectionJob: Job? = null
    private var warmConnectionHostId: String? = null
    private var warmConnectionCwd: String? = null

    private val quickReplyCoordinator =
        QuickReplyCoordinator(
            controller = sessionController,
            tokenStore = tokenStore,
            hostById = { hostId -> _uiState.value.hosts.firstOrNull { it.id == hostId } },
            recordByKey = ::recordForKey,
            scope = viewModelScope,
            onOpenChat = { _navigateToChat.trySend(Unit) },
        )

    init {
        viewModelScope.launch {
            quickReplyCoordinator.state.collect { quickReply ->
                _uiState.update { it.copy(quickReply = quickReply) }
            }
        }
        viewModelScope.launch {
            sessionController.activeSession.collect { active ->
                _uiState.update {
                    it.copy(
                        activeSessionKey = active?.sessionKey,
                        activeSessionPath = active?.sessionPath,
                    )
                }
                rebuildProjection()
            }
        }
        viewModelScope.launch {
            sessionController.isStreaming.collect { streaming ->
                _uiState.update { it.copy(isRunActive = streaming || sessionController.isRetrying.value) }
            }
        }
        viewModelScope.launch {
            sessionController.isRetrying.collect { retrying ->
                _uiState.update {
                    it.copy(
                        isRetrying = retrying,
                        isRunActive = retrying || sessionController.isStreaming.value,
                    )
                }
            }
        }
        loadHosts()
    }

    fun refreshHosts() = loadHosts()

    fun onHostSelected(hostId: String) {
        val host = _uiState.value.hosts.firstOrNull { it.id == hostId } ?: return
        _uiState.update { state ->
            val groups = groupsForHost(host.id)
            val selectedCwd =
                resolveHostCwdSelection(
                    currentSelection = state.selectedCwdByHost[host.id] ?: readPreferredCwd(host.id),
                    groups = groups,
                )
            state.copy(
                selectedHostId = host.id,
                selectedCwd = selectedCwd,
                selectedCwdByHost = state.selectedCwdByHost.withSelection(host.id, selectedCwd),
                groups = groups,
                filter = state.filter.copy(hostId = host.id),
            )
        }
        rebuildProjection()
    }

    fun showAllHosts() {
        _uiState.update { it.copy(filter = it.filter.copy(hostId = null)) }
        rebuildProjection()
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
        rebuildProjection()
    }

    fun onWorkspaceFilterChanged(label: String?) {
        _uiState.update { state -> state.copy(filter = state.filter.copy(workspaceLabel = label)) }
        rebuildProjection()
    }

    fun togglePinnedFilter() {
        _uiState.update { it.copy(filter = it.filter.copy(pinnedOnly = !it.filter.pinnedOnly)) }
        rebuildProjection()
    }

    fun toggleHiddenFilter() {
        _uiState.update { it.copy(filter = it.filter.copy(hiddenOnly = !it.filter.hiddenOnly)) }
        rebuildProjection()
    }

    fun toggleActiveFilter() {
        _uiState.update { it.copy(filter = it.filter.copy(activeOnly = !it.filter.activeOnly)) }
        rebuildProjection()
    }

    fun setFreshnessFilter(filter: SessionFreshnessFilter) {
        _uiState.update { it.copy(filter = it.filter.copy(freshness = filter)) }
        rebuildProjection()
    }

    fun toggleDensity() {
        savedState =
            savedState.copy(
                density =
                    if (savedState.density == SessionCardDensity.COMFORTABLE) {
                        SessionCardDensity.COMPACT
                    } else {
                        SessionCardDensity.COMFORTABLE
                    },
            )
        persistSavedState()
    }

    fun onCwdSelected(cwd: String) {
        val normalizedCwd = cwd.trim().takeIf(String::isNotBlank) ?: return
        val hostId = _uiState.value.selectedHostId ?: return
        _uiState.update { state ->
            state.copy(
                selectedCwd = normalizedCwd,
                selectedCwdByHost = state.selectedCwdByHost.withSelection(hostId, normalizedCwd),
            )
        }
        cwdPreferenceStore.setPreferredCwd(hostId, normalizedCwd)
    }

    fun toggleFlatView() = toggleDensity()

    fun refreshSessions() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch(backgroundDispatcher) {
                repository.refreshAll(_uiState.value.hosts.map(HostProfile::id), MAX_CONCURRENT_HOST_REFRESHES)
            }
    }

    fun newSession(targetCwd: String? = null) {
        val host = selectedHost() ?: return
        viewModelScope.launch(backgroundDispatcher) {
            val token = tokenStore.getToken(host.id)
            if (token.isNullOrBlank()) {
                emitError("No token configured for host ${host.name}")
                return@launch
            }
            _uiState.update { it.copy(isResuming = true, errorMessage = null) }
            val cwd = targetCwd?.trim()?.takeIf { it.isNotBlank() } ?: resolveConnectionCwdForHost(host.id)
            val connected = sessionController.ensureConnected(host, token, cwd)
            if (connected.isFailure) {
                emitError(connected.exceptionOrNull()?.message ?: "Failed to connect for new session")
                return@launch
            }
            markConnectionWarm(host.id, cwd)
            onCwdSelected(cwd)
            val result = sessionController.newSession()
            if (result.isSuccess) {
                _uiState.update { it.copy(isResuming = false, errorMessage = null) }
                _messages.tryEmit("New session created")
                _navigateToChat.trySend(Unit)
            } else {
                emitError(result.exceptionOrNull()?.message ?: "Failed to create new session")
            }
        }
    }

    fun resumeSession(session: SessionRecord) {
        _uiState.value.items.firstOrNull { it.record == session }?.let(::resumeSession)
    }

    fun resumeSession(item: SessionCockpitItem) {
        val session =
            item.record ?: run {
                emitError("Refresh this host before opening the saved session")
                return
            }
        val host = _uiState.value.hosts.firstOrNull { it.id == item.hostId } ?: return
        selectHostWorkspace(host.id, session.cwd)
        onResumeStarted()
        viewModelScope.launch(backgroundDispatcher) {
            val token = tokenStore.getToken(host.id)
            if (token.isNullOrBlank()) {
                emitError("No token configured for host ${host.name}")
                return@launch
            }
            _uiState.update { it.copy(isResuming = true, errorMessage = null) }
            val result = sessionController.resume(host, token, session)
            if (result.isSuccess) {
                markConnectionWarm(host.id, session.cwd)
                _messages.tryEmit("Opened ${item.title}")
                _navigateToChat.trySend(Unit)
            }
            _uiState.update {
                it.copy(
                    isResuming = false,
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun togglePin(item: SessionCockpitItem) {
        val key = requireStableKey(item) ?: return
        savedState = if (item.isPinned) savedState.unpin(key) else savedState.pin(key)
        persistSavedState()
    }

    fun toggleHidden(item: SessionCockpitItem) {
        val key = requireStableKey(item) ?: return
        savedState = if (item.isHidden) savedState.unhide(key) else savedState.hide(key)
        persistSavedState()
    }

    fun removeSavedItem(item: SessionCockpitItem) {
        val key = item.key ?: return
        savedState = savedState.remove(key)
        persistSavedState()
    }

    fun retrySavedItem(item: SessionCockpitItem) {
        viewModelScope.launch(backgroundDispatcher) { repository.refresh(item.hostId) }
    }

    fun openQuickReply(item: SessionCockpitItem) {
        val key = requireStableKey(item) ?: return
        quickReplyCoordinator.open(key, "${item.title} · ${item.hostLabel}")
    }

    fun updateQuickReplyDraft(value: String) = quickReplyCoordinator.updateDraft(value)

    fun selectQuickReplyMode(mode: QuickReplyDeliveryMode) = quickReplyCoordinator.selectDeliveryMode(mode)

    fun sendQuickReply(openAfterSend: Boolean = false) = quickReplyCoordinator.send(openAfterSend)

    fun dismissQuickReply() = quickReplyCoordinator.dismiss()

    fun openQuickReplyChat() = quickReplyCoordinator.openCurrentChat()

    fun shareSession(item: SessionCockpitItem) {
        val source =
            shareRemoteDataSource ?: run {
                emitError("Sharing is unavailable")
                return
            }
        val session = item.record ?: return
        val host = _uiState.value.hosts.firstOrNull { it.id == item.hostId } ?: return
        if (requireStableKey(item) == null) return
        viewModelScope.launch(backgroundDispatcher) {
            _uiState.update { it.copy(isPerformingAction = true, errorMessage = null) }
            runCatching {
                val share = source.getOrCreate(host.id, session)
                share.webUrl ?: SharedSessionLocatorCodec.encode(
                    SharedSessionLocator(host.endpointShareAuthority(), share.shareReference),
                )
            }.onSuccess { link ->
                _uiState.update { it.copy(isPerformingAction = false, shareLink = link) }
                _messages.tryEmit("Share link ready")
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isPerformingAction = false,
                        errorMessage = error.message ?: "Sharing failed",
                    )
                }
            }
        }
    }

    fun revokeSessionShare(item: SessionCockpitItem) {
        val source = shareRemoteDataSource ?: return
        val session = item.record ?: return
        viewModelScope.launch(backgroundDispatcher) {
            _uiState.update { it.copy(isPerformingAction = true, errorMessage = null) }
            runCatching { source.revoke(item.hostId, session) }
                .onSuccess {
                    _uiState.update { it.copy(isPerformingAction = false, shareLink = null) }
                    _messages.tryEmit("Shared link revoked")
                }.onFailure { error ->
                    _uiState.update { it.copy(isPerformingAction = false, errorMessage = error.message) }
                }
        }
    }

    fun shareActiveSession() = activeItem()?.let(::shareSession) ?: emitError("Resume a session before sharing")

    fun revokeActiveSessionShare() = activeItem()?.let(::revokeSessionShare) ?: Unit

    fun runSessionAction(action: SessionAction) {
        val hostId = _uiState.value.activeSessionKey?.hostProfileId ?: return emitError("Resume a session first")
        viewModelScope.launch(backgroundDispatcher) {
            _uiState.update { it.copy(isPerformingAction = true, errorMessage = null) }
            val result = action.execute(sessionController)
            if (result.isSuccess) repository.refresh(hostId)
            _uiState.update {
                it.copy(
                    isPerformingAction = false,
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
            if (result.isSuccess) _messages.tryEmit(action.successMessage)
        }
    }

    fun requestForkMessages() {
        if (_uiState.value.activeSessionKey == null) return emitError("Resume a session before forking")
        viewModelScope.launch(backgroundDispatcher) {
            _uiState.update { it.copy(isLoadingForkMessages = true, isForkPickerVisible = true) }
            val result = sessionController.getForkMessages()
            _uiState.update {
                it.copy(
                    isLoadingForkMessages = false,
                    isForkPickerVisible = result.isSuccess && result.getOrNull().orEmpty().isNotEmpty(),
                    forkCandidates = result.getOrNull().orEmpty(),
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun dismissForkPicker() {
        _uiState.update { it.copy(isForkPickerVisible = false, forkCandidates = emptyList()) }
    }

    fun forkFromSelectedMessage(entryId: String) {
        dismissForkPicker()
        runSessionAction(SessionAction.ForkFromEntry(entryId))
    }

    private fun loadHosts() {
        loadHostsJob?.cancel()
        initializeJob?.cancel()
        observeJob?.cancel()
        loadHostsJob =
            viewModelScope.launch(backgroundDispatcher) {
                val hosts = profileStore.list().sortedBy { it.name.lowercase() }
                val ids = hosts.map(HostProfile::id)
                applyConfiguredHosts(hosts, ids)
                observeJob =
                    launch {
                        repository.observeAll(ids).collect { states ->
                            applyObservedStates(states)
                            rebuildProjection()
                        }
                    }
                initializeJob =
                    launch(backgroundDispatcher) {
                        repository.initializeAll(ids, MAX_CONCURRENT_HOST_REFRESHES)
                    }
            }
    }

    private fun applyConfiguredHosts(
        hosts: List<HostProfile>,
        ids: List<String>,
    ) {
        val hostIds = ids.toSet()
        savedState = savedStateStore.reconcileConfiguredHosts(hostIds)
        observedStates = observedStates.filter { it.hostId in hostIds }
        val selectedHostId = _uiState.value.selectedHostId?.takeIf(ids::contains) ?: ids.firstOrNull()
        _uiState.update { state ->
            var selections = state.selectedCwdByHost.filterKeys(hostIds::contains).toMutableMap()
            ids.forEach { hostId ->
                if (hostId !in selections) readPreferredCwd(hostId)?.let { selections[hostId] = it }
            }
            val groups = groupsForHost(selectedHostId)
            val selectedCwd =
                selectedHostId?.let { hostId -> resolveHostCwdSelection(selections[hostId], groups) }
            if (selectedHostId != null) selections = selections.withSelection(selectedHostId, selectedCwd).toMutableMap()
            state.copy(
                hosts = hosts,
                selectedHostId = selectedHostId,
                selectedCwd = selectedCwd,
                selectedCwdByHost = selections,
                groups = groups,
                isLoading = hosts.isNotEmpty(),
                errorMessage = if (hosts.isEmpty()) "Add a host to browse sessions." else null,
                filter = state.filter.copy(hostId = state.filter.hostId?.takeIf(ids::contains)),
                density = savedState.density,
            )
        }
    }

    private fun applyObservedStates(states: List<SessionIndexState>) {
        observedStates = states
        _uiState.update { state ->
            val groups = groupsForHost(state.selectedHostId, states)
            val selectedCwd =
                state.selectedHostId?.let { hostId ->
                    resolveHostCwdSelection(state.selectedCwdByHost[hostId], groups)
                }
            state.copy(
                isLoading =
                    states.isNotEmpty() &&
                        states.all { it.groups.isEmpty() && it.errorMessage == null },
                selectedCwd = selectedCwd,
                selectedCwdByHost =
                    state.selectedHostId?.let { hostId ->
                        state.selectedCwdByHost.withSelection(hostId, selectedCwd)
                    } ?: state.selectedCwdByHost,
                groups = groups,
                isRefreshing = states.any(SessionIndexState::isRefreshing),
            )
        }
    }

    private fun rebuildProjection() {
        val state = _uiState.value
        val projection =
            buildSessionCockpit(
                hosts = state.hosts,
                states = observedStates,
                saved = savedState,
                activeKey = state.activeSessionKey,
                query = state.query,
                filter = state.filter,
            )
        _uiState.update {
            it.copy(
                items = projection.items,
                hostStatuses = projection.hostStatuses,
                workspaceLabels = projection.workspaceLabels,
                density = savedState.density,
            )
        }
    }

    private fun persistSavedState() {
        savedState = savedState.normalized()
        savedStateStore.write(savedState)
        rebuildProjection()
    }

    private fun requireStableKey(item: SessionCockpitItem): SessionKey? {
        val key = item.key
        if (key == null) emitError(item.stableActionDisabledReason ?: "This action needs a stable session identity")
        return key
    }

    private fun recordForKey(key: SessionKey): SessionRecord? =
        _uiState.value.items.firstOrNull { it.key == key && it.record?.hasStableIdentity == true }?.record
            ?: observedStates.asSequence().filter { it.hostId == key.hostProfileId }
                .flatMap { it.groups.asSequence() }.flatMap { it.sessions.asSequence() }
                .firstOrNull { it.hasStableIdentity && it.sessionId == key.sessionId }

    private fun activeItem(): SessionCockpitItem? =
        _uiState.value.activeSessionKey?.let { key ->
            _uiState.value.items.firstOrNull { it.key == key }
        }

    private fun selectedHost(): HostProfile? =
        _uiState.value.hosts.firstOrNull { it.id == _uiState.value.selectedHostId }

    private fun selectHostWorkspace(
        hostId: String,
        cwd: String,
    ) {
        val normalizedCwd = cwd.trim().takeIf(String::isNotBlank) ?: return
        _uiState.update { state ->
            state.copy(
                selectedHostId = hostId,
                selectedCwd = normalizedCwd,
                selectedCwdByHost = state.selectedCwdByHost.withSelection(hostId, normalizedCwd),
                groups = groupsForHost(hostId),
            )
        }
        cwdPreferenceStore.setPreferredCwd(hostId, normalizedCwd)
    }

    private fun groupsForHost(
        hostId: String?,
        states: List<SessionIndexState> = observedStates,
    ): List<CwdSessionGroupUiState> = states.firstOrNull { it.hostId == hostId }?.groups.orEmpty().map(::mapGroup)

    private fun resolveConnectionCwdForHost(hostId: String): String {
        val state = _uiState.value
        return resolveConnectionCwd(
            hostId = hostId,
            selectedCwd = state.selectedCwdByHost[hostId],
            warmConnectionHostId = warmConnectionHostId,
            warmConnectionCwd = warmConnectionCwd,
            groups = groupsForHost(hostId),
        )
    }

    private fun markConnectionWarm(
        hostId: String,
        cwd: String,
    ) {
        warmConnectionHostId = hostId
        warmConnectionCwd = cwd
    }

    private fun readPreferredCwd(hostId: String): String? =
        cwdPreferenceStore.getPreferredCwd(hostId)?.trim()?.takeIf(String::isNotBlank)

    private fun emitError(message: String) {
        _uiState.update { it.copy(isResuming = false, isPerformingAction = false, errorMessage = message) }
    }

    override fun onCleared() {
        loadHostsJob?.cancel()
        observeJob?.cancel()
        initializeJob?.cancel()
        refreshJob?.cancel()
        warmupConnectionJob?.cancel()
        quickReplyCoordinator.dismiss()
        _navigateToChat.close()
    }
}

private const val MAX_CONCURRENT_HOST_REFRESHES = 2
private const val DEFAULT_NEW_SESSION_CWD = "/home/user"

sealed interface SessionAction {
    val successMessage: String

    suspend fun execute(controller: SessionController): Result<String?>

    data class Rename(val name: String) : SessionAction {
        override val successMessage = "Renamed active session"

        override suspend fun execute(controller: SessionController) = controller.renameSession(name)
    }

    data object Compact : SessionAction {
        override val successMessage = "Compacted active session"

        override suspend fun execute(controller: SessionController) = controller.compactSession()
    }

    data class ForkFromEntry(val entryId: String) : SessionAction {
        override val successMessage = "Forked from selected message"

        override suspend fun execute(controller: SessionController) = controller.forkSessionFromEntryId(entryId)
    }

    data object Export : SessionAction {
        override val successMessage = "Exported active session"

        override suspend fun execute(controller: SessionController): Result<String?> =
            controller.exportSession().map { null }
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
): String =
    selectedCwd
        ?: warmConnectionCwd?.takeIf { warmConnectionHostId == hostId }
        ?: groups.firstOrNull()?.cwd
        ?: defaultCwd

internal fun resolveSelectedCwd(
    currentSelection: String?,
    groups: List<CwdSessionGroupUiState>,
): String? =
    currentSelection?.takeIf { selected -> groups.any { it.cwd == selected } }
        ?: groups.firstOrNull()?.cwd

private fun resolveHostCwdSelection(
    currentSelection: String?,
    groups: List<CwdSessionGroupUiState>,
): String? = if (groups.isEmpty()) currentSelection else resolveSelectedCwd(currentSelection, groups)

private fun Map<String, String>.withSelection(
    hostId: String,
    cwd: String?,
): Map<String, String> = if (cwd == null) this - hostId else this + (hostId to cwd)

private fun mapGroup(group: SessionGroup) = CwdSessionGroupUiState(group.cwd, group.sessions)

@Suppress("LongParameterList")
data class SessionsUiState(
    val isLoading: Boolean = false,
    val hosts: List<HostProfile> = emptyList(),
    val selectedHostId: String? = null,
    val selectedCwd: String? = null,
    val selectedCwdByHost: Map<String, String> = emptyMap(),
    val query: String = "",
    val groups: List<CwdSessionGroupUiState> = emptyList(),
    val items: List<SessionCockpitItem> = emptyList(),
    val hostStatuses: List<HostSessionStatus> = emptyList(),
    val workspaceLabels: List<String> = emptyList(),
    val filter: SessionCockpitFilter = SessionCockpitFilter(),
    val density: SessionCardDensity = SessionCardDensity.COMFORTABLE,
    val isRefreshing: Boolean = false,
    val isResuming: Boolean = false,
    val isPerformingAction: Boolean = false,
    val isLoadingForkMessages: Boolean = false,
    val isForkPickerVisible: Boolean = false,
    val forkCandidates: List<ForkableMessage> = emptyList(),
    val activeSessionKey: SessionKey? = null,
    val activeSessionPath: String? = null,
    val isRunActive: Boolean = false,
    val isRetrying: Boolean = false,
    val quickReply: QuickReplyState = QuickReplyState(),
    val shareLink: String? = null,
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
    private val savedStateStore: SessionSavedStateStore,
    private val shareRemoteDataSource: BridgeSessionShareRemoteDataSource? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == SessionsViewModel::class.java) { "Unsupported ViewModel class: ${modelClass.name}" }
        @Suppress("UNCHECKED_CAST")
        return SessionsViewModel(
            profileStore,
            tokenStore,
            repository,
            sessionController,
            cwdPreferenceStore,
            savedStateStore,
            shareRemoteDataSource,
        ) as T
    }
}
