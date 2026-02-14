package com.ayagmar.pimobile.coresessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionIndexRepository(
    private val remoteDataSource: SessionIndexRemoteDataSource,
    private val cache: SessionIndexCache,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val stateByHost = linkedMapOf<String, MutableStateFlow<SessionIndexState>>()
    private val refreshMutexByHost = linkedMapOf<String, Mutex>()

    suspend fun initialize(hostId: String) {
        val state = stateForHost(hostId)
        val cachedIndex = cache.read(hostId)

        if (cachedIndex != null) {
            state.value =
                SessionIndexState(
                    hostId = hostId,
                    groups = cachedIndex.groups,
                    isRefreshing = false,
                    source = SessionIndexSource.CACHE,
                    lastUpdatedEpochMs = cachedIndex.cachedAtEpochMs,
                    errorMessage = null,
                )
        }

        refreshInBackground(hostId)
    }

    fun observe(
        hostId: String,
        query: String = "",
    ): Flow<SessionIndexState> {
        val normalizedQuery = query.trim()
        return stateForHost(hostId).asStateFlow().map { state ->
            state.filter(normalizedQuery)
        }
    }

    suspend fun refresh(hostId: String): SessionIndexState {
        val mutex = mutexForHost(hostId)
        val state = stateForHost(hostId)

        return mutex.withLock {
            state.update { current -> current.copy(isRefreshing = true, errorMessage = null) }

            runCatching {
                val incomingGroups = remoteDataSource.fetch(hostId)
                val mergedGroups = mergeGroups(existing = state.value.groups, incoming = incomingGroups)
                val updatedState =
                    SessionIndexState(
                        hostId = hostId,
                        groups = mergedGroups,
                        isRefreshing = false,
                        source = SessionIndexSource.REMOTE,
                        lastUpdatedEpochMs = nowEpochMs(),
                        errorMessage = null,
                    )

                cache.write(
                    CachedSessionIndex(
                        hostId = hostId,
                        cachedAtEpochMs = requireNotNull(updatedState.lastUpdatedEpochMs),
                        groups = mergedGroups,
                    ),
                )

                state.value = updatedState
                updatedState
            }.getOrElse { throwable ->
                val failedState =
                    state.value.copy(
                        isRefreshing = false,
                        errorMessage = throwable.message ?: "Failed to refresh sessions",
                    )
                state.value = failedState
                failedState
            }
        }
    }

    fun refreshInBackground(hostId: String): Job {
        return scope.launch {
            refresh(hostId)
        }
    }

    private fun stateForHost(hostId: String): MutableStateFlow<SessionIndexState> {
        return synchronized(stateByHost) {
            stateByHost.getOrPut(hostId) {
                MutableStateFlow(SessionIndexState(hostId = hostId))
            }
        }
    }

    private fun mutexForHost(hostId: String): Mutex {
        return synchronized(refreshMutexByHost) {
            refreshMutexByHost.getOrPut(hostId) {
                Mutex()
            }
        }
    }
}

private fun SessionIndexState.filter(query: String): SessionIndexState {
    if (query.isBlank()) return this

    val normalizedQuery = query.lowercase()

    val filteredGroups =
        groups.mapNotNull { group ->
            val groupMatches = group.cwd.lowercase().contains(normalizedQuery)
            if (groupMatches) {
                group
            } else {
                val filteredSessions =
                    group.sessions.filter { session ->
                        session.matches(normalizedQuery)
                    }

                if (filteredSessions.isEmpty()) {
                    null
                } else {
                    SessionGroup(cwd = group.cwd, sessions = filteredSessions)
                }
            }
        }

    return copy(groups = filteredGroups)
}

private fun SessionRecord.matches(query: String): Boolean {
    return sessionPath.lowercase().contains(query) ||
        cwd.lowercase().contains(query) ||
        (displayName?.lowercase()?.contains(query) == true) ||
        (firstUserMessagePreview?.lowercase()?.contains(query) == true) ||
        (lastModel?.lowercase()?.contains(query) == true)
}

private fun mergeGroups(
    existing: List<SessionGroup>,
    incoming: List<SessionGroup>,
): List<SessionGroup> {
    val existingByCwd = existing.associateBy { group -> group.cwd }

    return incoming
        .sortedBy { group -> group.cwd }
        .map { incomingGroup ->
            val existingGroup = existingByCwd[incomingGroup.cwd]
            if (existingGroup == null) {
                SessionGroup(
                    cwd = incomingGroup.cwd,
                    sessions = incomingGroup.sessions.sortedByDescending { session -> session.updatedAt },
                )
            } else {
                mergeGroup(existingGroup = existingGroup, incomingGroup = incomingGroup)
            }
        }
}

private fun mergeGroup(
    existingGroup: SessionGroup,
    incomingGroup: SessionGroup,
): SessionGroup {
    val existingSessionsByPath = existingGroup.sessions.associateBy { session -> session.sessionPath }

    val mergedSessions =
        incomingGroup.sessions
            .sortedByDescending { session -> session.updatedAt }
            .map { incomingSession ->
                val existingSession = existingSessionsByPath[incomingSession.sessionPath]
                if (existingSession != null && existingSession == incomingSession) {
                    existingSession
                } else {
                    incomingSession
                }
            }

    val isUnchanged =
        existingGroup.sessions.size == mergedSessions.size &&
            existingGroup.sessions.zip(mergedSessions).all { (left, right) -> left === right }

    return if (isUnchanged) {
        existingGroup
    } else {
        SessionGroup(cwd = incomingGroup.cwd, sessions = mergedSessions)
    }
}
