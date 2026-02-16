package com.ayagmar.pimobile.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class CwdSelectionLogicTest {
    @Test
    fun resolveConnectionCwdPrefersExplicitSelection() {
        val groups = listOf(group("/home/ayagmar/project-a"), group("/home/ayagmar/project-b"))

        val resolved =
            resolveConnectionCwd(
                hostId = "host-1",
                selectedCwd = "/home/ayagmar/project-b",
                warmConnectionHostId = "host-1",
                warmConnectionCwd = "/home/ayagmar/project-a",
                groups = groups,
            )

        assertEquals("/home/ayagmar/project-b", resolved)
    }

    @Test
    fun resolveConnectionCwdFallsBackToWarmConnectionForSameHost() {
        val groups = listOf(group("/home/ayagmar/project-a"))

        val resolved =
            resolveConnectionCwd(
                hostId = "host-1",
                selectedCwd = null,
                warmConnectionHostId = "host-1",
                warmConnectionCwd = "/home/ayagmar/warm",
                groups = groups,
            )

        assertEquals("/home/ayagmar/warm", resolved)
    }

    @Test
    fun resolveConnectionCwdFallsBackToFirstGroupWhenWarmConnectionIsForOtherHost() {
        val groups = listOf(group("/home/ayagmar/project-a"), group("/home/ayagmar/project-b"))

        val resolved =
            resolveConnectionCwd(
                hostId = "host-1",
                selectedCwd = null,
                warmConnectionHostId = "host-2",
                warmConnectionCwd = "/home/ayagmar/warm",
                groups = groups,
            )

        assertEquals("/home/ayagmar/project-a", resolved)
    }

    @Test
    fun resolveSelectedCwdKeepsCurrentWhenStillAvailable() {
        val groups = listOf(group("/home/ayagmar/project-a"), group("/home/ayagmar/project-b"))

        val resolved = resolveSelectedCwd("/home/ayagmar/project-b", groups)

        assertEquals("/home/ayagmar/project-b", resolved)
    }

    @Test
    fun resolveSelectedCwdFallsBackToFirstGroupWhenMissing() {
        val groups = listOf(group("/home/ayagmar/project-a"), group("/home/ayagmar/project-b"))

        val resolved = resolveSelectedCwd("/home/ayagmar/unknown", groups)

        assertEquals("/home/ayagmar/project-a", resolved)
    }

    private fun group(cwd: String): CwdSessionGroupUiState {
        return CwdSessionGroupUiState(
            cwd = cwd,
            sessions = emptyList(),
        )
    }
}
