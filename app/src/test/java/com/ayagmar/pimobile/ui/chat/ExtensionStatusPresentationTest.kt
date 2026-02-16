package com.ayagmar.pimobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStatusPresentationTest {
    @Test
    fun compactPresentationPrioritizesChangedStatuses() {
        val presentation =
            buildExtensionStatusPresentation(
                statuses =
                    mapOf(
                        "git" to "idle",
                        "lsp" to "indexing workspace",
                        "search" to "ready",
                    ),
                previousStatuses =
                    mapOf(
                        "git" to "idle",
                        "lsp" to "idle",
                        "search" to "ready",
                    ),
                expanded = false,
            )

        assertEquals(1, presentation.changedCount)
        assertEquals(1, presentation.visibleEntries.size)
        assertEquals("lsp", presentation.visibleEntries.first().key)
        assertEquals(2, presentation.hiddenCount)
        assertEquals(1, presentation.activeCount)
        assertEquals(2, presentation.quietCount)
    }

    @Test
    fun expandedPresentationShowsAllStatuses() {
        val presentation =
            buildExtensionStatusPresentation(
                statuses =
                    mapOf(
                        "alpha" to "busy",
                        "beta" to "ready",
                        "gamma" to "running",
                    ),
                previousStatuses = emptyMap(),
                expanded = true,
            )

        assertEquals(3, presentation.visibleEntries.size)
        assertEquals(0, presentation.hiddenCount)
        assertEquals(listOf("alpha", "beta", "gamma"), presentation.visibleEntries.map { it.key })
    }

    @Test
    fun lowSignalStatusHeuristicsClassifyIdleStates() {
        assertTrue(isLowSignalExtensionStatus("ready"))
        assertTrue(isLowSignalExtensionStatus("Connected and synced"))
        assertFalse(isLowSignalExtensionStatus("running command"))
        assertFalse(isLowSignalExtensionStatus("error: failed to start"))
    }
}
