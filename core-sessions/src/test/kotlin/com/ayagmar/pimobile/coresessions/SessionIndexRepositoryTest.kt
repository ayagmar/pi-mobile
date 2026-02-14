package com.ayagmar.pimobile.coresessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionIndexRepositoryTest {
    @Test
    fun `initialize serves cached sessions then refreshes with merged remote`() =
        runTest {
            val hostId = "host-a"
            val dispatcher = StandardTestDispatcher(testScheduler)
            val unchangedCachedSession = buildUnchangedSession()
            val changedCachedSession = buildChangedSession()

            val cache = InMemorySessionIndexCache()
            cache.write(
                CachedSessionIndex(
                    hostId = hostId,
                    cachedAtEpochMs = 100,
                    groups =
                        listOf(
                            SessionGroup(
                                cwd = "/tmp/project",
                                sessions = listOf(unchangedCachedSession, changedCachedSession),
                            ),
                        ),
                ),
            )

            val remote = FakeSessionRemoteDataSource()
            remote.groupsByHost[hostId] =
                listOf(
                    SessionGroup(
                        cwd = "/tmp/project",
                        sessions =
                            listOf(
                                unchangedCachedSession,
                                changedCachedSession.copy(firstUserMessagePreview = "modernized"),
                            ),
                    ),
                )

            val repository = createRepository(remote = remote, cache = cache, dispatcher = dispatcher)
            repository.initialize(hostId)

            assertCachedState(repository = repository, hostId = hostId)

            advanceUntilIdle()

            val refreshedState =
                repository.observe(hostId).first { state ->
                    state.source == SessionIndexSource.REMOTE && !state.isRefreshing
                }

            val refreshedSessions = refreshedState.groups.single().sessions
            assertEquals(2, refreshedSessions.size)

            val unchangedRef = refreshedSessions.first { session -> session.sessionPath == "/tmp/a.jsonl" }
            val changedRef = refreshedSessions.first { session -> session.sessionPath == "/tmp/b.jsonl" }

            assertTrue(unchangedRef === unchangedCachedSession)
            assertEquals("modernized", changedRef.firstUserMessagePreview)

            assertFilteredPaymentState(repository = repository, hostId = hostId)
        }

    @Test
    fun `file cache persists entries per host`() =
        runTest {
            val directory = Files.createTempDirectory("session-cache-test")
            val cache = FileSessionIndexCache(cacheDirectory = directory)
            val index =
                CachedSessionIndex(
                    hostId = "host-b",
                    cachedAtEpochMs = 321,
                    groups =
                        listOf(
                            SessionGroup(
                                cwd = "/tmp/project-b",
                                sessions =
                                    listOf(
                                        SessionRecord(
                                            sessionPath = "/tmp/session.jsonl",
                                            cwd = "/tmp/project-b",
                                            createdAt = "2026-01-01T00:00:00.000Z",
                                            updatedAt = "2026-01-01T01:00:00.000Z",
                                        ),
                                    ),
                            ),
                        ),
                )

            cache.write(index)
            val loaded = cache.read("host-b")

            assertNotNull(loaded)
            assertEquals(index, loaded)
        }

    private suspend fun assertCachedState(
        repository: SessionIndexRepository,
        hostId: String,
    ) {
        val cachedState = repository.observe(hostId).first { state -> state.source == SessionIndexSource.CACHE }
        assertEquals(2, cachedState.groups.single().sessions.size)
        assertEquals(100, cachedState.lastUpdatedEpochMs)
    }

    private suspend fun assertFilteredPaymentState(
        repository: SessionIndexRepository,
        hostId: String,
    ) {
        val filtered =
            repository.observe(hostId, query = "payment").first { state ->
                state.source == SessionIndexSource.REMOTE
            }
        assertEquals(1, filtered.groups.single().sessions.size)
        assertEquals("/tmp/a.jsonl", filtered.groups.single().sessions.single().sessionPath)
    }

    private fun buildUnchangedSession(): SessionRecord {
        return SessionRecord(
            sessionPath = "/tmp/a.jsonl",
            cwd = "/tmp/project",
            createdAt = "2026-01-01T10:00:00.000Z",
            updatedAt = "2026-01-02T10:00:00.000Z",
            displayName = "Alpha",
            firstUserMessagePreview = "payment flow",
            messageCount = 3,
            lastModel = "claude",
        )
    }

    private fun buildChangedSession(): SessionRecord {
        return SessionRecord(
            sessionPath = "/tmp/b.jsonl",
            cwd = "/tmp/project",
            createdAt = "2026-01-01T10:00:00.000Z",
            updatedAt = "2026-01-03T10:00:00.000Z",
            displayName = "Beta",
            firstUserMessagePreview = "legacy",
            messageCount = 7,
            lastModel = "gpt",
        )
    }

    private fun createRepository(
        remote: SessionIndexRemoteDataSource,
        cache: SessionIndexCache,
        dispatcher: TestDispatcher,
    ): SessionIndexRepository {
        val repositoryScope = CoroutineScope(dispatcher)

        return SessionIndexRepository(
            remoteDataSource = remote,
            cache = cache,
            scope = repositoryScope,
            nowEpochMs = { 999 },
        )
    }

    private class FakeSessionRemoteDataSource : SessionIndexRemoteDataSource {
        val groupsByHost = linkedMapOf<String, List<SessionGroup>>()

        override suspend fun fetch(hostId: String): List<SessionGroup> {
            return groupsByHost[hostId] ?: emptyList()
        }
    }
}
