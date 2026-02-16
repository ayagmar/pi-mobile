package com.ayagmar.pimobile.coresessions

import kotlinx.serialization.Serializable

@Serializable
data class SessionRecord(
    val sessionPath: String,
    val cwd: String,
    val createdAt: String,
    val updatedAt: String,
    val displayName: String? = null,
    val firstUserMessagePreview: String? = null,
    val messageCount: Int? = null,
    val lastModel: String? = null,
)

@Serializable
data class SessionGroup(
    val cwd: String,
    val sessions: List<SessionRecord>,
)

@Serializable
data class CachedSessionIndex(
    val hostId: String,
    val cachedAtEpochMs: Long,
    val groups: List<SessionGroup>,
)

enum class SessionIndexSource {
    NONE,
    CACHE,
    REMOTE,
}

data class SessionIndexState(
    val hostId: String,
    val groups: List<SessionGroup> = emptyList(),
    val isRefreshing: Boolean = false,
    val source: SessionIndexSource = SessionIndexSource.NONE,
    val lastUpdatedEpochMs: Long? = null,
    val errorMessage: String? = null,
)
